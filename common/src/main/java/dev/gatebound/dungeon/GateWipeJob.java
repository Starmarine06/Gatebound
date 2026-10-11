package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts a finished gate dungeon floor back to solid bedrock, the way the dimension started, so old gates leave nothing
 * behind. Runs under the shared work budget; the floor is rebuilt from scratch if its slot is ever used again.
 */
public final class GateWipeJob extends WorkQueue.Job {
    private static final int BULK = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    private final int floor;
    private final List<long[]> chunks = new ArrayList<>();
    private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
    private int held;
    private int cursor;
    private boolean forced;

    /** Creates a job that wipes one gate floor. */
    public GateWipeJob(int floor) {
        super("wipe:" + floor, "Gate " + floor);
        this.floor = floor;
    }

    @Override
    public boolean step(MinecraftServer server, WorkQueue.Budget b) {
        ServerLevel level = server.getLevel(DungeonRegistries.DUNGEON);
        if (level == null) {
            failed = true;
            return true;
        }
        AABB box = Spire.bounds(floor);
        int x0 = (int) box.minX;
        int z0 = (int) box.minZ;
        int w = (int) (box.maxX - box.minX);
        int d = (int) (box.maxZ - box.minZ);
        if (chunks.isEmpty()) {
            for (int cx = x0 >> 4; cx <= (x0 + w) >> 4; cx++) {
                for (int cz = z0 >> 4; cz <= (z0 + d) >> 4; cz++) {
                    chunks.add(new long[]{cx, cz});
                }
            }
        }
        while (held < chunks.size()) {
            ChunkHold.hold(level, (int) chunks.get(held)[0], (int) chunks.get(held)[1]);
            held++;
            forced = true;
            if (held % 8 == 0) {
                return false;
            }
        }
        for (long[] c : chunks) {
            if (level.getChunkSource().getChunkNow((int) c[0], (int) c[1]) == null) {
                return false; // wait for the chunks to load
            }
        }
        for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
            if (!(e instanceof Player)) {
                e.discard();
            }
        }
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        int n = w * d;
        while (cursor < n) {
            if (!b.ok()) {
                return false;
            }
            int x = x0 + cursor % w;
            int z = z0 + cursor / w;
            cursor++;
            int cost = 1;
            for (int y = (int) box.minY; y <= (int) box.maxY; y++) {
                if (!level.getBlockState(mp.set(x, y, z)).is(Blocks.BEDROCK)) {
                    level.setBlock(mp, bedrock, BULK);
                    cost++;
                }
            }
            b.use(cost);
        }
        return true;
    }

    @Override
    public int percent() {
        AABB box = Spire.bounds(floor);
        return (int) (100L * cursor / Math.max(1, (long) ((box.maxX - box.minX) * (box.maxZ - box.minZ))));
    }

    @Override
    public void cleanup(MinecraftServer server) {
        ServerLevel level = server.getLevel(DungeonRegistries.DUNGEON);
        if (level != null && forced) {
            for (long[] c : chunks) {
                ChunkHold.release(level, (int) c[0], (int) c[1]);
            }
        }
    }
}
