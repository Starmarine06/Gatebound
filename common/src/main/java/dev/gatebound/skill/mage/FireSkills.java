package dev.gatebound.skill.mage;

import dev.gatebound.skill.fx.FormFx;
import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fire: Scorch. Burning enemies take extra damage from every fire skill, and an enemy a Mage set alight explodes if it dies
 * while still burning, setting its neighbours alight in turn.
 */
public final class FireSkills {
    public static final int FIREBALL_COST = 10;
    public static final int BURST_COST = 22;
    public static final int IGNITE_COST = 18;
    public static final int WALL_COST = 26;
    public static final int IMMOLATE_COST = 34;
    public static final int COMBUSTION_COST = 45;
    public static final int METEOR_COST = 70;
    public static final int INFERNO_COST = 100;
    public static final int IFRIT_COST = 90;

    private static final Palette FIRE = Palette.of(Element.FIRE);
    private static final String IGNITE = "ignite";
    private static final String IMMOLATE = "immolate";

    /** Who set a thing alight and until when its death still counts as a scorch. */
    private record Scorched(UUID by, long until) {
    }

    private static final Map<UUID, Scorched> SCORCHED = new HashMap<>();

    /** Static holder; not instantiable. */
    private FireSkills() {
    }

    /** Registers the Fire hurt and death hooks. */
    public static void init() {
        EntityEvent.LIVING_HURT.register(FireSkills::onHurt);
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            onDeath(entity, source);
            return EventResult.pass();
        });
        LifecycleEvent.SERVER_STOPPED.register(server -> SCORCHED.clear());
    }

    // ---------------------------------------------------------------- helpers

    /** Fire damage scaled by the player's level, stats and the target. */
    private static float fireDmg(PlayerData data, double base, LivingEntity target) {
        float amount = MageCore.dmg(data, base);
        return target.isOnFire() ? (float) (amount * (1 + MageConfig.scorchBonus)) : amount;
    }

    /** Hits for fire damage and sets the target alight. */
    private static void scorch(ServerPlayer p, ServerLevel level, PlayerData data, LivingEntity target, double base, int fireTicks) {
        MageCore.strike(p, level, target, fireDmg(data, base, target));
        ignite(p, target, fireTicks);
    }

    /** Sets a target on fire for some ticks. */
    private static void ignite(ServerPlayer p, LivingEntity target, int ticks) {
        target.setRemainingFireTicks(Math.max(target.getRemainingFireTicks(), ticks));
        long now = p.level().getServer().getTickCount();
        SCORCHED.put(target.getUUID(), new Scorched(p.getUUID(), now + Math.max(MageConfig.scorchTicks, ticks)));
    }

    /** Small explosion visual at a point. */
    private static void blast(ServerLevel level, Vec3 at, float size) {
        Fx.impact(level, FIRE, at, size * 2.2);
        MageCore.particles(level, ParticleTypes.EXPLOSION, at, 1, size * 0.2, 0);
        MageCore.particles(level, ParticleTypes.LARGE_SMOKE, at.add(0, 0.5, 0), (int) (6 * size), size * 0.4, 0.05);
    }

    // ---------------------------------------------------------------- events

    /** Hurt hook: burning bonuses. */
    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (MageCore.applying() || amount <= 0 || !(victim.level() instanceof ServerLevel level) || victim instanceof ServerPlayer) {
            return EventResult.pass();
        }
        if (source.getEntity() instanceof ServerPlayer p) {
            if (MageCore.buffed(p, IGNITE)) {
                ignite(p, victim, 80);
            }
            if (MageCore.inForm(p, Element.FIRE)) {
                PlayerData data = PlayerDataStore.of(p);
                ignite(p, victim, 100);
                for (LivingEntity e : MageCore.foesAt(p, level, victim.position(), 2.5)) {
                    if (e != victim) {
                        scorch(p, level, data, e, 1.5, 80);
                    }
                }
                MageCore.particles(level, ParticleTypes.FLAME, victim.position().add(0, 0.8, 0), 12, 0.4, 0.1);
            }
        }
        return EventResult.pass();
    }

    /** Death hook: burning kills spread fire. */
    private static void onDeath(LivingEntity entity, DamageSource source) {
        Scorched s = SCORCHED.remove(entity.getUUID());
        if (s == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getServer().getTickCount();
        ServerPlayer p = MageCore.playerOf(level.getServer(), s.by());
        if (s.until() <= now || !entity.isOnFire() || p == null) {
            return;
        }
        PlayerData data = PlayerDataStore.of(p);
        Vec3 at = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
        blast(level, at, 1.6f);
        for (LivingEntity e : MageCore.foesAt(p, level, entity.position(), 3.5)) {
            if (e != entity) {
                scorch(p, level, data, e, 3, 100);
            }
        }
    }

    // ---------------------------------------------------------------- skills

    /** Fireball: explodes and sets enemies alight. */
    public static boolean fireball(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, FIREBALL_COST);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BLAZE_SHOOT), 1.0f, 1.0f);
        Props.Prop[] ball = {null};
        MageCore.bolt(p, level, p.getEyePosition().add(p.getLookAngle().scale(0.6)), p.getLookAngle(), 1.3, 40, 0.7,
                (lv, a, b, tick) -> {
                    // The fireball itself: a textured model that spins as it flies.
                    if (ball[0] == null) {
                        ball[0] = Props.sprite(lv, a, "fireball", 80);
                    }
                    ball[0].pose(b.subtract(ball[0].origin()), Circles.face(new Vec3(0.3, 1, 0.2), tick * 0.9), 1.9, 1.9, 1.9, 1);
                    Fx.trail(lv, FIRE, a, b, tick);
                    lv.sendParticles(ParticleTypes.FLAME, b.x, b.y, b.z, 6, 0.25, 0.25, 0.25, 0.02); // the fireball's head
                    lv.sendParticles(ParticleTypes.LARGE_SMOKE, a.x, a.y, a.z, 1, 0.1, 0.1, 0.1, 0.01);
                    if (tick % 2 == 0) {
                        lv.sendParticles(ParticleTypes.LAVA, b.x, b.y, b.z, 1, 0.2, 0.2, 0.2, 0);
                    }
                },
                (lv, at, target) -> {
                    if (ball[0] != null) {
                        ball[0].remove();
                    }
                    if (target != null) {
                        Circles.onTarget(lv, Element.FIRE, p.position(), target, 2.6, 14); // the circle the fireball lands through
                    }
                    blast(lv, at, 1.2f);
                    for (LivingEntity e : MageCore.foesAt(p, lv, at, 2.5)) {
                        scorch(p, lv, data, e, e == target ? 6 : 3.5, 100);
                    }
                });
        return true;
    }

    /** Flame Burst: burst of flame around you. */
    public static boolean flameBurst(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, BURST_COST);
        for (LivingEntity e : MageCore.foes(p, level, 5)) {
            scorch(p, level, data, e, 3.5, 120);
            MageCore.knock(e, p.position(), 0.5, 0.2);
        }
        Fx.shockwave(level, FIRE, p.position(), 5.5, 8);
        Fx.spiral(level, ParticleTypes.FLAME, p.position(), 1.2, 2.6, 2, 8);
        Fx.sphere(level, ParticleTypes.FLAME, p.position().add(0, 1, 0), 40, 0.45);
        Fx.sphere(level, ParticleTypes.LAVA, p.position().add(0, 1, 0), 6, 0.3);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.FIRECHARGE_USE), 0.9f, 0.8f);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 0.6f, 1.4f);
        return true;
    }

    /** Ignite: weapon and arrows set targets alight; fire resistance. */
    public static boolean ignite(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, IGNITE_COST);
        int ticks = MageConfig.buffSeconds * 20;
        MageCore.buff(p, IGNITE, ticks);
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ticks, 0));
        Fx.spiral(level, ParticleTypes.FLAME, p.position(), 0.9, 2.2, 3, 14);
        Fx.sphere(level, ParticleTypes.SMALL_FLAME, p.position().add(0, 1.2, 0), 24, 0.25);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.FIRECHARGE_USE), 1.0f, 1.3f);
        // Embers cling to the hands while it lasts.
        MageCore.run((server, now) -> {
            if (!MageCore.buffed(p, IGNITE) || !p.isAlive() || p.isRemoved()) {
                return true;
            }
            if (now % 3 == 0) {
                Vec3 hand = p.getEyePosition().add(p.getLookAngle().scale(0.7)).add(0, -0.55, 0);
                p.level().sendParticles(ParticleTypes.SMALL_FLAME, hand.x, hand.y, hand.z, 2, 0.12, 0.12, 0.12, 0.01);
            }
            return false;
        });
        Compat.overlay(p, Component.literal("Your strikes burn."));
        return true;
    }

    /** Flame Wall: wall of fire where you aim. */
    public static boolean flameWall(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, WALL_COST);
        Vec3 flat = MageCore.flatLook(p);
        Vec3 center = MageCore.aimSpot(p, level, 10).subtract(flat.scale(0.3));
        Vec3 side = new Vec3(-flat.z, 0, flat.x);
        MageCore.zone(p, level, center, 6, e -> {
            Vec3 d = e.position().subtract(center);
            return Math.abs(d.dot(side)) <= 4.5 && Math.abs(d.dot(flat)) <= 1.2;
        }, 160, 10,
                in -> {
                    for (LivingEntity e : in) {
                        scorch(p, level, data, e, 1.8, 80);
                    }
                },
                lv -> {
                    // Tongues of flame of different heights licking along the wall.
                    for (int i = -9; i <= 9; i++) {
                        Vec3 q = center.add(side.scale(i * 0.5 + (lv.getRandom().nextDouble() - 0.5) * 0.4));
                        double h = 0.8 + lv.getRandom().nextDouble() * 1.8;
                        lv.sendParticles(ParticleTypes.FLAME, q.x, q.y + h * 0.5, q.z, 1, 0.08, h * 0.5, 0.08, 0.01);
                        if (i % 3 == 0) {
                            lv.sendParticles(ParticleTypes.LARGE_SMOKE, q.x, q.y + h + 0.3, q.z, 1, 0.1, 0.1, 0.1, 0.01);
                            lv.sendParticles(ParticleTypes.LAVA, q.x, q.y + h, q.z, 1, 0.1, 0.1, 0.1, 0);
                        }
                    }
                });
        // The wall erupts from the middle outward.
        for (int i = 0; i <= 9; i++) {
            int n = i;
            MageCore.after(n, server -> {
                for (int s = -1; s <= 1; s += 2) {
                    Vec3 q = center.add(side.scale(n * 0.5 * s));
                    Fx.sphere(level, ParticleTypes.FLAME, q.add(0, 0.5, 0), 6, 0.3);
                    level.sendParticles(ParticleTypes.EXPLOSION, q.x, q.y + 0.3, q.z, 1, 0, 0, 0, 0);
                }
            });
        }
        Circles.ground(level, Element.FIRE, center, 6.0, 40);
        MageCore.sound(level, center, GateboundSounds.of(SoundEvents.FIRECHARGE_USE), 1.0f, 0.6f);
        MageCore.sound(level, center, GateboundSounds.of(SoundEvents.BLAZE_SHOOT), 0.8f, 0.6f);
        return true;
    }

    /** Immolate: burning aura around you. */
    public static boolean immolate(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, IMMOLATE_COST);
        int ticks = 240;
        MageCore.buff(p, IMMOLATE, ticks);
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ticks, 0));
        // The aura follows the caster, so it is a ticker on the player rather than a fixed zone.
        MageCore.run((server, now) -> {
            if (!MageCore.buffed(p, IMMOLATE) || !p.isAlive() || p.isRemoved()) {
                return true;
            }
            ServerLevel lv = p.level();
            Fx.swirl(lv, ParticleTypes.FLAME, p.position(), 2.0, 2.4, now, 4);
            Fx.swirl(lv, Fx.fade(FIRE.rgb1(), FIRE.rgb2(), 1.1f), p.position(), 3.0, 2.0, now + 9, 3);
            if (now % 10 == 0) {
                Fx.ring(lv, Fx.dust(FIRE.rgb1(), 1.0f), p.position().add(0, 0.1, 0), 4.5, 40);
                for (LivingEntity e : MageCore.foes(p, lv, 4.5)) {
                    scorch(p, lv, data, e, 1.3, 80);
                }
            }
            return false;
        });
        Fx.shockwave(level, FIRE, p.position(), 4.5, 6);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BLAZE_AMBIENT), 1.0f, 0.8f);
        return true;
    }

    /** Combustion: detonates every burning enemy nearby. */
    public static boolean combustion(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, COMBUSTION_COST);
        List<LivingEntity> burning = MageCore.foes(p, level, 20).stream().filter(LivingEntity::isOnFire).toList();
        for (LivingEntity e : burning) {
            Vec3 at = e.position().add(0, e.getBbHeight() * 0.5, 0);
            Fx.arc(level, ParticleTypes.FLAME, p.getEyePosition().add(0, -0.3, 0), at, 0.8); // a fuse of fire runs to each target
            Fx.pillar(level, FIRE, e.position(), 4, 0.8, 8);
            Circles.onTarget(level, Element.FIRE, p.position(), e, 2.4, 14);
            blast(level, at, 1.4f);
            MageCore.strike(p, level, e, MageCore.dmg(data, 9));
            e.clearFire();
            for (LivingEntity n : MageCore.foesAt(p, level, e.position(), 3)) {
                if (n != e) {
                    scorch(p, level, data, n, 3, 80);
                }
            }
        }
        return !burning.isEmpty();
    }

    /** Meteor: calls a meteor onto the aimed spot. */
    public static boolean meteor(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, METEOR_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 40);
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BLAZE_SHOOT), 2.0f, 0.5f);
        // A glowing rock that tumbles in from the sky on a slant while the ground below it is marked with a burning circle.
        Vec3 from = new Vec3(-16, 46, -9);
        Props.Prop rock = Props.sprite(level, spot, "meteor", 80);
        rock.pose(from, Circles.face(new Vec3(0, 1, 0), 0), 3.6, 3.6, 3.6, 0);
        MageCore.after(2, server -> rock.pose(new Vec3(0, 1.8, 0), Circles.face(new Vec3(0.6, 1, 0.3), 5.0), 3.6, 3.6, 3.6, 26));
        Circles.ground(level, Element.FIRE, spot, 13, 34); // the circle that marks where it will land
        long[] t = {0};
        MageCore.run((server, now) -> {
            t[0]++;
            if (t[0] % 3 == 0) {
                Fx.ring(level, Fx.dust(FIRE.rgb1(), 1.2f), spot.add(0, 0.1, 0), 6.0 * (1.0 - t[0] / 30.0) + 0.5, 30); // closing ring
            }
            double f = Math.min(1.0, Math.max(0, (t[0] - 2) / 26.0));
            if (f > 0) {
                Vec3 pos = spot.add(from.scale(1 - f)).add(0, 2.6 * f, 0);
                level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 12, 1.0, 1.0, 1.0, 0.04);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 6, 1.2, 1.2, 1.2, 0.03);
                level.sendParticles(ParticleTypes.LAVA, pos.x, pos.y, pos.z, 3, 1.0, 1.0, 1.0, 0);
                Fx.trail(level, FIRE, pos.add(from.normalize().scale(4)), pos, t[0]);
            }
            if (t[0] < 30) {
                return false;
            }
            rock.remove();
            blast(level, spot, 3.0f);
            Fx.pillar(level, FIRE, spot, 9, 2.2, 14);
            Fx.shockwave(level, FIRE, spot, 9, 10);
            Fx.ringOut(level, Fx.dust(0x3A2A1A, 1.6f), spot.add(0, 0.3, 0), 1.0, 24, 0.4); // crater dust
            Fx.sphere(level, ParticleTypes.LAVA, spot.add(0, 1, 0), 24, 0.6);
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, spot.x, spot.y + 0.5, spot.z, 1, 0, 0, 0, 0);
            ServerPlayer o = MageCore.playerOf(server, p.getUUID());
            if (o != null) {
                for (LivingEntity e : MageCore.foesAt(o, level, spot, 6)) {
                    scorch(o, level, data, e, 18, 200);
                    MageCore.knock(e, spot, 1.0, 0.5);
                }
            }
            return true;
        });
        return true;
    }

    /** Inferno: vast field of fire at the aimed spot. */
    public static boolean inferno(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.FIRE, INFERNO_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 30);
        MageCore.zone(p, level, spot, 8, null, 200, 10,
                in -> {
                    for (LivingEntity e : in) {
                        scorch(p, level, data, e, 2.8, 100);
                    }
                },
                lv -> {
                    // Columns of fire rising from the whole field, with smoke and flying embers above it.
                    for (int i = 0; i < 8; i++) {
                        double a = lv.getRandom().nextDouble() * Math.PI * 2;
                        double r = Math.sqrt(lv.getRandom().nextDouble()) * 8;
                        double h = 1.0 + lv.getRandom().nextDouble() * 2.5;
                        lv.sendParticles(ParticleTypes.FLAME, spot.x + Math.cos(a) * r, spot.y + h * 0.5, spot.z + Math.sin(a) * r, 2, 0.1, h * 0.5, 0.1, 0.02);
                        if (i % 3 == 0) {
                            lv.sendParticles(ParticleTypes.LAVA, spot.x + Math.cos(a) * r, spot.y + h, spot.z + Math.sin(a) * r, 1, 0.2, 0.2, 0.2, 0);
                        }
                    }
                    lv.sendParticles(ParticleTypes.LARGE_SMOKE, spot.x, spot.y + 3, spot.z, 2, 3.5, 0.8, 3.5, 0.02);
                });
        Circles.ground(level, Element.FIRE, spot, 17, 200);
        Fx.shockwave(level, FIRE, spot, 8.5, 10);
        Fx.pillar(level, FIRE, spot, 6, 2, 10);
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.BLAZE_SHOOT), 2.0f, 0.4f);
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 1.0f, 0.6f);
        return true;
    }

    /** Ifrit Form: ultimate fire form with a burning aura. */
    public static boolean ifritForm(ServerPlayer p, ServerLevel level, PlayerData data) {
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MageConfig.formSeconds * 20, 0));
        MageCore.form(p, Element.FIRE, 20, o -> {
            ServerLevel lv = o.level();
            PlayerData d = PlayerDataStore.of(o);
            for (LivingEntity e : MageCore.foes(o, lv, 4)) {
                scorch(o, lv, d, e, 2, 80);
            }
        });
        CastFx.transform(p, Element.FIRE);
        FormFx.start(p, Element.FIRE);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.BLAZE_AMBIENT), 1.5f, 0.5f);
        Compat.overlay(p, Component.literal("You become Ifrit."));
        return true;
    }
}
