package dev.gatebound.skill.fx;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.UUID;

/** Small animation helpers shared by the class and form effects: model pops, per-player frame loops, beams between points. */
final class Anim {
    static final Vec3 UP = new Vec3(0, 1, 0);

    private Anim() {
    }

    /** The server level an entity is in (older versions type Entity.level() as plain Level). */
    static ServerLevel lv(Entity e) {
        return (ServerLevel) e.level();
    }

    static Quaternionf yaw(double rad) {
        return new Quaternionf().rotationY((float) rad);
    }

    static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0, v.z);
        return f.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : f.normalize();
    }

    /** A model that pops in, holds, then shrinks away. */
    static Props.Prop pop(ServerLevel level, Vec3 at, String model, Quaternionf rot, double sx, double sy, double sz, int hold) {
        Props.Prop prop = Props.sprite(level, at, model, hold + 16);
        prop.pose(Vec3.ZERO, rot, sx * 0.2, sy * 0.2, sz * 0.2, 0);
        Tasks.after(1, s -> prop.pose(Vec3.ZERO, rot, sx, sy, sz, 3));
        Tasks.after(hold + 3, s -> {
            prop.pose(Vec3.ZERO, rot, sx * 1.1, sy * 0.02, sz * 1.1, 6);
            prop.removeAfter(7);
        });
        return prop;
    }

    static Props.Prop pop(ServerLevel level, Vec3 at, String model, Quaternionf rot, double size, int hold) {
        return pop(level, at, model, rot, size, size, size, hold);
    }

    /** A strip model (vertical, 1 block wide before scaling) stretched between two points, shown briefly. */
    static void beam(ServerLevel level, String model, Vec3 from, Vec3 to, double width, int hold) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 0.1) {
            return;
        }
        pop(level, from.add(to).scale(0.5), model, Circles.face(d, 0), width, len, width, hold);
    }

    /** Runs {@code frame} for a player every {@code every} ticks for {@code ticks} ticks, then {@code end}. */
    interface Frame {
        void at(ServerPlayer p, ServerLevel level, int age);
    }

    /** Like {@link #during} but ends when {@code alive} turns false instead of after a fixed time. */
    interface Alive {
        boolean test(ServerPlayer p);
    }

    static void during(ServerPlayer p, int ticks, int every, Frame frame, Runnable end) {
        during(p, ticks, every, o -> true, frame, end);
    }

    static void during(ServerPlayer p, int ticks, int every, Alive alive, Frame frame, Runnable end) {
        UUID id = p.getUUID();
        int[] age = {0};
        Tasks.run((server, now) -> {
            ServerPlayer o = Tasks.playerOf(server, id);
            if (o == null || age[0] >= ticks || !alive.test(o)) {
                if (end != null) {
                    end.run();
                }
                return true;
            }
            if (age[0] % every == 0) {
                frame.at(o, lv(o), age[0]);
            }
            age[0]++;
            return false;
        });
    }

    static void fadeOut(Props.Prop prop, Quaternionf rot) {
        prop.pose(Vec3.ZERO, rot, 0.02, 0.02, 0.02, 4);
        prop.removeAfter(5);
    }
}
