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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lightning: Static. A lightning hit leaves Static on the target; hitting a target that already has Static chains the
 * shock on to the nearest enemy and spends it. Lightning is speed and chaining.
 */
public final class LightningSkills {
    public static final int ARC_COST = 10;
    public static final int STORM_COST = 24;
    public static final int STEP_COST = 16;
    public static final int OVERCHARGE_COST = 20;
    public static final int CHAIN_COST = 30;
    public static final int ROD_COST = 45;
    public static final int STRIKE_COST = 70;
    public static final int SURGE_COST = 90;
    public static final int GOD_COST = 90;

    private static final String OVERCHARGE = "overcharge";
    private static final int STATIC_TICKS = 160;

    private static final Map<UUID, Long> STATIC = new HashMap<>();

    private LightningSkills() {
    }

    public static void init() {
        EntityEvent.LIVING_HURT.register(LightningSkills::onHurt);
        LifecycleEvent.SERVER_STOPPED.register(server -> STATIC.clear());
    }

    // ---------------------------------------------------------------- helpers

    private static boolean charged(LivingEntity e) {
        Long until = STATIC.get(e.getUUID());
        return until != null && until > e.level().getServer().getTickCount();
    }

    /** Shocks a target. If it already held Static the shock jumps to the nearest other enemy. */
    private static void zap(ServerPlayer p, ServerLevel level, LivingEntity target, float damage) {
        boolean jump = charged(target);
        MageCore.strike(p, level, target, damage);
        Vec3 at = target.position().add(0, target.getBbHeight() * 0.5, 0);
        dev.gatebound.skill.fx.SpellFx.shockAt(level, "lightning", target.position(), 2.4);
        if (jump) {
            STATIC.remove(target.getUUID());
            LivingEntity next = nearest(p, level, target, 7, new HashSet<>());
            if (next != null) {
                dev.gatebound.skill.fx.SpellFx.bolt(level, at, next.position().add(0, next.getBbHeight() * 0.5, 0), 0.8, 6);
                MageCore.strike(p, level, next, damage * 0.6f);
                STATIC.put(next.getUUID(), (long) level.getServer().getTickCount() + STATIC_TICKS);
            }
        } else {
            STATIC.put(target.getUUID(), (long) level.getServer().getTickCount() + STATIC_TICKS);
        }
    }

    private static LivingEntity nearest(ServerPlayer p, ServerLevel level, LivingEntity from, double range, Set<UUID> skip) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : MageCore.foesAt(p, level, from.position(), range)) {
            if (e == from || skip.contains(e.getUUID())) {
                continue;
            }
            double d = e.distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** Moves the player up to {@code distance} along their look (flat), stopping at walls. Returns start and end. */
    private static Vec3[] blink(ServerPlayer p, ServerLevel level, double distance) {
        Vec3 from = p.position();
        Vec3 dir = MageCore.flatLook(p);
        Vec3 eye = from.add(0, 1.0, 0);
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(distance)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double travel = hit.getType() == HitResult.Type.MISS ? distance : Math.max(0, eye.distanceTo(hit.getLocation()) - 0.8);
        Vec3 to = from.add(dir.scale(travel));
        p.teleportTo(level, to.x, to.y, to.z, Set.of(), p.getYRot(), p.getXRot(), true);
        return new Vec3[]{from, to};
    }

    private static void shockPath(ServerPlayer p, ServerLevel level, PlayerData data, Vec3 from, Vec3 to, double base) {
        dev.gatebound.skill.fx.SpellFx.dash(level, from, to, base > 6 ? 1.8 : 1.0);
        Vec3 seg = to.subtract(from);
        Set<UUID> hit = new HashSet<>();
        int steps = Math.max(1, (int) seg.length());
        for (int i = 0; i <= steps; i++) {
            Vec3 q = from.add(seg.scale(i / (double) steps));
            for (LivingEntity e : MageCore.foesAt(p, level, q, 1.6)) {
                if (hit.add(e.getUUID())) {
                    zap(p, level, e, MageCore.dmg(data, base));
                }
            }
        }
    }

    // ---------------------------------------------------------------- events

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (MageCore.applying() || amount <= 0 || victim instanceof ServerPlayer || !(victim.level() instanceof ServerLevel level)
                || !(source.getEntity() instanceof ServerPlayer p)) {
            return EventResult.pass();
        }
        boolean god = MageCore.inForm(p, Element.LIGHTNING);
        if (god || MageCore.buffed(p, OVERCHARGE)) {
            PlayerData data = PlayerDataStore.of(p);
            zap(p, level, victim, MageCore.dmg(data, god ? 3 : 2));
        }
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- skills

    public static boolean arcBolt(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, ARC_COST);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_THUNDER), 0.6f, 2.0f);
        dev.gatebound.skill.fx.Props.Prop[] orb = {null};
        MageCore.bolt(p, level, p.getEyePosition().add(p.getLookAngle().scale(0.5)), p.getLookAngle(), 2.6, 36, 0.6,
                (lv, a, b, tick) -> {
                    dev.gatebound.skill.fx.SpellFx.ride(orb, lv, "bolt", a, b, tick, 0.9, 2.6, 0.9, true);
                    MageCore.line(lv, ParticleTypes.ELECTRIC_SPARK, a, b);
                },
                (lv, at, target) -> {
                    dev.gatebound.skill.fx.SpellFx.stop(orb);
                    dev.gatebound.skill.fx.SpellFx.shockAt(lv, "lightning", target != null ? target.position() : at.subtract(0, 0.5, 0), 2.6);
                    if (target != null) {
                        zap(p, lv, target, MageCore.dmg(data, 6));
                    }
                });
        return true;
    }

    public static boolean thunderStorm(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, STORM_COST);
        List<LivingEntity> foes = MageCore.foes(p, level, 7);
        for (LivingEntity e : foes) {
            MageCore.visualBolt(level, e.position());
            dev.gatebound.skill.fx.SpellFx.column(level, e.position(), 9, 1.6, 5);
            zap(p, level, e, MageCore.dmg(data, 4.5));
        }
        dev.gatebound.skill.fx.SpellFx.stormStart(p, p.position(), 14, 24);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_THUNDER), 1.0f, 1.2f);
        return true;
    }

    public static boolean staticStep(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, STEP_COST);
        Vec3[] path = blink(p, level, 9);
        shockPath(p, level, data, path[0], path[1], 4);
        MageCore.sound(level, path[1], GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_IMPACT), 0.5f, 2.0f);
        return true;
    }

    public static boolean overcharge(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, OVERCHARGE_COST);
        int ticks = MageConfig.buffSeconds * 20;
        MageCore.buff(p, OVERCHARGE, ticks);
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 0));
        p.addEffect(new MobEffectInstance(MobEffects.HASTE, ticks, 1));
        dev.gatebound.skill.fx.SpellFx.overcharge(p, ticks);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_IMPACT), 0.6f, 1.6f);
        Compat.overlay(p, Component.literal("Lightning crackles along your arms."));
        return true;
    }

    public static boolean chainLightning(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, CHAIN_COST);
        List<LivingEntity> first = dev.gatebound.skill.SkillTargeting.ray(p, level, 28, 1, ParticleTypes.ELECTRIC_SPARK);
        if (first.isEmpty()) {
            return false;
        }
        Set<UUID> seen = new HashSet<>();
        LivingEntity cur = first.get(0);
        float damage = MageCore.dmg(data, 9);
        Vec3 prev = p.getEyePosition();
        for (int hop = 0; hop < 8 && cur != null; hop++) {
            seen.add(cur.getUUID());
            Vec3 at = cur.position().add(0, cur.getBbHeight() * 0.5, 0);
            dev.gatebound.skill.fx.SpellFx.bolt(level, prev, at, 0.8, 6);
            dev.gatebound.skill.fx.SpellFx.shockAt(level, "lightning", cur.position(), 2.2);
            MageCore.strike(p, level, cur, damage);
            STATIC.put(cur.getUUID(), (long) level.getServer().getTickCount() + STATIC_TICKS);
            damage *= 0.88f;
            prev = at;
            cur = nearest(p, level, cur, 7, seen);
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_THUNDER), 0.8f, 1.5f);
        return true;
    }

    public static boolean lightningRod(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, ROD_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 20);
        dev.gatebound.skill.fx.SpellFx.rod(level, spot, 240);
        MageCore.zone(p, level, spot, 11, null, 240, 20,
                in -> {
                    LivingEntity target = null;
                    double best = Double.MAX_VALUE;
                    for (LivingEntity e : in) {
                        double d = e.distanceToSqr(spot.x, spot.y, spot.z);
                        if (d < best) {
                            best = d;
                            target = e;
                        }
                    }
                    if (target != null) {
                        MageCore.visualBolt(level, target.position());
                        dev.gatebound.skill.fx.SpellFx.rodStrike(level, spot, target);
                        zap(p, level, target, MageCore.dmg(data, 7));
                    }
                },
                lv -> { });
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.TRIDENT_THUNDER.value()), 1.0f, 1.0f);
        return true;
    }

    public static boolean thunderstrike(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, STRIKE_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 40);
        dev.gatebound.skill.fx.SpellFx.stormStart(p, spot, 11, 24);
        MageCore.after(18, server -> {
            ServerPlayer o = MageCore.playerOf(server, p.getUUID());
            MageCore.visualBolt(level, spot);
            MageCore.visualBolt(level, spot.add(1.5, 0, 0));
            dev.gatebound.skill.fx.SpellFx.column(level, spot, 14, 3.2, 8);
            dev.gatebound.skill.fx.SpellFx.column(level, spot.add(1.5, 0, 0.5), 12, 2.0, 8);
            dev.gatebound.skill.fx.SpellFx.shockAt(level, "lightning", spot, 11);
            level.sendParticles(ParticleTypes.EXPLOSION, spot.x, spot.y + 0.5, spot.z, 3, 1, 0.3, 1, 0);
            if (o != null) {
                for (LivingEntity e : MageCore.foesAt(o, level, spot, 5.5)) {
                    zap(o, level, e, MageCore.dmg(data, 17));
                    e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 5));
                }
            }
        });
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_THUNDER), 1.5f, 0.8f);
        return true;
    }

    public static boolean stormSurge(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.LIGHTNING, SURGE_COST);
        Vec3[] path = blink(p, level, 16);
        shockPath(p, level, data, path[0], path[1], 10);
        dev.gatebound.skill.fx.SpellFx.cloud(level, path[1].add(0, 7, 0), 9, 14);
        MageCore.sound(level, path[1], GateboundSounds.of(SoundEvents.LIGHTNING_BOLT_THUNDER), 0.8f, 1.8f);
        return true;
    }

    public static boolean thunderGod(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = MageConfig.formSeconds * 20;
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 2));
        p.addEffect(new MobEffectInstance(MobEffects.HASTE, ticks, 2));
        MageCore.form(p, Element.LIGHTNING, 10, o -> {
            ServerLevel lv = o.level();
            PlayerData d = PlayerDataStore.of(o);
            List<LivingEntity> foes = MageCore.foes(o, lv, 12);
            if (!foes.isEmpty()) {
                LivingEntity target = foes.get(o.getRandom().nextInt(foes.size()));
                MageCore.visualBolt(lv, target.position());
                zap(o, lv, target, MageCore.dmg(d, 4));
            }
        });
        MageCore.visualBolt(level, p.position());
        CastFx.transform(p, Element.LIGHTNING);
        FormFx.start(p, Element.LIGHTNING);
        Compat.overlay(p, Component.literal("You become the Thunder God."));
        return true;
    }
}
