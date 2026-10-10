package dev.gatebound.skill.fx;

import dev.gatebound.data.Element;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Textured magic circles and rings: flat model props with the element's inscribed circle (textures/item/fx/circle_*.png) that
 * grow in, turn slowly, and shrink away. A circle can lie on the ground, stand upright in front of the caster to cast from,
 * or stand in front of an enemy facing the caster.
 */
public final class Circles {
    private static final int STEP = 4; // ticks between pose updates while spinning

    private Circles() {
    }

    /** The rotation that turns the model's flat face (its +Y normal) to point along {@code normal}, then spins it by {@code spin}. */
    public static Quaternionf face(Vec3 normal, double spin) {
        Vec3 n = normal.lengthSqr() < 1.0e-6 ? new Vec3(0, 1, 0) : normal.normalize();
        Quaternionf q = new Quaternionf().rotationTo(0f, 1f, 0f, (float) n.x, (float) n.y, (float) n.z);
        return q.rotateY((float) spin);
    }

    /** A circle that lives {@code life} ticks: grows in, spins, then shrinks out. Returns the prop. */
    public static Props.Prop circle(ServerLevel level, String key, Vec3 center, Vec3 normal, double size, int life) {
        Props.Prop prop = Props.sprite(level, center, "circle_" + key, life + 12);
        double[] spin = {0};
        int[] t = {0};
        prop.pose(Vec3.ZERO, face(normal, 0), size * 0.15, size * 0.15, size * 0.15, 0);
        Tasks.run((server, now) -> {
            if (t[0] == 1) {
                spin[0] += 0.8;
                prop.pose(Vec3.ZERO, face(normal, spin[0]), size, size, size, 6); // grows in with a half turn
            } else if (t[0] > 6 && t[0] < life - 5 && t[0] % STEP == 0) {
                spin[0] += 0.35;
                prop.pose(Vec3.ZERO, face(normal, spin[0]), size, size, size, STEP);
            } else if (t[0] == life - 5) {
                spin[0] += 1.2;
                prop.pose(Vec3.ZERO, face(normal, spin[0]), size * 1.25, size * 1.25, size * 1.25, 5); // flares as it fades
            }
            t[0]++;
            if (t[0] >= life) {
                prop.pose(Vec3.ZERO, face(normal, spin[0]), 0.02, 0.02, 0.02, 2);
                prop.removeAfter(3);
                return true;
            }
            return false;
        });
        return prop;
    }

    /** A circle lying on the ground. {@code size} is its diameter in blocks. */
    public static void ground(ServerLevel level, Element element, Vec3 center, double size, int life) {
        ground(level, Palette.of(element).key(), center, size, life);
    }

    public static void ground(ServerLevel level, String key, Vec3 center, double size, int life) {
        circle(level, key, center.add(0, 0.06, 0), new Vec3(0, 1, 0), size, life);
    }

    /** An upright circle in front of the caster, facing them, that a spell is cast through. Returns its centre. */
    public static Vec3 castCircle(ServerPlayer p, Element element, double size, int life) {
        return castCircle(p, Palette.of(element).key(), size, life);
    }

    public static Vec3 castCircle(ServerPlayer p, String key, double size, int life) {
        Vec3 look = p.getLookAngle();
        Vec3 center = p.getEyePosition().add(look.scale(2.3)).add(0, -0.2, 0);
        circle(p.level(), key, center, look, size, life);
        return center;
    }

    /** An upright circle in front of an enemy, on the side facing the caster, that the spell lands through. */
    public static void onTarget(ServerLevel level, Element element, Vec3 caster, LivingEntity target, double size, int life) {
        onTarget(level, Palette.of(element).key(), caster, target, size, life);
    }

    public static void onTarget(ServerLevel level, String key, Vec3 caster, LivingEntity target, double size, int life) {
        Vec3 mid = target.position().add(0, target.getBbHeight() * 0.55, 0);
        Vec3 toCaster = caster.subtract(mid);
        Vec3 flat = new Vec3(toCaster.x, 0, toCaster.z);
        flat = flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
        double dist = target.getBbWidth() * 0.5 + 0.8;
        circle(level, key, mid.add(flat.scale(dist)), flat, Math.max(size, target.getBbHeight() * 1.5), life);
    }

    /**
     * A circle that stays with a moving entity for {@code life} ticks: flat on the ground under it ({@code lift} about 0.06) or
     * floating above its head as a mark (a larger {@code lift}, with {@code upright} false the mark lies flat and spins).
     * Ends early if the entity dies or leaves.
     */
    public static Props.Prop follow(net.minecraft.world.entity.Entity entity, String key, double size, int life, double lift) {
        ServerLevel level = (ServerLevel) entity.level();
        Props.Prop prop = Props.sprite(level, entity.position().add(0, lift, 0), "circle_" + key, life + 12);
        Quaternionf flat = face(new Vec3(0, 1, 0), 0);
        prop.pose(Vec3.ZERO, flat, size * 0.15, size * 0.15, size * 0.15, 0);
        double[] spin = {0};
        int[] t = {0};
        Vec3 origin = prop.origin();
        Tasks.run((server, now) -> {
            boolean gone = !entity.isAlive() || entity.isRemoved();
            if (gone || t[0] >= life) {
                prop.pose(Vec3.ZERO, face(new Vec3(0, 1, 0), spin[0]), 0.02, 0.02, 0.02, 2);
                prop.removeAfter(3);
                return true;
            }
            if (t[0] % STEP == 0) {
                spin[0] += 0.35;
                Vec3 offset = entity.position().add(0, lift, 0).subtract(origin);
                prop.pose(offset, face(new Vec3(0, 1, 0), spin[0]), size, size, size, STEP);
            }
            t[0]++;
            return false;
        });
        return prop;
    }

    /** A flat textured ring that sweeps outward from the centre to {@code maxRadius} and vanishes. */
    public static void ring(ServerLevel level, String key, Vec3 center, double maxRadius, int ticks) {
        Props.Prop prop = Props.sprite(level, center.add(0, 0.12, 0), "ring_" + key, ticks + 8);
        Quaternionf flat = face(new Vec3(0, 1, 0), 0);
        prop.pose(Vec3.ZERO, flat, 0.4, 0.4, 0.4, 0);
        Tasks.after(1, server -> prop.pose(Vec3.ZERO, flat, maxRadius * 2.1, maxRadius * 2.1, maxRadius * 2.1, ticks));
        Tasks.after(ticks + 1, server -> {
            prop.pose(Vec3.ZERO, flat, maxRadius * 2.3, maxRadius * 2.3, maxRadius * 2.3, 2);
            prop.removeAfter(3);
        });
    }
}
