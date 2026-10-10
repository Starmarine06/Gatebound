package dev.gatebound.skill.fx;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerDataStore;
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
 * What the level 100 Mage forms launch from the body: flying models, beams, spikes and strikes, and the damage each one deals on
 * impact (MageCore.dmg scaled by HIT_SCALE, on top of each form's own aura in its skill). The body attachments themselves
 * (wings, halos, horns...) are drawn by the client (client/FormBodyLayer). One emitter per player runs until the form ends
 * (checked with MageCore.inForm).
 */
public final class FormFx {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final Random RNG = new Random();

    /** Static holder; not instantiable. */
    private FormFx() {
    }

    /** Clears running forms when the server stops. */
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

    /** Vector to the left of a facing direction. */
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

    /** The nearest foes within range, up to a maximum. */
    private static List<LivingEntity> nearest(ServerPlayer o, ServerLevel level, double range, int max) {
        List<LivingEntity> foes = new ArrayList<>(MageCore.foes(o, level, range));
        foes.sort(Comparator.comparingDouble(e -> e.distanceToSqr(o)));
        return foes.size() > max ? foes.subList(0, max) : foes;
    }

    /** Middle of an entity's body. */
    private static Vec3 mid(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.55, 0);
    }

    /** Position of the player's left or right hand. */
    private static Vec3 hand(ServerPlayer o, int sign) {
        Vec3 f = facing(o);
        return o.position().add(f.scale(0.5)).add(left(f).scale(0.45 * sign)).add(0, 1.1, 0);
    }

    /** Position above the player's left or right shoulder. */
    private static Vec3 shoulder(ServerPlayer o, int sign) {
        return o.position().add(left(facing(o)).scale(0.55 * sign)).add(0, 1.85, 0);
    }

    // ---------------------------------------------------------------- damage

    /** Volley hits are many and fast, so each one hits for a fraction of a single skill's damage. */
    private static final float HIT_SCALE = 0.6f;

    /** Damages a foe with a fraction of a skill's damage. */
    private static void hit(ServerPlayer o, ServerLevel level, LivingEntity e, double base) {
        if (e.isAlive()) {
            MageCore.strike(o, level, e, MageCore.dmg(PlayerDataStore.of(o), base) * HIT_SCALE);
        }
    }

    /** Hits everything near a point; {@code knock} pushes it away from the point. */
    private static void splash(ServerPlayer o, ServerLevel level, Vec3 at, double radius, double base, double knock) {
        for (LivingEntity e : MageCore.foesAt(o, level, at, radius)) {
            hit(o, level, e, base);
            if (knock > 0) {
                MageCore.knock(e, at, knock, 0.25);
            }
        }
    }

    /** A stone spike bursting out of the ground under a point, then sinking back. */
    private static void spike(ServerLevel level, Vec3 base, double h) {
        Props.Prop s = Props.sprite(level, base, "stone_spike", 40);
        Quaternionf rot = yaw(RNG.nextDouble() * 3);
        s.pose(Vec3.ZERO, rot, 1.2, 0.1, 1.2, 0);
        Tasks.after(1, t -> s.pose(new Vec3(0, h * 0.5 - 0.3, 0), rot, 1.6, h, 1.6, 3));
        Tasks.after(20, t -> {
            s.pose(new Vec3(0, -0.2, 0), rot, 1.4, 0.1, 1.4, 6);
            s.removeAfter(7);
        });
        Circles.ground(level, Element.EARTH, base, 2.8, 14);
    }

    /** Per-frame emitters of an element's form. */
    private static void emit(ServerPlayer o, ServerLevel level, Element element, int age) {
        Palette pal = Palette.of(element);
        String key = pal.key();
        switch (element) {
            case FIRE -> {
                if (age % 12 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 14, 2)) {
                        Vec3 to = mid(e);
                        fly(level, "fireball", hand(o, sign), to, 1.1, 1.2, 0, yaw(0), () -> {
                            Circles.ring(level, key, to, 1.8, 5);
                            splash(o, level, to, 2.2, 5, 0.3);
                            e.setRemainingFireTicks(80);
                        });
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
                        fly(level, "ice_shard", from, to, 0.9, 1.6, 0, Circles.face(to.subtract(from), 0), () -> {
                            Circles.ring(level, key, to, 1.6, 5);
                            hit(o, level, e, 3);
                        });
                        n++;
                    }
                }
                if (age % 40 == 20) { // a freezing pulse
                    Circles.ring(level, key, o.position(), 7, 9);
                    splash(o, level, o.position(), 7, 3, 0.2);
                }
            }
            case LIGHTNING -> {
                if (age % 6 == 0) { // a bolt from the chest that chains from one enemy to the next
                    Vec3 from = o.position().add(0, 1.3, 0);
                    for (LivingEntity e : nearest(o, level, 14, 3)) {
                        Vec3 to = mid(e);
                        beam(level, "bolt", from, to, 1.1, 2);
                        Circles.ring(level, key, to, 1.6, 5);
                        hit(o, level, e, 3);
                        from = to;
                    }
                }
                if (age % 24 == 0) { // strikes crashing down from the sky
                    for (LivingEntity e : nearest(o, level, 16, 3)) {
                        beam(level, "bolt", e.position().add(0, 14, 0), e.position().add(0, 0.2, 0), 1.8, 4);
                        Circles.ground(level, Element.LIGHTNING, e.position(), 3.2, 10);
                        Tasks.flash(level, e.getX(), e.getY() + 1, e.getZ());
                        Tasks.sound(level, e.position(), pal.hit(), 0.7f, 1.3f);
                        splash(o, level, e.position(), 3.0, 9, 0.2);
                    }
                }
                if (age % 4 == 0) { // crackle over the skin
                    Vec3 c = o.position().add(0, 1.0, 0);
                    for (int i = 0; i < 2; i++) {
                        Vec3 a2 = c.add((RNG.nextDouble() - 0.5) * 1.8, RNG.nextDouble() * 1.2, (RNG.nextDouble() - 0.5) * 1.8);
                        Vec3 b2 = c.add((RNG.nextDouble() - 0.5) * 1.8, RNG.nextDouble() * 1.2, (RNG.nextDouble() - 0.5) * 1.8);
                        beam(level, "bolt", a2, b2, 0.5, 2);
                    }
                }
            }
            case WIND -> {
                if (age % 6 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 14, 3)) {
                        Vec3 to = mid(e);
                        Vec3 from = hand(o, sign);
                        Vec3 dir = flat(to.subtract(from));
                        fly(level, "wind_blade", from, to, 2.6, 1.6, 0, Circles.face(UP, Math.atan2(-dir.x, -dir.z)), () -> {
                            Circles.ring(level, key, to, 1.8, 5);
                            hit(o, level, e, 3);
                            MageCore.knock(e, from, 0.4, 0.15);
                        });
                        sign = -sign;
                    }
                }
                if (age % 20 == 0) { // a ring of blades flung out in every direction, cutting everything they pass
                    Circles.ring(level, key, o.position(), 7, 9);
                    for (int i = 0; i < 6; i++) {
                        double ang = i * Math.PI / 3 + age * 0.1;
                        Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                        Vec3 from = o.position().add(0, 1.0, 0).add(dir.scale(0.6));
                        fly(level, "wind_blade", from, from.add(dir.scale(8)), 2.8, 1.3, 0, Circles.face(UP, Math.atan2(-dir.x, -dir.z)), null);
                    }
                    splash(o, level, o.position(), 8, 4, 0.9);
                }
            }
            case EARTH -> {
                if (age % 14 == 0) { // boulders lobbed from behind the shoulders
                    for (LivingEntity e : nearest(o, level, 14, 3)) {
                        Vec3 to = mid(e);
                        Vec3 from = o.position().add(0, 2.3, 0).add(facing(o).scale(-0.3));
                        fly(level, "boulder", from, to, 1.3, 0.9, 2.8, new Quaternionf().rotationXYZ(0.6f, 0.4f, 0.2f), () -> {
                            Circles.ground(level, Element.EARTH, e.position(), 3.0, 14);
                            Circles.ring(level, key, e.position(), 2.4, 6);
                            splash(o, level, e.position(), 3.0, 6, 0.5);
                        });
                    }
                }
                if (age % 16 == 0) { // stone spikes erupt under the enemies
                    for (LivingEntity e : nearest(o, level, 14, 4)) {
                        spike(level, e.position(), 2.6 + RNG.nextDouble() * 1.4);
                        splash(o, level, e.position(), 1.8, 4, 0);
                        MageCore.knock(e, e.position().add(0, -1, 0), 0.0, 0.7);
                    }
                }
                if (age % 30 == 10) {
                    Circles.ring(level, key, o.position(), 6, 8);
                    splash(o, level, o.position(), 6, 3, 0.7);
                }
            }
            case LIGHT -> {
                if (age % 12 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 12, 2)) {
                        Vec3 to = mid(e);
                        beam(level, "light_beam", hand(o, sign), to, 1.0, 3);
                        Circles.ring(level, key, to, 1.6, 5);
                        hit(o, level, e, 3);
                        sign = -sign;
                    }
                }
            }
            case DARK -> {
                if (age % 14 == 0) {
                    int sign = 1;
                    for (LivingEntity e : nearest(o, level, 14, 2)) {
                        Vec3 to = mid(e);
                        fly(level, "void_sphere", hand(o, sign), to, 1.0, 1.0, 0, Circles.face(UP, 0), () -> {
                            Circles.ring(level, key, to, 1.8, 5);
                            splash(o, level, to, 2.0, 4, 0);
                            o.heal(0.5f);
                        });
                        sign = -sign;
                    }
                }
                if (age % 20 == 10) {
                    for (LivingEntity e : nearest(o, level, 12, 1)) {
                        beam(level, "tendril", o.position().add(0, 1.3, 0).add(facing(o).scale(-0.3)), mid(e), 0.9, 5);
                        hit(o, level, e, 4);
                    }
                }
            }
        }
    }
}
