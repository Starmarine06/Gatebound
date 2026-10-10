package dev.gatebound.skill.fx;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static dev.gatebound.skill.fx.Anim.*;

/**
 * Rogue effects: speed, shadow and afterimages, drawn with textured models (silhouettes, smoke spheres, daggers, slash marks,
 * reticles, chains, tendrils). Gameplay lives in RogueSkills; this class only draws. The level 100 Shadow Possession is a form
 * (tag "form:rogue", drawn on the body by client/FormBodyLayer). A shadowed Rogue is invisible, so those effects are world props.
 */
public final class RogueFx {
    private static final Random RNG = new Random();
    private static final Map<UUID, Long> LAST_VENOM = new HashMap<>();

    private RogueFx() {
    }

    // ---------------------------------------------------------------- primitives

    /** Where a flat dagger or slash must be turned so that the top of its texture points along {@code dir}. */
    private static double aim(Vec3 dir) {
        return Math.atan2(-dir.x, -dir.z);
    }

    /** A smoke sphere that swells, lingers and shrinks away. */
    public static void puff(ServerLevel level, Vec3 at, double size, int linger) {
        Props.Prop prop = Props.sprite(level, at, "smoke_puff", linger + 24);
        Quaternionf rot = new Quaternionf().rotationXYZ((float) RNG.nextDouble() * 3, (float) RNG.nextDouble() * 3, (float) RNG.nextDouble() * 3);
        prop.pose(Vec3.ZERO, rot, size * 0.2, size * 0.2, size * 0.2, 0);
        Tasks.after(1, s -> prop.pose(new Vec3(0, size * 0.15, 0), rot, size, size * 0.8, size, 8));
        Tasks.after(linger, s -> {
            prop.pose(new Vec3(0, size * 0.4, 0), rot, size * 0.1, size * 0.1, size * 0.1, 14);
            prop.removeAfter(15);
        });
    }

    /** A glowing silhouette left where the Rogue was, fading away. */
    public static void afterimage(ServerPlayer p) {
        ServerLevel level = lv(p);
        pop(level, p.position().add(0, 1.0, 0), "silhouette", yaw(-Math.toRadians(p.yBodyRot)), 2.1, 2.1, 2.1, 6);
    }

    /** A puff of shadow where the Rogue arrives. */
    public static void arrival(ServerLevel level, Vec3 at) {
        puff(level, at.add(0, 0.6, 0), 1.6, 6);
        Circles.ring(level, "rogue", at, 2.2, 6);
    }

    /** A thin shadow thread between two points (a knife's flight, an arm's strike). */
    public static void thread(ServerLevel level, Vec3 from, Vec3 to) {
        beam(level, "chain", from, to, 0.35, 4);
    }

    /** A small slash mark across a target. */
    public static void cut(ServerLevel level, ServerPlayer p, LivingEntity t) {
        Vec3 mid = t.position().add(0, t.getBbHeight() * 0.55, 0);
        Vec3 toward = flat(p.position().subtract(mid));
        pop(level, mid.add(toward.scale(0.45)), "rogue_slash", Circles.face(toward, (RNG.nextDouble() - 0.5) * 2.4),
                Math.max(1.8, t.getBbHeight() * 1.3), 4);
    }

    /** A flurry of slashes, a few ticks apart. */
    public static void flurry(ServerLevel level, LivingEntity t) {
        Vec3 mid = t.position().add(0, t.getBbHeight() * 0.55, 0);
        for (int i = 0; i < 3; i++) {
            Tasks.after(i * 2, s -> {
                Vec3 dir = new Vec3(RNG.nextDouble() - 0.5, 0, RNG.nextDouble() - 0.5);
                Vec3 n = flat(dir);
                pop(level, mid.add(n.scale(0.5)), "rogue_slash", Circles.face(n, (RNG.nextDouble() - 0.5) * 3.0),
                        Math.max(2.0, t.getBbHeight() * 1.4), 4);
            });
        }
    }

    // ---------------------------------------------------------------- skills

    /** Dash: an afterimage at the start, ghosts along the way, and smoke where it ends. */
    public static void shadowStep(ServerPlayer p) {
        ServerLevel level = lv(p);
        afterimage(p);
        puff(level, p.position().add(0, 0.6, 0), 1.8, 8);
        Circles.ground(level, "rogue", p.position(), 2.8, 12);
        during(p, 8, 2, (o, lv, age) -> {
            if (age > 0) {
                pop(lv, o.position().add(0, 1.0, 0), "silhouette", yaw(-Math.toRadians(o.yBodyRot)), 2.1, 2.1, 2.1, 3);
            }
        }, () -> {
            ServerPlayer o = p;
            if (o.isAlive()) {
                arrival(lv(o), o.position());
            }
        });
    }

    /** Knives flying from the hand to each target. */
    public static void knives(ServerPlayer p, List<LivingEntity> targets) {
        ServerLevel level = lv(p);
        Vec3 from = p.getEyePosition().add(0, -0.3, 0);
        for (LivingEntity e : targets) {
            Vec3 to = e.position().add(0, e.getBbHeight() * 0.55, 0);
            Vec3 dir = flat(to.subtract(from));
            Anim.fly(level, "dagger", from, to, 1.2, 2.0, 0, Circles.face(UP, aim(dir)), () -> cut(level, p, e));
        }
    }

    /** Vanish: a cloud of smoke closes over the Rogue and a silhouette dissolves. */
    public static void vanish(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 at = p.position();
        for (int i = 0; i < 3; i++) {
            double a = i * Math.PI * 2 / 3;
            puff(level, at.add(Math.cos(a) * 0.6, 0.5, Math.sin(a) * 0.6), 1.8, 12);
        }
        afterimage(p);
        Circles.ground(level, "rogue", at, 3.4, 16);
        Circles.ring(level, "rogue", at, 3, 8);
    }

    /** A red lock-on ring turning above the marked enemy until the mark ends. */
    public static void mark(LivingEntity target, int ticks) {
        Circles.followModel(target, "reticle", 1.5, ticks, target.getBbHeight() + 0.9);
    }

    /** Melding into a shadow: nothing is drawn, a hidden Rogue leaves no pool, mist or shadow behind. */
    public static void meld(ServerPlayer p) {
    }

    public static void venomStart(ServerPlayer p) {
        Circles.ground(lv(p), "venom", p.position(), 2.6, 18);
        Circles.ring(lv(p), "venom", p.position(), 2.4, 6);
    }

    /** A green circle where a venomed blade lands (throttled per enemy). */
    public static void venomHit(ServerLevel level, LivingEntity victim) {
        long now = level.getServer().getTickCount();
        Long last = LAST_VENOM.get(victim.getUUID());
        if (last != null && now - last < 15) {
            return;
        }
        LAST_VENOM.put(victim.getUUID(), now);
        if (LAST_VENOM.size() > 200) {
            LAST_VENOM.values().removeIf(t -> now - t > 100);
        }
        Circles.ground(level, "venom", victim.position(), 2.4, 14);
    }

    /** Backstab: a double slash across the target and a shadow flash behind it. */
    public static void backstab(LivingEntity t, ServerPlayer p) {
        ServerLevel level = lv(t);
        Vec3 mid = t.position().add(0, t.getBbHeight() * 0.55, 0);
        Vec3 toward = flat(p.position().subtract(mid));
        double size = Math.max(2.6, t.getBbHeight() * 1.9);
        pop(level, mid.add(toward.scale(0.5)), "rogue_slash", Circles.face(toward, 0.8), size, 6);
        Tasks.after(1, s -> pop(level, mid.add(toward.scale(0.55)), "rogue_slash", Circles.face(toward, -0.8), size, 6));
        puff(level, t.position().add(toward.scale(0.8)).add(0, 0.8, 0), 1.6, 6);
        Tasks.particles(level, ParticleTypes.CRIT, mid, 10, 0.3, 0.3);
    }

    /** A cloud that lingers: big smoke spheres over a sigil. */
    public static void smokeBomb(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 at = p.position();
        Circles.ground(level, "rogue", at, 12.0, 60);
        Circles.ring(level, "rogue", at, 6, 9);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + RNG.nextDouble() * 0.4;
            double r = i == 0 ? 0 : 3.0 + RNG.nextDouble() * 1.2;
            puff(level, at.add(Math.cos(a) * r, 0.9 + RNG.nextDouble() * 0.6, Math.sin(a) * r), 4.4 + RNG.nextDouble() * 1.4, 56);
        }
    }

    /** A ring of daggers flung out in every direction. */
    public static void fan(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 from = p.position().add(0, 1.0, 0);
        Circles.ground(level, "rogue", p.position(), 5.5, 14);
        int n = 10;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
            Anim.fly(level, "dagger", from.add(dir.scale(0.5)), from.add(dir.scale(6.5)), 1.2, 1.8, 0, Circles.face(UP, aim(dir)), null);
        }
    }

    /** Execute: the reticle locks on, a giant X cuts through and shadow bursts out. */
    public static void execute(ServerPlayer p, LivingEntity t) {
        ServerLevel level = lv(t);
        Vec3 mid = t.position().add(0, t.getBbHeight() * 0.55, 0);
        Vec3 toward = flat(p.position().subtract(mid));
        pop(level, t.position().add(0, 0.1, 0), "reticle", Circles.face(UP, 0), 3.4, 10);
        double size = Math.max(5.0, t.getBbHeight() * 3.0);
        pop(level, mid.add(toward.scale(0.5)), "rogue_slash", Circles.face(toward, 0.75), size, 8);
        Tasks.after(2, s -> pop(level, mid.add(toward.scale(0.55)), "rogue_slash", Circles.face(toward, -0.75), size, 8));
        puff(level, mid, 2.4, 8);
    }

    /** Shadow Dance: the Rogue is invisible for the dance, so nothing is drawn (no silhouettes, sigil or shadow). */
    public static void dance(ServerPlayer p, int ticks) {
    }

    /** Thousand Cuts: a big sigil under the Rogue while the blades go to work. */
    public static void cutsStart(ServerPlayer p) {
        ServerLevel level = lv(p);
        Circles.ground(level, "rogue", p.position(), 10.0, 44);
        Circles.ring(level, "rogue", p.position(), 8, 10);
    }

    // ---------------------------------------------------------------- Shadow Possession

    /** The level 100 form: the shadow sovereign (skin, wings, halo and arms are drawn on the body by the client). */
    public static void possession(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        FxState.set(p, "form:rogue", ticks);
        Circles.ground(level, "rogue", p.position(), 9.0, 40);
        Circles.ring(level, "rogue", p.position(), 9, 10);
        Fx.pillar(level, Palette.of("rogue"), p.position(), 7, 1.4, 14);
        Tasks.flash(level, p.getX(), p.getY() + 1, p.getZ());
    }

    /** A sigil under a bound enemy for as long as it is bound. */
    public static void bindMark(LivingEntity mob, int ticks) {
        Circles.follow(mob, "rogue", Math.max(2.2, mob.getBbWidth() * 2.4), ticks, 0.06);
        puff(lv(mob), mob.position().add(0, 0.5, 0), 1.4, 8);
    }

    /** A chain from the Rogue's chest to a bound enemy. */
    public static void chain(ServerLevel level, ServerPlayer p, LivingEntity mob) {
        beam(level, "chain", p.position().add(0, 1.2, 0), mob.position().add(0, mob.getBbHeight() * 0.5, 0), 0.55, 12);
    }

    /** One of the sovereign's shadow arms striking a bound enemy. */
    public static void arm(ServerLevel level, ServerPlayer p, LivingEntity target) {
        Vec3 from = p.position().add(0, 1.4, 0);
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.5, 0);
        beam(level, "tendril", from, to, 0.8, 4);
        cut(level, p, target);
    }
}
