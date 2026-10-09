package dev.gatebound.skill.mage;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.gatebound.combat.PlayerCombat;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Wind: Gale. Control and mobility: knockback, pulling, launching, deflecting missiles and, in the end, flight.
 * Wind deals less damage than the other elements but moves everything, you included.
 */
public final class WindSkills {
    public static final int GUST_COST = 8;
    public static final int CYCLONE_COST = 20;
    public static final int DASH_COST = 12;
    public static final int UPDRAFT_COST = 18;
    public static final int BLADES_COST = 26;
    public static final int BARRIER_COST = 38;
    public static final int TORNADO_COST = 65;
    public static final int FLIGHT_COST = 80;
    public static final int AVATAR_COST = 90;

    private static final String BARRIER = "wind_barrier";

    private WindSkills() {
    }

    public static void init() {
        EntityEvent.LIVING_HURT.register(WindSkills::onHurt);
    }

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (MageCore.applying() || amount <= 0 || victim instanceof ServerPlayer || !(source.getEntity() instanceof ServerPlayer p)) {
            return EventResult.pass();
        }
        if (MageCore.inForm(p, Element.WIND)) {
            MageCore.knock(victim, p.position(), 1.4, 0.35);
            MageCore.particles((ServerLevel) victim.level(), ParticleTypes.CLOUD, victim.position().add(0, 0.8, 0), 8, 0.4, 0.1);
        }
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- skills

    public static boolean gust(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, GUST_COST);
        Vec3 look = p.getLookAngle();
        for (LivingEntity e : MageCore.foes(p, level, 10)) {
            Vec3 to = e.position().subtract(p.position()).normalize();
            if (to.dot(look) > 0.6) { // a cone about 53 degrees to each side
                MageCore.strike(p, level, e, MageCore.dmg(data, 2));
                MageCore.knock(e, p.position(), 1.7, 0.45);
            }
        }
        for (int i = 1; i <= 8; i++) {
            Vec3 q = p.getEyePosition().add(look.scale(i));
            level.sendParticles(ParticleTypes.CLOUD, q.x, q.y - 0.2, q.z, 3, 0.2 + i * 0.07, 0.2 + i * 0.05, 0.2 + i * 0.07, 0.02);
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BREEZE_WIND_CHARGE_BURST.value()), 1.0f, 1.0f);
        return true;
    }

    public static boolean cyclone(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, CYCLONE_COST);
        for (LivingEntity e : MageCore.foes(p, level, 8)) {
            MageCore.strike(p, level, e, MageCore.dmg(data, 3));
            Vec3 pull = p.position().subtract(e.position());
            Vec3 flat = new Vec3(pull.x, 0, pull.z);
            flat = flat.lengthSqr() < 1.0e-4 ? Vec3.ZERO : flat.normalize().scale(1.1);
            e.setDeltaMovement(flat.x, 0.5, flat.z);
            e.needsSync = true;
        }
        level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.6, p.getZ(), 90, 3.5, 1.2, 3.5, 0.15);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BREEZE_WIND_CHARGE_BURST.value()), 1.2f, 0.6f);
        return true;
    }

    public static boolean airDash(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, DASH_COST);
        Vec3 dir = MageCore.flatLook(p);
        p.setDeltaMovement(dir.x * 1.9, 0.2, dir.z * 1.9);
        p.fallDistance = 0;
        MageCore.send(p);
        PlayerCombat.grantIFrames(p, 8);
        level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 25, 0.3, 0.4, 0.3, 0.1);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BREEZE_WIND_CHARGE_BURST.value()), 0.8f, 1.5f);
        return true;
    }

    public static boolean updraft(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, UPDRAFT_COST);
        for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(p) <= 36)) {
            other.setDeltaMovement(other.getDeltaMovement().x, 1.25, other.getDeltaMovement().z);
            other.fallDistance = 0;
            other.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 160, 0));
            MageCore.send(other);
            level.sendParticles(ParticleTypes.CLOUD, other.getX(), other.getY(), other.getZ(), 20, 0.4, 0.1, 0.4, 0.15);
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BREEZE_WIND_CHARGE_BURST.value()), 1.0f, 0.8f);
        return true;
    }

    public static boolean windBlades(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, BLADES_COST);
        Vec3 look = p.getLookAngle();
        Vec3 side = new Vec3(-look.z, 0, look.x).normalize();
        for (int i = -1; i <= 1; i++) {
            Vec3 dir = look.add(side.scale(i * 0.12)).normalize();
            MageCore.bolt(p, level, p.getEyePosition().add(look.scale(0.5)), dir, 2.2, 30, 0.8, ParticleTypes.SWEEP_ATTACK,
                    (lv, at, target) -> {
                        if (target != null) {
                            MageCore.strike(p, lv, target, MageCore.dmg(data, 3.5));
                            MageCore.knock(target, p.position(), 0.6, 0.15);
                        }
                    });
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 1.0f, 1.4f);
        return true;
    }

    public static boolean windBarrier(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, BARRIER_COST);
        MageCore.buff(p, BARRIER, 160);
        MageCore.run((server, now) -> {
            if (!MageCore.buffed(p, BARRIER) || !p.isAlive() || p.isRemoved()) {
                return true;
            }
            ServerLevel lv = p.level();
            lv.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 3, 1.6, 0.8, 1.6, 0.01);
            AABB box = p.getBoundingBox().inflate(4.5);
            for (Projectile proj : lv.getEntitiesOfClass(Projectile.class, box, x -> x.getOwner() != p && x.getDeltaMovement().lengthSqr() > 0.01)) {
                proj.setOwner(p);
                proj.setDeltaMovement(proj.getDeltaMovement().scale(-1.0));
                proj.needsSync = true;
                lv.sendParticles(ParticleTypes.CLOUD, proj.getX(), proj.getY(), proj.getZ(), 6, 0.2, 0.2, 0.2, 0.05);
            }
            return false;
        });
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BREEZE_WIND_CHARGE_BURST.value()), 1.0f, 0.5f);
        Compat.overlay(p, Component.literal("A wall of wind turns missiles back."));
        return true;
    }

    public static boolean tornado(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, TORNADO_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 25);
        long[] age = {0};
        MageCore.zone(p, level, spot, 5.5, null, 160, 5,
                in -> {
                    age[0]++;
                    for (LivingEntity e : in) {
                        // Swirl: pull in and round, and keep them up.
                        Vec3 off = e.position().subtract(spot);
                        Vec3 tangent = new Vec3(-off.z, 0, off.x).normalize().scale(0.45);
                        Vec3 inward = new Vec3(-off.x, 0, -off.z).normalize().scale(0.2);
                        e.setDeltaMovement(tangent.x + inward.x, 0.55, tangent.z + inward.z);
                        e.needsSync = true;
                        if (age[0] % 2 == 0) {
                            MageCore.strike(p, level, e, MageCore.dmg(data, 2));
                        }
                    }
                },
                lv -> {
                    for (int i = 0; i < 12; i++) {
                        double h = lv.getRandom().nextDouble() * 6;
                        double r = 0.8 + h * 0.5;
                        double a = lv.getRandom().nextDouble() * Math.PI * 2;
                        lv.sendParticles(ParticleTypes.CLOUD, spot.x + Math.cos(a) * r, spot.y + h, spot.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
                    }
                });
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BREEZE_WIND_CHARGE_BURST.value()), 1.5f, 0.4f);
        return true;
    }

    public static boolean tempestFlight(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.WIND, FLIGHT_COST);
        MageCore.flight(p, 200);
        p.setDeltaMovement(p.getDeltaMovement().x, 0.8, p.getDeltaMovement().z);
        MageCore.send(p);
        level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 40, 0.5, 0.2, 0.5, 0.2);
        Compat.overlay(p, Component.literal("The wind carries you: look to fly, jump to rise, sneak to sink."));
        return true;
    }

    public static boolean windAvatar(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = MageConfig.formSeconds * 20;
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1));
        MageCore.flight(p, ticks);
        MageCore.form(p, Element.WIND, 10, o -> {
            ServerLevel lv = o.level();
            lv.sendParticles(ParticleTypes.CLOUD, o.getX(), o.getY() + 1, o.getZ(), 12, 0.7, 0.8, 0.7, 0.05);
            for (LivingEntity e : MageCore.foes(o, lv, 3.5)) {
                MageCore.knock(e, o.position(), 0.5, 0.1);
            }
        });
        Compat.overlay(p, Component.literal("You become the wind."));
        return true;
    }
}
