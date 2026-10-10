package dev.gatebound.skill.fx;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Random;

import static dev.gatebound.skill.fx.Anim.*;

/**
 * Summoner effects: teal soul magic drawn with textured models (wisps, ghosts, a watching eye, chains and shackles, beams, pillars,
 * a latticed dome) and inscribed summoning circles. Gameplay lives in EchoSkills / EchoManager; this class only draws. The level 100
 * Sovereign's Domain is a form (tag "form:summoner", drawn on the body by client/FormBodyLayer).
 */
public final class SummonerFx {
    private static final Random RNG = new Random();
    private static final String KEY = "summoner";
    /** Most echoes that get a per-echo effect from one cast (the props are cheap, but not free). */
    private static final int MAX_ECHOES = 8;

    /** Static holder; not instantiable. */
    private SummonerFx() {
    }

    // ---------------------------------------------------------------- primitives

    /** Turns a wisp (head on the +x side of its texture) so it flies along {@code dir}. */
    private static Quaternionf along(Vec3 dir) {
        return yaw(Math.atan2(-dir.z, dir.x));
    }

    /** Chest height of an entity. */
    private static Vec3 chest(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.6, 0);
    }

    /** A wisp that flies from one point to another, arcing a little, then calls {@code onHit}. */
    public static void wisp(ServerLevel level, Vec3 from, Vec3 to, double size, Runnable onHit) {
        double arc = 0.5 + RNG.nextDouble() * 1.2;
        fly(level, "soul_wisp", from, to, size, 1.4, arc, along(to.subtract(from)), onHit);
    }

    /** {@code n} wisps leaving {@code from} one after another, all heading for {@code to}. */
    public static void stream(ServerLevel level, Vec3 from, Vec3 to, int n, double size, Runnable onLast) {
        for (int i = 0; i < n; i++) {
            final boolean last = i == n - 1;
            Tasks.after(i * 2, s -> {
                Vec3 a = from.add((RNG.nextDouble() - 0.5) * 0.8, (RNG.nextDouble() - 0.3) * 0.8, (RNG.nextDouble() - 0.5) * 0.8);
                wisp(level, a, to, size, last ? onLast : null);
            });
        }
    }

    /** Wisps rising from a point and fading away. */
    public static void rise(ServerLevel level, Vec3 at, int n, double height) {
        for (int i = 0; i < n; i++) {
            double a = RNG.nextDouble() * Math.PI * 2;
            Vec3 from = at.add(Math.cos(a) * 0.6, 0.1, Math.sin(a) * 0.6);
            Vec3 to = from.add(Math.cos(a) * 0.3, height * (0.7 + RNG.nextDouble() * 0.5), Math.sin(a) * 0.3);
            Tasks.after(i, s -> fly(level, "soul_wisp", from, to, 0.9, 0.5, 0, new Quaternionf().rotationZ((float) Math.PI / 2), null));
        }
    }

    /** A column of soul light that flares and fades. */
    public static void pillar(ServerLevel level, Vec3 base, double width, double height, int hold) {
        pop(level, base.add(0, height * 0.5, 0), "soul_beam", yaw(RNG.nextDouble() * 3), width, height, width, hold);
    }

    /** A hooded ghost rising from a point and thinning away. */
    public static void ghost(ServerLevel level, Vec3 at, double size) {
        Props.Prop prop = Props.sprite(level, at.add(0, size * 0.5, 0), "soul_ghost", 40);
        Quaternionf rot = yaw(RNG.nextDouble() * 6);
        prop.pose(Vec3.ZERO, rot, size * 0.3, size * 0.3, size * 0.3, 0);
        Tasks.after(1, s -> prop.pose(new Vec3(0, size * 0.3, 0), rot, size, size, size, 8));
        Tasks.after(10, s -> {
            prop.pose(new Vec3(0, size * 1.6, 0), rot, size * 0.5, size * 1.2, size * 0.5, 18);
            prop.removeAfter(19);
        });
    }

    /** A soul thread between two points. */
    public static void thread(ServerLevel level, Vec3 from, Vec3 to, double width, int hold) {
        beam(level, "soul_beam", from, to, width, hold);
    }

    // ---------------------------------------------------------------- cast, summon, release

    /** The cast gesture: a soul circle on the ground and upright in front of the Summoner. */
    public static void cast(ServerPlayer p, boolean quiet) {
        ServerLevel level = lv(p);
        Circles.ground(level, KEY, p.position(), quiet ? 2.4 : 3.0, quiet ? 12 : 14);
        if (!quiet) {
            Circles.castCircle(p, KEY, 2.4, 14);
        }
        Tasks.sound(level, p.position(), Palette.of(KEY).cast(), quiet ? 0.4f : 0.7f, 1.3f);
    }

    /** An echo steps out of a circle in a pillar of light. */
    public static void summoned(ServerLevel level, Vec3 at) {
        Circles.ground(level, KEY, at, 3.2, 22);
        Circles.ring(level, KEY, at, 2.4, 8);
        pillar(level, at, 1.4, 4.0, 10);
        ghost(level, at, 2.0);
        rise(level, at, 5, 3.0);
    }

    /** An echo falls: a ghost slips out of it and wisps drift away. */
    public static void released(ServerLevel level, Vec3 at) {
        Circles.ground(level, KEY, at, 2.4, 14);
        ghost(level, at, 1.8);
        rise(level, at, 6, 3.5);
    }

    /** An echo dismissed: its soul streams back to the Summoner. */
    public static void recalled(ServerLevel level, Vec3 from, ServerPlayer p) {
        stream(level, from.add(0, 0.8, 0), chest(p), 3, 0.9, null);
        Circles.ground(level, KEY, from, 1.8, 8);
    }

    // ---------------------------------------------------------------- skills

    /** Spirit Ward: three wisps circle the Summoner for as long as the ward lasts, over a soul circle. */
    public static void spiritWard(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Circles.follow(p, KEY, 3.4, 50, 0.06);
        Props.Prop[] wisps = new Props.Prop[3];
        for (int i = 0; i < wisps.length; i++) {
            wisps[i] = Props.sprite(level, p.position().add(0, 1.0, 0), "soul_wisp", ticks + 20);
            wisps[i].pose(Vec3.ZERO, yaw(0), 0.2, 0.2, 0.2, 0);
        }
        during(p, ticks, 2, (o, lv, age) -> {
            for (int i = 0; i < wisps.length; i++) {
                double a = age * 0.16 + i * Math.PI * 2 / wisps.length;
                Vec3 pos = o.position().add(Math.cos(a) * 1.2, 1.0 + Math.sin(age * 0.07 + i) * 0.35, Math.sin(a) * 1.2);
                wisps[i].pin(pos, yaw(-a - Math.PI / 2 + Math.PI), 1.1, 1.1, 1.1, 2);
            }
        }, () -> {
            for (Props.Prop w : wisps) {
                fadeOut(w, yaw(0));
            }
        });
    }

    /** Scout's Eye: a ghost eye flies out to the echo you looked at. */
    public static void eye(ServerPlayer p, LivingEntity echo) {
        ServerLevel level = lv(p);
        Vec3 from = p.getEyePosition().add(p.getLookAngle().scale(0.8)).add(0, -0.2, 0);
        Vec3 to = echo.position().add(0, echo.getBbHeight() + 0.4, 0);
        Vec3 d = to.subtract(from);
        fly(level, "soul_eye", from, to, 1.3, 1.6, 0.6, yaw(Math.atan2(d.x, d.z)), () -> {
            Circles.ground(level, KEY, echo.position(), 2.4, 14);
            rise(level, echo.position(), 4, 2.5);
        });
        stream(level, from, to, 4, 0.8, null);
    }

    /** Soul Drain: a circle under the target and a stream of wisps pouring into the Summoner. */
    public static void drain(ServerPlayer p, LivingEntity target) {
        ServerLevel level = lv(p);
        Circles.onTarget(level, KEY, p.position(), target, 2.8, 16);
        Vec3 from = chest(target);
        stream(level, from, chest(p), 8, 1.0, () -> Circles.ring(level, KEY, p.position(), 2.0, 6));
    }

    /** Rally, outgoing half: a soul slips away from where the echo stood. */
    public static void rallyFrom(ServerLevel level, LivingEntity echo) {
        Circles.ground(level, KEY, echo.position(), 2.0, 10);
        stream(level, chest(echo), echo.position().add(0, 2.5, 0), 3, 0.8, null);
    }

    /** Rally, incoming half: the echo arrives beside the Summoner, a chain of light back to them. */
    public static void rallyTo(ServerPlayer p, LivingEntity echo) {
        ServerLevel level = lv(p);
        Circles.ground(level, KEY, echo.position(), 2.4, 12);
        pillar(level, echo.position(), 1.0, 3.0, 6);
        thread(level, chest(p), chest(echo), 0.5, 8);
    }

    /** Rally: circle and ring around the Summoner. */
    public static void rallyWave(ServerPlayer p) {
        ServerLevel level = lv(p);
        Circles.ground(level, KEY, p.position(), 5.0, 16);
        Circles.ring(level, KEY, p.position(), 14, 12);
    }

    /** Command Strike: a lock-on ring on the target and a beam from every echo. */
    public static void command(ServerPlayer p, LivingEntity mark, List<? extends LivingEntity> echoes, int ticks) {
        ServerLevel level = lv(p);
        Circles.onTarget(level, KEY, p.position(), mark, 2.8, 14);
        Circles.followModel(mark, "reticle_soul", 2.4, ticks, 0.15);
        int n = 0;
        for (LivingEntity e : echoes) {
            if (n++ >= MAX_ECHOES) {
                break;
            }
            thread(level, chest(e), chest(mark), 0.5, 8);
            wisp(level, chest(e), chest(mark), 1.0, null);
        }
    }

    /** Empower: a circle underfoot and a halo of light over the echo for the whole buff. */
    public static void empower(LivingEntity echo, int ticks) {
        ServerLevel level = lv(echo);
        Circles.follow(echo, KEY, Math.max(2.4, echo.getBbWidth() * 2.4), ticks, 0.06);
        pillar(level, echo.position(), 1.0, 3.0, 8);
        rise(level, echo.position(), 4, 2.5);
    }

    /** Soul Link: a sigil under the Summoner and threads of soul to every echo for the whole link. */
    public static void link(ServerPlayer p, List<? extends LivingEntity> echoes, int ticks) {
        ServerLevel level = lv(p);
        Circles.follow(p, KEY, 3.0, ticks, 0.06);
        Circles.ring(level, KEY, p.position(), 4, 8);
        during(p, ticks, 8, (o, lv, age) -> {
            int n = 0;
            for (LivingEntity e : echoes) {
                if (n++ >= MAX_ECHOES) {
                    break;
                }
                if (e.isAlive() && !e.isRemoved()) {
                    thread(lv, chest(o), chest(e), 0.35, 10);
                }
            }
        }, null);
    }

    /** Sacrifice: the echo dissolves into a ghost and its souls pour into the Summoner. */
    public static void sacrifice(ServerPlayer p, LivingEntity echo) {
        ServerLevel level = lv(p);
        Circles.ground(level, KEY, echo.position(), 3.0, 16);
        ghost(level, echo.position(), 2.2);
        stream(level, chest(echo), chest(p), 12, 1.1, () -> {
            Circles.ring(level, KEY, p.position(), 3.0, 8);
            pillar(level, p.position(), 1.4, 3.5, 8);
        });
    }

    /** Soul Swap: circles at both places and two ghosts crossing over. */
    public static void swap(ServerLevel level, Vec3 mine, Vec3 theirs) {
        Circles.ground(level, KEY, mine, 3.0, 14);
        Circles.ground(level, KEY, theirs, 3.0, 14);
        fly(level, "soul_ghost", mine.add(0, 1.0, 0), theirs.add(0, 1.0, 0), 1.8, 1.6, 0.8, yaw(0), null);
        fly(level, "soul_ghost", theirs.add(0, 1.0, 0), mine.add(0, 1.0, 0), 1.8, 1.6, 0.8, yaw(0), null);
        stream(level, mine.add(0, 1, 0), theirs.add(0, 1, 0), 5, 0.9, null);
    }

    /** Bind Chains: a chain shoots out, then shackles and a sigil tighten around every bound enemy. */
    public static void chains(ServerPlayer p, LivingEntity mark, List<LivingEntity> bound, int ticks) {
        ServerLevel level = lv(p);
        beam(level, "soul_chain", chest(p), chest(mark), 0.7, 12);
        int n = 0;
        for (LivingEntity e : bound) {
            if (n++ >= 8) {
                break;
            }
            Circles.follow(e, KEY, Math.max(2.2, e.getBbWidth() * 2.4), ticks, 0.06);
            Circles.followModel(e, "soul_shackle", Math.max(1.4, e.getBbHeight() * 1.05), ticks, 0.0);
            if (e != mark) {
                beam(level, "soul_chain", chest(mark), chest(e), 0.5, 10);
            }
        }
    }

    /** Legion Call: a huge circle opens and pillars of light stand up around the Summoner as the spectral echoes rise. */
    public static void legion(ServerPlayer p) {
        ServerLevel level = lv(p);
        Vec3 at = p.position();
        Circles.ground(level, KEY, at, 14.0, 50);
        Circles.ring(level, KEY, at, 9, 12);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            Vec3 base = at.add(Math.cos(a) * 5.0, 0, Math.sin(a) * 5.0);
            Tasks.after(i, s -> {
                pillar(level, base, 1.4, 5.0, 14);
                ghost(level, base, 2.0);
            });
        }
    }

    /** Soul Storm: wisps circle the Summoner, circles open at the echoes and a bolt of souls leaps to each target. */
    public static void stormStart(ServerPlayer p) {
        ServerLevel level = lv(p);
        Circles.ground(level, KEY, p.position(), 7.0, 30);
        Circles.ring(level, KEY, p.position(), 8, 10);
        Props.Prop[] wisps = new Props.Prop[6];
        for (int i = 0; i < wisps.length; i++) {
            wisps[i] = Props.sprite(level, p.position().add(0, 1.0, 0), "soul_wisp", 60);
            wisps[i].pose(Vec3.ZERO, yaw(0), 0.2, 0.2, 0.2, 0);
        }
        during(p, 30, 2, (o, lv, age) -> {
            for (int i = 0; i < wisps.length; i++) {
                double a = age * 0.3 + i * Math.PI * 2 / wisps.length;
                double r = 1.5 + age * 0.05;
                Vec3 pos = o.position().add(Math.cos(a) * r, 0.8 + (i % 3) * 0.5 + age * 0.04, Math.sin(a) * r);
                wisps[i].pin(pos, yaw(-a - Math.PI / 2 + Math.PI), 1.4, 1.4, 1.4, 2);
            }
        }, () -> {
            for (Props.Prop w : wisps) {
                fadeOut(w, yaw(0));
            }
        });
    }

    /** One echo's bolt of souls. */
    public static void stormBolt(ServerLevel level, LivingEntity echo, LivingEntity target) {
        Circles.ground(level, KEY, echo.position(), 2.4, 10);
        thread(level, echo.getEyePosition(), chest(target), 0.9, 6);
        Circles.ground(level, KEY, target.position(), 3.0, 10);
        ghost(level, target.position(), 1.8);
    }

    /** Ascension: a halo, a circle and a column of light on each empowered echo for the duration. */
    public static void ascension(LivingEntity echo, int ticks) {
        ServerLevel level = lv(echo);
        Circles.follow(echo, KEY, Math.max(2.8, echo.getBbWidth() * 2.8), ticks, 0.06);
        Circles.followModel(echo, "halo_summoner", 1.8, ticks, echo.getBbHeight() + 0.5);
        pillar(level, echo.position(), 1.6, 4.5, 14);
        Circles.ring(level, KEY, echo.position(), 3.5, 8);
        rise(level, echo.position(), 6, 3.5);
    }

    // ---------------------------------------------------------------- Sovereign's Domain

    /**
     * The level 100 form: the soul sovereign (skin, wings, crown and orbiting spirits are drawn on the body by the client), a
     * giant circle and a latticed dome over the whole domain, and pulses that ring out for as long as it lasts.
     */
    public static void domain(ServerPlayer p, int ticks) {
        ServerLevel level = lv(p);
        Vec3 at = p.position();
        FxState.set(p, "form:summoner", ticks);
        Circles.ground(level, KEY, at, 42.0, ticks);
        Circles.ring(level, KEY, at, 22, 16);
        Fx.pillar(level, Palette.of(KEY), at, 8, 1.6, 16);
        Tasks.flash(level, p.getX(), p.getY() + 1, p.getZ());
        pillar(level, at, 2.4, 10.0, 24);

        Props.Prop dome = Props.sprite(level, at.add(0, 0.2, 0), "soul_cage", ticks + 30);
        dome.pose(Vec3.ZERO, yaw(0), 2, 2, 2, 0);
        Tasks.after(1, s -> dome.pose(Vec3.ZERO, yaw(1.0), 41, 41, 41, 16));
        int[] tick = {0};
        Tasks.run((server, now) -> {
            if (tick[0] >= ticks) {
                dome.pose(Vec3.ZERO, yaw(1.0 + tick[0] / 20.0 * 0.5), 50, 50, 50, 6);
                dome.removeAfter(7);
                return true;
            }
            if (tick[0] > 0 && tick[0] % 20 == 0) {
                dome.pose(Vec3.ZERO, yaw(1.0 + tick[0] / 20.0 * 0.5), 41, 41, 41, 20);
            }
            if (tick[0] > 0 && tick[0] % 50 == 0) {
                Circles.ring(level, KEY, at, 22, 14);
            }
            tick[0]++;
            return false;
        });
    }

    /** A sovereign's echo: a circle, a halo and a column of light for the domain. */
    public static void domainEcho(LivingEntity echo, int ticks) {
        Circles.follow(echo, KEY, Math.max(3.0, echo.getBbWidth() * 3.0), ticks, 0.06);
        Circles.followModel(echo, "halo_summoner", 2.0, ticks, echo.getBbHeight() + 0.5);
        pillar(lv(echo), echo.position(), 1.6, 5.0, 14);
        rise(lv(echo), echo.position(), 6, 3.5);
    }

    /** A soul spark where a sovereign's echo lands a hit (kept small; particles only supplement the models). */
    public static void spark(ServerLevel level, Vec3 at) {
        Tasks.particles(level, ParticleTypes.SOUL_FIRE_FLAME, at, 6, 0.3, 0.05);
    }
}
