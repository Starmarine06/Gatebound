package dev.gatebound.skill.fx;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Server-side particle shapes: rings, shockwaves, helixes, pillars, runes, arcs and projectile trails. Everything is made of
 * vanilla particles so any client sees it. Effects that last more than a tick run on {@link Tasks#run}. Particle counts are
 * kept small per tick and spread over time instead of one big burst.
 */
public final class Fx {
    private Fx() {
    }

    // ---------------------------------------------------------------- particles

    public static ParticleOptions dust(int rgb, float size) {
        return dev.gatebound.compat.Compat.dust(rgb, size);
    }

    /** Dust that fades from one colour to another. */
    public static ParticleOptions fade(int from, int to, float size) {
        return dev.gatebound.compat.Compat.fade(from, to, size);
    }

    public static void put(ServerLevel level, ParticleOptions type, Vec3 at) {
        level.sendParticles(type, at.x, at.y, at.z, 1, 0, 0, 0, 0);
    }

    /** One particle that travels in a direction (a count of zero turns the offset into a velocity). */
    public static void fly(ServerLevel level, ParticleOptions type, Vec3 at, Vec3 velocity, double speed) {
        level.sendParticles(type, at.x, at.y, at.z, 0, velocity.x, velocity.y, velocity.z, speed);
    }

    // ---------------------------------------------------------------- flat shapes

    /** A ring of still particles. */
    public static void ring(ServerLevel level, ParticleOptions type, Vec3 c, double radius, int count) {
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            put(level, type, c.add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
    }

    /** A ring whose particles drift outward. */
    public static void ringOut(ServerLevel level, ParticleOptions type, Vec3 c, double radius, int count, double speed) {
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            fly(level, type, c.add(Math.cos(a) * radius, 0, Math.sin(a) * radius), new Vec3(Math.cos(a), 0.05, Math.sin(a)), speed);
        }
    }

    /** A wave that grows from the centre to {@code maxRadius} over {@code ticks}, leaving a bright leading edge. */
    public static void shockwave(ServerLevel level, Palette pal, Vec3 c, double maxRadius, int ticks) {
        Circles.ring(level, pal.key(), c, maxRadius, ticks);
        int[] t = {0};
        Tasks.run((server, now) -> {
            t[0]++;
            double r = maxRadius * t[0] / ticks;
            int n = (int) Math.min(90, 10 + r * 9);
            ring(level, fade(pal.rgb1(), pal.rgb2(), 1.4f), c.add(0, 0.15, 0), r, n);
            ringOut(level, pal.main(), c.add(0, 0.2, 0), r, Math.max(6, n / 3), 0.06);
            return t[0] >= ticks;
        });
    }

    /** A bright flash and sparks thrown out in every direction. */
    public static void sphere(ServerLevel level, ParticleOptions type, Vec3 c, int count, double speed) {
        RandomSource rng = level.getRandom();
        for (int i = 0; i < count; i++) {
            Vec3 d = new Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian());
            if (d.lengthSqr() < 1.0e-4) {
                continue;
            }
            fly(level, type, c, d.normalize(), speed * (0.5 + rng.nextDouble()));
        }
    }

    // ---------------------------------------------------------------- vertical shapes

    /** A helix that winds up (or down) around a point. */
    public static void spiral(ServerLevel level, ParticleOptions type, Vec3 base, double radius, double height, double turns, int ticks) {
        int[] t = {0};
        Tasks.run((server, now) -> {
            int steps = 3;
            for (int s = 0; s < steps; s++) {
                double f = (t[0] * steps + s) / (double) (ticks * steps);
                double a = f * turns * Math.PI * 2;
                put(level, type, base.add(Math.cos(a) * radius, f * height, Math.sin(a) * radius));
                put(level, type, base.add(Math.cos(a + Math.PI) * radius, f * height, Math.sin(a + Math.PI) * radius));
            }
            t[0]++;
            return t[0] >= ticks;
        });
    }

    /** A column that shoots up from the ground and thins out. */
    public static void pillar(ServerLevel level, Palette pal, Vec3 base, double height, double radius, int ticks) {
        int[] t = {0};
        Tasks.run((server, now) -> {
            t[0]++;
            double top = height * Math.min(1.0, t[0] / (double) Math.max(1, ticks / 2));
            for (double y = 0; y <= top; y += 0.7) {
                double fall = 1.0 - y / height * 0.6;
                double a = level.getRandom().nextDouble() * Math.PI * 2;
                put(level, pal.main(), base.add(Math.cos(a) * radius * fall, y, Math.sin(a) * radius * fall));
            }
            put(level, fade(pal.rgb1(), pal.rgb2(), 1.6f), base.add(0, top, 0));
            return t[0] >= ticks;
        });
    }

    /** One tick of a swirl of particles around a vertical axis; call it every tick with a rising counter for motion. */
    public static void swirl(ServerLevel level, ParticleOptions type, Vec3 c, double radius, double height, long tick, int strands) {
        for (int s = 0; s < strands; s++) {
            double a = tick * 0.35 + Math.PI * 2 * s / strands;
            double y = (tick * 0.11 + s * (height / strands)) % height;
            put(level, type, c.add(Math.cos(a) * radius, y, Math.sin(a) * radius));
        }
    }

    // ---------------------------------------------------------------- runes

    /** One tick of a magic circle: two rings turning against each other and spokes between them. */
    public static void runeTick(ServerLevel level, Palette pal, Vec3 c, double radius, long tick) {
        double spin = tick * 0.12;
        int n = (int) (radius * 7);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + spin;
            put(level, dust(pal.rgb1(), 0.9f), c.add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
            double b = Math.PI * 2 * i / n - spin * 1.3;
            put(level, dust(pal.rgb2(), 0.7f), c.add(Math.cos(b) * radius * 0.65, 0, Math.sin(b) * radius * 0.65));
        }
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 3 * i + spin * 0.5;
            Vec3 in = c.add(Math.cos(a) * radius * 0.65, 0, Math.sin(a) * radius * 0.65);
            Vec3 out = c.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            put(level, pal.accent(), in.lerp(out, 0.5));
        }
    }

    /** A magic circle on the ground that turns for a while. */
    public static void rune(ServerLevel level, Palette pal, Vec3 c, double radius, int ticks) {
        int[] t = {0};
        Tasks.run((server, now) -> {
            if (t[0] % 4 == 0) { // ground particles last a second or more, so a redraw every fourth tick looks continuous
                runeTick(level, pal, c.add(0, 0.08, 0), radius, t[0]);
            }
            t[0]++;
            return t[0] >= ticks;
        });
    }

    /** Sparks that circle a player for a while, rising as they go. */
    public static void orbit(ServerPlayer p, Palette pal, int ticks) {
        UUID id = p.getUUID();
        int[] t = {0};
        Tasks.run((server, now) -> {
            ServerPlayer o = Tasks.playerOf(server, id);
            if (o == null) {
                return true;
            }
            ServerLevel lv = o.level();
            Vec3 c = o.position();
            swirl(lv, pal.main(), c, 0.9, 2.0, t[0], 3);
            swirl(lv, dust(pal.rgb1(), 0.8f), c, 0.7, 2.0, t[0] + 5, 2);
            if (t[0] % 3 == 0) {
                put(lv, pal.accent(), c.add(0, 1 + level(lv), 0));
            }
            t[0]++;
            return t[0] >= ticks;
        });
    }

    private static double level(ServerLevel lv) {
        return lv.getRandom().nextDouble() - 0.5;
    }

    // ---------------------------------------------------------------- lines

    /** A zig-zag line between two points, like lightning. */
    public static void arc(ServerLevel level, ParticleOptions type, Vec3 a, Vec3 b, double jitter) {
        Vec3 span = b.subtract(a);
        int segments = (int) Math.max(2, span.length() / 0.7);
        Vec3 prev = a;
        for (int i = 1; i <= segments; i++) {
            Vec3 next = a.add(span.scale(i / (double) segments));
            if (i < segments) {
                next = next.add((level.getRandom().nextDouble() - 0.5) * jitter, (level.getRandom().nextDouble() - 0.5) * jitter,
                        (level.getRandom().nextDouble() - 0.5) * jitter);
            }
            Vec3 seg = next.subtract(prev);
            int pts = (int) Math.max(2, seg.length() * 3);
            for (int k = 0; k <= pts; k++) {
                put(level, type, prev.add(seg.scale(k / (double) pts)));
            }
            prev = next;
        }
    }

    /** A straight line of particles. */
    public static void beam(ServerLevel level, ParticleOptions type, Vec3 a, Vec3 b, double spacing) {
        Vec3 span = b.subtract(a);
        int pts = (int) Math.max(2, span.length() / spacing);
        for (int i = 0; i <= pts; i++) {
            put(level, type, a.add(span.scale(i / (double) pts)));
        }
    }

    // ---------------------------------------------------------------- composite effects

    /**
     * What a projectile leaves behind each tick: a bright core, two strands winding around the path and the odd sparkle.
     */
    public static void trail(ServerLevel level, Palette pal, Vec3 from, Vec3 to, long tick) {
        Vec3 span = to.subtract(from);
        if (span.lengthSqr() < 1.0e-6) {
            return;
        }
        Vec3 dir = span.normalize();
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 up = dir.cross(side).normalize();
        int steps = Math.max(1, (int) Math.ceil(span.length() * 2));
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            Vec3 p = from.add(span.scale(f));
            double a = (tick + f) * 1.1;
            for (int strand = 0; strand < 2; strand++) {
                double ang = a + Math.PI * strand;
                Vec3 off = side.scale(Math.cos(ang) * 0.28).add(up.scale(Math.sin(ang) * 0.28));
                put(level, fade(pal.rgb1(), pal.rgb2(), 0.9f), p.add(off));
            }
            level.sendParticles(pal.main(), p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.005);
        }
        if (tick % 2 == 0) {
            put(level, pal.accent(), to);
        }
        level.sendParticles(pal.alt(), to.x, to.y, to.z, 2, 0.1, 0.1, 0.1, 0.01);
    }

    /** The burst a spell makes where it lands: flash, thrown sparks, a shockwave and a ring of ground dust. */
    public static void impact(ServerLevel level, Palette pal, Vec3 at, double size) {
        sphere(level, pal.main(), at, (int) (14 + size * 8), 0.25 + size * 0.08);
        sphere(level, pal.accent(), at, (int) (4 + size * 3), 0.2);
        ringOut(level, fade(pal.rgb1(), pal.rgb2(), 1.2f), at.add(0, 0.2, 0), 0.4, 14, 0.18 + size * 0.04);
        shockwave(level, pal, at, size * 1.6, 5);
        Tasks.flash(level, at.x, at.y, at.z);
        Tasks.sound(level, at, pal.hit(), (float) Math.min(1.5, 0.6 + size * 0.15), 1.0f);
    }
}
