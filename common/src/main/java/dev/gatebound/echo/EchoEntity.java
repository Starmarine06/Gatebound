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
    private static final EntityDataAccessor<String> MOB_TYPE =
            SynchedEntityData.defineId(EchoEntity.class, EntityDataSerializers.STRING);
    /** Height of the registered echo hitbox; the SCALE attribute stretches it to the bound mob's size. */
    private static final double BASE_HEIGHT = 1.95;

    private double attackDamage = 3;
    // Brain state (server side only), used by EchoBrain.
    int attackCooldown;
    int abilityCooldown;
    int repath;
    int wanderCooldown;
    net.minecraft.core.BlockPos seekFound;

    public EchoEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, 3)
                .add(Attributes.FOLLOW_RANGE, 32);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MOB_TYPE, "minecraft:zombie");
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
        double scale = Math.max(0.25, Math.min(8, proto.getBbHeight() / BASE_HEIGHT));
        set(Attributes.SCALE, scale);
        this.refreshDimensions();
        this.setHealth(this.getMaxHealth());
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
        if (!level().isClientSide() && tickCount > 10 && !EchoManager.isEcho(this)) {
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
