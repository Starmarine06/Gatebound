package dev.gatebound.skill.fx;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.gatebound.data.Element;
import dev.gatebound.skill.mage.MageCore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dev.gatebound.skill.fx.Anim.*;

/**
 * The look of the level 100 Mage forms, drawn with textured models: wings, halos, horns, a crown, orbiting boulders, tendrils
 * pinned to the body, and the element launched from the body at enemies as flying models. It only draws; each form's damage
 * stays in its own skill. One rig per player runs until the form ends (checked with MageCore.inForm).
 */
public final class FormFx {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final Random RNG = new Random();

    private FormFx() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STOPPED.register(server -> ACTIVE.clear());
    }

    // ---------------------------------------------------------------- rig plumbing

    /** Body attachments (wings, halos, horns, crowns, spikes, boulders, tendrils) are drawn by the client from the real rendered
     *  body (client/FormBodyLayer) so they never lag; the server only has to emit the element from the body. */

    /** Body facing as a unit vector (follows the body, not the head). */
    private static Vec3 facing(ServerPlayer o) {
        double b = Math.toRadians(o.yBodyRot);
        return new Vec3(-Math.sin(b), 0, Math.cos(b));
    }

    private static Vec3 left(Vec3 facing) {
        return new Vec3(facing.z, 0, -facing.x);
    }

    /** Starts the form's emitters. Safe to call again while it runs (a refresh): the second call does nothing. */
    public static void start(ServerPlayer p, Element element) {
        if (!ACTIVE.add(p.getUUID())) {
            return;
        }
        Alive alive = o -> MageCore.inForm(o, element);
        during(p, 1_000_000, 2, alive, (o, level, age) -> emit(o, level, element, age),
                () -> ACTIVE.remove(p.getUUID()));
    }

    // ---------------------------------------------------------------- the element, launched from the body

    private static List<LivingEntity> nearest(ServerPlayer o, ServerLevel level, double range, int max) {
        List<LivingEntity> foes = new ArrayList<>(MageCore.foes(o, level, range));
        foes.sort(Comparator.comparingDouble(e -> e.distanceToSqr(o)));
        return foes.size() > max ? foes.subList(0, max) : foes;
    }

    private static Vec3 mid(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.55, 0);
    }

    private static Vec3 hand(ServerPlayer o, int sign) {
        Vec3 f = facing(o);
        return o.position().add(f.scale(0.5)).add(left(f).scale(0.45 * sign)).add(0, 1.1, 0);
    }

    private static Vec3 shoulder(ServerPlayer o, int sign) {
        return o.position().add(left(facing(o)).scale(0.55 * sign)).add(0, 1.85, 0);
    }

    /** A model that flies from one point to another, then calls {@code onHit}. */
    private static void fly(ServerLevel level, String model, Vec3 from, Vec3 to, double size, double speed, double arc,
                            Quaternionf rot, Runnable onHit) {
        int travel = (int) Math.max(3, Math.min(16, from.distanceTo(to) / speed));
        Props.Prop prop = Props.sprite(level, from, model, travel + 14);
        prop.pose(Vec3.ZERO, rot, size * 0.3, size * 0.3, size * 0.3, 0);
        Vec3 delta = to.subtract(from);
        if (arc > 0) {
            Tasks.after(1, s -> prop.pose(delta.scale(0.5).add(0, arc, 0), rot, size, size, size, travel / 2));
            Tasks.after(1 + travel / 2, s -> prop.pose(delta, rot, size, size, size, travel - travel / 2));
        } else {
            Tasks.after(1, s -> prop.pose(delta, rot, size, size, size, travel));
        }
        Tasks.after(travel + 1, s -> {
            prop.pose(delta, rot, size * 1.4, size * 1.4, size * 1.4, 2);
            prop.removeAfter(3);
            if (onHit != null) {
                onHit.run();
            }
        });
    }

    private static void emit(ServerPlayer o, ServerLevel level, Element element, int age) {
        Palette pal = Palette.of(element);
        String key = pal.key();
        switch (element) {
            case FIRE -> {
                if (age % 12 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 14, 2)) {
                        Vec3 to = mid(e);
                        fly(level, "fireball", hand(o, sign), to, 1.1, 1.2, 0, yaw(0), () -> Circles.ring(level, key, to, 1.8, 5));
                        sign = -sign;
                    }
                }
            }
            case ICE -> {
                if (age % 14 == 0) {
                    int n = 0;
                    for (LivingEntity e : nearest(o, level, 14, 3)) {
                        Vec3 to = mid(e);
                        Vec3 from = shoulder(o, n % 2 == 0 ? 1 : -1);
                        fly(level, "ice_shard", from, to, 0.9, 1.6, 0, Circles.face(to.subtract(from), 0), () -> Circles.ring(level, key, to, 1.6, 5));
                        n++;
                    }
                }
            }
            case LIGHTNING -> {
                if (age % 8 == 0) {
                    for (LivingEntity e : nearest(o, level, 12, 2)) {
                        Vec3 to = mid(e);
                        beam(level, "bolt", o.position().add(0, 1.3, 0), to, 0.9, 2);
                        Circles.ring(level, key, to, 1.4, 5);
                    }
                }
                if (age % 6 == 0) { // crackle over the skin
                    Vec3 c = o.position().add(0, 1.0, 0);
                    Vec3 a = c.add((RNG.nextDouble() - 0.5) * 1.6, RNG.nextDouble() * 1.0, (RNG.nextDouble() - 0.5) * 1.6);
                    Vec3 b = c.add((RNG.nextDouble() - 0.5) * 1.6, RNG.nextDouble() * 1.0, (RNG.nextDouble() - 0.5) * 1.6);
                    beam(level, "bolt", a, b, 0.5, 2);
                }
            }
            case WIND -> {
                if (age % 10 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 14, 2)) {
                        Vec3 to = mid(e);
                        Vec3 from = hand(o, sign);
                        Vec3 dir = flat(to.subtract(from));
                        fly(level, "wind_blade", from, to, 2.4, 1.5, 0, Circles.face(UP, Math.atan2(-dir.x, -dir.z)), () -> Circles.ring(level, key, to, 1.8, 5));
                        sign = -sign;
                    }
                }
            }
            case EARTH -> {
                if (age % 20 == 0) {
                    for (LivingEntity e : nearest(o, level, 14, 2)) {
                        Vec3 to = mid(e);
                        Vec3 from = o.position().add(0, 2.3, 0).add(facing(o).scale(-0.3));
                        fly(level, "boulder", from, to, 1.2, 0.9, 2.8, new Quaternionf().rotationXYZ(0.6f, 0.4f, 0.2f), () -> {
                            Circles.ground(level, Element.EARTH, e.position(), 3.0, 14);
                            Circles.ring(level, key, e.position(), 2.4, 6);
                        });
                    }
                }
                if (age % 40 == 20) {
                    Circles.ring(level, key, o.position(), 5, 8);
                }
            }
            case LIGHT -> {
                if (age % 12 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 12, 2)) {
                        Vec3 to = mid(e);
                        beam(level, "light_beam", hand(o, sign), to, 1.0, 3);
                        Circles.ring(level, key, to, 1.6, 5);
                        sign = -sign;
                    }
                }
            }
            case DARK -> {
                if (age % 14 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 14, 2)) {
                        Vec3 to = mid(e);
                        fly(level, "void_sphere", hand(o, sign), to, 1.0, 1.0, 0, Circles.face(UP, 0), () -> Circles.ring(level, key, to, 1.8, 5));
                        sign = -sign;
                    }
                }
                if (age % 20 == 10) {
                    for (LivingEntity e : nearest(o, level, 12, 1)) {
                        beam(level, "tendril", o.position().add(0, 1.3, 0).add(facing(o).scale(-0.3)), mid(e), 0.9, 5);
                    }
                }
            }
        }
    }
}
