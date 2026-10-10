package dev.gatebound.skill.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Random;

import static dev.gatebound.skill.fx.Anim.*;

/**
 * Ranger effects: green and gold nature magic drawn with textured models (glowing arrows that ride the real arrows, leaves, thorn
 * vines, reticles, light beams, a hawk, antlers) and inscribed circles. Gameplay lives in RangerSkills; this class only draws.
 * The level 100 Apex Predator is a form (tag "form:ranger", drawn on the body by client/FormBodyLayer).
 */
public final class RangerFx {
    private static final Random RNG = new Random();
    private static final String KEY = "ranger";
    /** Glow props alive at once (one per skill arrow in flight). */
    private static final int MAX_GLOWS = 30;
    private static int glows;

    private RangerFx() {
    }

    /** Forgets the live glow count (the props themselves are swept by the prop cleanup). */
    public static void reset() {
        glows = 0;
    }

    // ---------------------------------------------------------------- primitives

    private static Vec3 center(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.6, 0);
    }

    /** Leaves thrown outward from a point, tumbling as they fall. */
    public static void leaves(ServerLevel level, Vec3 at, int n) {
        for (int i = 0; i < n; i++) {
            double a = RNG.nextDouble() * Math.PI * 2;
            double r = 1.2 + RNG.nextDouble() * 1.6;
            Vec3 from = at.add(0, 0.4, 0);
            Vec3 to = at.add(Math.cos(a) * r, 0.2 + RNG.nextDouble() * 1.2, Math.sin(a) * r);
            Tasks.after(i / 2, s -> fly(level, "leaf", from, to, 0.9, 0.5, 0.8, yaw(RNG.nextDouble() * 6), null));
        }
    }

    /** A beam of light between two points. */
    public static void beamBetween(ServerLevel level, Vec3 a, Vec3 b, double width, int hold) {
        beam(level, "ranger_beam", a, b, width, hold);
    }

    /** A reticle lying face-on toward {@code toward}, snapping onto a point. */
    public static void reticleAt(ServerLevel level, Vec3 at, Vec3 toward, double size, int hold) {
        pop(level, at, "reticle_gold", Circles.face(toward, 0), size, hold);
    }

    // ---------------------------------------------------------------- glowing arrows

    /** A glowing arrow model that rides the real one; null when too many are alive. */
    public static Props.Prop glow(ServerLevel level, AbstractArrow arrow) {
        if (glows >= MAX_GLOWS) {
            return null;
        }
        glows++;
        Props.Prop prop = Props.sprite(level, arrow.position(), "arrow_glow", 400);
        prop.pose(Vec3.ZERO, orient(arrow.getDeltaMovement()), 0.5, 0.2, 0.2, 0);
        return prop;
    }

    private static Quaternionf orient(Vec3 v) {
        Vec3 d = v.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : v.normalize();
        return new Quaternionf().rotationTo(1f, 0f, 0f, (float) d.x, (float) d.y, (float) d.z);
    }

    public static void moveGlow(Props.Prop prop, AbstractArrow arrow) {
        prop.pin(arrow.position(), orient(arrow.getDeltaMovement()), 2.4, 0.8, 0.8, 2);
    }

    public static void endGlow(Props.Prop prop) {
        glows = Math.max(0, glows - 1);
        prop.pose(Vec3.ZERO, new Quaternionf(), 0.02, 0.02, 0.02, 3);
        prop.removeAfter(4);
    }

    // ---------------------------------------------------------------- skills

    /** The cast gesture: a leaf circle on the ground and upright in front of the Ranger. */
    public static void cast(ServerPlayer p, boolean quiet) {
        ServerLevel level = lv(p);
        Circles.ground(level, KEY, p.position(), quiet ? 2.4 : 3.0, quiet ? 12 : 14);
        if (!quiet) {
            Circles.castCircle(p, KEY, 2.2, 14);
        }
        Tasks.sound(level, p.position(), Palette.of(KEY).cast(), quiet ? 0.4f : 0.7f, 1.2f);
    }

    /** Eagle Eye: a ring sweeps out and a gold reticle hangs over every enemy it finds. */
    public static void eagle(ServerPlayer p, List<LivingEntity> seen) {
        ServerLevel level = lv(p);
        Circles.ring(level, KEY, p.position(), 24, 14);
        Circles.ground(level, KEY, p.position(), 4.0, 20);
        int n = 0;
        for (LivingEntity e : seen) {
            if (n++ >= 10) {
                break;
            }
            Circles.followModel(e, "reticle_gold", 1.4, 160, e.getBbHeight() + 0.7);
        }
    }

    /** A bow skill is primed: leaves swirl up and a circle floats in front of the bow. */
    public static void primed(ServerPlayer p) {
        ServerLevel level = lv(p);
        Circles.castCircle(p, KEY, 2.0, 18);
        Circles.ground(level, KEY, p.position(), 2.6, 16);
        leaves(level, p.position(), 5);
    }

    /** Homing Shot: the reticle snaps onto what the arrow will chase. */
    public static void lock(ServerPlayer p, Entity entity, BlockPos block) {
        ServerLevel level = lv(p);
        if (entity instanceof LivingEntity le) {
            Circles.followModel(le, "reticle_gold", 1.8, 80, le.getBbHeight() + 0.6);
            Circles.ring(level, KEY, le.position(), 2.6, 8);
        } else if (block != null) {
            Vec3 at = Vec3.atCenterOf(block);
            reticleAt(level, at, p.position().subtract(at), 1.6, 60);
        }
        primed(p);
    }

    /** Piercing Shot: a beam of light through the whole line, rings and reticles at every enemy it passes. */
    public static void pierce(ServerPlayer p, List<LivingEntity> hits) {
        ServerLevel level = lv(p);
        Vec3 from = p.getEyePosition().add(p.getLookAngle().scale(0.8)).add(0, -0.2, 0);
        Vec3 end = p.getEyePosition().add(p.getLookAngle().scale(40));
        if (!hits.isEmpty()) {
            end = center(hits.get(hits.size() - 1)).add(p.getLookAngle().scale(6));
        }
        beamBetween(level, from, end, 0.9, 7);
        for (LivingEntity e : hits) {
            Circles.ring(level, KEY, e.position(), 2.6, 8);
            reticleAt(level, center(e), p.position().subtract(center(e)), 2.2, 8);
        }
    }

    /** Hunter's Quarry: a reticle turns over the marked enemy and a circle follows its feet for the whole mark. */
    public static void quarry(ServerPlayer p, LivingEntity q, int ticks) {
        ServerLevel level = lv(p);
        Circles.onTarget(level, KEY, p.position(), q, 2.8, 14);
        Circles.followModel(q, "reticle_gold", 2.0, ticks, q.getBbHeight() + 0.9);
        Circles.follow(q, KEY, Math.max(2.4, q.getBbWidth() * 2.6), ticks, 0.06);
    }

    /** Evasive Roll: an afterimage where the Ranger was, a ring of dust and leaves. */
    public static void roll(ServerPlayer p) {
        ServerLevel level = lv(p);
        pop(level, p.position().add(0, 1.0, 0), "silhouette_ranger", yaw(-Math.toRadians(p.yBodyRot)), 2.1, 2.1, 2.1, 6);
        Circles.ring(level, KEY, p.position(), 2.6, 7);
        leaves(level, p.position(), 6);
    }

    /** A skill arrow landed on an enemy. */
    public static void impact(ServerLevel level, LivingEntity victim, String kind) {
        switch (kind) {
            case "poison" -> {
                Circles.ground(level, "venom", victim.position(), 3.0, 36);
                Circles.ring(level, "venom", victim.position(), 2.6, 8);
            }
            case "snare" -> {
                Circles.follow(victim, KEY, Math.max(2.6, victim.getBbWidth() * 2.8), 60, 0.06);
                for (int i = 0; i < 4; i++) {
                    double a = i * Math.PI / 2 + RNG.nextDouble() * 0.5;
                    Vec3 base = victim.position().add(Math.cos(a) * 0.7, victim.getBbHeight() * 0.5, Math.sin(a) * 0.7);
                    pop(level, base, "thorn_vine", yaw(a), 0.9, victim.getBbHeight() * 1.2, 0.9, 45);
                }
            }
            default -> {
                Circles.ring(level, KEY, victim.position(), 2.2, 6);
                leaves(level, center(victim), 3);
            }
        }
    }

    /** Rain of Arrows: a big circle opens where the arrows will fall. */
    public static void rain(ServerLevel level, Vec3 spot, double diameter, int ticks) {
        Circles.ground(level, KEY, spot, diameter, ticks);
        Circles.ring(level, KEY, spot, diameter * 0.5, 10);
    }

    /** Ricochet: beams of light between the bounce points with a ring at each. */
    public static void ricochet(ServerPlayer p, List<Vec3> points) {
        ServerLevel level = lv(p);
        Vec3 prev = p.getEyePosition().add(0, -0.2, 0);
        for (Vec3 pt : points) {
            beamBetween(level, prev, pt, 0.6, 7);
            Circles.ring(level, KEY, pt.subtract(0, 0.9, 0), 2.0, 7);
            prev = pt;
        }
    }

    /** Headshot: the reticle zooms onto the head, a beam and a flash follow. */
    public static void headshot(ServerPlayer p, LivingEntity t) {
        ServerLevel level = lv(p);
        Vec3 head = t.position().add(0, t.getBbHeight() * 0.9, 0);
        Vec3 toward = p.getEyePosition().subtract(head);
        reticleAt(level, head, toward, 3.0, 4);
        Tasks.after(2, s -> reticleAt(level, head, toward, 1.3, 8));
        beamBetween(level, p.getEyePosition().add(p.getLookAngle().scale(0.8)).add(0, -0.2, 0), head, 0.5, 6);
        Circles.ring(level, KEY, t.position(), 2.4, 8);
        Tasks.flash(level, head.x, head.y, head.z);
    }

    /** Hunter's Trance: a big green ripple, an eye circle and leaves swirling round the Ranger for the whole trance. */
    public static void trance(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Circles.ring(level, KEY, p.position(), 18, 14);
        // The circle under the feet and the leaves swirling round the body are drawn by the client so they never lag behind.
        FxState.set(p, "circle:ranger:4.5", Math.min(ticks, 80));
        FxState.set(p, "trance", ticks);
    }

    /** Arrow Barrage: a big launcher circle stands in front of the Ranger while the volley goes out. */
    public static void barrage(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Circles.castCircle(p, KEY, 4.4, ticks + 14);
        Circles.ground(level, KEY, p.position(), 4.0, ticks + 14);
    }

    // ---------------------------------------------------------------- Apex Predator

    /** The level 100 form: the apex predator (skin, wings, antlers, orbiting arrows and a hawk are drawn on the body by the client). */
    public static void apex(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        FxState.set(p, "form:ranger", ticks);
        FxState.set(p, "circle:ranger:22", ticks); // drawn by the client under the feet, so it follows exactly
        Circles.ring(level, KEY, p.position(), 14, 12);
        Fx.pillar(level, Palette.of(KEY), p.position(), 7, 1.4, 14);
        Tasks.flash(level, p.getX(), p.getY() + 1, p.getZ());
    }

    /** One of the predator's strikes: a beam of light from the sky, a ring and a reticle at the enemy's feet. */
    public static void strike(ServerLevel level, LivingEntity e) {
        Vec3 c = center(e);
        beamBetween(level, c.add(0, 9, 0), c, 0.8, 4);
        Circles.ring(level, KEY, e.position(), 2.6, 6);
        reticleAt(level, e.position().add(0, 0.12, 0), new Vec3(0, 1, 0), 2.2, 5);
    }
}
