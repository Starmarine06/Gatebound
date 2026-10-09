package dev.gatebound.skill.mage;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.skill.SkillTargeting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Light: Radiance. Support and holy damage: it heals and shields allies, blinds foes, and hits the undead 50% harder.
 */
public final class LightSkills {
    public static final int BOLT_COST = 10;
    public static final int SANCTUARY_COST = 20;
    public static final int MENDING_COST = 22;
    public static final int FLASH_COST = 20;
    public static final int SMITE_COST = 32;
    public static final int AEGIS_COST = 40;
    public static final int JUDGEMENT_COST = 70;
    public static final int RESURRECTION_COST = 100;
    public static final int SERAPH_COST = 90;

    private static final String BLESSED = "resurrection";

    private LightSkills() {
    }

    public static void init() {
        EntityEvent.LIVING_DEATH.register(LightSkills::onDeath);
    }

    // ---------------------------------------------------------------- helpers

    /** Holy damage: undead take 50% more. */
    private static void holy(ServerPlayer p, ServerLevel level, LivingEntity target, float amount) {
        MageCore.strike(p, level, target, target.typeHolder().is(EntityTypeTags.UNDEAD) ? amount * 1.5f : amount);
    }

    private static void healAround(ServerPlayer p, ServerLevel level, double radius, float amount) {
        for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(p) <= radius * radius)) {
            other.heal(amount);
            level.sendParticles(ParticleTypes.HEART, other.getX(), other.getY() + 1.6, other.getZ(), 3, 0.3, 0.3, 0.3, 0);
        }
    }

    // ---------------------------------------------------------------- events

    /** A blessing (Resurrection) turns a killing blow into half health, once. */
    private static EventResult onDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof ServerPlayer p && MageCore.buffed(p, BLESSED)) {
            MageCore.buff(p, BLESSED, 0);
            p.setHealth(p.getMaxHealth() * 0.5f);
            p.removeAllEffects();
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 4));
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            ServerLevel level = p.level();
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 60, 0.5, 1.0, 0.5, 0.4);
            MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.TOTEM_USE), 1.0f, 1.0f);
            Compat.overlay(p, Component.literal("The light refuses to let you fall."));
            return EventResult.interruptFalse();
        }
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- skills

    public static boolean radiantBolt(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, BOLT_COST);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 0.9f, 1.4f);
        MageCore.bolt(p, level, p.getEyePosition().add(p.getLookAngle().scale(0.5)), p.getLookAngle(), 1.8, 36, 0.6, ParticleTypes.END_ROD,
                (lv, at, target) -> {
                    MageCore.particles(lv, ParticleTypes.END_ROD, at, 14, 0.4, 0.1);
                    if (target != null) {
                        holy(p, lv, target, MageCore.dmg(data, 4.5));
                        p.heal(2.0f);
                    }
                });
        return true;
    }

    public static boolean sanctuary(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, SANCTUARY_COST);
        Vec3 center = p.position();
        MageCore.zone(p, level, center, 6, null, 160, 20,
                in -> {
                    for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(center.x, center.y, center.z) <= 36)) {
                        other.heal(MageCore.dmg(data, 2));
                        level.sendParticles(ParticleTypes.HEART, other.getX(), other.getY() + 1.6, other.getZ(), 2, 0.3, 0.3, 0.3, 0);
                    }
                    for (LivingEntity e : in) {
                        if (e.typeHolder().is(EntityTypeTags.UNDEAD)) {
                            holy(p, level, e, MageCore.dmg(data, 2));
                        }
                    }
                },
                lv -> {
                    for (int i = 0; i < 10; i++) {
                        double a = lv.getRandom().nextDouble() * Math.PI * 2;
                        lv.sendParticles(ParticleTypes.END_ROD, center.x + Math.cos(a) * 6, center.y + 0.1, center.z + Math.sin(a) * 6, 1, 0, 0.3, 0, 0.02);
                    }
                    lv.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 0.3, center.z, 2, 2.5, 0.1, 2.5, 0.01);
                });
        MageCore.sound(level, center, GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), 1.2f, 1.4f);
        return true;
    }

    public static boolean mendingLight(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, MENDING_COST);
        healAround(p, level, 9, MageCore.dmg(data, 6));
        level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.8, 1.0, 0.8, 0.05);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 1.2f, 1.0f);
        return true;
    }

    public static boolean blindingFlash(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, FLASH_COST);
        for (LivingEntity e : MageCore.foes(p, level, 10)) {
            e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0));
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0));
            if (e instanceof Mob mob) {
                mob.setTarget(null);
            }
        }
        MageCore.flash(level, p.getX(), p.getY() + 1, p.getZ());
        level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 60, 3, 1.5, 3, 0.2);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.FIREWORK_ROCKET_BLAST), 1.0f, 1.5f);
        return true;
    }

    public static boolean holySmite(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, SMITE_COST);
        List<LivingEntity> hit = SkillTargeting.ray(p, level, 30, 1, ParticleTypes.END_ROD);
        if (hit.isEmpty()) {
            return false;
        }
        LivingEntity target = hit.get(0);
        for (int i = 0; i < 6; i++) {
            level.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + i * 0.8, target.getZ(), 4, 0.1, 0.1, 0.1, 0.02);
        }
        holy(p, level, target, MageCore.dmg(data, 14));
        MageCore.sound(level, target.position(), GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_IMPACT), 0.6f, 2.0f);
        return true;
    }

    public static boolean aegis(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, AEGIS_COST);
        for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(p) <= 100)) {
            other.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 400, 2));
            other.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 0));
            level.sendParticles(ParticleTypes.END_ROD, other.getX(), other.getY() + 1, other.getZ(), 25, 0.5, 0.9, 0.5, 0.05);
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BEACON_POWER_SELECT), 1.0f, 1.5f);
        return true;
    }

    public static boolean judgement(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, JUDGEMENT_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 40);
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), 1.5f, 0.6f);
        for (int t = 0; t < 20; t += 2) {
            int n = t;
            MageCore.after(t, server -> {
                double r = 6.0 * (1.0 - n / 20.0);
                for (int i = 0; i < 12; i++) {
                    double a = Math.PI * 2 * i / 12;
                    level.sendParticles(ParticleTypes.END_ROD, spot.x + Math.cos(a) * r, spot.y + 0.2, spot.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
                }
            });
        }
        MageCore.after(22, server -> {
            for (int y = 0; y < 30; y++) {
                level.sendParticles(ParticleTypes.END_ROD, spot.x, spot.y + y, spot.z, 6, 0.4, 0.3, 0.4, 0.02);
            }
            MageCore.flash(level, spot.x, spot.y + 1, spot.z);
            MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_THUNDER), 1.5f, 1.5f);
            ServerPlayer o = MageCore.playerOf(server, p.getUUID());
            if (o != null) {
                for (LivingEntity e : MageCore.foesAt(o, level, spot, 6.5)) {
                    holy(o, level, e, MageCore.dmg(data, 16));
                    e.setRemainingFireTicks(80);
                }
            }
        });
        return true;
    }

    public static boolean resurrection(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, RESURRECTION_COST);
        for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(p) <= 100)) {
            MageCore.buff(other, BLESSED, 1200);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, other.getX(), other.getY() + 1, other.getZ(), 30, 0.5, 1.0, 0.5, 0.2);
            Compat.overlay(other, Component.literal("A blessing guards you for 60 s: the next killing blow will not land."));
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BEACON_POWER_SELECT), 1.5f, 0.8f);
        return true;
    }

    public static boolean seraphForm(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = MageConfig.formSeconds * 20;
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 1));
        MageCore.flight(p, ticks);
        MageCore.form(p, Element.LIGHT, 20, o -> {
            ServerLevel lv = o.level();
            PlayerData d = PlayerDataStore.of(o);
            healAround(o, lv, 9, MageCore.dmg(d, 2));
            for (LivingEntity e : MageCore.foes(o, lv, 9)) {
                holy(o, lv, e, MageCore.dmg(d, 3));
                e.setRemainingFireTicks(40);
            }
            lv.sendParticles(ParticleTypes.END_ROD, o.getX(), o.getY() + 1.2, o.getZ(), 25, 1.5, 1.0, 1.5, 0.05);
        });
        MageCore.flash(level, p.getX(), p.getY() + 1, p.getZ());
        Compat.overlay(p, Component.literal("You become a Seraph: look to fly, jump to rise, sneak to sink."));
        return true;
    }
}
