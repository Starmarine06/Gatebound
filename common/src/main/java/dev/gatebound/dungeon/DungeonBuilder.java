package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedurally builds one dungeon inside a plot of the dungeon dimension.
 * A plot is a 2048-block stripe along X (plot N starts at x = N * 2048, z 0..31) so runs never touch.
 * Layout, west to east: entrance room, several combat rooms joined by corridors, a large boss arena.
 */
public final class DungeonBuilder {
    public static final int PLOT_SPACING = 2048;
    public static final int FLOOR_Y = 40;
    public static final int PLOT_LENGTH = 176;
    public static final int PLOT_WIDTH = 32;
    private static final int Z_MID = 16;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Result of a build. mobRooms entries are {x1, z1, x2, z2} interior bounds. */
    public record Layout(BlockPos spawn, BlockPos boss, BlockPos exit, List<int[]> mobRooms) {
    }

    private DungeonBuilder() {
    }

    public static int plotOf(double x) {
        return Math.floorDiv((int) Math.floor(x), PLOT_SPACING);
    }

    public static int originX(int plot) {
        return plot * PLOT_SPACING;
    }

    /** Everything a plot may contain, with a small margin. */
    public static AABB bounds(int plot) {
        int ox = originX(plot);
        return new AABB(ox - 4, FLOOR_Y - 4, -4, ox + PLOT_LENGTH + 4, FLOOR_Y + 16, PLOT_WIDTH + 4);
    }

    public static Layout build(ServerLevel level, int plot, GateRank rank, RandomSource rng) {
        int ox = originX(plot);
        BlockState[] wall = wallPalette(rank);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState lantern = Blocks.SEA_LANTERN.defaultBlockState();

        // Room interiors: {x1, z1, x2, z2, height}
        List<int[]> rooms = new ArrayList<>();
        int x1 = ox + 3;
        rooms.add(new int[]{x1, Z_MID - 4, x1 + 8, Z_MID + 4, 6});
        int combat = rank.roomCount();
        for (int i = 0; i < combat; i++) {
            int[] prev = rooms.get(rooms.size() - 1);
            int w = 9 + 2 * rng.nextInt(4);
            int d = 9 + 2 * rng.nextInt(4);
            int nx = prev[2] + 9;
            rooms.add(new int[]{nx, Z_MID - d / 2, nx + w - 1, Z_MID + d / 2, 6});
        }
        int[] last = rooms.get(rooms.size() - 1);
        int bx = last[2] + 9;
        int[] bossRoom = {bx, Z_MID - 10, bx + 20, Z_MID + 10, 9};
        rooms.add(bossRoom);

        // Pass 1: solid shells (rooms and corridors share material and overlap safely).
        for (int i = 0; i < rooms.size(); i++) {
            int[] r = rooms.get(i);
            shell(level, rng, wall, r[0] - 1, FLOOR_Y, r[1] - 1, r[2] + 1, FLOOR_Y + r[4] + 1, r[3] + 1);
            if (i > 0) {
                int[] p = rooms.get(i - 1);
                shell(level, rng, wall, p[2] + 1, FLOOR_Y, Z_MID - 2, r[0] - 1, FLOOR_Y + 5, Z_MID + 2);
            }
        }
        // Pass 2: carve interiors and doorways.
        for (int i = 0; i < rooms.size(); i++) {
            int[] r = rooms.get(i);
            fill(level, r[0], FLOOR_Y + 1, r[1], r[2], FLOOR_Y + r[4], r[3], air);
            if (i > 0) {
                int[] p = rooms.get(i - 1);
                fill(level, p[2] + 1, FLOOR_Y + 1, Z_MID - 1, r[0] - 1, FLOOR_Y + 4, Z_MID + 1, air);
                int mid = (p[2] + 1 + r[0] - 1) / 2;
                set(level, mid, FLOOR_Y + 4, Z_MID, lantern);
            }
        }
        // Pass 3: lighting and pillars.
        for (int i = 0; i < rooms.size(); i++) {
            int[] r = rooms.get(i);
            int top = FLOOR_Y + r[4];
            for (int cx : new int[]{r[0] + 1, r[2] - 1}) {
                for (int cz : new int[]{r[1] + 1, r[3] - 1}) {
                    set(level, cx, top, cz, lantern);
                }
            }
            if (i == rooms.size() - 1) {
                BlockState pillar = wall[3];
                for (int cx : new int[]{r[0] + 4, r[2] - 4}) {
                    for (int cz : new int[]{r[1] + 4, r[3] - 4}) {
                        fill(level, cx, FLOOR_Y + 1, cz, cx, top - 1, cz, pillar);
                    }
                }
            }
        }

        int[] entrance = rooms.get(0);
        BlockPos spawn = new BlockPos((entrance[0] + entrance[2]) / 2, FLOOR_Y + 1, Z_MID);
        BlockPos boss = new BlockPos(bossRoom[0] + 14, FLOOR_Y + 1, Z_MID);
        BlockPos exit = new BlockPos(bossRoom[2] - 3, FLOOR_Y + 1, Z_MID);
        List<int[]> mobRooms = new ArrayList<>();
        for (int i = 1; i < rooms.size() - 1; i++) {
            int[] r = rooms.get(i);
            mobRooms.add(new int[]{r[0] + 1, r[1] + 1, r[2] - 1, r[3] - 1});
        }
        return new Layout(spawn, boss, exit, mobRooms);
    }

    /** Wipes everything in a plot (blocks only; entities are discarded by the caller). */
    public static void clear(ServerLevel level, int plot) {
        AABB b = bounds(plot);
        fill(level, (int) b.minX, (int) b.minY, (int) b.minZ, (int) b.maxX, (int) b.maxY, (int) b.maxZ,
                Blocks.AIR.defaultBlockState());
    }

    private static BlockState[] wallPalette(GateRank rank) {
        return switch (rank) {
            case E -> new BlockState[]{b(Blocks.STONE_BRICKS), b(Blocks.MOSSY_STONE_BRICKS), b(Blocks.CRACKED_STONE_BRICKS), b(Blocks.CHISELED_STONE_BRICKS)};
            case D -> new BlockState[]{b(Blocks.STONE_BRICKS), b(Blocks.CRACKED_STONE_BRICKS), b(Blocks.MOSSY_STONE_BRICKS), b(Blocks.CHISELED_STONE_BRICKS)};
            case C -> new BlockState[]{b(Blocks.DEEPSLATE_BRICKS), b(Blocks.POLISHED_DEEPSLATE), b(Blocks.DEEPSLATE_BRICKS), b(Blocks.POLISHED_DEEPSLATE)};
            case B -> new BlockState[]{b(Blocks.POLISHED_BLACKSTONE_BRICKS), b(Blocks.BLACKSTONE), b(Blocks.POLISHED_BLACKSTONE_BRICKS), b(Blocks.GILDED_BLACKSTONE)};
            case A -> new BlockState[]{b(Blocks.NETHER_BRICKS), b(Blocks.RED_NETHER_BRICKS), b(Blocks.NETHER_BRICKS), b(Blocks.RED_NETHER_BRICKS)};
            case S -> new BlockState[]{b(Blocks.BLACKSTONE), b(Blocks.OBSIDIAN), b(Blocks.BLACKSTONE), b(Blocks.CRYING_OBSIDIAN)};
        };
    }

    private static BlockState b(Block block) {
        return block.defaultBlockState();
    }

    private static void shell(ServerLevel level, RandomSource rng, BlockState[] wall,
                              int x1, int y1, int z1, int x2, int y2, int z2) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    int roll = rng.nextInt(10);
                    BlockState s = roll < 7 ? wall[0] : roll < 9 ? wall[1] : wall[2];
                    level.setBlock(p.set(x, y, z), s, FLAGS);
                }
            }
        }
    }

    private static void fill(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    level.setBlock(p.set(x, y, z), state, FLAGS);
                }
            }
        }
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, FLAGS);
    }
}
