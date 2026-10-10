package dev.gatebound.dungeon;

import dev.gatebound.compat.Compat;
import dev.gatebound.Gatebound;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the overworld landmark: a tiered, deepslate-and-stone tower with a glowing, beaconed crown and a big gate at
 * the base. Placed layer by layer under the shared work budget so it never freezes the server.
 */
public final class TowerJob extends WorkQueue.Job {
    private enum Phase {
        SITE, WAIT, BUILD, GATE
    }

    private static final int BULK = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    private static final int HALL_H = 15;
    private static final int FOUNDATION = 24;

    private final int[] requestedXZ;
    private Phase phase = Phase.SITE;
    private int radius;
    private int height;
    private int cx;
    private int cz;
    private int y0;
    private int half;
    private int tierH;
    private final List<int[]> chunks = new ArrayList<>();
    private boolean forced;
    private int waitTicks;
    private int forcedIdx;
    private int grace;
    private int yCursor;
    private int cellCursor;
    private int minRel;
    private int maxRel;
    private long placed;
    private int[][] pinnacles;
    private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** @param requestedXZ {x, z} to build at, or null to use the stored site / world spawn. */
    public TowerJob(int[] requestedXZ) {
        super("tower", "The Spire");
        this.requestedXZ = requestedXZ;
    }

    @Override
    public int percent() {
        if (phase == Phase.SITE || phase == Phase.WAIT) {
            return 0;
        }
        if (phase == Phase.GATE) {
            return 99;
        }
        return (int) (98L * (yCursor - minRel) / Math.max(1, maxRel - minRel));
    }

    @Override
    public String status() {
        return phase.name();
    }

    public long placedBlocks() {
        return placed;
    }

    @Override
    public boolean step(MinecraftServer server, WorkQueue.Budget b) {
        ServerLevel level = server.overworld();
        DungeonStore store = DungeonStore.get(server);
        for (int guard = 0; guard < 8; guard++) {
            switch (phase) {
                case SITE -> {
                    site(server, level, store);
                    phase = Phase.WAIT;
                }
                case WAIT -> {
                    if (!waitForChunks(level)) {
                        return false;
                    }
                    startBuild(level, store);
                    phase = Phase.BUILD;
                }
                case BUILD -> {
                    if (!build(level, b)) {
                        return false;
                    }
                    phase = Phase.GATE;
                }
                case GATE -> {
                    finish(server, level, store);
                    return true;
                }
            }
            if (!b.ok()) {
                return false;
            }
        }
        return false;
    }

    @Override
    public void cleanup(MinecraftServer server) {
        if (forced) {
            ServerLevel level = server.overworld();
            for (int[] c : chunks) {
                ChunkHold.release(level, c[0], c[1]);
            }
            forced = false;
        }
    }

    // ------------------------------------------------------------------ phases

    private void site(MinecraftServer server, ServerLevel level, DungeonStore store) {
        radius = Math.max(10, Math.min(48, DungeonConfig.towerRadius));
        height = Math.max(40, Math.min(250, DungeonConfig.towerHeight));
        if (requestedXZ != null) {
            cx = requestedXZ[0];
            cz = requestedXZ[1];
            y0 = 0;
        } else if (store.towerPlaced) {
            cx = store.towerPos.getX();
            cz = store.towerPos.getZ();
            y0 = store.towerPos.getY();
        } else {
            BlockPos spawn = level.getRespawnData().pos();
            cx = spawn.getX() + DungeonConfig.towerOffsetX;
            cz = spawn.getZ() + DungeonConfig.towerOffsetZ;
            y0 = 0;
        }
        half = radius + 7;
        store.towerPlaced = true;
        store.towerBuilt = false;
        store.towerPos = new BlockPos(cx, y0, cz);
        store.setDirty();
        for (int x = (cx - half - 2) >> 4; x <= (cx + half + 2) >> 4; x++) {
            for (int z = (cz - half - 2) >> 4; z <= (cz + half + 2) >> 4; z++) {
                chunks.add(new int[]{x, z});
            }
        }
    }

    private boolean waitForChunks(ServerLevel level) {
        waitTicks++;
        // Tickets are added a few chunks per tick so terrain generation does not hit the server thread all at once.
        for (int n = 0; n < 3 && forcedIdx < chunks.size(); n++) {
            int[] c = chunks.get(forcedIdx++);
            ChunkHold.hold(level, c[0], c[1]);
            forced = true;
        }
        if (forcedIdx < chunks.size()) {
            return false;
        }
        boolean all = true;
        for (int[] c : chunks) {
            if (level.getChunkSource().getChunkNow(c[0], c[1]) == null) {
                all = false;
                break;
            }
        }
        if (all || waitTicks > DungeonConfig.buildChunkWaitTicks) {
            grace++;
            return grace >= 10 || waitTicks > DungeonConfig.buildChunkWaitTicks + 10;
        }
        return false;
    }

    private void startBuild(ServerLevel level, DungeonStore store) {
        if (y0 == 0) {
            long sum = sample(level, cx, cz);
            int n = 1;
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * k / 4;
                sum += sample(level, cx + (int) Math.round(Math.cos(a) * radius * 0.6),
                        cz + (int) Math.round(Math.sin(a) * radius * 0.6));
                n++;
            }
            y0 = (int) Math.max(level.getSeaLevel() + 1, Math.round((double) sum / n));
            store.towerPos = new BlockPos(cx, y0, cz);
            store.setDirty();
        }
        int maxY = level.getMinY() + level.getHeight() - 1;
        height = Math.min(height, maxY - 30 - y0);
        if (height < 40) {
            throw new IllegalStateException("not enough build height for the tower at y=" + y0);
        }
        tierH = height / 4;
        minRel = -FOUNDATION;
        maxRel = height + 9;
        yCursor = minRel;
        cellCursor = 0;
        int rTop = radius - 9;
        pinnacles = new int[8][];
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * k / 4;
            pinnacles[k] = new int[]{(int) Math.round(Math.cos(a) * (rTop - 1)), (int) Math.round(Math.sin(a) * (rTop - 1))};
        }
        Gatebound.LOGGER.info("Building the tower at {} {} {} radius {} height {}", cx, y0, cz, radius, height);
    }

    private int sample(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private boolean build(ServerLevel level, WorkQueue.Budget b) {
        int side = half * 2 + 1;
        int cells = side * side;
        while (yCursor <= maxRel) {
            while (cellCursor < cells) {
                if (!b.ok()) {
                    return false;
                }
                int dx = cellCursor % side - half;
                int dz = cellCursor / side - half;
                cellCursor++;
                BlockState want = blockAt(dx, yCursor, dz);
                if (want == null) {
                    continue;
                }
                mp.set(cx + dx, y0 + yCursor, cz + dz);
                BlockState cur = level.getBlockState(mp);
                if (want == AIR) {
                    if (cur.isAir()) {
                        continue;
                    }
                } else if (yCursor < -1) {
                    if (!(cur.isAir() || !cur.getFluidState().isEmpty() || cur.canBeReplaced())) {
                        continue;
                    }
                } else if (cur == want) {
                    continue;
                }
                level.setBlock(mp, want, BULK);
                placed++;
                b.use(1);
            }
            cellCursor = 0;
            yCursor++;
        }
        return true;
    }

    private void finish(MinecraftServer server, ServerLevel level, DungeonStore store) {
        // The entrance arch stays open; the tower's gate stands on the dais in the middle of the hall (see TowerHall).
        store.towerBuilt = true;
        store.towerPos = new BlockPos(cx, y0, cz);
        store.towerRadius = radius;
        store.towerTop = y0 + height + 10;
        store.towerExit = new BlockPos(cx, y0, cz + radius + 3);
        store.setDirty();
        TowerHall.ensure(server, store);
        Gatebound.LOGGER.info("The tower stands at {} {} {} ({} blocks placed)", cx, y0, cz, placed);
    }

    // ------------------------------------------------------------------ shape

    private static boolean inOpening(int dx, int y) {
        int ax = Math.abs(dx);
        return (ax <= 4 && y <= 9) || (ax <= 3 && y == 10) || (ax <= 2 && y == 11);
    }

    private static BlockState b(Block block) {
        return block.defaultBlockState();
    }

    private static int hash(int x, int y, int z) {
        return (int) (Spire.mix(x, y, z) >>> 40) & 0xFFFF;
    }

    /** Desired block at a position relative to the tower centre and ground level, or null to leave it alone. */
    private BlockState blockAt(int dx, int y, int dz) {
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        int r = radius;
        if (y < -1) {
            if (d <= r + 2.5) {
                return hash(dx, y, dz) % 100 < 70 ? b(Blocks.STONE_BRICKS) : b(Blocks.COBBLESTONE);
            }
            return null;
        }
        if (y == -1) {
            if (d <= r - 3) {
                return ((int) d % 6 == 5) ? b(Blocks.CHISELED_STONE_BRICKS) : b(Blocks.POLISHED_DEEPSLATE);
            }
            if (d <= r + 2.5) {
                return b(Blocks.STONE_BRICKS);
            }
            if (d <= r + 7) {
                return ((dx + dz) & 1) == 0 ? b(Blocks.POLISHED_ANDESITE) : b(Blocks.STONE_BRICKS);
            }
            return null;
        }
        if (y >= height) {
            return crown(dx, y - height, dz, d);
        }
        int tier = Math.min(3, y / tierH);
        int rOut = r - 3 * tier;
        int rIn = rOut - 3;

        // Entrance arch cuts through the wall and the plaza in front of it.
        if (dz > 0 && Math.abs(dx) <= 4 && inOpening(dx, y) && d > r - 4.5 && d <= r + 7) {
            return AIR;
        }
        if (d > rIn && d <= rOut) {
            double ang = Math.atan2(dz, dx);
            double step = Math.PI / 8;
            double k = ang / step;
            double frac = k - Math.floor(k); // 0..1 within a sector; buttress at 0, window at 0.5
            if (y > HALL_H + 2 && Math.abs(frac - 0.5) < 0.9 / (rOut * step) &&(y % 14) >= 4 && (y % 14) <= 9) {
                return b(Blocks.GLOWSTONE);
            }
            if (y % 14 == 13) {
                return tier >= 2 ? b(Blocks.GILDED_BLACKSTONE) : b(Blocks.CHISELED_STONE_BRICKS);
            }
            return wall(tier, dx, y, dz);
        }
        // Ring deck at the top of each tier carries the next, narrower tier.
        if (tier < 3 && y == (tier + 1) * tierH - 1 && d > rOut - 6 && d <= rOut) {
            return b(Blocks.POLISHED_DEEPSLATE);
        }
        // Buttresses on the outside of the wall.
        if (d > rOut && d <= rOut + 1.6 && y >= 0 && y < (tier + 1) * tierH - 1) {
            double ang = Math.atan2(dz, dx);
            double k = ang / (Math.PI / 8);
            double near = Math.abs(k - Math.rint(k)) * (Math.PI / 8) * rOut;
            if (near < 0.9) {
                return b(tier >= 2 ? Blocks.DEEPSLATE_BRICKS : Blocks.STONE_BRICKS);
            }
        }
        if (d <= rIn) {
            if (y < HALL_H) {
                return AIR;
            }
            if (y == HALL_H) {
                return (Math.floorMod(dx, 6) == 3 && Math.floorMod(dz, 6) == 3) ? b(Blocks.SEA_LANTERN) : b(Blocks.POLISHED_DEEPSLATE);
            }
            return y <= 30 ? AIR : null;
        }
        if (d <= r + 6 && y < 30) {
            return AIR;
        }
        return null;
    }

    private BlockState wall(int tier, int dx, int y, int dz) {
        int h = hash(dx, y, dz) % 100;
        return switch (tier) {
            case 0 -> h < 70 ? b(Blocks.STONE_BRICKS) : h < 85 ? b(Blocks.MOSSY_STONE_BRICKS) : b(Blocks.CRACKED_STONE_BRICKS);
            case 1 -> h < 75 ? b(Blocks.DEEPSLATE_BRICKS) : b(Blocks.CRACKED_DEEPSLATE_BRICKS);
            case 2 -> h < 70 ? b(Blocks.DEEPSLATE_TILES) : b(Blocks.POLISHED_DEEPSLATE);
            default -> h < 70 ? b(Blocks.POLISHED_BLACKSTONE_BRICKS) : h < 90 ? b(Blocks.BLACKSTONE) : b(Blocks.GILDED_BLACKSTONE);
        };
    }

    private BlockState crown(int dx, int yy, int dz, double d) {
        int rTop = radius - 9;
        if (yy == 0) {
            if (d <= rTop) {
                return (Math.floorMod(dx + dz, 5) == 0 && d < rTop - 2) ? b(Blocks.SEA_LANTERN) : b(Blocks.POLISHED_BLACKSTONE);
            }
            return null;
        }
        // Beacon pedestal: iron base, beacon, tinted glass for a purple beam.
        if (yy == 1 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            return b(Blocks.IRON_BLOCK);
        }
        if (dx == 0 && dz == 0) {
            if (yy == 2) {
                return b(Blocks.BEACON);
            }
            if (yy == 3) {
                return b(Compat.PURPLE_STAINED_GLASS);
            }
        }
        if (yy == 1 && d >= 2.0 && d <= 3.0) {
            return b(Blocks.SEA_LANTERN);
        }
        // Pinnacles and battlements.
        for (int[] p : pinnacles) {
            if (dx == p[0] && dz == p[1]) {
                if (yy >= 1 && yy <= 7) {
                    return b(Blocks.DEEPSLATE_BRICKS);
                }
                if (yy == 8) {
                    return b(Blocks.SEA_LANTERN);
                }
            }
        }
        if (yy <= 2 && d > rTop - 2 && d <= rTop) {
            if (yy == 1) {
                return b(Blocks.POLISHED_BLACKSTONE_BRICKS);
            }
            double ang = Math.atan2(dz, dx);
            if (((int) Math.floor(ang * rTop / 2.0) & 1) == 0) {
                return b(Blocks.POLISHED_BLACKSTONE_BRICKS);
            }
        }
        return null;
    }

    /** Box where survival players may not edit blocks, or null if no tower. */
    public static net.minecraft.world.phys.AABB protectedBox(DungeonStore store) {
        if (!store.towerBuilt) {
            return null;
        }
        int r = store.towerRadius + 8;
        BlockPos c = store.towerPos;
        return new net.minecraft.world.phys.AABB(c.getX() - r, c.getY() - FOUNDATION, c.getZ() - r,
                c.getX() + r + 1, store.towerTop + 1, c.getZ() + r + 1);
    }

    public static boolean isOverworld(Level level) {
        return level.dimension().equals(Level.OVERWORLD);
    }
}
