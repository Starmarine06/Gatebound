package dev.gatebound.dungeon;

import dev.gatebound.Gatebound;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Builds (or rebuilds, or just repopulates) one floor in slices over many ticks.
 * Phases: PLAN, FORCE chunks, WAIT for them to load, [CLEAR], CELLS, DECOS, FEATURES, MOBS, FINISH.
 */
public final class FloorJob extends WorkQueue.Job {
    private enum Phase {
        PLAN, FORCE, WAIT, CLEAR, CELLS, DECOS, FEATURES, PURGE, MOBS, FINISH
    }

    /** Skip onPlace (no fluid ticks / redstone), update clients, no neighbour shape updates. */
    private static final int BULK = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    public final int floor;
    private final boolean rebuild;
    private final boolean populateOnly;
    private final int popLevel;

    private Phase phase = Phase.PLAN;
    private FloorPlan plan;
    private int total;
    private final List<long[]> chunks = new ArrayList<>();
    private int waitTicks;
    private int forceIdx;
    private int grace;
    private int cursor;
    private Iterator<Map.Entry<Long, BlockState>> decoIt;
    private int mobIndex;
    private int mobTarget;
    private boolean bossSpawned;
    private int spawned;
    private long startedTick = -1;
    private long placed;
    private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
    private RandomSource rng;
    private boolean forced;
    private boolean playersMoved;
    /** Graves lifted out of the floor before a rebuild wiped it; set down again at the arrival point afterwards. */
    private List<dev.gatebound.grave.GraveHandler.Saved> savedGraves = List.of();

    /** Creates a job that builds or populates a floor. */
    public FloorJob(int floor, boolean rebuild, boolean populateOnly, int popLevel) {
        super("floor:" + floor, "Floor " + floor);
        this.floor = floor;
        this.rebuild = rebuild;
        this.populateOnly = populateOnly;
        this.popLevel = Math.max(1, popLevel);
    }

    @Override
    public int percent() {
        return switch (phase) {
            case PLAN, FORCE -> 0;
            case WAIT -> 5;
            case CLEAR -> 8 + (int) (10L * cursor / Math.max(1, plan.size * plan.size));
            case CELLS -> 20 + (int) (50L * cursor / Math.max(1, plan.size * plan.size));
            case DECOS -> 75;
            case FEATURES -> 85;
            case PURGE -> 88;
            case MOBS -> 90 + (mobTarget == 0 ? 0 : 9 * mobIndex / Math.max(1, mobTarget));
            case FINISH -> 99;
        };
    }

    @Override
    public String status() {
        return phase.name();
    }

    /** Number of blocks placed so far. */
    public long placedBlocks() {
        return placed;
    }

    /** Number of mobs spawned so far. */
    public int spawnedMobs() {
        return spawned;
    }

    @Override
    public boolean step(MinecraftServer server, WorkQueue.Budget b) {
        ServerLevel level = server.getLevel(DungeonRegistries.DUNGEON);
        if (level == null) {
            failed = true;
            return true;
        }
        DungeonStore store = DungeonStore.get(server);
        if (startedTick < 0) {
            startedTick = server.getTickCount();
        }
        for (int guard = 0; guard < 16; guard++) {
            boolean advance;
            switch (phase) {
                case PLAN -> {
                    total = store.ensureTower(server);
                    plan = FloorPlan.generate(level.getSeed(), floor, total);
                    rng = RandomSource.create(Spire.mix(level.getSeed(), floor, server.getTickCount()));
                    if (!populateOnly) {
                        DungeonStore.FloorState fs = store.floor(floor);
                        fs.built = false;
                        store.setDirty();
                    }
                    advance = true;
                }
                case FORCE -> advance = forceSome(level);
                case WAIT -> advance = waitForChunks(level);
                case CLEAR -> {
                    if (!playersMoved) {
                        evacuate(server, level);
                        savedGraves = dev.gatebound.grave.GraveHandler.collect(level, Spire.bounds(floor));
                        playersMoved = true;
                    }
                    advance = sweep(level, b, true);
                    if (advance) {
                        discardEntities(level, false);
                        cursor = 0;
                    }
                }
                case CELLS -> advance = sweep(level, b, false);
                case DECOS -> advance = decos(level, b);
                case FEATURES -> advance = features(level);
                case PURGE -> {
                    discardEntities(level, true);
                    refreshChests(level);
                    advance = true;
                }
                case MOBS -> advance = mobs(level);
                case FINISH -> {
                    finish(server, store, level);
                    return true;
                }
                default -> advance = true;
            }
            if (!advance) {
                return false;
            }
            phase = next(phase);
            cursor = 0;
            if (!b.ok()) {
                return false;
            }
        }
        return false;
    }

    /** The phase that follows another. */
    private Phase next(Phase p) {
        return switch (p) {
            case PLAN -> Phase.FORCE;
            case FORCE -> Phase.WAIT;
            case WAIT -> populateOnly ? Phase.PURGE : rebuild ? Phase.CLEAR : Phase.CELLS;
            case CLEAR -> Phase.CELLS;
            case CELLS -> Phase.DECOS;
            case DECOS -> Phase.FEATURES;
            case FEATURES -> Phase.MOBS;
            case PURGE -> Phase.MOBS;
            case MOBS -> Phase.FINISH;
            default -> Phase.FINISH;
        };
    }

    @Override
    public void cleanup(MinecraftServer server) {
        ServerLevel level = server.getLevel(DungeonRegistries.DUNGEON);
        if (level != null && forced) {
            forceChunks(level, false);
        }
    }

    // ------------------------------------------------------------------ chunks

    /** Lists the chunks the floor covers. */
    private void listChunks() {
        if (chunks.isEmpty() && plan != null) {
            for (int cx = plan.ox >> 4; cx <= (plan.ox + plan.size - 1) >> 4; cx++) {
                for (int cz = 0; cz <= (plan.size - 1) >> 4; cz++) {
                    chunks.add(new long[]{cx, cz});
                }
            }
        }
    }

    /** Adds chunk tickets a few per tick (terrain-free chunks are cheap, but 100 at once is still a visible hitch). */
    private boolean forceSome(ServerLevel level) {
        listChunks();
        for (int n = 0; n < 8 && forceIdx < chunks.size(); n++) {
            long[] c = chunks.get(forceIdx++);
            ChunkHold.hold(level, (int) c[0], (int) c[1]);
            forced = true;
        }
        return forceIdx >= chunks.size();
    }

    /** Forces or releases the floor's chunks. */
    private void forceChunks(ServerLevel level, boolean on) {
        listChunks();
        for (long[] c : chunks) {
            if (on) {
                ChunkHold.hold(level, (int) c[0], (int) c[1]);
            } else {
                ChunkHold.release(level, (int) c[0], (int) c[1]);
            }
        }
        forced = on;
    }

    /** Waits until all floor chunks are loaded (with a timeout). */
    private boolean waitForChunks(ServerLevel level) {
        waitTicks++;
        boolean all = true;
        for (long[] c : chunks) {
            if (level.getChunkSource().getChunkNow((int) c[0], (int) c[1]) == null) {
                all = false;
                break;
            }
        }
        if (all || waitTicks > DungeonConfig.buildChunkWaitTicks) {
            if (!all) {
                Gatebound.LOGGER.warn("Floor {}: chunks not fully loaded after {} ticks, building anyway", floor, waitTicks);
            }
            // Entity sections load a little after the chunk itself.
            grace++;
            return grace >= 20 || waitTicks > DungeonConfig.buildChunkWaitTicks + 20;
        }
        return false;
    }

    // ------------------------------------------------------------------ block placement

    /** Places a block (counted). */
    private void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(mp.set(x, y, z), state, BULK);
        placed++;
    }

    /** Clears or fills the floor volume within the work budget. */
    private boolean sweep(ServerLevel level, WorkQueue.Budget b, boolean clear) {
        int n = plan.size * plan.size;
        BlockState air = Blocks.AIR.defaultBlockState();
        while (cursor < n) {
            if (!b.ok()) {
                return false;
            }
            int idx = cursor++;
            int hh = plan.h[idx];
            int wh = plan.wh[idx];
            if (hh <= 0 && wh <= 0) {
                if (clear) {
                    // Solid rock between rooms, so a regenerated floor ends up bedrock-filled even where the chunk was once air.
                    int x0 = plan.ox + idx % plan.size;
                    int z0 = idx / plan.size;
                    int cost0 = 0;
                    for (int y = Spire.FY - 2; y <= Spire.FY + 40; y++) {
                        if (!level.getBlockState(mp.set(x0, y, z0)).is(Blocks.BEDROCK)) {
                            set(level, x0, y, z0, Blocks.BEDROCK.defaultBlockState());
                            cost0++;
                        }
                    }
                    b.use(Math.max(1, cost0));
                }
                continue;
            }
            int i = idx % plan.size;
            int j = idx / plan.size;
            int x = plan.ox + i;
            int cost = 0;
            if (clear) {
                int top = (hh > 0 ? hh : wh) + 1;
                for (int y = Spire.FY - 2; y <= Spire.FY + top; y++) {
                    if (!level.getBlockState(mp.set(x, y, j)).isAir()) {
                        set(level, x, y, j, air);
                        cost++;
                    }
                }
                b.use(Math.max(1, cost));
            } else if (hh > 0) {
                // The dimension is solid bedrock, so the room is carved out between its floor and ceiling.
                set(level, x, Spire.FY, j, plan.floorState(i, j));
                for (int y = Spire.FY + 1; y <= Spire.FY + hh; y++) {
                    set(level, x, y, j, air);
                }
                set(level, x, Spire.FY + hh + 1, j, plan.ceilingState(i, j));
                b.use(hh + 2);
            } else {
                for (int y = Spire.FY; y <= Spire.FY + wh + 1; y++) {
                    set(level, x, y, j, plan.wallState(x, y, j));
                }
                b.use(wh + 2);
            }
        }
        return true;
    }

    /** Places decorations within the work budget. */
    private boolean decos(ServerLevel level, WorkQueue.Budget b) {
        if (decoIt == null) {
            decoIt = plan.decos.entrySet().iterator();
        }
        while (decoIt.hasNext()) {
            if (!b.ok()) {
                return false;
            }
            Map.Entry<Long, BlockState> e = decoIt.next();
            long k = e.getKey();
            int y = (int) (k >> 32);
            int j = (int) ((k >> 16) & 0xFFFF);
            int i = (int) (k & 0xFFFF);
            set(level, plan.ox + i, y, j, e.getValue());
            b.use(1);
        }
        decoIt = null;
        return true;
    }

    /** Places portals and the other floor features. */
    private boolean features(ServerLevel level) {
        DungeonStore store = DungeonStore.get(level.getServer());
        DungeonStore.FloorState fs = store.floor(floor);
        // Portals hang on walls: the way down on the west wall, the way up on the east wall (away from where you arrive).
        PortalPlacer.place(level, plan.downPortal, GateExitBlock.Kind.DOWN, net.minecraft.core.Direction.WEST);
        if (plan.kind == Spire.Kind.NORMAL || plan.kind == Spire.Kind.REST) {
            PortalPlacer.place(level, plan.upPortal, GateExitBlock.Kind.UP, net.minecraft.core.Direction.EAST);
        }
        if (fs.bossDead && Spire.isGate(floor)) {
            PortalPlacer.place(level, plan.upPortal, GateExitBlock.Kind.HOME, net.minecraft.core.Direction.EAST);
        } else if (fs.bossDead && plan.kind == Spire.Kind.BOSS) {
            PortalPlacer.place(level, plan.upPortal, GateExitBlock.Kind.UP, net.minecraft.core.Direction.EAST);
        }
        placeChests(level);
        placeCrystals(level, fs);
        if (!savedGraves.isEmpty()) {
            dev.gatebound.grave.GraveHandler.restore(level, savedGraves, plan.downArrive);
            savedGraves = List.of();
        }
        return true;
    }

    /** Places the floor's chests. */
    private void placeChests(ServerLevel level) {
        boolean rest = plan.kind == Spire.Kind.REST;
        for (FloorPlan.Chest c : plan.chests) {
            FloorPopulator.placeChest(level, c, floor, total, rng, rest);
        }
    }

    /** Re-places chests and crystals. */
    private void refreshChests(ServerLevel level) {
        placeChests(level);
        placeCrystals(level, DungeonStore.get(level.getServer()).floor(floor));
    }

    /** Places mana crystals (weak once the boss is dead). */
    private void placeCrystals(ServerLevel level, DungeonStore.FloorState fs) {
        net.minecraft.world.level.block.state.BlockState st = DungeonRegistries.MANA_CRYSTAL.get().defaultBlockState()
                .setValue(ManaCrystalBlock.WEAKENED, fs.bossDead);
        for (net.minecraft.core.BlockPos pos : plan.crystals) {
            level.setBlock(pos, st, 3);
        }
        fs.crystals = new ArrayList<>(plan.crystals);
    }

    // ------------------------------------------------------------------ entities

    /** Sends players on the floor home. */
    private void evacuate(MinecraftServer server, ServerLevel level) {
        AABB box = Spire.bounds(floor);
        for (ServerPlayer p : level.getPlayers(pl -> box.contains(pl.getX(), pl.getY(), pl.getZ()))) {
            DungeonManager.sendHome(p);
        }
    }

    /** Removes non-player entities from the floor (optionally only Spire mobs). */
    private void discardEntities(ServerLevel level, boolean onlySpireMobs) {
        AABB box = Spire.bounds(floor);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
            if (e instanceof Player) {
                continue;
            }
            if (onlySpireMobs && !e.entityTags().contains(FloorPopulator.TAG_MOB)) {
                continue;
            }
            e.discard();
        }
    }

    /** Gate dungeons keep a 10 block radius around every arrival point (and the start) free of monsters. */
    private boolean nearArrival(BlockPos spot) {
        if (!Spire.isGate(floor)) {
            return false;
        }
        double r2 = 10.0 * 10.0;
        for (BlockPos p : plan.rifts) {
            if (p.distSqr(spot) < r2) {
                return true;
            }
        }
        return plan.downArrive != null && plan.downArrive.distSqr(spot) < r2;
    }

    /** Spawns the floor's mobs gradually; true when done. */
    private boolean mobs(ServerLevel level) {
        if (plan.kind == Spire.Kind.REST) {
            return true;
        }
        if (mobIndex == 0 && mobTarget == 0) {
            mobTarget = Spire.isGate(floor) && !plan.mobSpots.isEmpty()
                    ? FloorPopulator.gateMobCount(Spire.rank(floor, total), rng)
                    : FloorPopulator.mobCount(plan);
        }
        int budget = Math.max(1, DungeonConfig.mobsPerTick) * (Spire.isGate(floor) ? 3 : 1);
        while (budget-- > 0 && mobIndex < mobTarget) {
            BlockPos spot = plan.mobSpots.get(mobIndex % plan.mobSpots.size());
            if (mobIndex >= plan.mobSpots.size()) {
                // More monsters than spots: packs, scattered a few blocks around each spot.
                BlockPos near = spot.offset(rng.nextInt(7) - 3, 0, rng.nextInt(7) - 3);
                if (level.getBlockState(near).isAir() && level.getBlockState(near.below()).isSolid()) {
                    spot = near;
                }
            }
            Mob m = nearArrival(spot) ? null : FloorPopulator.spawnRegular(level, spot, plan, popLevel, rng, 1.0);
            if (m != null) {
                spawned++;
            }
            mobIndex++;
        }
        if (mobIndex < mobTarget) {
            return false;
        }
        if (!bossSpawned && plan.boss != null) {
            bossSpawned = true;
            Mob boss = FloorPopulator.spawnBoss(level, plan, popLevel);
            if (boss != null) {
                spawned++;
            }
            int escorts = 4 + Spire.scaleFloor(floor, total) / 10;
            for (int k = 0; k < escorts; k++) {
                BlockPos spot = plan.boss.offset(rng.nextInt(13) - 6, 0, rng.nextInt(13) - 6);
                if (level.getBlockState(spot).isAir() && level.getBlockState(spot.below()).isSolid()) {
                    Mob m = FloorPopulator.spawnRegular(level, spot, plan, popLevel, rng, 1.3);
                    if (m != null) {
                        spawned++;
                    }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ finish

    /** Finishes the job: marks the floor built and releases chunks. */
    private void finish(MinecraftServer server, DungeonStore store, ServerLevel level) {
        long now = server.overworld().getGameTime();
        DungeonStore.FloorState fs = store.floor(floor);
        if (!populateOnly) {
            dev.gatebound.echo.EchoManager.forgetExploration();
            fs.built = true;
            fs.downArrive = plan.downArrive;
            fs.upArrive = plan.upArrive;
            fs.upPortal = plan.upPortal;
            fs.rifts = new ArrayList<>(plan.rifts);
            if (rebuild) {
                fs.bossDead = false;
            }
        }
        fs.populatedAt = now;
        fs.popLevel = popLevel;
        fs.totalMobs = spawned;
        fs.kills = 0;
        fs.cleared = plan.kind == Spire.Kind.REST;
        fs.clearedAt = fs.cleared ? now : 0L;
        store.setDirty();
        Gatebound.LOGGER.info("Floor {} {} ({}, {}): {} blocks, {} mobs, {} ticks", floor,
                populateOnly ? "repopulated" : rebuild ? "rebuilt" : "built", plan.theme.label(), plan.kind,
                placed, spawned, server.getTickCount() - startedTick);
    }
}
