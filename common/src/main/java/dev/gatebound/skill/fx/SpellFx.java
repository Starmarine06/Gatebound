package dev.gatebound.skill.fx;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static dev.gatebound.skill.fx.Anim.*;

/**
 * Textured-model effects for the Lightning, Wind, Earth and Dark spells (the spell, not the level 100 forms: those are FormFx and
 * the client FormBodyLayer). Gameplay stays in the *Skills classes; they only call into here.
 */
public final class SpellFx {
    private static final Random RNG = new Random();
    private static final Map<UUID, Long> MARKED = new HashMap<>();

    private SpellFx() {
    }

    // ---------------------------------------------------------------- shared primitives

    /** A model riding a projectile: created on the first call, then posed at the head of the path every tick. */
    public static void ride(Props.Prop[] holder, ServerLevel lv, String model, Vec3 a, Vec3 b, long tick, double sx, double sy, double sz,
                            boolean along) {
        if (holder[0] == null) {
            holder[0] = Props.sprite(lv, a, model, 90);
        }
        Vec3 d = b.subtract(a);
        Quaternionf rot = along && d.lengthSqr() > 1.0e-6 ? Circles.face(d, tick * 0.6) : Circles.face(new Vec3(0.3, 1, 0.2), tick * 0.9);
        holder[0].pose(b.subtract(holder[0].origin()), rot, sx, sy, sz, 1);
    }

    public static void stop(Props.Prop[] holder) {
        if (holder[0] != null) {
            holder[0].remove();
            holder[0] = null;
        }
    }

    private static Vec3 mid(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.55, 0);
    }

    /** Dust thrown up at a point. */
    public static void dust(ServerLevel level, Vec3 at, double size, int n) {
        for (int i = 0; i < n; i++) {
            double a = RNG.nextDouble() * Math.PI * 2;
            double r = RNG.nextDouble() * size * 0.6;
            Vec3 p = at.add(Math.cos(a) * r, 0.3, Math.sin(a) * r);
            Props.Prop prop = Props.sprite(level, p, "dust_puff", 30);
            Quaternionf rot = new Quaternionf().rotationXYZ((float) RNG.nextDouble() * 3, (float) RNG.nextDouble() * 3, (float) RNG.nextDouble() * 3);
            prop.pose(Vec3.ZERO, rot, 0.2, 0.2, 0.2, 0);
            double s = size * (0.35 + RNG.nextDouble() * 0.3);
            Tasks.after(1, t -> prop.pose(new Vec3(0, s * 0.4, 0), rot, s, s * 0.8, s, 6));
            Tasks.after(8, t -> {
                prop.pose(new Vec3(0, s * 0.9, 0), rot, s * 0.1, s * 0.1, s * 0.1, 10);
                prop.removeAfter(11);
            });
        }
    }

    // ---------------------------------------------------------------- Lightning

    /** A jagged bolt between two points. */
    public static void bolt(ServerLevel level, Vec3 a, Vec3 b, double width, int hold) {
        beam(level, "bolt", a, b, width, hold);
    }

    /** A bolt that drops from the sky onto a point. */
    public static void column(ServerLevel level, Vec3 base, double height, double width, int hold) {
        pop(level, base.add(0, height * 0.5, 0), "bolt", yaw(RNG.nextDouble() * 3), width, height, width, hold);
    }

    /** A thundercloud that gathers high above a point, then drifts off. */
    public static void cloud(ServerLevel level, Vec3 at, double size, int ticks) {
        Props.Prop prop = Props.sprite(level, at, "storm_cloud", ticks + 30);
        Quaternionf flat = Circles.face(new Vec3(0, 1, 0), 0);
        prop.pose(Vec3.ZERO, flat, size * 0.2, size * 0.2, size * 0.2, 0);
        Tasks.after(1, s -> prop.pose(Vec3.ZERO, Circles.face(new Vec3(0, 1, 0), 0.5), size, size, size, 14));
        Tasks.after(ticks, s -> {
            prop.pose(new Vec3(0, 1.5, 0), Circles.face(new Vec3(0, 1, 0), 1.0), size * 1.1, size * 0.02, size * 1.1, 14);
            prop.removeAfter(15);
        });
    }

    /** A circle and ring where a shock lands. */
    public static void shockAt(ServerLevel level, String key, Vec3 at, double size) {
        Circles.ground(level, key, at, size, 12);
        Circles.ring(level, key, at, size * 0.6, 6);
    }

    /** A dash along a path: a bolt down the whole line and circles at both ends. */
    public static void dash(ServerLevel level, Vec3 from, Vec3 to, double width) {
        Vec3 a = from.add(0, 1, 0);
        Vec3 b = to.add(0, 1, 0);
        bolt(level, a, b, width, 6);
        Tasks.after(1, s -> bolt(level, a.add(0, 0.3, 0), b.add(0, -0.3, 0), width * 0.7, 5));
        Circles.ground(level, "lightning", from, 3.0, 10);
        Circles.ground(level, "lightning", to, 3.4, 12);
        Circles.ring(level, "lightning", to, 3, 6);
    }

    /** Overcharge: a circle underfoot and the tag the client draws orbiting bolts from. */
    public static void overcharge(ServerPlayer p, int ticks) {
        Circles.follow(p, "lightning", 3.2, 50, 0.06);
        Circles.ring(lv(p), "lightning", p.position(), 3, 8);
        FxState.set(p, "overcharge", ticks);
    }

    /** Lightning Rod: a metal rod stands in the ground, crackling, under a circle. */
    public static void rod(ServerLevel level, Vec3 spot, int ticks) {
        Circles.ground(level, "lightning", spot, 22.0, ticks);
        Props.Prop rod = Props.sprite(level, spot, "lightning_rod", ticks + 30);
        Quaternionf rot = yaw(0);
        rod.pose(Vec3.ZERO, rot, 0.2, 0.2, 0.2, 0);
        Tasks.after(1, s -> rod.pose(new Vec3(0, 1.4, 0), rot, 2.8, 2.8, 2.8, 8));
        Tasks.after(ticks, s -> {
            rod.pose(new Vec3(0, 1.4, 0), rot, 0.2, 0.2, 0.2, 8);
            rod.removeAfter(9);
        });
    }

    /** One strike from the rod's tip to an enemy. */
    public static void rodStrike(ServerLevel level, Vec3 spot, LivingEntity target) {
        bolt(level, spot.add(0, 3.2, 0), mid(target), 1.0, 5);
        column(level, target.position(), 9, 1.6, 5);
        shockAt(level, "lightning", target.position(), 3.0);
    }

    /** Thunder Storm / Thunderstrike: the cloud gathers and a circle opens. */
    public static void stormStart(ServerPlayer p, Vec3 center, double size, int ticks) {
        ServerLevel level = lv(p);
        cloud(level, center.add(0, 9, 0), size * 1.4, ticks);
        Circles.ground(level, "lightning", center, size, ticks);
        Circles.ring(level, "lightning", center, size * 0.6, 12);
    }

    // ---------------------------------------------------------------- Wind

    /** Gust: crescents of wind fan out in front of the caster. */
    public static void gust(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 look = flat(p.getLookAngle());
        double aim = Math.atan2(-look.x, -look.z);
        for (int i = 0; i < 3; i++) {
            Vec3 at = p.position().add(look.scale(2.0 + i * 2.5)).add(0, 0.9, 0);
            double size = 4.0 + i * 2.2;
            Tasks.after(i * 2, s -> pop(level, at, "gust_cone", Circles.face(new Vec3(0, 1, 0), aim), size, 5));
        }
        Circles.ring(level, "wind", p.position(), 5, 8);
    }

    /** A funnel of wind (the tornado model) that spins up around a point, then thins away. */
    public static void funnel(ServerLevel level, Vec3 at, double width, double height, int ticks) {
        Props.Prop prop = Props.sprite(level, at, "tornado", ticks + 30);
        Quaternionf rot = yaw(0);
        prop.pose(Vec3.ZERO, rot, width * 0.2, height * 0.2, width * 0.2, 0);
        Tasks.after(1, s -> prop.pose(Vec3.ZERO, yaw(2.0), width, height, width, 8));
        int[] t = {0};
        Tasks.run((server, now) -> {
            if (t[0] >= ticks) {
                prop.pose(Vec3.ZERO, yaw(t[0] * 0.5), width * 1.3, height * 0.1, width * 1.3, 8);
                prop.removeAfter(9);
                return true;
            }
            if (t[0] > 0 && t[0] % 4 == 0) {
                prop.pose(Vec3.ZERO, yaw(t[0] * 0.5), width, height, width, 4);
            }
            t[0]++;
            return false;
        });
    }

    public static void cyclone(ServerPlayer p) {
        ServerLevel level = lv(p);
        funnel(level, p.position(), 7.0, 5.0, 14);
        Circles.ground(level, "wind", p.position(), 8.0, 16);
        Circles.ring(level, "wind", p.position(), 8, 10);
    }

    /** Air Dash: gust marks left along the line and rings at the start. */
    public static void airDash(ServerPlayer p, Vec3 dir) {
        ServerLevel level = lv(p);
        double aim = Math.atan2(-dir.x, -dir.z);
        Circles.ring(level, "wind", p.position(), 3, 7);
        for (int i = 0; i < 4; i++) {
            Vec3 at = p.position().add(dir.scale(-0.5 - i * 1.4)).add(0, 0.8, 0);
            Tasks.after(i, s -> pop(level, at, "gust_cone", Circles.face(new Vec3(0, 1, 0), aim), 2.6, 4));
        }
    }

    /** Updraft: a rising column under each lifted player. */
    public static void updraft(ServerLevel level, LivingEntity e) {
        funnel(level, e.position(), 2.6, 4.0, 14);
        Circles.ring(level, "wind", e.position(), 3, 8);
    }

    /** A wind blade riding a projectile (see {@link #ride}). */
    public static void bladeHit(ServerLevel level, Vec3 at, Vec3 dir) {
        Vec3 f = flat(dir);
        pop(level, at, "slash", Circles.face(f, 0.4), 3.2, 3);
    }

    /** Wind Barrier: a spinning wall of wind around the caster for as long as it holds. */
    public static void barrier(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Circles.ground(level, "wind", p.position(), 6.0, 20);
        Circles.ring(level, "wind", p.position(), 5, 8);
        FxState.set(p, "wind_barrier", ticks);
    }

    /** Tornado: a huge funnel over a circle for the whole spell. */
    public static void tornado(ServerLevel level, Vec3 spot, int ticks) {
        Circles.ground(level, "wind", spot, 12.0, ticks);
        funnel(level, spot, 10.0, 9.0, ticks);
    }

    /** Tempest Flight: wings of wind (drawn on the body by the client). */
    public static void flight(ServerPlayer p, int ticks) {
        FxState.set(p, "wind_wings", ticks);
        Circles.ring(lv(p), "wind", p.position(), 4, 8);
        Circles.ground(lv(p), "wind", p.position(), 4.0, 16);
    }

    // ---------------------------------------------------------------- Earth

    /** A spike of stone that bursts out of the ground and sinks back. */
    public static void spike(ServerLevel level, Vec3 at, double size, int hold) {
        Props.Prop prop = Props.sprite(level, at, "stone_spike", hold + 24);
        Quaternionf rot = yaw(RNG.nextDouble() * 6);
        prop.pose(new Vec3(0, -size * 0.6, 0), rot, size * 0.8, size * 0.1, size * 0.8, 0);
        Tasks.after(1, s -> prop.pose(new Vec3(0, size * 0.55, 0), rot, size, size * 1.6, size, 3));
        Tasks.after(hold + 3, s -> {
            prop.pose(new Vec3(0, -size * 0.6, 0), rot, size * 0.8, size * 0.1, size * 0.8, 8);
            prop.removeAfter(9);
        });
    }

    /** Cracks spreading across the ground. */
    public static void cracks(ServerLevel level, Vec3 at, double size, int ticks) {
        Props.Prop prop = Props.sprite(level, at.add(0, 0.1, 0), "crack", ticks + 20);
        Quaternionf flat = Circles.face(new Vec3(0, 1, 0), RNG.nextDouble() * 6);
        prop.pose(Vec3.ZERO, flat, size * 0.3, size * 0.3, size * 0.3, 0);
        Tasks.after(1, s -> prop.pose(Vec3.ZERO, flat, size, size, size, 6));
        Tasks.after(ticks, s -> {
            prop.pose(Vec3.ZERO, flat, size * 1.05, size * 0.02, size * 1.05, 10);
            prop.removeAfter(11);
        });
    }

    /** Stone Spike: a row of spikes bursting up along the path, one per block. */
    public static void spikeAt(ServerLevel level, Vec3 at, int i) {
        spike(level, at, 1.6 + (i % 3) * 0.3, 10);
        if (i % 3 == 0) {
            cracks(level, at, 3.0, 12);
        }
    }

    /** Stone armour: orbiting rocks and pauldrons drawn on the body by the client, plus a circle underfoot. */
    public static void armor(ServerPlayer p, int ticks, double circleSize) {
        FxState.set(p, "stone_armor", ticks);
        Circles.follow(p, "earth", circleSize, 50, 0.06);
        Circles.ring(lv(p), "earth", p.position(), circleSize * 0.7, 8);
        dust(lv(p), p.position(), 2.4, 3);
    }

    /** Bedrock: spikes ring the caster. */
    public static void bedrockRing(ServerPlayer p) {
        ServerLevel level = lv(p);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            Vec3 at = p.position().add(Math.cos(a) * 2.6, 0, Math.sin(a) * 2.6);
            Tasks.after(i, s -> spike(level, at, 2.0, 24));
        }
    }

    /** Tremor and quake pulses: cracks, a ring and dust. */
    public static void quakeBurst(ServerLevel level, Vec3 at, double radius) {
        cracks(level, at, radius * 2, 18);
        Circles.ring(level, "earth", at, radius, 8);
        dust(level, at, radius * 0.6, 4);
    }

    /** One pulse of an earthquake: a ring, a few spikes and dust. */
    public static void quakePulse(ServerLevel level, Vec3 center, double radius) {
        Circles.ring(level, "earth", center, radius, 8);
        randomSpikes(level, center, radius * 0.9, 4);
        dust(level, center.add((RNG.nextDouble() - 0.5) * radius, 0, (RNG.nextDouble() - 0.5) * radius), 3.0, 1);
    }

    /** A random spike somewhere inside the radius (earthquake pulses). */
    public static void randomSpikes(ServerLevel level, Vec3 center, double radius, int n) {
        for (int i = 0; i < n; i++) {
            double a = RNG.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(RNG.nextDouble()) * radius;
            spike(level, center.add(Math.cos(a) * r, 0, Math.sin(a) * r), 1.6 + RNG.nextDouble(), 6);
        }
    }

    /** Stone Wall: a circle under it and dust at both ends. */
    public static void wall(ServerLevel level, Vec3 center, Vec3 side) {
        Circles.ground(level, "earth", center, 7.0, 24);
        dust(level, center.add(side.scale(2.4)), 2.0, 2);
        dust(level, center.add(side.scale(-2.4)), 2.0, 2);
    }

    /** A boulder rolling over the ground, tumbling. */
    public static void roll(ServerLevel level, Vec3 at, double size, int hold) {
        pop(level, at.add(0, size * 0.4, 0), "boulder", new Quaternionf().rotationXYZ((float) RNG.nextDouble() * 3, (float) RNG.nextDouble() * 3, 0), size, hold);
    }

    // ---------------------------------------------------------------- Dark

    /** A curse sigil turning above an enemy for as long as the curse lasts (once per curse). */
    public static void curse(LivingEntity e, int ticks) {
        long now = e.level().getServer().getTickCount();
        Long last = MARKED.get(e.getUUID());
        if (last != null && now - last < ticks - 20) {
            return;
        }
        MARKED.put(e.getUUID(), now);
        if (MARKED.size() > 300) {
            MARKED.values().removeIf(t -> now - t > ticks);
        }
        Circles.followModel(e, "curse_mark", 1.6, ticks, e.getBbHeight() + 0.8);
    }

    /** Tendrils reaching up from the ground under an enemy. */
    public static void tendrils(ServerLevel level, LivingEntity e) {
        for (int i = 0; i < 3; i++) {
            double a = i * Math.PI * 2 / 3 + RNG.nextDouble();
            Vec3 base = e.position().add(Math.cos(a) * 0.6, 0.9, Math.sin(a) * 0.6);
            pop(level, base, "tendril", yaw(a), 0.9, 2.2, 0.9, 14);
        }
        Circles.ground(level, "dark", e.position(), 2.8, 14);
    }

    /** A thread of void between two points. */
    public static void thread(ServerLevel level, Vec3 a, Vec3 b, double width, int hold) {
        beam(level, "tendril", a, b, width, hold);
    }

    /** Void Grasp / Dread: a dark circle and ring from the caster. */
    public static void voidPulse(ServerPlayer p, double radius) {
        ServerLevel level = lv(p);
        Circles.ground(level, "dark", p.position(), radius * 2, 16);
        Circles.ring(level, "dark", p.position(), radius, 10);
    }

    /** Black Hole: a void sphere swells over a circle with an accretion disc, then collapses. Returns nothing: the zone drives the pull. */
    public static void blackHole(ServerLevel level, Vec3 spot, int ticks) {
        Circles.ground(level, "dark", spot.subtract(0, 1.2, 0), 24.0, ticks);
        Props.Prop sphere = Props.sprite(level, spot, "void_sphere", ticks + 30);
        Props.Prop disc = Props.sprite(level, spot, "eclipse_disc", ticks + 30);
        sphere.pose(Vec3.ZERO, yaw(0), 0.5, 0.5, 0.5, 0);
        disc.pose(Vec3.ZERO, Circles.face(new Vec3(0, 1, 0), 0), 1, 1, 1, 0);
        Tasks.after(1, s -> {
            sphere.pose(Vec3.ZERO, yaw(1.0), 4.5, 4.5, 4.5, 16);
            disc.pose(Vec3.ZERO, Circles.face(new Vec3(0.1, 1, 0), 1.0), 18, 18, 18, 16);
        });
        int[] t = {0};
        Tasks.run((server, now) -> {
            if (t[0] >= ticks) {
                sphere.pose(Vec3.ZERO, yaw(t[0] * 0.4), 0.3, 0.3, 0.3, 4);
                disc.pose(Vec3.ZERO, Circles.face(new Vec3(0.1, 1, 0), t[0] * 0.4), 1, 1, 1, 4);
                sphere.removeAfter(6);
                disc.removeAfter(6);
                return true;
            }
            if (t[0] > 0 && t[0] % 4 == 0) {
                double s = 4.5 + Math.sin(t[0] * 0.3) * 0.4;
                sphere.pose(Vec3.ZERO, yaw(t[0] * 0.4), s, s, s, 4);
                disc.pose(Vec3.ZERO, Circles.face(new Vec3(0.1, 1, 0), t[0] * 0.5), 18, 18, 18, 4);
            }
            t[0]++;
            return false;
        });
    }

    /** The hole collapses: a flash and a shockwave. */
    public static void collapse(ServerLevel level, Vec3 spot) {
        Circles.ring(level, "dark", spot.subtract(0, 1.0, 0), 9, 8);
        Tasks.flash(level, spot.x, spot.y, spot.z);
    }

    /** Eclipse: a black sun hangs over the caster, a circle opens and the client draws a halo while it lasts. */
    public static void eclipse(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Vec3 c = p.position();
        Circles.ground(level, "dark", c, 26.0, 60);
        Circles.ring(level, "dark", c, 14, 14);
        Props.Prop disc = Props.sprite(level, c.add(0, 14, 0), "eclipse_disc", ticks + 30);
        disc.pose(Vec3.ZERO, Circles.face(new Vec3(0, 1, 0), 0), 2, 2, 2, 0);
        Tasks.after(1, s -> disc.pose(Vec3.ZERO, Circles.face(new Vec3(0, 1, 0), 0.6), 34, 34, 34, 20));
        int[] t = {0};
        Tasks.run((server, now) -> {
            if (t[0] >= ticks) {
                disc.pose(Vec3.ZERO, Circles.face(new Vec3(0, 1, 0), t[0] * 0.05), 3, 3, 3, 14);
                disc.removeAfter(15);
                return true;
            }
            if (t[0] > 0 && t[0] % 20 == 0) {
                disc.pose(Vec3.ZERO, Circles.face(new Vec3(0, 1, 0), t[0] * 0.05), 34, 34, 34, 20);
            }
            t[0]++;
            return false;
        });
        FxState.set(p, "eclipse", ticks);
    }
}
