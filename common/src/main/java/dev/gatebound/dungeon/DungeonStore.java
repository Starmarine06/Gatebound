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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-level save of the Spire: floor count, generated floors and their state, the overworld tower, gates,
 * player return points and per-player floor progress. Everything that must survive a restart lives here.
 */
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

    /** A gate in a world: where it leads, whether it spawned naturally, its age and how often it was used. */
    public static final class GateRef {
        /** One party's (or one solo player's) own copy of a gate dungeon. */
        public record Instance(UUID key, int floor) {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("key").forGetter(Instance::key),
                    Codec.INT.fieldOf("floor").forGetter(Instance::floor)
            ).apply(i, Instance::new));
        }

        public static final Codec<GateRef> CODEC = RecordCodecBuilder.create(i -> i.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dim").forGetter(g -> g.dim),
                BlockPos.CODEC.fieldOf("pos").forGetter(g -> g.pos),
                Codec.INT.optionalFieldOf("floor", 0).forGetter(g -> g.floor),
                Codec.BOOL.optionalFieldOf("natural", true).forGetter(g -> g.natural),
                Codec.LONG.optionalFieldOf("created", 0L).forGetter(g -> g.created),
                Codec.INT.optionalFieldOf("uses", 0).forGetter(g -> g.uses),
                Codec.BOOL.optionalFieldOf("cleared", false).forGetter(g -> g.cleared),
                Instance.CODEC.listOf().optionalFieldOf("instances", List.of()).forGetter(g -> {
                    List<Instance> out = new ArrayList<>();
                    g.instances.forEach((k, f) -> out.add(new Instance(k, f)));
                    return out;
                })
        ).apply(i, (dim, pos, floor, natural, created, uses, cleared, insts) -> {
            GateRef g = new GateRef(dim, pos, floor, natural, created, uses, cleared);
            for (Instance in : insts) {
                g.instances.put(in.key(), in.floor());
            }
            return g;
        }));

        /** Owner key (party id, or the player's UUID when alone) to the floor index of that owner's copy of this dungeon. */
        public final Map<UUID, Integer> instances = new HashMap<>();

        public final ResourceKey<Level> dim;
        public final BlockPos pos;
        public int floor;
        public final boolean natural;
        public final long created;
        public int uses;
        /** True once the gate dungeon's guardian has fallen: the gate closes and stops leaking monsters. */
        public boolean cleared;

        public GateRef(ResourceKey<Level> dim, BlockPos pos, int floor, boolean natural, long created, int uses) {
            this(dim, pos, floor, natural, created, uses, false);
        }

        public GateRef(ResourceKey<Level> dim, BlockPos pos, int floor, boolean natural, long created, int uses, boolean cleared) {
            this.cleared = cleared;
            this.dim = dim;
            this.pos = pos;
            this.floor = floor;
            this.natural = natural;
            this.created = created;
            this.uses = uses;
        }
    }

    /** Persistent state of one generated floor. */
    public static final class FloorState {
        public static final Codec<FloorState> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("floor").forGetter(f -> f.floor),
                Codec.BOOL.optionalFieldOf("built", false).forGetter(f -> f.built),
                Codec.LONG.optionalFieldOf("populated_at", 0L).forGetter(f -> f.populatedAt),
                Codec.INT.optionalFieldOf("total_mobs", 0).forGetter(f -> f.totalMobs),
                Codec.INT.optionalFieldOf("kills", 0).forGetter(f -> f.kills),
                Codec.BOOL.optionalFieldOf("cleared", false).forGetter(f -> f.cleared),
                Codec.LONG.optionalFieldOf("cleared_at", 0L).forGetter(f -> f.clearedAt),
                Codec.BOOL.optionalFieldOf("boss_dead", false).forGetter(f -> f.bossDead),
                Codec.INT.optionalFieldOf("pop_level", 1).forGetter(f -> f.popLevel),
                BlockPos.CODEC.optionalFieldOf("down_arrive", BlockPos.ZERO).forGetter(f -> f.downArrive),
                BlockPos.CODEC.optionalFieldOf("up_arrive", BlockPos.ZERO).forGetter(f -> f.upArrive),
                BlockPos.CODEC.optionalFieldOf("up_portal", BlockPos.ZERO).forGetter(f -> f.upPortal),
                BlockPos.CODEC.listOf().optionalFieldOf("rifts", List.of()).forGetter(f -> f.rifts),
                Codec.LONG.optionalFieldOf("collapse_at", 0L).forGetter(f -> f.collapseAt),
                BlockPos.CODEC.listOf().optionalFieldOf("crystals", List.of()).forGetter(f -> f.crystals)
        ).apply(i, FloorState::new));

        public final int floor;
        /** True once the whole layout has been placed (a restart mid-build leaves this false so it is redone). */
        public boolean built;
        public long populatedAt;
        public int totalMobs;
        public int kills;
        public boolean cleared;
        public long clearedAt;
        public boolean bossDead;
        public int popLevel;
        public BlockPos downArrive;
        public BlockPos upArrive;
        public BlockPos upPortal;
        public List<BlockPos> rifts;
        /** Gate dungeons: game time at which the dungeon collapses after its guardian fell (0 = no countdown running). */
        public long collapseAt;
        /** Mana crystals placed on this floor (turned weakened when the guardian falls). */
        public List<BlockPos> crystals;

        public FloorState(int floor, boolean built, long populatedAt, int totalMobs, int kills, boolean cleared,
                          long clearedAt, boolean bossDead, int popLevel, BlockPos downArrive, BlockPos upArrive,
                          BlockPos upPortal, List<BlockPos> rifts, long collapseAt, List<BlockPos> crystals) {
            this.collapseAt = collapseAt;
            this.crystals = new ArrayList<>(crystals);
            this.floor = floor;
            this.built = built;
            this.populatedAt = populatedAt;
            this.totalMobs = totalMobs;
            this.kills = kills;
            this.cleared = cleared;
            this.clearedAt = clearedAt;
            this.bossDead = bossDead;
            this.popLevel = popLevel;
            this.downArrive = downArrive;
            this.upArrive = upArrive;
            this.upPortal = upPortal;
            this.rifts = new ArrayList<>(rifts);
        }

        public FloorState(int floor) {
            this(floor, false, 0L, 0, 0, false, 0L, false, 1, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, List.of(), 0L, List.of());
        }
    }

    /** Per-player floor progress. */
    public static final class Progress {
        public static final Codec<Progress> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(p -> p.player),
                Codec.INT.optionalFieldOf("highest", 0).forGetter(p -> p.highest),
                Codec.INT.optionalFieldOf("last_rest", 0).forGetter(p -> p.lastRest),
                Codec.INT.listOf().optionalFieldOf("bosses", List.of()).forGetter(p -> p.bosses)
        ).apply(i, Progress::new));

        public final UUID player;
        public int highest;
        public int lastRest;
        /** Boss floors whose first-clear reward this player has already received. */
        public final List<Integer> bosses;

        public Progress(UUID player, int highest, int lastRest, List<Integer> bosses) {
            this.player = player;
            this.highest = highest;
            this.lastRest = lastRest;
            this.bosses = new ArrayList<>(bosses);
        }
    }

    private static final Codec<DungeonStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("total_floors", 0).forGetter(s -> s.totalFloors),
            Codec.BOOL.optionalFieldOf("tower_built", false).forGetter(s -> s.towerBuilt),
            BlockPos.CODEC.optionalFieldOf("tower_pos", BlockPos.ZERO).forGetter(s -> s.towerPos),
            Codec.BOOL.optionalFieldOf("tower_placed", false).forGetter(s -> s.towerPlaced),
            BlockPos.CODEC.optionalFieldOf("entrance_min", BlockPos.ZERO).forGetter(s -> s.entranceMin),
            BlockPos.CODEC.optionalFieldOf("entrance_max", BlockPos.ZERO).forGetter(s -> s.entranceMax),
            BlockPos.CODEC.optionalFieldOf("tower_exit", BlockPos.ZERO).forGetter(s -> s.towerExit),
            Codec.INT.optionalFieldOf("tower_radius", 0).forGetter(s -> s.towerRadius),
            Codec.INT.optionalFieldOf("tower_top", 0).forGetter(s -> s.towerTop),
            FloorState.CODEC.listOf().optionalFieldOf("floors", List.of()).forGetter(s -> new ArrayList<>(s.floors.values())),
            Return.CODEC.listOf().optionalFieldOf("returns", List.of()).forGetter(s -> new ArrayList<>(s.returns.values())),
            GateRef.CODEC.listOf().optionalFieldOf("gates", List.of()).forGetter(s -> s.gates),
            Progress.CODEC.listOf().optionalFieldOf("progress", List.of()).forGetter(s -> new ArrayList<>(s.progress.values())),
            Codec.INT.optionalFieldOf("layout", 0).forGetter(s -> s.layout)
    ).apply(i, DungeonStore::new));

    /** Bump when FloorPlan generates different floors for the same seed: every saved floor is then rebuilt on next use. */
    public static final int LAYOUT_VERSION = 2;
    public int layout = LAYOUT_VERSION;

    public static final SavedDataType<DungeonStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "spire"),
            DungeonStore::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    /** Number of floors; 0 until the tower is first needed, then fixed for the life of the world. */
    public int totalFloors;
    public boolean towerBuilt;
    public BlockPos towerPos = BlockPos.ZERO;
    /** True once a tower site was chosen (so a half-built tower is resumed at the same place). */
    public boolean towerPlaced;
    public BlockPos entranceMin = BlockPos.ZERO;
    public BlockPos entranceMax = BlockPos.ZERO;
    /** Where players are put when they leave the tower: in front of the entrance gate. */
    public BlockPos towerExit = BlockPos.ZERO;
    public int towerRadius;
    public int towerTop;
    public final Map<Integer, FloorState> floors = new HashMap<>();
    public final Map<UUID, Return> returns = new HashMap<>();
    public final List<GateRef> gates = new ArrayList<>();
    public final Map<UUID, Progress> progress = new HashMap<>();

    /** Creates an empty store. */
    public DungeonStore() {
    }

    /** Creates a store from saved data. */
    private DungeonStore(int total, boolean built, BlockPos towerPos, boolean placed, BlockPos entMin, BlockPos entMax,
                         BlockPos exit, int radius, int top, List<FloorState> floors, List<Return> returns,
                         List<GateRef> gates, List<Progress> progress, int layout) {
        this.layout = layout;
        this.totalFloors = total;
        this.towerBuilt = built;
        this.towerPos = towerPos;
        this.towerPlaced = placed;
        this.entranceMin = entMin;
        this.entranceMax = entMax;
        this.towerExit = exit;
        this.towerRadius = radius;
        this.towerTop = top;
        for (FloorState f : floors) {
            this.floors.put(f.floor, f);
        }
        for (Return r : returns) {
            this.returns.put(r.player(), r);
        }
        this.gates.addAll(gates);
        for (Progress p : progress) {
            this.progress.put(p.player, p);
        }
    }

    /** The server's dungeon store (stale floors are marked for rebuild). */
    public static DungeonStore get(MinecraftServer server) {
        DungeonStore s = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        if (s.layout != LAYOUT_VERSION) {
            // Saved floors were built from an older layout: forget them so they are rebuilt (and cleared) when next entered.
            for (FloorState f : s.floors.values()) {
                f.built = false;
                f.populatedAt = 0;
                f.cleared = false;
                f.bossDead = false;
                f.kills = 0;
                f.totalMobs = 0;
            }
            s.layout = LAYOUT_VERSION;
            s.setDirty();
            Gatebound.LOGGER.info("Dungeon layout changed: all saved floors will be rebuilt when entered");
        }
        return s;
    }

    /** Decides the floor count on first use, deterministically from the world seed, and saves it. */
    public int ensureTower(MinecraftServer server) {
        if (totalFloors <= 0) {
            int min = Math.max(6, DungeonConfig.towerMinFloors);
            int max = Math.max(min, DungeonConfig.towerMaxFloors);
            java.util.Random r = new java.util.Random(Spire.mix(server.overworld().getSeed(), 0x5917EL, 7L));
            totalFloors = min + r.nextInt(max - min + 1);
            setDirty();
            Gatebound.LOGGER.info("The Spire has {} floors", totalFloors);
        }
        return totalFloors;
    }

    /** Floor state, created on demand. */
    public FloorState floor(int f) {
        return floors.computeIfAbsent(f, FloorState::new);
    }

    /** A player's progress, created on demand. */
    public Progress progressOf(UUID id) {
        return progress.computeIfAbsent(id, k -> new Progress(k, 0, 0, List.of()));
    }

    /** The gate at a position, or null. */
    public GateRef gateAt(ResourceKey<Level> dim, BlockPos pos) {
        for (GateRef g : gates) {
            if (g.dim.equals(dim) && g.pos.equals(pos)) {
                return g;
            }
        }
        return null;
    }

    /** True for the big base gate of the overworld tower. */
    public boolean isTowerGate(ResourceKey<Level> dim, BlockPos pos) {
        return towerBuilt && dim.equals(Level.OVERWORLD)
                && pos.getX() >= entranceMin.getX() && pos.getX() <= entranceMax.getX()
                && pos.getY() >= entranceMin.getY() && pos.getY() <= entranceMax.getY()
                && pos.getZ() >= entranceMin.getZ() && pos.getZ() <= entranceMax.getZ();
    }
}
