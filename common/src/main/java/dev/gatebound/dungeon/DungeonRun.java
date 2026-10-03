package dev.gatebound.dungeon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One live dungeon instance. Plain mutable data, persisted by DungeonStore. */
public final class DungeonRun {
    public static final Codec<DungeonRun> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("plot").forGetter(r -> r.plot),
            Codec.INT.fieldOf("rank").forGetter(r -> r.rank),
            Level.RESOURCE_KEY_CODEC.fieldOf("gate_dim").forGetter(r -> r.gateDim),
            BlockPos.CODEC.fieldOf("gate_pos").forGetter(r -> r.gatePos),
            BlockPos.CODEC.fieldOf("spawn").forGetter(r -> r.spawn),
            BlockPos.CODEC.fieldOf("boss").forGetter(r -> r.boss),
            BlockPos.CODEC.fieldOf("exit").forGetter(r -> r.exit),
            Codec.INT.fieldOf("total_mobs").forGetter(r -> r.totalMobs),
            Codec.INT.optionalFieldOf("kills", 0).forGetter(r -> r.kills),
            Codec.BOOL.optionalFieldOf("cleared", false).forGetter(r -> r.cleared),
            Codec.LONG.fieldOf("created").forGetter(r -> r.created),
            Codec.LONG.optionalFieldOf("cleared_at", 0L).forGetter(r -> r.clearedAt),
            Codec.LONG.optionalFieldOf("last_inside", 0L).forGetter(r -> r.lastInside),
            UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(r -> r.members)
    ).apply(i, DungeonRun::new));

    public final int plot;
    public final int rank;
    public final ResourceKey<Level> gateDim;
    public final BlockPos gatePos;
    /** Where players arrive. */
    public final BlockPos spawn;
    /** Boss room centre. */
    public final BlockPos boss;
    /** Where the exit portal and chests appear after the clear. */
    public final BlockPos exit;
    public final int totalMobs;
    public int kills;
    public boolean cleared;
    public final long created;
    public long clearedAt;
    public long lastInside;
    public final List<UUID> members;

    public DungeonRun(int plot, int rank, ResourceKey<Level> gateDim, BlockPos gatePos, BlockPos spawn, BlockPos boss,
                      BlockPos exit, int totalMobs, int kills, boolean cleared, long created, long clearedAt,
                      long lastInside, List<UUID> members) {
        this.plot = plot;
        this.rank = rank;
        this.gateDim = gateDim;
        this.gatePos = gatePos;
        this.spawn = spawn;
        this.boss = boss;
        this.exit = exit;
        this.totalMobs = totalMobs;
        this.kills = kills;
        this.cleared = cleared;
        this.created = created;
        this.clearedAt = clearedAt;
        this.lastInside = lastInside;
        this.members = new ArrayList<>(members);
    }

    public GateRank gateRank() {
        return GateRank.byIndex(rank);
    }
}
