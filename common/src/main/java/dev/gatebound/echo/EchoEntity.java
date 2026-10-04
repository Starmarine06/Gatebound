package dev.gatebound.echo;

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
    private int attackCooldown;

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
        this.goalSelector.addGoal(1, new FightGoal());
        this.goalSelector.addGoal(6, new EchoFollowGoal(this));
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

    /** Own melee: damage is computed here, so mobs without an attack attribute (animals, flyers) still fight. */
    private final class FightGoal extends Goal {
        private int repath;

        FightGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            getNavigation().stop();
            setAggressive(false);
        }

        @Override
        public void tick() {
            LivingEntity target = getTarget();
            if (target == null || !(level() instanceof ServerLevel server)) {
                return;
            }
            setAggressive(true);
            getLookControl().setLookAt(target, 30, 30);
            if (attackCooldown > 0) {
                attackCooldown--;
            }
            double reach = getBbWidth() * 1.2 + target.getBbWidth() + 0.6;
            double dist = distanceToSqr(target.getX(), target.getY(), target.getZ());
            if (dist > reach * reach) {
                if (--repath <= 0) {
                    repath = 8;
                    getNavigation().moveTo(target, 1.25);
                }
            } else {
                getNavigation().stop();
                if (attackCooldown <= 0) {
                    attackCooldown = 20;
                    swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                    target.hurtServer(server, server.damageSources().mobAttack(EchoEntity.this), (float) attackDamage);
                }
            }
        }
    }
}
