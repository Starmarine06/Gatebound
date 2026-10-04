package dev.gatebound.dungeon;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.gatebound.Gatebound;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Progression;
import dev.gatebound.trade.TradeItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the Spire: gates and portals, floor travel, kills and clears, rewards, death handling, natural gates
 * and the jobs that build floors and the overworld tower.
 */
public final class DungeonManager {
    public static final String TAG_MOB = FloorPopulator.TAG_MOB;
    public static final String TAG_BOSS = FloorPopulator.TAG_BOSS;
    private static final String PREFIX = "[Gatebound] ";

    /** In-memory only; all of this is safe to lose on restart (see join handling). */
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
    private static final Set<UUID> PENDING_HOME = new HashSet<>();
    private static final Set<UUID> FAILED = new HashSet<>();
    private static final Set<UUID> PENDING_RESPAWN = new HashSet<>();
    private static boolean towerChecked;
    private static int selfTestStage;
    private static int selfTestPending;
    private static long selfTestStart;

    private DungeonManager() {
    }

    public static void init() {
        DungeonRegistries.init();
        DungeonCommands.register();
        GateLabels.init();

        TickEvent.SERVER_POST.register(DungeonManager::tick);
        LifecycleEvent.SERVER_STARTED.register(DungeonManager::onStarted);
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            COOLDOWNS.clear();
            PENDING_HOME.clear();
            PENDING_RESPAWN.clear();
            FAILED.clear();
            WorkQueue.reset(server);
            GatePull.reset();
            towerChecked = false;
            selfTestStage = 0;
        });

        PlayerEvent.PLAYER_JOIN.register(player -> {
            // A player who logs in inside the dungeon dimension on a floor that no longer exists is sent home.
            if (player.level().dimension().equals(DungeonRegistries.DUNGEON)) {
                DungeonStore store = DungeonStore.get(player.level().getServer());
                int f = Spire.floorAt(player.getX(), player.getZ(), store.totalFloors);
                if (f == 0 || !store.floor(f).built) {
                    PENDING_HOME.add(player.getUUID());
                }
            }
        });
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> {
            if (FAILED.remove(player.getUUID())) {
                PENDING_RESPAWN.add(player.getUUID());
            }
        });
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (!entity.level().isClientSide() && entity.level().dimension().equals(DungeonRegistries.DUNGEON)) {
                if (entity instanceof ServerPlayer sp) {
                    FAILED.add(sp.getUUID());
                    DungeonStore store = DungeonStore.get(sp.level().getServer());
                    int rest = store.progressOf(sp.getUUID()).lastRest;
                    String where = DungeonConfig.deathRespawnMode == 1 && rest > 0
                            ? "at rest floor " + rest : "at the tower entrance";
                    sp.sendSystemMessage(Component.literal(PREFIX + "You fell in the Spire. You will wake " + where
                            + "; your belongings stay where you fell.").withStyle(ChatFormatting.RED));
                } else if (entity instanceof Mob && entity.level() instanceof ServerLevel level) {
                    onMobDeath(level, entity);
                }
            }
            return EventResult.pass();
        });
        // Spire walls belong to the Spire, and the overworld tower to the landscape: survival players cannot edit them.
        BlockEvent.BREAK.register((level, pos, state, player) -> {
            if (player != null && !player.isCreative() && protectedBlock(level, pos)) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
        BlockEvent.PLACE.register((level, pos, state, entity) -> {
            if (entity instanceof Player p && !p.isCreative() && protectedBlock(level, pos)) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
    }

    private static boolean protectedBlock(net.minecraft.world.level.LevelAccessor level, BlockPos pos) {
        if (!(level instanceof Level l)) {
            return false;
        }
        if (l.dimension().equals(DungeonRegistries.DUNGEON)) {
            return true;
        }
        if (DungeonConfig.towerProtect && l.dimension().equals(Level.OVERWORLD) && l.getServer() != null) {
            AABB box = TowerJob.protectedBox(DungeonStore.get(l.getServer()));
            return box != null && box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        return false;
    }

    private static void onStarted(MinecraftServer server) {
        // Chunk tickets from a build interrupted by a crash or shutdown are not wanted; floors rebuild on demand.
        DungeonStore store = DungeonStore.get(server);
        ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
        if (dungeon != null) {
            int s = Spire.size();
            for (DungeonStore.FloorState fs : store.floors.values()) {
                if (fs.built && fs.populatedAt > 0) {
                    continue; // only floors whose build was interrupted can have leftover tickets
                }
                int ox = Spire.originX(fs.floor);
                for (int cx = ox >> 4; cx <= (ox + s - 1) >> 4; cx++) {
                    for (int cz = 0; cz <= (s - 1) >> 4; cz++) {
                        ChunkHold.release(dungeon, cx, cz);
                    }
                }
            }
        }
        if (store.towerPlaced && !store.towerBuilt) {
            int r = Math.max(10, store.towerRadius > 0 ? store.towerRadius : DungeonConfig.towerRadius) + 9;
            BlockPos c = store.towerPos;
            ServerLevel overworld = server.overworld();
            for (int x = (c.getX() - r) >> 4; x <= (c.getX() + r) >> 4; x++) {
                for (int z = (c.getZ() - r) >> 4; z <= (c.getZ() + r) >> 4; z++) {
                    ChunkHold.release(overworld, x, z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ portals

    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    public static boolean portalReady(ServerPlayer player) {
        return !WorkQueue.isHolding(player.getUUID())
                && now(player.level().getServer()) >= COOLDOWNS.getOrDefault(player.getUUID(), 0L);
    }

    public static void markPortalUsed(ServerPlayer player) {
        COOLDOWNS.put(player.getUUID(), now(player.level().getServer()) + DungeonConfig.portalCooldownTicks);
    }

    /** A gate was entered (walked into, clicked or pulled into): throw the player to a random rift on the gate's floor. */
    public static void enterGate(ServerPlayer player, BlockPos gatePos, GateRank rank) {
        if (!portalReady(player)) {
            return;
        }
        ServerLevel from = player.level();
        MinecraftServer server = from.getServer();
        if (server.getLevel(DungeonRegistries.DUNGEON) == null) {
            player.sendSystemMessage(Component.literal(PREFIX + "The dungeon dimension is not loaded.").withStyle(ChatFormatting.RED));
            return;
        }
        if (from.dimension().equals(DungeonRegistries.DUNGEON)) {
            return;
        }
        DungeonStore store = DungeonStore.get(server);
        if (store.isTowerGate(from.dimension(), gatePos)) {
            markPortalUsed(player);
            store.returns.remove(player.getUUID());
            store.setDirty();
            player.sendSystemMessage(Component.translatableWithFallback("gatebound.tower.enter", "You pass beneath the arch of the Spire.")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            travelFloor(player, 1, WorkQueue.Dest.BOTTOM);
            return;
        }
        int total = store.ensureTower(server);
        DungeonStore.GateRef g = store.gateAt(from.dimension(), gatePos);
        if (g == null) {
            g = new DungeonStore.GateRef(from.dimension(), gatePos, 0, false, now(server), 0);
            store.gates.add(g);
        }
        if (g.floor <= 0 || g.floor > total) {
            g.floor = Spire.pickFloor(rank, total, from.getRandom());
            BlockState st = from.getBlockState(gatePos);
            if (st.getBlock() instanceof GateBlock) {
                from.setBlock(gatePos, st.setValue(GateBlock.RANK, Spire.rank(g.floor, total)), 3);
            }
        }
        g.uses++;

        // Return a little in front of the gate so walking out does not immediately re-trigger it.
        double rx = player.getX();
        double rz = player.getZ();
        double dx = rx - (gatePos.getX() + 0.5);
        double dz = rz - (gatePos.getZ() + 0.5);
        if (Math.abs(player.getY() - gatePos.getY()) < 2.0 && dx * dx + dz * dz < 4.0) {
            BlockState st = from.getBlockState(gatePos);
            Direction f = st.getBlock() instanceof GateBlock ? st.getValue(GateBlock.FACING) : Direction.SOUTH;
            rx = gatePos.getX() + 0.5 + f.getStepX() * 2.2;
            rz = gatePos.getZ() + 0.5 + f.getStepZ() * 2.2;
        }
        store.returns.put(player.getUUID(), new DungeonStore.Return(player.getUUID(), from.dimension(),
                rx, player.getY(), rz, player.getYRot()));
        store.setDirty();
        markPortalUsed(player);
        GatePull.grantImmunity(player);
        GateRank r = Spire.rank(g.floor, total);
        player.sendSystemMessage(Component.literal(PREFIX + "You are pulled through the gate! " + r.label() + " - floor " + g.floor)
                .withStyle(r.color()));
        travelFloor(player, g.floor, WorkQueue.Dest.RIFT);
    }

    /** A floor portal block was used. */
    public static void usePortal(ServerPlayer player, BlockPos pos, GateExitBlock.Kind kind) {
        if (!portalReady(player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (!player.level().dimension().equals(DungeonRegistries.DUNGEON)) {
            return;
        }
        DungeonStore store = DungeonStore.get(server);
        int total = store.totalFloors;
        int f = Spire.floorAt(pos.getX(), pos.getZ(), total);
        switch (kind) {
            case HOME -> sendHome(player);
            case UP -> {
                if (f > 0 && f < total) {
                    markPortalUsed(player);
                    travelFloor(player, f + 1, WorkQueue.Dest.BOTTOM);
                }
            }
            case DOWN -> {
                if (f == 1) {
                    markPortalUsed(player);
                    exitTower(player);
                } else if (f > 1) {
                    markPortalUsed(player);
                    travelFloor(player, f - 1, WorkQueue.Dest.TOP);
                }
            }
        }
    }

    /** Sends the player to a floor, building or repopulating it first (holding the player) if needed. */
    public static void travelFloor(ServerPlayer player, int floor, WorkQueue.Dest dest) {
        MinecraftServer server = player.level().getServer();
        DungeonStore store = DungeonStore.get(server);
        int total = store.ensureTower(server);
        floor = Math.max(1, Math.min(total, floor));
        if (server.getLevel(DungeonRegistries.DUNGEON) == null) {
            return;
        }
        DungeonStore.FloorState fs = store.floor(floor);
        WorkQueue.Job job = WorkQueue.find("floor:" + floor);
        if (job == null && fs.built && !needsRepopulate(fs, total, now(server))) {
            completeTravel(server, player, floor, dest);
            return;
        }
        if (job == null) {
            job = new FloorJob(floor, false, fs.built, PlayerDataStore.of(player).level);
            WorkQueue.enqueue(job);
        }
        job.waiting.put(player.getUUID(), dest);
        WorkQueue.hold(server);
        player.sendSystemMessage(Component.translatableWithFallback("gatebound.tower.forming", "The floor takes shape around you...")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC));
    }

    static boolean needsRepopulate(DungeonStore.FloorState fs, int total, long now) {
        Spire.Kind kind = Spire.kind(fs.floor, total);
        if (kind == Spire.Kind.REST) {
            return false;
        }
        if (fs.populatedAt <= 0) {
            return true;
        }
        long delay = kind == Spire.Kind.NORMAL ? DungeonConfig.repopulateTicks : DungeonConfig.bossRepopulateTicks;
        return fs.cleared && now - fs.clearedAt >= delay;
    }

    /** Teleports a player onto a built floor and announces it. */
    public static void completeTravel(MinecraftServer server, ServerPlayer player, int floor, WorkQueue.Dest dest) {
        ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
        DungeonStore store = DungeonStore.get(server);
        DungeonStore.FloorState fs = store.floor(floor);
        if (dungeon == null || !fs.built) {
            player.sendSystemMessage(Component.literal(PREFIX + "That floor is not ready.").withStyle(ChatFormatting.RED));
            return;
        }
        BlockPos target = switch (dest) {
            case BOTTOM -> fs.downArrive;
            case TOP -> fs.upArrive;
            case RIFT -> fs.rifts.isEmpty() ? fs.downArrive : fs.rifts.get(player.getRandom().nextInt(fs.rifts.size()));
        };
        if (target.equals(BlockPos.ZERO)) {
            target = fs.downArrive;
        }
        markPortalUsed(player);
        GatePull.grantImmunity(player);
        player.teleportTo(dungeon, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, Set.of(), -90f, 0f, true);

        int total = store.totalFloors;
        Spire.Kind kind = Spire.kind(floor, total);
        GateRank rank = Spire.rank(floor, total);
        DungeonStore.Progress prog = store.progressOf(player.getUUID());
        boolean record = floor > prog.highest;
        if (record) {
            prog.highest = floor;
        }
        if (kind == Spire.Kind.REST) {
            prog.lastRest = floor;
        }
        store.setDirty();
        ChatFormatting color = kind == Spire.Kind.REST ? ChatFormatting.GREEN : rank.color();
        player.sendSystemMessage(Component.translatableWithFallback("gatebound.tower.floor", "Floor %s / %s", floor, total)
                .withStyle(color, ChatFormatting.BOLD));
        player.sendOverlayMessage(Component.translatableWithFallback("gatebound.tower.floor_title", "Floor %s - %s",
                floor, Spire.describe(floor, total)).withStyle(color));
        if (record && floor > 1) {
            player.sendSystemMessage(Component.translatableWithFallback("gatebound.tower.record", "New highest floor reached!")
                    .withStyle(ChatFormatting.GOLD));
        }
        if (kind == Spire.Kind.REST) {
            player.sendSystemMessage(Component.translatableWithFallback("gatebound.tower.rest",
                    "A safe haven. Rest by the fire; you will wake here if you fall.").withStyle(ChatFormatting.GREEN));
        } else if (kind == Spire.Kind.BOSS || kind == Spire.Kind.FINAL) {
            player.sendSystemMessage(Component.translatableWithFallback("gatebound.tower.boss",
                    "A guardian lairs on this floor. The way up is sealed until it falls.").withStyle(ChatFormatting.DARK_RED));
        }
    }

    // ------------------------------------------------------------------ leaving

    /** Returns a player to where they entered (a gate), else the tower entrance, else the world spawn. */
    public static void sendHome(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        DungeonStore store = DungeonStore.get(server);
        DungeonStore.Return ret = store.returns.remove(player.getUUID());
        store.setDirty();
        markPortalUsed(player);
        GatePull.grantImmunity(player);
        ServerLevel target = ret == null ? null : server.getLevel(ret.dim());
        if (target == null) {
            exitTower(player);
        } else {
            player.teleportTo(target, ret.x(), ret.y(), ret.z(), Set.of(), ret.yaw(), 0f, true);
            player.sendSystemMessage(Component.literal(PREFIX + "You return from the Spire.").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Puts a player outside the tower's entrance gate (or at world spawn if there is no tower). */
    public static void exitTower(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        DungeonStore store = DungeonStore.get(server);
        store.returns.remove(player.getUUID());
        store.setDirty();
        markPortalUsed(player);
        GatePull.grantImmunity(player);
        if (store.towerBuilt) {
            BlockPos e = store.towerExit;
            player.teleportTo(server.overworld(), e.getX() + 0.5, e.getY(), e.getZ() + 0.5, Set.of(), 180f, 0f, true);
        } else {
            player.teleport(TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING));
        }
        player.sendSystemMessage(Component.literal(PREFIX + "You return from the Spire.").withStyle(ChatFormatting.GRAY));
    }

    /** /gatebound gate leave */
    public static boolean leave(ServerPlayer player) {
        if (!player.level().dimension().equals(DungeonRegistries.DUNGEON)) {
            return false;
        }
        sendHome(player);
        return true;
    }

    // ------------------------------------------------------------------ kills and clears

    private static void onMobDeath(ServerLevel level, Entity entity) {
        DungeonStore store = DungeonStore.get(level.getServer());
        int total = store.totalFloors;
        int f = Spire.floorAt(entity.getX(), entity.getZ(), total);
        if (f == 0 || entity.entityTags().contains("gatebound_echo")) {
            return;
        }
        DungeonStore.FloorState fs = store.floor(f);
        if (entity.entityTags().contains(TAG_BOSS)) {
            onBossDeath(level, store, fs, f, total);
        } else if (entity.entityTags().contains(TAG_MOB)) {
            fs.kills++;
            store.setDirty();
            Component msg = Component.literal("Monsters slain: " + Math.min(fs.kills, fs.totalMobs) + "/" + fs.totalMobs)
                    .withStyle(Spire.rank(f, total).color());
            for (ServerPlayer p : playersOnFloor(level, f)) {
                p.sendOverlayMessage(msg);
            }
            Spire.Kind kind = Spire.kind(f, total);
            if (!fs.cleared && fs.totalMobs > 0 && fs.kills >= fs.totalMobs && kind == Spire.Kind.NORMAL) {
                onFloorCleared(level, store, fs, f, total);
            }
        }
    }

    private static List<ServerPlayer> playersOnFloor(ServerLevel dungeon, int floor) {
        AABB box = Spire.bounds(floor);
        return dungeon.getPlayers(p -> box.contains(p.getX(), p.getY(), p.getZ()));
    }

    private static void onFloorCleared(ServerLevel level, DungeonStore store, DungeonStore.FloorState fs, int f, int total) {
        fs.cleared = true;
        fs.clearedAt = now(level.getServer());
        store.setDirty();
        int xp = DungeonConfig.floorClearXpBase + DungeonConfig.floorClearXpPerFloor * f;
        for (ServerPlayer p : playersOnFloor(level, f)) {
            p.sendSystemMessage(Component.literal(PREFIX + "Floor " + f + " cleared. The monsters will return in time.")
                    .withStyle(Spire.rank(f, total).color()));
            Progression.giveXp(p, xp);
        }
    }

    private static void onBossDeath(ServerLevel level, DungeonStore store, DungeonStore.FloorState fs, int f, int total) {
        MinecraftServer server = level.getServer();
        Spire.Kind kind = Spire.kind(f, total);
        GateRank rank = Spire.rank(f, total);
        boolean fin = kind == Spire.Kind.FINAL;
        fs.bossDead = true;
        fs.cleared = true;
        fs.clearedAt = now(server);
        store.setDirty();

        BlockPos exit = fs.upPortal;
        if (!exit.equals(BlockPos.ZERO)) {
            level.setBlock(exit, GateExitBlock.state(fin ? GateExitBlock.Kind.HOME : GateExitBlock.Kind.UP), 3);
            RandomSource rng = level.getRandom();
            int[] offsets = fin ? new int[]{-9, -6, -3, 3, 6, 9} : new int[]{-3, 3};
            for (int dz : offsets) {
                BlockPos pos = exit.offset(-2, 0, dz);
                BlockState existing = level.getBlockState(pos);
                if (existing.isAir() || existing.is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    FloorPopulator.placeChest(level, new FloorPlan.Chest(pos, Direction.WEST), f, total, rng, false);
                    if (fin) {
                        FloorPopulator.fillChest(level, pos, f + 10, total + 10, rng, false);
                    }
                }
            }
        }
        for (ServerPlayer p : playersOnFloor(level, f)) {
            p.sendSystemMessage(Component.literal(PREFIX + (fin ? "The Sovereign of the Spire has fallen!" : "The guardian of floor " + f + " has fallen!"))
                    .withStyle(rank.color(), ChatFormatting.BOLD));
            p.sendSystemMessage(Component.literal(PREFIX + (fin ? "A portal home and a trove of treasure appear." : "The way up opens, and treasure appears."))
                    .withStyle(ChatFormatting.GRAY));
            p.sendOverlayMessage(Component.literal(fin ? "THE SPIRE IS CONQUERED" : "GUARDIAN DEFEATED").withStyle(rank.color(), ChatFormatting.BOLD));
            p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
            DungeonStore.Progress prog = store.progressOf(p.getUUID());
            if (!prog.bosses.contains(f)) {
                prog.bosses.add(f);
                store.setDirty();
                int xp = fin ? DungeonConfig.finalBossXp : DungeonConfig.bossXpBase + DungeonConfig.bossXpPerFloor * f;
                Progression.giveXp(p, xp);
                int shards = fin ? 25 : 3 + f / 5;
                giveItem(p, new ItemStack(TradeItems.GATE_SHARD.get(), shards));
                p.sendSystemMessage(Component.literal(PREFIX + "First clear reward: " + xp + " XP and " + shards + " Gate Shards.")
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private static void giveItem(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.spawnAtLocation(player.level(), stack);
        }
    }

    // ------------------------------------------------------------------ maintenance

    private static void tick(MinecraftServer server) {
        WorkQueue.tick(server);
        int tc = server.getTickCount();
        DungeonStore store = DungeonStore.get(server);
        GatePull.tick(server, store);
        if (DungeonConfig.selfTest && DungeonConfig.selfTestBot && selfTestStage >= 3) {
            SelfTestBot.tick(server, store);
        }

        if (!PENDING_HOME.isEmpty()) {
            for (UUID id : new ArrayList<>(PENDING_HOME)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) {
                    PENDING_HOME.remove(id);
                    if (p.level().dimension().equals(DungeonRegistries.DUNGEON)) {
                        sendHome(p);
                    }
                }
            }
        }
        if (!PENDING_RESPAWN.isEmpty()) {
            for (UUID id : new ArrayList<>(PENDING_RESPAWN)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null && !p.isDeadOrDying()) {
                    PENDING_RESPAWN.remove(id);
                    respawnAfterFall(p, store);
                }
            }
        }
        if (tc % 20 != 0) {
            return;
        }
        if (tc > 100 && !towerChecked) {
            towerChecked = true;
            if (DungeonConfig.towerEnabled && !store.towerBuilt && WorkQueue.find("tower") == null) {
                store.ensureTower(server);
                WorkQueue.enqueue(new TowerJob(null));
            }
        }
        if (DungeonConfig.selfTest) {
            selfTest(server, store, tc);
        }
        maintainGates(server, store);
        ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
        if (dungeon != null) {
            maintainDungeon(server, dungeon, store, tc);
        }
        if (DungeonConfig.naturalSpawn && tc % Math.max(20, DungeonConfig.spawnCheckTicks) == 0) {
            rollNaturalGate(server, store);
        }
    }

    private static void respawnAfterFall(ServerPlayer p, DungeonStore store) {
        int rest = store.progressOf(p.getUUID()).lastRest;
        if (DungeonConfig.deathRespawnMode == 1 && rest > 0 && store.floor(rest).built) {
            p.sendSystemMessage(Component.literal(PREFIX + "You wake at the rest floor.").withStyle(ChatFormatting.GREEN));
            travelFloor(p, rest, WorkQueue.Dest.TOP);
        } else {
            exitTower(p);
        }
    }

    private static void maintainDungeon(MinecraftServer server, ServerLevel dungeon, DungeonStore store, int tc) {
        int total = store.totalFloors;
        if (total <= 0) {
            return;
        }
        long now = now(server);
        Set<Integer> occupied = new HashSet<>();
        for (ServerPlayer p : dungeon.players()) {
            int f = Spire.floorAt(p.getX(), p.getZ(), total);
            if (f == 0) {
                // Outside every floor and fallen into the void: return to a safe place.
                if (!p.isSpectator() && !p.isCreative() && !WorkQueue.isHolding(p.getUUID()) && p.getY() < 4) {
                    sendHome(p);
                }
                continue;
            }
            occupied.add(f);
            if (p.getY() < 6) {
                DungeonStore.FloorState fs = store.floor(f);
                if (fs.built && !fs.downArrive.equals(BlockPos.ZERO)) {
                    p.teleportTo(dungeon, fs.downArrive.getX() + 0.5, fs.downArrive.getY(), fs.downArrive.getZ() + 0.5,
                            Set.of(), -90f, 0f, true);
                }
            }
            if (DungeonConfig.restFloorHealing && Spire.kind(f, total) == Spire.Kind.REST && tc % 40 == 0) {
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1, true, false));
                p.addEffect(new MobEffectInstance(MobEffects.SATURATION, 40, 0, true, false));
            }
        }
        if (tc % 200 == 0) {
            for (int f : occupied) {
                checkClear(dungeon, store, f, total, now);
            }
        }
    }

    /** Safety net: a floor whose monsters are all gone but whose kill counter never completed is marked cleared. */
    private static void checkClear(ServerLevel level, DungeonStore store, int f, int total, long now) {
        DungeonStore.FloorState fs = store.floor(f);
        Spire.Kind kind = Spire.kind(f, total);
        if (!fs.built || fs.cleared || kind == Spire.Kind.REST || fs.populatedAt <= 0 || now - fs.populatedAt < 600
                || WorkQueue.find("floor:" + f) != null) {
            return;
        }
        int s = Spire.size();
        int ox = Spire.originX(f);
        for (int cx = ox >> 4; cx <= (ox + s) >> 4; cx++) {
            for (int cz = 0; cz <= s >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return;
                }
            }
        }
        List<Mob> alive = level.getEntitiesOfClass(Mob.class, Spire.bounds(f),
                m -> m.isAlive() && m.entityTags().contains(TAG_MOB));
        if (!alive.isEmpty()) {
            return;
        }
        if (kind == Spire.Kind.NORMAL) {
            onFloorCleared(level, store, fs, f, total);
        } else {
            onBossDeath(level, store, fs, f, total);
        }
    }

    // ------------------------------------------------------------------ gates

    /** Places a gate at the given position facing the given point. */
    public static void placeGate(ServerLevel level, BlockPos pos, GateRank rank, double towardX, double towardZ) {
        double dx = towardX - (pos.getX() + 0.5);
        double dz = towardZ - (pos.getZ() + 0.5);
        Direction f = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        level.setBlock(pos, DungeonRegistries.GATE.get().defaultBlockState()
                .setValue(GateBlock.RANK, rank).setValue(GateBlock.FACING, f), 3);
    }

    /** Op command: places a gate leading to the given floor (0 = random floor of the rank's band). */
    public static int spawnGate(ServerPlayer player, GateRank rank, int floor) {
        MinecraftServer server = player.level().getServer();
        DungeonStore store = DungeonStore.get(server);
        int total = store.ensureTower(server);
        if (floor > 0) {
            floor = Math.min(floor, total);
            rank = Spire.rank(floor, total);
        } else {
            floor = Spire.pickFloor(rank, total, player.getRandom());
        }
        ServerLevel level = player.level();
        BlockPos pos = player.blockPosition();
        // Grace period so the player standing inside the new gate is not pulled in at once.
        markPortalUsed(player);
        GatePull.grantImmunity(player);
        placeGate(level, pos, rank, player.getX(), player.getZ() + 1.0);
        store.gates.removeIf(g -> g.dim.equals(level.dimension()) && g.pos.equals(pos));
        store.gates.add(new DungeonStore.GateRef(level.dimension(), pos, floor, false, now(server), 0));
        store.setDirty();
        return floor;
    }

    private static void maintainGates(MinecraftServer server, DungeonStore store) {
        long now = now(server);
        boolean changed = false;
        for (DungeonStore.GateRef g : new ArrayList<>(store.gates)) {
            ServerLevel l = server.getLevel(g.dim);
            if (l == null || !l.hasChunkAt(g.pos)) {
                continue;
            }
            boolean exists = l.getBlockState(g.pos).getBlock() instanceof GateBlock;
            boolean expired = now - g.created > DungeonConfig.gateLifetimeTicks || g.uses >= DungeonConfig.gateMaxUses;
            if (exists && expired) {
                boolean busy = false;
                for (ServerPlayer p : l.players()) {
                    if (p.distanceToSqr(g.pos.getX() + 0.5, g.pos.getY() + 0.5, g.pos.getZ() + 0.5) < 16) {
                        busy = true;
                    }
                }
                if (busy) {
                    continue;
                }
                l.sendParticles(ParticleTypes.REVERSE_PORTAL, g.pos.getX() + 0.5, g.pos.getY() + 1.0, g.pos.getZ() + 0.5,
                        40, 0.6, 1.0, 0.6, 0.2);
                l.playSound(null, g.pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, 1.0f, 0.7f);
                l.removeBlock(g.pos, false);
                exists = false;
            }
            if (!exists) {
                store.gates.remove(g);
                changed = true;
            }
        }
        if (changed) {
            store.setDirty();
        }
    }

    private static void rollNaturalGate(MinecraftServer server, DungeonStore store) {
        ServerLevel level = server.overworld();
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        int natural = 0;
        for (DungeonStore.GateRef g : store.gates) {
            if (g.natural) {
                natural++;
            }
        }
        if (natural >= DungeonConfig.maxNaturalGates) {
            return;
        }
        RandomSource rng = level.getRandom();
        if (rng.nextDouble() >= DungeonConfig.spawnChance) {
            return;
        }
        ServerPlayer player = players.get(rng.nextInt(players.size()));
        for (DungeonStore.GateRef g : store.gates) {
            if (g.natural && g.dim.equals(level.dimension())) {
                double dx = g.pos.getX() - player.getX();
                double dz = g.pos.getZ() - player.getZ();
                if (dx * dx + dz * dz < (double) DungeonConfig.perPlayerRadius * DungeonConfig.perPlayerRadius) {
                    return;
                }
            }
        }

        int span = Math.max(1, DungeonConfig.spawnMaxDistance - DungeonConfig.spawnMinDistance);
        for (int attempt = 0; attempt < 6; attempt++) {
            double dist = DungeonConfig.spawnMinDistance + rng.nextInt(span + 1);
            double ang = rng.nextDouble() * Math.PI * 2;
            int x = (int) Math.floor(player.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(player.getZ() + Math.sin(ang) * dist);
            BlockPos column = new BlockPos(x, level.getSeaLevel(), z);
            if (!level.hasChunkAt(column)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!validGateSpot(level, pos)) {
                continue;
            }
            int total = store.ensureTower(server);
            GateRank rank = pickRank(rng);
            int floor = Spire.pickFloor(rank, total, rng);
            placeGate(level, pos, rank, player.getX(), player.getZ());
            store.gates.add(new DungeonStore.GateRef(level.dimension(), pos, floor, true, now(server), 0));
            store.setDirty();
            player.sendSystemMessage(Component.literal(PREFIX + "A gate has appeared...").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            player.level().playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 1.5f, 0.6f);
            Gatebound.LOGGER.info("Natural {} gate (floor {}) spawned at {} near {}", rank, floor, pos, player.getName().getString());
            return;
        }
    }

    private static boolean validGateSpot(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        boolean naturalGround = ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(BlockTags.TERRACOTTA)
                || ground.is(BlockTags.BASE_STONE_OVERWORLD) || ground.is(BlockTags.SNOW);
        if (!naturalGround || !ground.getFluidState().isEmpty()) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty() || !level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above())) {
            return false;
        }
        if (!level.canSeeSky(pos)) {
            return false;
        }
        DungeonStore store = DungeonStore.get(level.getServer());
        AABB tower = TowerJob.protectedBox(store);
        if (tower != null && tower.inflate(10).contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
            return false;
        }
        return !level.structureManager().getStructureWithPieceAt(pos, h -> true).isValid();
    }

    private static GateRank pickRank(RandomSource rng) {
        int total = 0;
        for (int w : DungeonConfig.rankWeights) {
            total += Math.max(0, w);
        }
        int roll = rng.nextInt(Math.max(1, total));
        for (int i = 0; i < GateRank.values().length && i < DungeonConfig.rankWeights.length; i++) {
            roll -= Math.max(0, DungeonConfig.rankWeights[i]);
            if (roll < 0) {
                return GateRank.byIndex(i);
            }
        }
        return GateRank.E;
    }

    // ------------------------------------------------------------------ op helpers (commands)

    /** Queues a rebuild of a floor from scratch. Returns false if it is already being worked on. */
    public static boolean regenFloor(MinecraftServer server, int floor, int level, Runnable done) {
        DungeonStore store = DungeonStore.get(server);
        int total = store.ensureTower(server);
        if (floor < 1 || floor > total || WorkQueue.find("floor:" + floor) != null) {
            return false;
        }
        DungeonStore.FloorState fs = store.floor(floor);
        fs.built = false;
        store.setDirty();
        FloorJob job = new FloorJob(floor, true, false, level);
        if (done != null) {
            job.onDone.add(done);
        }
        WorkQueue.enqueue(job);
        return true;
    }

    /** Queues a build of a floor without moving anyone (headless generation). */
    public static boolean prebuildFloor(MinecraftServer server, int floor, int level, Runnable done) {
        DungeonStore store = DungeonStore.get(server);
        int total = store.ensureTower(server);
        if (floor < 1 || floor > total) {
            return false;
        }
        WorkQueue.Job existing = WorkQueue.find("floor:" + floor);
        if (existing != null) {
            if (done != null) {
                existing.onDone.add(done);
            }
            return true;
        }
        DungeonStore.FloorState fs = store.floor(floor);
        if (fs.built) {
            if (done != null) {
                done.run();
            }
            return true;
        }
        FloorJob job = new FloorJob(floor, false, false, level);
        if (done != null) {
            job.onDone.add(done);
        }
        WorkQueue.enqueue(job);
        return true;
    }

    /** Queues (re)building the overworld tower, at the given x/z or the stored/default site. */
    public static boolean buildTower(MinecraftServer server, int[] xz, Runnable done) {
        if (WorkQueue.find("tower") != null) {
            return false;
        }
        DungeonStore.get(server).ensureTower(server);
        TowerJob job = new TowerJob(xz);
        if (done != null) {
            job.onDone.add(done);
        }
        WorkQueue.enqueue(job);
        return true;
    }

    // ------------------------------------------------------------------ self test (headless)

    static void testClearCooldowns() {
        COOLDOWNS.clear();
    }

    static void testBossDeath(ServerLevel level, DungeonStore store, int floor) {
        onBossDeath(level, store, store.floor(floor), floor, store.totalFloors);
    }

    /** Checks a freshly built floor in the real world: sealed walls, floors, portals and safe arrival spots. */
    private static void verifyFloor(MinecraftServer server, ServerLevel d, DungeonStore store, int floor) {
        int total = store.totalFloors;
        FloorPlan plan = FloorPlan.generate(server.overworld().getSeed(), floor, total);
        DungeonStore.FloorState fs = store.floor(floor);
        int leaks = 0;
        int noFloor = 0;
        int open = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        for (int j = 0; j < plan.size; j++) {
            for (int i = 0; i < plan.size; i++) {
                if (plan.h[j * plan.size + i] <= 0) {
                    continue;
                }
                open++;
                int x = plan.ox + i;
                BlockState floorState = d.getBlockState(p.set(x, Spire.FY, j));
                if (!floorState.isSolid() && floorState.getFluidState().isEmpty()) {
                    noFloor++;
                }
                for (int k = 0; k < 4; k++) {
                    int ni = i + dx[k];
                    int nj = j + dz[k];
                    boolean nbOpen = ni >= 0 && nj >= 0 && ni < plan.size && nj < plan.size && plan.h[nj * plan.size + ni] > 0;
                    if (!nbOpen && !d.getBlockState(p.set(plan.ox + ni, Spire.FY + 1, nj)).isSolid()) {
                        leaks++;
                    }
                }
            }
        }
        int badArrival = 0;
        List<BlockPos> arrivals = new ArrayList<>(fs.rifts);
        arrivals.add(fs.downArrive);
        arrivals.add(fs.upArrive);
        for (BlockPos a : arrivals) {
            if (!d.getBlockState(a).isAir() || !d.getBlockState(a.above()).isAir() || !d.getBlockState(a.below()).isSolid()) {
                badArrival++;
            }
        }
        int missingPortals = 0;
        if (!(d.getBlockState(fs.downArrive.west(2)).getBlock() instanceof GateExitBlock)) {
            missingPortals++;
        }
        Spire.Kind kind = Spire.kind(floor, total);
        boolean upExpected = kind == Spire.Kind.NORMAL || kind == Spire.Kind.REST;
        if (upExpected && !(d.getBlockState(fs.upArrive.east(2)).getBlock() instanceof GateExitBlock)) {
            missingPortals++;
        }
        for (BlockPos r : fs.rifts) {
            if (!(d.getBlockState(r.west(2)).getBlock() instanceof GateExitBlock)) {
                missingPortals++;
            }
        }
        int chestsFound = 0;
        for (FloorPlan.Chest c : plan.chests) {
            if (d.getBlockState(c.pos()).is(net.minecraft.world.level.block.Blocks.CHEST)) {
                chestsFound++;
            }
        }
        boolean ok = leaks == 0 && noFloor == 0 && badArrival == 0 && missingPortals == 0 && chestsFound == plan.chests.size();
        Gatebound.LOGGER.info("SELFTEST verify floor {}: {} open cells, wallLeaks={}, missingFloor={}, badArrivals={}, missingPortals={}, chests={}/{} -> {}",
                floor, open, leaks, noFloor, badArrival, missingPortals, chestsFound, plan.chests.size(), ok ? "OK" : "PROBLEM");
    }

    private static void selfTest(MinecraftServer server, DungeonStore store, int tc) {
        if (tc < 200) {
            return;
        }
        if (selfTestStage == 0) {
            selfTestStage = 1;
            selfTestStart = System.nanoTime();
            int total = store.ensureTower(server);
            long seed = server.overworld().getSeed();
            Gatebound.LOGGER.info("SELFTEST start: {} floors, seed {}", total, seed);
            int bad = 0;
            int nondeterministic = 0;
            long t0 = System.nanoTime();
            for (int f = 1; f <= total; f++) {
                FloorPlan a = FloorPlan.generate(seed, f, total);
                FloorPlan b = FloorPlan.generate(seed, f, total);
                if (!a.reachable) {
                    bad++;
                    Gatebound.LOGGER.warn("SELFTEST floor {} plan is not fully reachable", f);
                }
                if (!java.util.Arrays.equals(a.h, b.h) || !a.decos.equals(b.decos) || !a.mobSpots.equals(b.mobSpots)) {
                    nondeterministic++;
                }
                int open = 0;
                for (byte v : a.h) {
                    if (v > 0) {
                        open++;
                    }
                }
                if (f <= 12 || f % 10 == 0 || f == total) {
                    Gatebound.LOGGER.info("SELFTEST plan floor {} {} {}: {} open cells, {} decos, {} spots, {} chests, {} rifts",
                            f, a.kind, a.theme.label(), open, a.decos.size(), a.mobSpots.size(), a.chests.size(), a.riftPortals.size());
                }
            }
            Gatebound.LOGGER.info("SELFTEST plans: {} floors in {} ms, unreachable={}, nondeterministic={}",
                    total, (System.nanoTime() - t0) / 1_000_000, bad, nondeterministic);
            int[] floors = {1, 2, 5, 8, 10, 14, 15, 20, 30, total};
            Set<Integer> seen = new HashSet<>();
            selfTestPending = 0;
            for (int f : floors) {
                if (f < 1 || f > total || !seen.add(f)) {
                    continue;
                }
                selfTestPending++;
                final int floor = f;
                final long t1 = System.nanoTime();
                prebuildFloor(server, f, 20, () -> {
                    selfTestPending--;
                    Gatebound.LOGGER.info("SELFTEST floor {} built in {} ms wall (queue idle={})", floor,
                            (System.nanoTime() - t1) / 1_000_000, WorkQueue.isIdle());
                    ServerLevel d = server.getLevel(DungeonRegistries.DUNGEON);
                    if (d != null) {
                        verifyFloor(server, d, store, floor);
                        List<Mob> mobs = d.getEntitiesOfClass(Mob.class, Spire.bounds(floor), m -> m.entityTags().contains(TAG_MOB));
                        Gatebound.LOGGER.info("SELFTEST floor {} loaded tagged mobs: {}, state mobs={}", floor, mobs.size(),
                                store.floor(floor).totalMobs);
                    }
                });
            }
            if (!store.towerBuilt && WorkQueue.find("tower") == null) {
                selfTestPending++;
                buildTower(server, null, () -> {
                    selfTestPending--;
                    Gatebound.LOGGER.info("SELFTEST tower built at {}", store.towerPos);
                });
            }
            selfTestStage = 2;
        } else if (selfTestStage == 2 && selfTestPending <= 0 && WorkQueue.isIdle()) {
            selfTestStage = 3;
            Gatebound.LOGGER.info("SELFTEST DONE in {} ms", (System.nanoTime() - selfTestStart) / 1_000_000);
        }
    }
}
