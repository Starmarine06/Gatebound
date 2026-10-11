package dev.gatebound.dungeon;

import dev.gatebound.compat.Compat;
import dev.gatebound.sound.GateboundSounds;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gravitational pull of active gates: players, hostile mobs and items within the radius are drawn toward the gate
 * with force growing as they near it, with a swirling particle vortex and an ambient hum. Only gates that have a
 * player nearby are processed, and only entities inside the radius.
 */
public final class GatePull {
    private static final Map<UUID, Long> IMMUNE = new HashMap<>();

    /** Static holder; not instantiable. */
    private GatePull() {
    }

    /** Clears pull immunity. */
    public static void reset() {
        IMMUNE.clear();
    }

    /** Players who just came through a gate are not pulled for a few seconds. */
    public static void grantImmunity(ServerPlayer player) {
        IMMUNE.put(player.getUUID(), player.level().getServer().overworld().getGameTime() + DungeonConfig.pullImmunityTicks);
    }

    /** True if the player is immune to the pull right now. */
    private static boolean immune(ServerPlayer p, long now) {
        Long until = IMMUNE.get(p.getUUID());
        if (until == null) {
            return false;
        }
        if (now >= until) {
            IMMUNE.remove(p.getUUID());
            return false;
        }
        return true;
    }

    /** Pulls nearby entities toward open gates and draws the vortex. */
    public static void tick(MinecraftServer server, DungeonStore store) {
        if (!DungeonConfig.gatePull || store.gates.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        int tc = server.getTickCount();
        double radius = Math.max(4.0, DungeonConfig.pullRadius);
        for (DungeonStore.GateRef g : store.gates) {
            ServerLevel level = server.getLevel(g.dim);
            if (level == null || !level.hasChunkAt(g.pos)) {
                continue;
            }
            BlockState state = level.getBlockState(g.pos);
            if (!(state.getBlock() instanceof GateBlock)) {
                continue;
            }
            double gx = g.pos.getX() + 0.5;
            double gy = g.pos.getY() + 1.0;
            double gz = g.pos.getZ() + 0.5;
            boolean near = false;
            boolean veryNear = false;
            for (ServerPlayer p : level.players()) {
                double dx = p.getX() - gx;
                double dy = p.getY() - gy;
                double dz = p.getZ() - gz;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < (radius + 6) * (radius + 6)) {
                    near = true;
                    if (d2 < 25) {
                        veryNear = true;
                    }
                }
            }
            if (!near) {
                continue;
            }
            Direction facing = state.getValue(GateBlock.FACING);
            if (DungeonConfig.pullParticles && tc % 3 == 0) {
                vortex(level, gx, gy, gz, facing, tc, veryNear, state.getValue(GateBlock.RANK));
            }
            int salt = g.pos.hashCode() & 0x7F;
            if ((tc + salt) % 130 == 0) {
                level.playSound(null, g.pos, GateboundSounds.custom("gate.ambient"), SoundSource.AMBIENT, 0.35f, 1f);
            }
            if (veryNear && (tc + salt) % 50 == 0) {
                level.playSound(null, g.pos, GateboundSounds.of(SoundEvents.BEACON_AMBIENT), SoundSource.AMBIENT, 0.5f, 0.5f);
            }

            AABB box = new AABB(gx - radius, gy - radius, gz - radius, gx + radius, gy + radius, gz + radius);
            Vec3 centre = new Vec3(gx, gy, gz);
            for (Entity e : level.getEntitiesOfClass(Entity.class, box, x -> pullable(x, now))) {
                pull(server, level, g, state, e, centre, radius, now);
            }
        }
    }

    /** True if the entity may be pulled. */
    private static boolean pullable(Entity e, long now) {
        if (e instanceof ServerPlayer p) {
            return !p.isSpectator() && !p.getAbilities().flying && !WorkQueue.isHolding(p.getUUID()) && !immune(p, now);
        }
        if (e.entityTags().contains("gatebound_echo")) {
            return false;
        }
        if (e instanceof ItemEntity) {
            return DungeonConfig.pullItems;
        }
        return DungeonConfig.pullMobs && e instanceof Enemy;
    }

    /** Pulls one entity toward a gate; players who reach it enter. */
    private static void pull(MinecraftServer server, ServerLevel level, DungeonStore.GateRef g, BlockState gate,
                             Entity e, Vec3 centre, double radius, long now) {
        Vec3 here = e.position().add(0, e.getBbHeight() * 0.5, 0);
        Vec3 to = centre.subtract(here);
        double dist = to.length();
        if (e instanceof ServerPlayer enterer && dist < 0.9) {
            // Drawn fully into the gate: same as walking into it.
            if (DungeonManager.portalReady(enterer)) {
                DungeonManager.enterGate(enterer, g.pos, gate.getValue(GateBlock.RANK));
            }
            return;
        }
        if (dist < 1.0e-3) {
            return;
        }
        double frac = 1.0 - dist / radius;
        if (frac <= 0) {
            return;
        }
        double acc = DungeonConfig.pullStrength * Math.pow(frac, 1.5);
        if (dist < 3.0) {
            acc *= 1.6;
        }
        Vec3 dir = to.scale(1.0 / dist);
        if (e instanceof ServerPlayer sp) {
            if (sp.isShiftKeyDown()) {
                acc *= 0.5;
            }
            Vec3 push = new Vec3(dir.x, dir.y * 0.5, dir.z).scale(acc);
            Vec3 v = sp.getKnownMovement().add(push);
            double max = 0.9;
            if (v.length() > max) {
                v = v.scale(max / v.length());
            }
            sp.setDeltaMovement(v);
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
        } else {
            acc *= e instanceof ItemEntity ? 0.6 : 0.4;
            Vec3 push = new Vec3(dir.x, dir.y * 0.5, dir.z).scale(acc);
            Vec3 v = e.getDeltaMovement().add(push);
            if (v.length() > 0.6) {
                v = v.scale(0.6 / v.length());
            }
            e.setDeltaMovement(v);
            e.needsSync = true;
        }
    }

    /** Draws a gate's vortex particles. */
    private static void vortex(ServerLevel level, double gx, double gy, double gz, Direction facing, int tc, boolean strong, GateRank rank) {
        boolean alongX = facing.getAxis() == Direction.Axis.X; // plane spans Z (and Y) when the gate faces X
        double t = tc * 0.16;
        int arms = strong ? 8 : 5;
        for (int k = 0; k < arms; k++) {
            double ang = t + k * (Math.PI * 2 / arms);
            double rad = 9.0 - 8.0 * (((tc + k * 9) % 48) / 48.0);
            double s = Math.cos(ang) * rad;
            double y = Math.sin(ang) * rad * 0.8;
            double px = alongX ? gx : gx + s;
            double pz = alongX ? gz + s : gz;
            double py = gy + y;
            // ENCHANT particles fly from their spawn point along the given vector, here straight into the gate centre.
            level.sendParticles(ParticleTypes.ENCHANT, px, py, pz, 0, gx - px, gy - py, gz - pz, 1.0);
        }
        // Rank-coloured frame: a ring turning in the gate plane, wider and denser for higher ranks.
        var dust = Compat.dust(rank.rgb(), 1.0f + rank.ordinal() * 0.12f);
        int beads = 6 + rank.ordinal() * 2;
        double ringR = 1.15 + rank.ordinal() * 0.06;
        for (int k = 0; k < beads; k++) {
            double ang = -t * 0.7 + k * (Math.PI * 2 / beads);
            double s = Math.cos(ang) * ringR;
            level.sendParticles(dust, alongX ? gx : gx + s, gy + Math.sin(ang) * ringR * 1.35, alongX ? gz + s : gz, 1, 0, 0, 0, 0);
        }
        if (rank.ordinal() >= 3 && tc % 6 == 0) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, gx, gy, gz, 3 + rank.ordinal(), 0.6, 1.1, 0.6, 0.3);
        }
        if (rank == GateRank.S && tc % 9 == 0) {
            level.sendParticles(ParticleTypes.END_ROD, gx, gy + 1.4, gz, 2, 0.3, 0.1, 0.3, 0.05);
        }
        level.sendParticles(ParticleTypes.PORTAL, gx, gy, gz, strong ? 10 : 4, 0.5, 0.9, 0.5, 0.4);
        if (strong) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, gx, gy, gz, 4, 0.8, 1.1, 0.8, 0.05);
        }
    }
}
