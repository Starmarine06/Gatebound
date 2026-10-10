package dev.gatebound.skill.fx;

import dev.gatebound.data.Element;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Random;

import static dev.gatebound.skill.fx.Anim.*;

/**
 * Warrior effects: weight, impact and steel. Mostly textured models (slash crescents, ground cracks, stone spikes, steel
 * plates, a spectral sword, a shield sigil, a blade ring) with a few particles as accents. Gameplay lives in WarriorSkills; this
 * class only draws. Model orientation (which way the crescent bulges) is set by {@link #bulge} and has not been checked in game.
 */
public final class WarriorFx {
    private static final Palette P = Palette.of("warrior");
    private static final Random RNG = new Random();

    private WarriorFx() {
    }

    // ---------------------------------------------------------------- primitives

    /** Spin that makes a flat slash lying on the ground bulge towards {@code dir} (the texture's top points north). */
    private static double bulge(Vec3 dir) {
        return Math.atan2(-dir.x, -dir.z);
    }

    /** A flat decal on the ground. */
    private static void decal(ServerLevel level, Vec3 ground, String model, double size, int hold) {
        pop(level, ground.add(0, 0.07, 0), model, Circles.face(UP, RNG.nextDouble() * Math.PI * 2), size, hold);
    }

    /** An upright slash across a point, on the side facing {@code from}. */
    private static void slashAcross(ServerLevel level, Vec3 centre, Vec3 from, double tilt, double size, int hold) {
        Vec3 toward = flat(from.subtract(centre));
        pop(level, centre.add(toward.scale(0.5)), "slash", Circles.face(toward, tilt), size, hold);
    }

    /** A slash mark on an enemy, tilted at random. */
    public static void hitSlash(ServerLevel level, LivingEntity target, Vec3 from) {
        Vec3 mid = target.position().add(0, target.getBbHeight() * 0.55, 0);
        slashAcross(level, mid, from, (RNG.nextDouble() - 0.5) * 2.2, Math.max(2.0, target.getBbHeight() * 1.5), 6);
        Tasks.particles(level, ParticleTypes.CRIT, mid, 8, 0.3, 0.3);
    }

    /** Stone chunks thrown up and out from a point, falling back. Capped by the caller. */
    private static void debris(ServerLevel level, Vec3 centre, double radius, int count) {
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count + RNG.nextDouble() * 0.6;
            Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
            double far = radius * (0.5 + RNG.nextDouble() * 0.5);
            double size = 0.45 + RNG.nextDouble() * 0.3;
            Props.Prop chunk = Props.sprite(level, centre.add(0, 0.2, 0), "stone_spike", 26);
            Quaternionf rot = new Quaternionf().rotationXYZ((float) RNG.nextDouble() * 3, (float) RNG.nextDouble() * 3, (float) RNG.nextDouble() * 3);
            chunk.pose(Vec3.ZERO, rot, size * 0.2, size * 0.2, size * 0.2, 0);
            Tasks.after(1, s -> chunk.pose(dir.scale(far * 0.6).add(0, 1.8 + RNG.nextDouble(), 0), rot, size, size, size, 6));
            Tasks.after(8, s -> chunk.pose(dir.scale(far).add(0, 0.1, 0), rot, size, size, size, 7));
            Tasks.after(18, s -> {
                chunk.pose(dir.scale(far).add(0, 0.1, 0), rot, 0.02, 0.02, 0.02, 6);
                chunk.removeAfter(7);
            });
        }
    }

    // ---------------------------------------------------------------- slam family

    /** Ground slam: crack decal, textured shockwave rings, flying stone chunks and a dust burst. */
    public static void slam(ServerPlayer p, ServerLevel level, double radius, boolean heavy) {
        Vec3 at = p.position();
        decal(level, at, "crack", radius * 2.4, heavy ? 44 : 30);
        Fx.shockwave(level, P, at, radius * 1.2, 8);
        if (heavy) {
            Tasks.after(3, s -> Fx.shockwave(level, P, at, radius * 1.6, 9));
        }
        debris(level, at, radius, heavy ? 6 : 4);
        Fx.sphere(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, at.add(0, 0.3, 0), 14, 0.12);
        Tasks.flash(level, at.x, at.y + 0.5, at.z);
    }

    public static void earthbreakStart(ServerPlayer p) {
        ServerLevel level = lv(p);
        Circles.ground(level, "warrior", p.position(), 5.0, 22);
        Fx.shockwave(level, P, p.position(), 4, 6);
    }

    /** One wave of Earthbreaker: a ring of stone spikes bursts up at the wave's radius, with a crack and a ring. */
    public static void earthbreakWave(ServerPlayer p, ServerLevel level, double radius, int wave) {
        Vec3 at = p.position();
        decal(level, at, "crack", radius * 2.3, 40);
        Fx.shockwave(level, P, at, radius, 7);
        int spikes = 4;
        for (int i = 0; i < spikes; i++) {
            double a = Math.PI * 2 * (i + wave * 0.5) / spikes + RNG.nextDouble() * 0.4;
            Vec3 base = at.add(Math.cos(a) * radius * 0.85, 0, Math.sin(a) * radius * 0.85);
            double h = 3.2 + RNG.nextDouble() * 1.4;
            Props.Prop spike = Props.sprite(level, base, "stone_spike", 40);
            Quaternionf rot = yaw(RNG.nextDouble() * 3);
            spike.pose(Vec3.ZERO, rot, 1.2, 0.1, 1.2, 0);
            Tasks.after(1, s -> spike.pose(new Vec3(0, h * 0.5 - 0.3, 0), rot, 1.5, h, 1.5, 4));
            Tasks.after(26, s -> {
                spike.pose(new Vec3(0, -0.2, 0), rot, 1.3, 0.1, 1.3, 8);
                spike.removeAfter(9);
            });
        }
        Fx.sphere(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, at.add(0, 0.3, 0), 16, 0.15);
    }

    // ---------------------------------------------------------------- Charge, Leap

    /** Afterimages and a dust wake while charging or leaping; call every other tick. */
    public static void streak(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 dir = flat(p.getDeltaMovement());
        pop(level, p.position().add(0, 0.9, 0).subtract(dir.scale(0.6)), "slash", Circles.face(UP, bulge(dir.scale(-1))), 2.6, 3);
        Tasks.particles(level, ParticleTypes.CLOUD, p.position().add(0, 0.15, 0), 3, 0.3, 0.02);
    }

    public static void leapStart(ServerPlayer p) {
        ServerLevel level = lv(p);
        Fx.shockwave(level, P, p.position(), 2.6, 6);
        decal(level, p.position(), "crack", 3.2, 14);
        Fx.sphere(level, ParticleTypes.CLOUD, p.position().add(0, 0.2, 0), 14, 0.1);
    }

    // ---------------------------------------------------------------- shouts and buffs

    /** Sigil, three sound rings, a raised spectral sword as a banner and an ember pillar. */
    public static void warCry(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 at = p.position();
        Circles.ground(level, "warrior", at, 7.0, 32);
        for (int i = 0; i < 3; i++) {
            double r = 5 + i * 3.5;
            Tasks.after(i * 4, s -> Circles.ring(level, "warrior", at, r, 9));
        }
        pop(level, at.add(0, 3.6, 0), "sword", yaw(0), 2.6, 3.4, 2.6, 24);
        Fx.pillar(level, P, at, 6, 1.2, 14);
        Tasks.flash(level, at.x, at.y + 1.8, at.z);
    }

    /** Steel plates circling the body and a faint sigil under the feet, drawn by the client for the whole duration. */
    public static void ironSkin(ServerPlayer p, int ticks) {
        FxState.set(p, "iron_skin", ticks);
        FxState.set(p, "circle:warrior:2.6", Math.min(ticks, 50));
        Anim.during(p, ticks, 10, (o, lv, age) -> Tasks.particles(lv, ParticleTypes.CRIT, o.position().add(0, 1.0, 0), 3, 0.5, 0.1), null);
    }

    /** Heal burst: gold circle under the player, rising light and rings. */
    public static void secondWind(ServerPlayer p) {
        ServerLevel level = lv(p);
        Palette light = Palette.of(Element.LIGHT);
        FxState.set(p, "circle:light:3.4", 36);
        Circles.ring(level, "light", p.position(), 3.6, 9);
        Fx.pillar(level, light, p.position(), 3.2, 0.8, 14);
        Fx.spiral(level, ParticleTypes.HAPPY_VILLAGER, p.position(), 0.8, 2.2, 2, 16);
    }

    /** A red sigil under the Warrior (drawn by the client) and an ember mantle for the length of the rage. */
    public static void rage(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Circles.ground(level, "warrior", p.position(), 6.0, 26);
        Circles.ring(level, "warrior", p.position(), 6, 8);
        Tasks.after(4, s -> Circles.ring(level, "warrior", p.position(), 9, 9));
        Fx.pillar(level, P, p.position(), 5, 1.0, 12);
        FxState.set(p, "circle:warrior:2.8", ticks);
        during(p, ticks, 4, (o, lv, age) -> {
            Tasks.particles(lv, ParticleTypes.FLAME, o.position().add(0, 1.2, 0), 2, 0.4, 0.01);
            Tasks.particles(lv, ParticleTypes.CAMPFIRE_COSY_SMOKE, o.position().add(0, 2.0, 0), 1, 0.2, 0.01);
        }, null);
    }

    // ---------------------------------------------------------------- melee

    /** A huge crescent sweeps across the arc in front of the player. */
    public static void cleave(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 look = flat(p.getLookAngle());
        Vec3 at = p.position().add(look.scale(1.9)).add(0, 1.0, 0);
        double face = bulge(look);
        Props.Prop slash = Props.sprite(level, at, "slash", 24);
        slash.pose(Vec3.ZERO, Circles.face(UP, face - 1.2), 2.0, 2.0, 2.0, 0);
        Tasks.after(1, s -> slash.pose(Vec3.ZERO, Circles.face(UP, face - 0.2), 5.6, 5.6, 5.6, 2));
        Tasks.after(3, s -> slash.pose(Vec3.ZERO, Circles.face(UP, face + 1.2), 5.6, 5.6, 5.6, 3));
        Tasks.after(7, s -> {
            slash.pose(Vec3.ZERO, Circles.face(UP, face + 1.4), 6.0, 0.05, 6.0, 5);
            slash.removeAfter(6);
        });
    }

    /** Spinning blade ring around the body (drawn by the client) with a wind spiral for the length of the whirl. */
    public static void whirlwind(ServerPlayer p) {
        FxState.set(p, "whirlwind", 22);
        during(p, 22, 4, (o, lv, age) -> Fx.spiral(lv, ParticleTypes.CLOUD, o.position(), 2.2, 1.8, 1, 6), null);
    }

    /** Three claw slashes on the target, and a sigil under it while it bleeds. */
    public static void rending(ServerPlayer p, LivingEntity target, int bleedTicks) {
        ServerLevel level = lv(p);
        Vec3 mid = target.position().add(0, target.getBbHeight() * 0.55, 0);
        double size = Math.max(2.2, target.getBbHeight() * 1.6);
        for (int i = 0; i < 3; i++) {
            int k = i;
            Tasks.after(i, s -> {
                Vec3 side = flat(p.position().subtract(mid));
                Vec3 across = new Vec3(-side.z, 0, side.x).scale((k - 1) * 0.55);
                slashAcross(level, mid.add(across), p.position(), 0.7, size, 7);
            });
        }
        Circles.follow(target, "warrior", Math.max(1.8, target.getBbWidth() * 2.2), bleedTicks, 0.06);
    }

    public static void bleedTick(ServerLevel level, LivingEntity target) {
        Tasks.particles(level, Fx.dust(0xB01818, 1.0f), target.position().add(0, target.getBbHeight() * 0.6, 0), 6, 0.25, 0.0);
    }

    /** The big one: an enormous slash, a crack under the target, a heavy ring and flying stone. */
    public static void crushing(ServerPlayer p, LivingEntity target) {
        ServerLevel level = lv(p);
        Vec3 mid = target.position().add(0, target.getBbHeight() * 0.55, 0);
        slashAcross(level, mid, p.position(), 0.6, Math.max(5.5, target.getBbHeight() * 3.0), 8);
        Tasks.after(2, s -> slashAcross(level, mid, p.position(), -0.5, Math.max(4.5, target.getBbHeight() * 2.4), 7));
        decal(level, target.position(), "crack", 6.5, 40);
        Fx.shockwave(level, P, target.position(), 4.5, 8);
        debris(level, target.position(), 3.5, 4);
        Tasks.flash(level, mid.x, mid.y, mid.z);
    }

    // ---------------------------------------------------------------- Bulwark

    /** An upright shield sigil held out in front for the whole duration (drawn by the client). */
    public static void bulwark(ServerPlayer p, int ticks) {
        FxState.set(p, "bulwark", ticks);
        Circles.ground(lv(p), "warrior", p.position(), 3.0, 16);
    }

    /** A returning flash when Bulwark reflects a blow. */
    public static void bulwarkReflect(ServerLevel level, ServerPlayer p, LivingEntity attacker) {
        Circles.ring(level, "warrior", p.position(), 3.2, 6);
        hitSlash(level, attacker, p.position());
    }

    // ---------------------------------------------------------------- Avatar of War

    /** A giant spectral sword plunged beside the player, a big sigil, and flame horns for the whole duration. */
    public static void avatarStart(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Vec3 at = p.position();
        Vec3 side = flat(p.getLookAngle());
        Vec3 stab = at.add(new Vec3(-side.z, 0, side.x).scale(2.2));
        Circles.ground(level, "warrior", at, 9.0, 40);
        Circles.ring(level, "warrior", at, 9, 10);
        Fx.pillar(level, P, at, 8, 1.6, 16);
        Props.Prop sword = Props.sprite(level, stab.add(0, 6.0, 0), "sword", ticks + 24);
        Quaternionf down = new Quaternionf().rotationZ((float) Math.PI);
        sword.pose(Vec3.ZERO, down, 1.0, 1.0, 1.0, 0);
        Tasks.after(1, s -> sword.pose(new Vec3(0, -3.6, 0), down, 4.0, 5.6, 4.0, 6));
        Tasks.after(ticks + 4, s -> {
            sword.pose(new Vec3(0, -3.6, 0), down, 0.02, 0.02, 0.02, 12);
            sword.removeAfter(13);
        });
        FxState.set(p, "avatar", ticks);
        FxState.set(p, "circle:warrior:4.0", ticks);
        during(p, ticks, 4, (o, lv, age) -> Tasks.particles(lv, ParticleTypes.FLAME, o.position().add(0, 1.0, 0), 2, 0.4, 0.01), null);
    }

    /** One pulse of the avatar's aura: blade ring bursts out and a ring runs ahead of it. */
    public static void avatarPulse(ServerPlayer p, double radius) {
        ServerLevel level = lv(p);
        Vec3 at = p.position().add(0, 0.9, 0);
        pop(level, at, "blade_ring", Circles.face(UP, RNG.nextDouble() * 6), radius * 1.6, 0.3, radius * 1.6, 5);
        Circles.ring(level, "warrior", p.position(), radius, 8);
        Tasks.particles(level, ParticleTypes.CRIT, p.position().add(0, 0.3, 0), 18, radius * 0.6, 0.3);
    }
}
