package dev.gatebound.skill.mage;

import dev.gatebound.skill.fx.FormFx;
import dev.gatebound.skill.fx.CastFx;
import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.skill.ManaState;
import dev.gatebound.skill.SkillHandler;
import dev.gatebound.skill.SkillTargeting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dark: Blight. Dark skills lay Curse on enemies. A cursed enemy takes 20% more from the Mage who cursed it, and when it
 * dies it feeds that Mage: mana and a little health. The strongest dark spells cost some of the caster's own health.
 */
public final class DarkSkills {
    public static final int BOLT_COST = 10;
    public static final int GRASP_COST = 20;
    public static final int HEX_COST = 14;
    public static final int SIPHON_COST = 28;
    public static final int DREAD_COST = 30;
    public static final int HARVEST_COST = 42;
    public static final int HOLE_COST = 70;
    public static final int ECLIPSE_COST = 95;
    public static final int VOID_COST = 90;

    private static final String ECLIPSE = "eclipse";
    private static final int CURSE_TICKS = 300;

    private record Curse(UUID by, long until) {
    }

    private static final Map<UUID, Curse> CURSED = new HashMap<>();

    private DarkSkills() {
    }

    public static void init() {
        EntityEvent.LIVING_HURT.register(DarkSkills::onHurt);
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            onDeath(entity);
            return EventResult.pass();
        });
        LifecycleEvent.SERVER_STOPPED.register(server -> CURSED.clear());
    }

    // ---------------------------------------------------------------- helpers

    private static boolean cursedBy(ServerPlayer p, LivingEntity e) {
        Curse c = CURSED.get(e.getUUID());
        return c != null && c.by().equals(p.getUUID()) && c.until() > e.level().getServer().getTickCount();
    }

    private static void curse(ServerPlayer p, ServerLevel level, LivingEntity e) {
        CURSED.put(e.getUUID(), new Curse(p.getUUID(), (long) level.getServer().getTickCount() + CURSE_TICKS));
        dev.gatebound.skill.fx.SpellFx.curse(e, CURSE_TICKS);
    }

    /** Dark damage; a cursed target takes 20% more. */
    private static void blight(ServerPlayer p, ServerLevel level, LivingEntity target, float amount, boolean applyCurse) {
        MageCore.strike(p, level, target, cursedBy(p, target) ? amount * 1.2f : amount);
        if (applyCurse) {
            curse(p, level, target);
        }
    }

    private static void drain(ServerPlayer p, double fractionOfMax) {
        ManaState.add(p, ManaState.max(PlayerDataStore.of(p)) * fractionOfMax);
        SkillHandler.sendMana(p, true);
    }

    // ---------------------------------------------------------------- events

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (MageCore.applying() || amount <= 0 || victim instanceof ServerPlayer || !(victim.level() instanceof ServerLevel level)
                || !(source.getEntity() instanceof ServerPlayer p)) {
            return EventResult.pass();
        }
        boolean lord = MageCore.inForm(p, Element.DARK);
        if (lord || MageCore.buffed(p, ECLIPSE)) {
            p.heal(amount * (lord ? 0.25f : 0.2f));
        }
        if (lord) {
            curse(p, level, victim);
            for (LivingEntity e : MageCore.foesAt(p, level, victim.position(), 4)) {
                curse(p, level, e);
            }
        }
        return EventResult.pass();
    }

    private static void onDeath(LivingEntity entity) {
        Curse c = CURSED.remove(entity.getUUID());
        if (c == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer p = MageCore.playerOf(level.getServer(), c.by());
        if (p == null || c.until() <= level.getServer().getTickCount()) {
            return;
        }
        drain(p, 0.08);
        p.heal(2.0f);
        level.sendParticles(ParticleTypes.SOUL, entity.getX(), entity.getY() + 0.8, entity.getZ(), 10, 0.3, 0.4, 0.3, 0.05);
        MageCore.line(level, ParticleTypes.SOUL, entity.position().add(0, 0.8, 0), p.position().add(0, 1, 0));
    }

    // ---------------------------------------------------------------- skills

    public static boolean shadowBolt(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, BOLT_COST);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.WITHER_SHOOT), 0.5f, 1.8f);
        dev.gatebound.skill.fx.Props.Prop[] orb = {null};
        MageCore.bolt(p, level, p.getEyePosition().add(p.getLookAngle().scale(0.5)), p.getLookAngle(), 1.5, 36, 0.6,
                (lv, a, b, tick) -> {
                    dev.gatebound.skill.fx.SpellFx.ride(orb, lv, "void_sphere", a, b, tick, 1.5, 1.5, 1.5, false);
                    MageCore.particles(lv, ParticleTypes.PORTAL, a, 2, 0.15, 0.2);
                },
                (lv, at, target) -> {
                    dev.gatebound.skill.fx.SpellFx.stop(orb);
                    if (target != null) {
                        dev.gatebound.skill.fx.SpellFx.tendrils(lv, target);
                        blight(p, lv, target, MageCore.dmg(data, 5), true);
                    }
                });
        return true;
    }

    public static boolean voidGrasp(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, GRASP_COST);
        for (LivingEntity e : MageCore.foes(p, level, 9)) {
            blight(p, level, e, MageCore.dmg(data, 3), true);
            Vec3 pull = p.position().subtract(e.position());
            Vec3 flat = new Vec3(pull.x, 0, pull.z);
            flat = flat.lengthSqr() < 1.0e-4 ? Vec3.ZERO : flat.normalize().scale(0.8);
            e.setDeltaMovement(flat.x, 0.1, flat.z);
            e.needsSync = true;
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 50, 10));
            dev.gatebound.skill.fx.SpellFx.tendrils(level, e);
        }
        dev.gatebound.skill.fx.SpellFx.voidPulse(p, 9);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.WARDEN_SONIC_CHARGE), 0.6f, 1.4f);
        return true;
    }

    public static boolean hex(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, HEX_COST);
        List<LivingEntity> hit = SkillTargeting.ray(p, level, 26, 1, ParticleTypes.PORTAL);
        if (hit.isEmpty()) {
            return false;
        }
        LivingEntity t = hit.get(0);
        blight(p, level, t, MageCore.dmg(data, 3), true);
        t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200, 2));
        t.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0));
        CURSED.put(t.getUUID(), new Curse(p.getUUID(), level.getServer().getTickCount() + 600L));
        dev.gatebound.skill.fx.SpellFx.tendrils(level, t);
        dev.gatebound.skill.fx.SpellFx.thread(level, p.getEyePosition().add(0, -0.3, 0), t.position().add(0, t.getBbHeight() * 0.6, 0), 0.6, 8);
        MageCore.sound(level, t.position(), GateboundSounds.of(SoundEvents.EVOKER_CAST_SPELL), 1.0f, 0.6f);
        return true;
    }

    public static boolean lifeSiphon(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, SIPHON_COST);
        for (int i = 0; i < 12; i++) {
            MageCore.after(i * 5, server -> {
                ServerPlayer o = MageCore.playerOf(server, p.getUUID());
                if (o == null) {
                    return;
                }
                List<LivingEntity> hit = SkillTargeting.ray(o, level, 14, 1, null);
                if (hit.isEmpty()) {
                    return;
                }
                LivingEntity t = hit.get(0);
                blight(o, level, t, MageCore.dmg(data, 2.5), true);
                o.heal(1.2f);
                dev.gatebound.skill.fx.SpellFx.thread(level, t.position().add(0, t.getBbHeight() * 0.6, 0), o.getEyePosition().add(0, -0.3, 0), 0.5, 5);
                MageCore.sound(level, t.position(), GateboundSounds.of(SoundEvents.SOUL_ESCAPE.value()), 0.5f, 1.2f);
            });
        }
        return true;
    }

    public static boolean dread(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, DREAD_COST);
        List<LivingEntity> foes = MageCore.foes(p, level, 11);
        for (LivingEntity e : foes) {
            curse(p, level, e);
        }
        for (int t = 0; t < 80; t += 4) {
            MageCore.after(t, server -> {
                for (LivingEntity e : foes) {
                    if (!e.isAlive()) {
                        continue;
                    }
                    if (e instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                    Vec3 away = e.position().subtract(p.position());
                    Vec3 flat = new Vec3(away.x, 0, away.z);
                    flat = flat.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : flat.normalize().scale(0.35);
                    e.setDeltaMovement(flat.x, e.getDeltaMovement().y, flat.z);
                    e.needsSync = true;
                    level.sendParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + e.getBbHeight() + 0.2, e.getZ(), 2, 0.2, 0.1, 0.2, 0.01);
                }
            });
        }
        dev.gatebound.skill.fx.SpellFx.voidPulse(p, 11);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.WARDEN_ROAR), 0.8f, 1.2f);
        return true;
    }

    public static boolean soulHarvest(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, HARVEST_COST);
        int reaped = 0;
        for (LivingEntity e : MageCore.foes(p, level, 18)) {
            if (cursedBy(p, e)) {
                reaped++;
                Vec3 at = e.position().add(0, e.getBbHeight() * 0.5, 0);
                dev.gatebound.skill.fx.SpellFx.thread(level, at, p.position().add(0, 1, 0), 0.7, 8);
                dev.gatebound.skill.fx.SpellFx.tendrils(level, e);
                blight(p, level, e, MageCore.dmg(data, 10), false);
            }
        }
        if (reaped > 0) {
            MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.SOUL_ESCAPE.value()), 1.2f, 0.7f);
        }
        return reaped > 0;
    }

    public static boolean blackHole(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, HOLE_COST);
        MageCore.sacrifice(p, 4.0f);
        Vec3 spot = MageCore.aimSpot(p, level, 25).add(0, 1.2, 0);
        dev.gatebound.skill.fx.SpellFx.blackHole(level, spot, 120);
        MageCore.zone(p, level, spot, 11, null, 120, 2,
                in -> {
                    for (LivingEntity e : in) {
                        Vec3 pull = spot.subtract(e.position().add(0, e.getBbHeight() * 0.5, 0)).normalize().scale(0.5);
                        e.setDeltaMovement(pull);
                        e.needsSync = true;
                    }
                },
                lv -> { });
        MageCore.after(122, server -> {
            ServerPlayer o = MageCore.playerOf(server, p.getUUID());
            dev.gatebound.skill.fx.SpellFx.collapse(level, spot);
            MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 1.5f, 0.5f);
            if (o != null) {
                for (LivingEntity e : MageCore.foesAt(o, level, spot, 5)) {
                    blight(o, level, e, MageCore.dmg(data, 16), true);
                }
            }
        });
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.END_PORTAL_SPAWN), 1.0f, 0.5f);
        return true;
    }

    public static boolean eclipse(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.DARK, ECLIPSE_COST);
        MageCore.sacrifice(p, 4.0f);
        for (LivingEntity e : MageCore.foes(p, level, 24)) {
            e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 160, 0));
            blight(p, level, e, MageCore.dmg(data, 5), true);
        }
        MageCore.buff(p, ECLIPSE, 240);
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, 240, 1));
        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 240, 0));
        dev.gatebound.skill.fx.SpellFx.eclipse(p, 240);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.WARDEN_SONIC_BOOM), 1.0f, 0.5f);
        Compat.overlay(p, Component.literal("The light dies. For 12 s you drain what you strike."));
        return true;
    }

    public static boolean voidLord(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.sacrifice(p, 6.0f);
        int ticks = MageConfig.formSeconds * 20;
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 1));
        MageCore.form(p, Element.DARK, 10, o -> {
            ServerLevel lv = o.level();
            PlayerData d = PlayerDataStore.of(o);
            for (LivingEntity e : MageCore.foes(o, lv, 6)) {
                blight(o, lv, e, MageCore.dmg(d, 2.5), true);
                o.heal(0.6f);
            }
        });
        CastFx.transform(p, Element.DARK);
        FormFx.start(p, Element.DARK);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.WARDEN_EMERGE), 1.0f, 0.7f);
        Compat.overlay(p, Component.literal("You become the Void Lord."));
        return true;
    }
}
