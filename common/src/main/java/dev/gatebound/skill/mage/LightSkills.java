package dev.gatebound.skill.mage;

import dev.gatebound.skill.fx.FormFx;
import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.skill.SkillTargeting;
import dev.gatebound.skill.fx.CastFx;
import dev.gatebound.skill.fx.Circles;
import dev.gatebound.skill.fx.Fx;
import dev.gatebound.skill.fx.Palette;
import dev.gatebound.skill.fx.Props;
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
import java.util.UUID;

/**
 * Light: Radiance. Support and holy damage: it heals and shields allies, blinds foes, and hits the undead 50% harder.
 */
public final class LightSkills {
    private static final Palette LIGHT = Palette.of(Element.LIGHT);
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

    /** Static holder; not instantiable. */
    private LightSkills() {
    }

    /** Registers the Light hooks. */
    public static void init() {
        EntityEvent.LIVING_DEATH.register(LightSkills::onDeath);
    }

    // ---------------------------------------------------------------- helpers

    /** Holy damage: undead take 50% more. */
    private static void holy(ServerPlayer p, ServerLevel level, LivingEntity target, float amount) {
        MageCore.strike(p, level, target, target.typeHolder().is(EntityTypeTags.UNDEAD) ? amount * 1.5f : amount);
    }

    /** Heals allies around the player. */
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

    /** Radiant Bolt: bolt of light that heals you a little. */
    public static boolean radiantBolt(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, BOLT_COST);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 0.9f, 1.4f);
        MageCore.bolt(p, level, p.getEyePosition().add(p.getLookAngle().scale(0.5)), p.getLookAngle(), 1.8, 36, 0.6,
                (lv, a, b, tick) -> {
                    Fx.trail(lv, LIGHT, a, b, tick);
                    lv.sendParticles(ParticleTypes.GLOW, b.x, b.y, b.z, 3, 0.15, 0.15, 0.15, 0.02);
                    lv.sendParticles(ParticleTypes.END_ROD, b.x, b.y, b.z, 2, 0.1, 0.1, 0.1, 0.02); // a bright head
                },
                (lv, at, target) -> {
                    Fx.impact(lv, LIGHT, at, 1.6);
                    Fx.spiral(lv, ParticleTypes.END_ROD, at.subtract(0, 0.5, 0), 0.6, 2.2, 2, 8);
                    if (target != null) {
                        Circles.onTarget(lv, Element.LIGHT, p.position(), target, 2.4, 16);
                        Fx.pillar(lv, LIGHT, target.position(), 3.5, 0.5, 8);
                        holy(p, lv, target, MageCore.dmg(data, 4.5));
                        p.heal(2.0f);
                    }
                });
        return true;
    }

    /** Sanctuary: holy zone that heals those inside and burns undead. */
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
                    long tick = lv.getServer().getTickCount();
                    for (int i = 0; i < 10; i++) {
                        double a = lv.getRandom().nextDouble() * Math.PI * 2;
                        lv.sendParticles(ParticleTypes.END_ROD, center.x + Math.cos(a) * 6, center.y + 0.1, center.z + Math.sin(a) * 6, 1, 0, 0.3, 0, 0.02);
                    }
                    lv.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 0.3, center.z, 2, 2.5, 0.1, 2.5, 0.01);
                    // Motes drift up through the holy ground and a halo of light turns around its edge.
                    for (int i = 0; i < 3; i++) {
                        double a = lv.getRandom().nextDouble() * Math.PI * 2;
                        double r = Math.sqrt(lv.getRandom().nextDouble()) * 6;
                        Fx.fly(lv, ParticleTypes.GLOW, new Vec3(center.x + Math.cos(a) * r, center.y + 0.2, center.z + Math.sin(a) * r), new Vec3(0, 1, 0), 0.08);
                    }
                    Fx.swirl(lv, ParticleTypes.END_ROD, center, 6.0, 2.5, tick, 3);
                });
        Circles.ground(level, Element.LIGHT, center, 13, 170);
        Fx.shockwave(level, LIGHT, center, 6.5, 8);
        Fx.pillar(level, LIGHT, center, 6, 1.5, 12);
        MageCore.sound(level, center, GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), 1.2f, 1.4f);
        return true;
    }

    /** Mending Light: heals you and nearby players. */
    public static boolean mendingLight(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, MENDING_COST);
        healAround(p, level, 9, MageCore.dmg(data, 6));
        Circles.ground(level, Element.LIGHT, p.position(), 18, 40);
        Fx.shockwave(level, LIGHT, p.position(), 9, 12);
        Fx.spiral(level, ParticleTypes.END_ROD, p.position(), 1.0, 3.0, 3, 18);
        // Each healed player is lifted in a spiral of light with hearts.
        for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(p) <= 81)) {
            Fx.spiral(level, ParticleTypes.GLOW, other.position(), 0.8, 2.4, 3, 16);
            Fx.pillar(level, LIGHT, other.position(), 3, 0.5, 10);
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 1.2f, 1.0f);
        return true;
    }

    /** Blinding Flash: blinds nearby enemies and makes them lose track of you. */
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
        Fx.sphere(level, ParticleTypes.END_ROD, p.position().add(0, 1, 0), 90, 0.9);
        Fx.shockwave(level, LIGHT, p.position(), 10, 8);
        int shown = 0;
        for (LivingEntity e : MageCore.foes(p, level, 10)) { // a circle of light blazes in front of each blinded enemy
            if (shown++ < 8) {
                Circles.onTarget(level, Element.LIGHT, p.position(), e, 2.4, 22);
            }
            MageCore.flash(level, e.getX(), e.getY(0.8), e.getZ());
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.FIREWORK_ROCKET_BLAST), 1.0f, 1.5f);
        return true;
    }

    /** Holy Smite: heavy blow of light on the target. */
    public static boolean holySmite(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, SMITE_COST);
        List<LivingEntity> hit = SkillTargeting.ray(p, level, 30, 1, ParticleTypes.END_ROD);
        if (hit.isEmpty()) {
            return false;
        }
        LivingEntity target = hit.get(0);
        Circles.onTarget(level, Element.LIGHT, p.position(), target, 3.0, 22);
        Circles.ground(level, Element.LIGHT, target.position(), 6, 24);
        // A shaft of light drops on the target from above after a heartbeat.
        Vec3 at = target.position();
        MageCore.after(6, server -> {
            for (int i = 0; i < 24; i++) {
                level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + i * 0.7, at.z, 3, 0.15, 0.2, 0.15, 0.02);
            }
            Fx.pillar(level, LIGHT, at, 14, 1.0, 10);
            Fx.shockwave(level, LIGHT, at, 4.5, 8);
            Fx.sphere(level, ParticleTypes.END_ROD, at.add(0, 1, 0), 40, 0.5);
            MageCore.flash(level, at.x, at.y + 1, at.z);
        });
        holy(p, level, target, MageCore.dmg(data, 14));
        MageCore.sound(level, target.position(), GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_IMPACT), 0.6f, 2.0f);
        return true;
    }

    /** Aegis: absorption and resistance for you and nearby players. */
    public static boolean aegis(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, AEGIS_COST);
        for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(p) <= 100)) {
            other.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 400, 2));
            other.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 0));
            Circles.ground(level, Element.LIGHT, other.position(), 4.5, 40);
            Fx.spiral(level, ParticleTypes.END_ROD, other.position(), 0.9, 2.4, 3, 16);
            Fx.sphere(level, ParticleTypes.GLOW, other.position().add(0, 1, 0), 24, 0.25);
            // A shell of light rings the player, drawn for the length of the shield.
            MageCore.run((server, now) -> {
                ServerPlayer o = MageCore.playerOf(server, other.getUUID());
                if (o == null || !o.hasEffect(MobEffects.ABSORPTION)) {
                    return true;
                }
                if (now % 3 == 0) {
                    Fx.swirl(o.level(), ParticleTypes.END_ROD, o.position(), 0.9, 2.0, now, 3);
                }
                return false;
            });
        }
        Fx.shockwave(level, LIGHT, p.position(), 10, 10);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BEACON_POWER_SELECT), 1.0f, 1.5f);
        return true;
    }

    /**
     * Three acts over about seven seconds: a large circle burns onto the ground while two halos descend from the sky and
     * rings close in (1.5 s); the strike lands in a flash and a column of light stands on the circle (5 s), pulsing and
     * scorching everything in it; then the column narrows and the light fades out.
     */
    public static boolean judgement(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, JUDGEMENT_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 40);
        UUID id = p.getUUID();
        int warn = 30;
        int beamTicks = 100;
        Vec3 up = new Vec3(0, 1, 0);
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), 1.5f, 0.6f);

        // The circle on the ground lasts the whole spell.
        Circles.ground(level, Element.LIGHT, spot, 16, warn + beamTicks + 12);
        // Two halos of light come down out of the sky towards it, turning in opposite directions.
        for (int k = 0; k < 2; k++) {
            double size = k == 0 ? 10 : 7;
            double startY = 16 + k * 5;
            double endY = 3.5 + k * 3.0;
            double spin = k == 0 ? 3.5 : -3.5;
            Props.Prop halo = Props.sprite(level, spot, "circle_light", warn + 24);
            halo.pose(new Vec3(0, startY, 0), Circles.face(up, 0), size * 0.3, size * 0.3, size * 0.3, 0);
            MageCore.after(1, server -> halo.pose(new Vec3(0, endY, 0), Circles.face(up, spin), size, size, size, warn - 3));
            MageCore.after(warn, server -> {
                halo.pose(new Vec3(0, endY - 2, 0), Circles.face(up, spin * 1.5), size * 0.2, size * 0.2, size * 0.2, 6);
                halo.removeAfter(7);
            });
        }

        Props.Prop[] beam = {null};
        long[] t = {0};
        MageCore.run((server, now) -> {
            long age = ++t[0];
            ServerPlayer o = MageCore.playerOf(server, id);

            // ---- act one: gathering
            if (age < warn) {
                double closing = 1.0 - age / (double) warn;
                if (age % 3 == 0) {
                    Fx.ring(level, Fx.dust(LIGHT.rgb1(), 1.3f), spot.add(0, 0.15, 0), 8.0 * closing + 0.5, 40);
                    Fx.ring(level, Fx.dust(LIGHT.rgb2(), 1.0f), spot.add(0, 0.15, 0), 5.0 * closing + 0.3, 28);
                }
                Fx.swirl(level, ParticleTypes.END_ROD, spot, 6.5 * closing + 1, 6, age, 3);
                for (int i = 0; i < 4; i++) { // motes lifting off the circle
                    double a = level.getRandom().nextDouble() * Math.PI * 2;
                    double r = Math.sqrt(level.getRandom().nextDouble()) * 7;
                    Fx.fly(level, ParticleTypes.END_ROD, spot.add(Math.cos(a) * r, 0.2, Math.sin(a) * r), new Vec3(0, 1, 0), 0.12);
                }
                if (age % 10 == 0) {
                    MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 1.2f, 0.7f + (float) age / 40f);
                }
                return false;
            }

            // ---- the strike lands
            if (age == warn) {
                MageCore.flash(level, spot.x, spot.y + 1, spot.z);
                Fx.sphere(level, ParticleTypes.END_ROD, spot.add(0, 1, 0), 80, 0.6);
                Fx.shockwave(level, LIGHT, spot, 10, 12);
                Fx.pillar(level, LIGHT, spot, 18, 2.5, 10);
                MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_THUNDER), 1.6f, 1.5f);
                MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BEACON_POWER_SELECT), 1.5f, 1.0f);
                beam[0] = Props.sprite(level, spot, "light_beam", beamTicks + 30);
                beam[0].pose(new Vec3(0, 22, 0), Circles.face(up, 0), 1.0, 44, 1.0, 0);
                MageCore.after(1, s -> beam[0].pose(new Vec3(0, 22, 0), Circles.face(up, 0), 5.0, 44, 5.0, 4)); // the column bursts open
                if (o != null) {
                    PlayerData d = PlayerDataStore.of(o);
                    for (LivingEntity e : MageCore.foesAt(o, level, spot, 7)) {
                        holy(o, level, e, MageCore.dmg(d, 12));
                        e.setRemainingFireTicks(80);
                        MageCore.knock(e, spot, 0.8, 0.5);
                        Circles.onTarget(level, Element.LIGHT, spot, e, 2.4, 16);
                    }
                }
                return false;
            }

            // ---- act two: the column of light
            long b = age - warn;
            if (b <= beamTicks) {
                if (b % 10 == 0) { // the column breathes in and out
                    double width = 4.2 + 1.0 * Math.sin(b * 0.15);
                    beam[0].pose(new Vec3(0, 22, 0), Circles.face(up, 0), width, 44, width, 10);
                }
                Fx.swirl(level, ParticleTypes.END_ROD, spot, 2.6, 18, age, 4);
                Fx.swirl(level, Fx.fade(LIGHT.rgb1(), LIGHT.rgb2(), 1.3f), spot, 3.6, 14, age + 8, 3);
                Fx.swirl(level, ParticleTypes.GLOW, spot, 1.4, 20, age + 15, 2);
                for (int i = 0; i < 3; i++) { // motes rising up the column
                    double a = level.getRandom().nextDouble() * Math.PI * 2;
                    double r = level.getRandom().nextDouble() * 4.5;
                    Fx.fly(level, ParticleTypes.END_ROD, spot.add(Math.cos(a) * r, 0.3, Math.sin(a) * r), new Vec3(0, 1, 0), 0.25);
                }
                if (b % 20 == 0) { // a ring of light rolls out across the ground
                    Fx.shockwave(level, LIGHT, spot, 8, 12);
                    MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BEACON_AMBIENT), 1.5f, 1.4f);
                }
                if (b % 10 == 0 && o != null) {
                    PlayerData d = PlayerDataStore.of(o);
                    for (LivingEntity e : MageCore.foesAt(o, level, spot, 7)) {
                        holy(o, level, e, MageCore.dmg(d, 1.2));
                        e.setRemainingFireTicks(60);
                        level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY(0.5), e.getZ(), 8, 0.3, 0.5, 0.3, 0.05);
                    }
                }
                return false;
            }

            // ---- act three: the light fades
            beam[0].pose(new Vec3(0, 22, 0), Circles.face(up, 0), 0.2, 44, 0.2, 8);
            beam[0].removeAfter(9);
            MageCore.flash(level, spot.x, spot.y + 1, spot.z);
            Fx.sphere(level, ParticleTypes.END_ROD, spot.add(0, 1, 0), 60, 0.4);
            Fx.shockwave(level, LIGHT, spot, 11, 10);
            MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BEACON_DEACTIVATE), 1.4f, 1.2f);
            return true;
        });
        return true;
    }


    /** Resurrection: next killing blow instead leaves you at half health. */
    public static boolean resurrection(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHT, RESURRECTION_COST);
        for (ServerPlayer other : level.getPlayers(o -> o.isAlive() && o.distanceToSqr(p) <= 100)) {
            MageCore.buff(other, BLESSED, 1200);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, other.getX(), other.getY() + 1, other.getZ(), 30, 0.5, 1.0, 0.5, 0.2);
            Circles.ground(level, Element.LIGHT, other.position(), 5, 50);
            Fx.pillar(level, LIGHT, other.position(), 6, 0.8, 16);
            Fx.spiral(level, ParticleTypes.END_ROD, other.position(), 0.8, 3.0, 4, 20);
            // A halo hangs over each blessed player while the blessing holds.
            MageCore.run((server, now) -> {
                ServerPlayer o = MageCore.playerOf(server, other.getUUID());
                if (o == null || !MageCore.buffed(o, BLESSED)) {
                    return true;
                }
                if (now % 3 == 0) {
                    Vec3 head = o.position().add(0, o.getBbHeight() + 0.35, 0);
                    Fx.ring(o.level(), ParticleTypes.END_ROD, head, 0.35, 10);
                    Fx.ring(o.level(), Fx.dust(LIGHT.rgb2(), 0.8f), head, 0.5, 10);
                }
                return false;
            });
            Compat.overlay(other, Component.literal("A blessing guards you for 60 s: the next killing blow will not land."));
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BEACON_POWER_SELECT), 1.5f, 0.8f);
        return true;
    }

    /** Seraph Form: ultimate light form with flight and a healing aura. */
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
            Fx.shockwave(lv, LIGHT, o.position(), 9, 12);
        });
        CastFx.transform(p, Element.LIGHT);
        FormFx.start(p, Element.LIGHT);
        MageCore.flash(level, p.getX(), p.getY() + 1, p.getZ());
        Compat.overlay(p, Component.literal("You become a Seraph: look to fly, jump to rise, sneak to sink."));
        return true;
    }
}
