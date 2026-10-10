package dev.gatebound.dungeon;

import dev.gatebound.compat.Compat;
import dev.gatebound.trade.GateboundNpc;
import dev.gatebound.trade.NpcHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Furnishes the inside of the overworld tower so it is a proper guild hall: a red carpet from the entrance to a raised
 * dais, a ring of lit pillars, campfires, lecterns, and the Skill Broker and Scholar waiting on the dais. It is
 * idempotent: the dais centre block is the marker, so it also repairs towers built before the hall existed.
 */
public final class TowerHall {
    /** Static holder; not instantiable. */
    private TowerHall() {
    }

    /** Default state of a block. */
    private static BlockState b(Block block) {
        return block.defaultBlockState();
    }

    /** Builds the tower hall and NPCs once the tower exists. */
    public static void ensure(MinecraftServer server, DungeonStore store) {
        if (!store.towerBuilt) {
            return;
        }
        ServerLevel level = server.overworld();
        BlockPos c = store.towerPos;
        int r = store.towerRadius > 0 ? store.towerRadius : DungeonConfig.towerRadius;
        int reach = r - 4;
        if (!level.hasChunkAt(c) || !level.hasChunkAt(c.offset(reach, 0, 0)) || !level.hasChunkAt(c.offset(-reach, 0, 0))
                || !level.hasChunkAt(c.offset(0, 0, reach)) || !level.hasChunkAt(c.offset(0, 0, -reach))) {
            return;
        }
        if (!level.getBlockState(c).is(Blocks.CHISELED_POLISHED_BLACKSTONE)) {
            build(level, c, r);
        }
        gate(level, store, c, r);
        npcs(level, c);
    }

    /** The tower's gate: a three-by-three pane on the dais in the middle of the hall. Also removes the old pane in the entrance arch. */
    private static void gate(ServerLevel level, DungeonStore store, BlockPos c, int r) {
        BlockPos min = c.offset(-1, 1, 0);
        BlockPos max = c.offset(1, 3, 0);
        BlockPos oldGate = c.offset(0, 1, r - 2);
        if (level.hasChunkAt(oldGate)) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int y = 0; y <= 11; y++) {
                    BlockPos p = c.offset(dx, y, r - 2);
                    if (level.getBlockState(p).getBlock() instanceof GateBlock) {
                        level.removeBlock(p, false);
                    }
                }
            }
        }
        BlockState pane = DungeonRegistries.GATE.get().defaultBlockState()
                .setValue(GateBlock.RANK, GateRank.S).setValue(GateBlock.FACING, Direction.SOUTH);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!(level.getBlockState(p).getBlock() instanceof GateBlock)) {
                level.setBlock(p, pane, 3);
            }
        }
        if (!store.entranceMin.equals(min) || !store.entranceMax.equals(max)) {
            store.entranceMin = min;
            store.entranceMax = max;
            store.setDirty();
        }
    }

    /** Builds the hall, dais and pillars. */
    private static void build(ServerLevel level, BlockPos c, int r) {
        int pillarR = Math.max(8, r - 9);
        int hallH = 15;
        // Dais with an inlaid centre.
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                BlockPos p = c.offset(dx, 0, dz);
                if (d <= 2.5) {
                    level.setBlock(p, b(Blocks.CHISELED_POLISHED_BLACKSTONE), 3);
                } else if (d <= 5.5) {
                    level.setBlock(p, ((int) d % 2 == 0) ? b(Blocks.POLISHED_BLACKSTONE) : b(Blocks.GILDED_BLACKSTONE), 3);
                }
            }
        }
        // Carpet runner from the entrance to the dais.
        for (int dz = 6; dz <= r - 4; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                level.setBlock(c.offset(dx, 0, dz), b(dx == 0 ? Compat.PURPLE_CARPET : Compat.RED_CARPET), 3);
            }
        }
        // Pillars with glowing bands.
        for (int k = 0; k < 12; k++) {
            double a = Math.PI * 2 * k / 12 + Math.PI / 12;
            int px = (int) Math.round(Math.cos(a) * pillarR);
            int pz = (int) Math.round(Math.sin(a) * pillarR);
            for (int y = 0; y < hallH; y++) {
                BlockState s = (y == 4 || y == 10) ? b(Blocks.SEA_LANTERN)
                        : (y == hallH - 1 ? b(Blocks.CHISELED_POLISHED_BLACKSTONE) : b(Blocks.POLISHED_DEEPSLATE));
                level.setBlock(c.offset(px, y, pz), s, 3);
            }
            // Braziers at the foot of every other pillar.
            if (k % 2 == 0) {
                int bx = (int) Math.round(Math.cos(a) * (pillarR - 2));
                int bz = (int) Math.round(Math.sin(a) * (pillarR - 2));
                level.setBlock(c.offset(bx, 0, bz), b(Blocks.CAMPFIRE), 3);
            }
        }
        // Lecterns around the dais, as notice boards.
        int[][] spots = {{8, 0}, {-8, 0}, {0, -8}, {0, 8}};
        for (int[] s : spots) {
            level.setBlock(c.offset(s[0], 0, s[1]), b(Blocks.LECTERN), 3);
        }
    }

    /** Spawns the Broker and Scholar if missing. */
    private static void npcs(ServerLevel level, BlockPos c) {
        AABB hall = new AABB(c).inflate(10, 6, 10);
        List<GateboundNpc> existing = level.getEntitiesOfClass(GateboundNpc.class, hall);
        boolean broker = existing.stream().anyMatch(GateboundNpc::isBroker);
        boolean scholar = existing.stream().anyMatch(n -> !n.isBroker());
        if (!broker) {
            NpcHandler.spawnNpc(level, c.offset(-3, 1, 0), true);
        }
        if (!scholar) {
            NpcHandler.spawnNpc(level, c.offset(3, 1, 0), false);
        }
    }
}
