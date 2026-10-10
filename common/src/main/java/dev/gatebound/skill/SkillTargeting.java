package dev.gatebound.skill;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Shared targeting helpers for skills. Players are never targeted. */
public final class SkillTargeting {
    /** Static holder; not instantiable. */
    private SkillTargeting() {
    }

    /** Living, non-player entities along the player's line of sight, nearest first, stopped by walls. */
    public static List<LivingEntity> ray(ServerPlayer player, ServerLevel level, double range, int max, ParticleOptions trail) {
        Vec3 from = player.getEyePosition();
        Vec3 end = from.add(player.getLookAngle().scale(range));
        BlockHitResult block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        final Vec3 rayEnd = end;
        AABB search = player.getBoundingBox().expandTowards(rayEnd.subtract(from)).inflate(1.0);
        List<Entity> candidates = level.getEntities(player, search, SkillTargeting::targetable);

        List<LivingEntity> hits = new ArrayList<>();
        for (Entity e : candidates) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.3).clip(from, rayEnd);
            if (hit.isPresent()) {
                hits.add((LivingEntity) e);
            }
        }
        hits.sort(Comparator.comparingDouble(e -> e.distanceToSqr(from)));
        if (hits.size() > max) {
            hits = new ArrayList<>(hits.subList(0, max));
        }

        if (trail != null) {
            Vec3 step = rayEnd.subtract(from);
            int points = (int) Math.max(2, step.length() * 2);
            for (int i = 1; i <= points; i++) {
                Vec3 p = from.add(step.scale(i / (double) points));
                level.sendParticles(trail, p.x, p.y - 0.15, p.z, 1, 0, 0, 0, 0);
            }
        }
        return hits;
    }

    /** Any living mob (hostile, neutral or passive); never players or armour stands. */
    public static boolean targetable(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !(e instanceof net.minecraft.world.entity.player.Player)
                && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand);
    }

    /** Hostile living entities within a radius of the player. */
    public static List<LivingEntity> enemiesAround(ServerPlayer player, ServerLevel level, double radius) {
        return around(player, level, radius, x -> x instanceof Enemy && targetable(x));
    }

    /** Every targetable mob within a radius of the player, hostile or not. */
    public static List<LivingEntity> creaturesAround(ServerPlayer player, ServerLevel level, double radius) {
        return around(player, level, radius, SkillTargeting::targetable);
    }

    /** Entities of a filter within a radius of the player. */
    private static List<LivingEntity> around(ServerPlayer player, ServerLevel level, double radius, java.util.function.Predicate<Entity> filter) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : level.getEntities(player, box, filter)) {
            if (e.distanceToSqr(player) <= radius * radius) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }
}
