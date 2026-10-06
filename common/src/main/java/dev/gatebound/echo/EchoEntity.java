package dev.gatebound.echo;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * An Echo. A custom entity with its own AI (follow the owner, fight the owner's targets, stay on the guard post).
 * It only borrows the look of the mob it was bound from: the type id is synced to the client, whose renderer
 * draws the vanilla model of that mob. Never saved to disk, so no echo can survive a restart as a broken leftover.
 */
public class EchoEntity extends PathfinderMob {
    private static final EntityDataAccessor<Boolean> FLIER =
            SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> OWNER_ID =
            SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MOB_TYPE =
            SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.STRING);
    /** Height of the registered echo hitbox; the SCALE attribute stretches it to the bound mob's size. */
    private static final double BASE_HEIGHT = 1.95;

    /** Mobs that fly in the wild; their echoes fly too. */
    private static final java.util.Set<String> FLIERS = java.util.Set.of(
            "phantom", "bat", "blaze", "ghast", "vex", "allay", "bee", "parrot", "breeze", "happy_ghast");

    private double attackDamage = 3;
    // Brain state (server side only), used by EchoBrain.
    int attackCooldown;
    int abilityCooldown;
    int repath;
    int wanderCooldown;
    net.minecraft.core.BlockPos seekFound;
    boolean guideWarned;
    /** Guide order: the owner has right-clicked this echo and it is leading the way to guideTarget. */
    boolean guideGo;
    boolean guideReady;
    boolean guideArrived;
    net.minecraft.core.BlockPos guideTarget;
    net.minecraft.world.phys.Vec3 exploreTarget;
    int exploreRound;
    int stuckChecks;
    /** How many times in a row the echo stayed stuck even after picking a new target. */
    int stuckStrikes;
    double lastX;
    double lastZ;
    // Summoner skill state (EchoSkills): game-time deadlines.
    long empowerUntil;
    double empowerMult = 1;
    long commandUntil;
    java.util.UUID commandTarget;
    long linkUntil;
    double linkFraction;
    long invulnUntil;
    /** Game time at which a spectral copy (Legion Call) fades; 0 for a normal echo. */
    long tempUntil;

    /** Damage multiplier from the owner's Empower and Command Strike while they last. */
    double damageMultiplier(LivingEntity target) {
        long now = level().getGameTime();
        double m = now < empowerUntil ? empowerMult : 1;
        if (now < commandUntil && target.getUUID().equals(commandTarget)) {
            m *= 1.5;
        }
        return m;
    }

    public EchoEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, 3)
                .add(Attributes.FLYING_SPEED, 0.6)
                .add(Attributes.STEP_HEIGHT, 1.0) // walks up slabs, stairs and full blocks without having to jump
                .add(Attributes.FOLLOW_RANGE, 64);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MOB_TYPE, "minecraft:zombie");
        builder.define(FLIER, false);
        builder.define(OWNER_ID, "");
    }

    /** The owner's UUID as text (synced so the client HUD can list its own echoes); empty for a corpse. */
    public String ownerId() {
        return this.entityData.get(OWNER_ID);
    }

    void setOwnerId(java.util.UUID id) {
        this.entityData.set(OWNER_ID, id.toString());
    }

    public String mobType() {
        return this.entityData.get(MOB_TYPE);
    }

    /** Server side: becomes a copy of the given mob's stats and size, multiplied by the owner's bonuses. */
    public void configure(String typeId, LivingEntity proto, double healthMult, double damageMult) {
        this.entityData.set(MOB_TYPE, typeId);
        set(Attributes.MAX_HEALTH, proto.getMaxHealth() * healthMult);
        AttributeInstance dmg = proto.getAttribute(Attributes.ATTACK_DAMAGE);
        this.attackDamage = (dmg != null ? Math.max(1, dmg.getValue()) : 3) * damageMult;
        set(Attributes.ATTACK_DAMAGE, this.attackDamage);
        double speed = proto.getAttributeValue(Attributes.MOVEMENT_SPEED);
        set(Attributes.MOVEMENT_SPEED, Math.max(0.2, Math.min(0.38, speed)));
        String typePath = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        boolean flies = FLIERS.contains(typePath);
        double scale = Math.max(0.25, Math.min(8, proto.getBbHeight() / BASE_HEIGHT));
        if (flies) {
            scale = Math.max(scale, 0.6); // big enough to carry a rider
        }
        set(Attributes.SCALE, scale);
        this.refreshDimensions();
        this.setHealth(this.getMaxHealth());
        String path = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        if (FLIERS.contains(path)) {
            makeFlier();
        }
    }

    private void set(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
        AttributeInstance inst = this.getAttribute(attribute);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        if (getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation ground) {
            ground.setCanOpenDoors(true);
            ground.setCanFloat(true);
            this.goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.OpenDoorGoal(this, true));
        }
    }

    private void makeFlier() {
        this.setNoGravity(true);
        this.entityData.set(FLIER, true);
        this.moveControl = new net.minecraft.world.entity.ai.control.FlyingMoveControl(this, 20, true);
        net.minecraft.world.entity.ai.navigation.FlyingPathNavigation nav =
                new net.minecraft.world.entity.ai.navigation.FlyingPathNavigation(this, this.level());
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        this.navigation = nav;
    }

    /** True only for soul bodies, which are never tracked by the manager. */
    public boolean isCorpse() {
        return false;
    }

    public boolean isFlier() {
        return this.entityData.get(FLIER);
    }

    @Override
    public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof net.minecraft.world.entity.player.Player p ? p : null;
    }

    /** The owner mounts a flying echo with an empty-handed right click. */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player, InteractionHand hand) {
        boolean mine = player.getUUID().toString().equals(ownerId());
        if (hand == InteractionHand.MAIN_HAND && mine && !isCorpse() && !isVehicle() && !player.isShiftKeyDown()) {
            if (level().isClientSide()) {
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            EchoManager.Info info = EchoManager.info(this);
            if (info != null && info.owner.equals(player.getUUID()) && player instanceof net.minecraft.server.level.ServerPlayer sp
                    && level() instanceof ServerLevel server) {
                // A Guide echo takes the right-click as the signal to lead the way; other flying echoes can be ridden.
                if (EchoManager.effectiveMode(sp, getUUID(), info.slot) == EchoRoster.GUIDE) {
                    EchoBrain.guideSignal(this, sp, server);
                    return net.minecraft.world.InteractionResult.SUCCESS_SERVER;
                }
                if (isFlier()) {
                    player.startRiding(this);
                    return net.minecraft.world.InteractionResult.SUCCESS_SERVER;
                }
            }
        }
        return super.mobInteract(player, hand);
    }

    /** Ridden: fly where the rider looks (W/S forward and back, A/D sideways); with no input it hovers. */
    @Override
    public void travel(net.minecraft.world.phys.Vec3 input) {
        if (isFlier() && getControllingPassenger() instanceof net.minecraft.world.entity.player.Player rider) {
            setYRot(rider.getYRot());
            yRotO = getYRot();
            setXRot(rider.getXRot() * 0.5f);
            setRot(getYRot(), getXRot());
            this.yBodyRot = getYRot();
            this.yHeadRot = getYRot();
            net.minecraft.world.phys.Vec3 look = rider.getLookAngle();
            double yaw = Math.toRadians(rider.getYRot());
            net.minecraft.world.phys.Vec3 left = new net.minecraft.world.phys.Vec3(Math.cos(yaw), 0, Math.sin(yaw));
            net.minecraft.world.phys.Vec3 wanted = look.scale(rider.zza).add(left.scale(rider.xxa));
            net.minecraft.world.phys.Vec3 motion = getDeltaMovement().lerp(wanted.scale(0.6), 0.2);
            setDeltaMovement(motion);
            move(net.minecraft.world.entity.MoverType.SELF, motion);
            calculateEntityAnimation(false);
            return;
        }
        super.travel(input);
    }

    public double attackDamage() {
        return attackDamage;
    }

    /** Display name of the bound mob, from the custom name "Echo: Zombie". */
    public String mobName() {
        Component name = getCustomName();
        String text = name == null ? mobType() : name.getString();
        return text.startsWith("Echo: ") ? text.substring(6) : text;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        EchoBrain.tick(this, level);
    }

    @Override
    public void tick() {
        super.tick();
        // An echo the manager no longer tracks is a stale copy (left in another dimension, say): remove it.
        if (!level().isClientSide() && tickCount > 10 && !isCorpse() && !EchoManager.isEcho(this)) {
            discard();
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceSqr) {
        return false;
    }
}
