package dev.gatebound.dungeon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gatebound.Gatebound;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-level save of active dungeon runs, player return positions, dirty plots and natural gates. */
public final class DungeonStore extends SavedData {
    /** Where a player stood before entering a gate. */
    public record Return(UUID player, ResourceKey<Level> dim, double x, double y, double z, float yaw) {
        public static final Codec<Return> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Return::player),
                Level.RESOURCE_KEY_CODEC.fieldOf("dim").forGetter(Return::dim),
                Codec.DOUBLE.fieldOf("x").forGetter(Return::x),
                Codec.DOUBLE.fieldOf("y").forGetter(Return::y),
                Codec.DOUBLE.fieldOf("z").forGetter(Return::z),
                Codec.FLOAT.fieldOf("yaw").forGetter(Return::yaw)
        ).apply(i, Return::new));
    }

    /** A naturally spawned gate we are tracking for the world cap. */
    public record GateRef(ResourceKey<Level> dim, BlockPos pos) {
        public static final Codec<GateRef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dim").forGetter(GateRef::dim),
                BlockPos.CODEC.fieldOf("pos").forGetter(GateRef::pos)
        ).apply(i, GateRef::new));
    }

    private static final Codec<DungeonStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            DungeonRun.CODEC.listOf().optionalFieldOf("runs", List.of()).forGetter(s -> new ArrayList<>(s.runs.values())),
            Return.CODEC.listOf().optionalFieldOf("returns", List.of()).forGetter(s -> new ArrayList<>(s.returns.values())),
            Codec.INT.listOf().optionalFieldOf("dirty_plots", List.of()).forGetter(s -> new ArrayList<>(s.dirtyPlots)),
            GateRef.CODEC.listOf().optionalFieldOf("natural_gates", List.of()).forGetter(s -> s.naturalGates)
    ).apply(i, DungeonStore::new));

    public static final SavedDataType<DungeonStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "dungeon_runs"),
            DungeonStore::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    public final Map<Integer, DungeonRun> runs = new HashMap<>();
    public final Map<UUID, Return> returns = new HashMap<>();
    public final Set<Integer> dirtyPlots = new HashSet<>();
    public final List<GateRef> naturalGates = new ArrayList<>();

    public DungeonStore() {
    }

    private DungeonStore(List<DungeonRun> runs, List<Return> returns, List<Integer> dirty, List<GateRef> gates) {
        for (DungeonRun r : runs) {
            this.runs.put(r.plot, r);
        }
        for (Return r : returns) {
            this.returns.put(r.player(), r);
        }
        this.dirtyPlots.addAll(dirty);
        this.naturalGates.addAll(gates);
    }

    public static DungeonStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public DungeonRun runByGate(ResourceKey<Level> dim, BlockPos pos) {
        for (DungeonRun r : runs.values()) {
            if (r.gateDim.equals(dim) && r.gatePos.equals(pos)) {
                return r;
            }
        }
        return null;
    }

    public int allocatePlot() {
        int i = 0;
        while (runs.containsKey(i) || dirtyPlots.contains(i)) {
            i++;
        }
        return i;
    }
}
