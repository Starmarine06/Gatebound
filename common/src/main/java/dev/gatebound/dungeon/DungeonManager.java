package dev.gatebound.dungeon;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
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
    /** Last time anyone was seen in a gate-dungeon copy, by floor index (not saved; a restart restarts the idle clock). */
    private static final java.util.Map<Integer, Long> INSTANCE_SEEN = new java.util.HashMap<>();
    private static boolean towerChecked;
    private static int selfTestStage;
    private static int selfTestPending;
    private static long selfTestStart;

    /** Static holder; not instantiable. */
    private DungeonManager() {
    }

    /** Registers the dungeon systems, hooks and tick. */
    public static void init() {
        DungeonRegistries.init();
        DungeonCommands.register();
        GateLabels.init();
        Parties.init();

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
                    if (Spire.isGate(Spire.floorAt(sp.getX(), sp.getZ(), store.totalFloors))) {
                        GATE_DEATHS.add(sp.getUUID());
                        sp.sendSystemMessage(Component.literal(PREFIX + "You fell in the gate dungeon. You will wake outside the gate, which is still open.")
                                .withStyle(ChatFormatting.RED));
                        return EventResult.pass();
                    }
                    int rest = store.progressOf(sp.getUUID()).lastRest;
                    String where = DungeonConfig.deathRespawnMode == 1 && rest > 0
                            ? "at rest floor " + rest : "at the tower entrance";
                    sp.sendSystemMessage(Component.literal(PREFIX + "You fell in the Spire. You will wake " + where
                            + "; your belongings rest in a grave where you fell.").withStyle(ChatFormatting.RED));
                } else if (entity instanceof Mob && entity.level() instanceof ServerLevel level) {
                    onMobDeath(level, entity);
                }
            }
            return EventResult.pass();
        });
        // Spire walls belong to the Spire, and the overworld tower to the landscape: survival players cannot edit them.
        Compat.onBlockBreak((level, pos, state, player) -> {
            // Spire chests can be smashed open: the loot spills out and the empty chest stays where it is.
            if (player != null && state.is(net.minecraft.world.level.block.Blocks.CHEST)
                    && level instanceof ServerLevel sl && sl.dimension().equals(DungeonRegistries.DUNGEON)
                    && sl.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                net.minecraft.world.Containers.dropContents(sl, pos, chest);
                chest.setChanged();
                sl.playSound(null, pos, GateboundSounds.custom("chest.smash"), SoundSource.BLOCKS, 1f, 1f);
                return EventResult.interruptFalse();
            }
            // Mana crystals are the one thing in the Spire meant to be mined.
            if (state.is(DungeonRegistries.MANA_CRYSTAL.get())) {
                return EventResult.pass();
            }
            if (player != null && (DungeonConfig.protectFromCreative || !player.isCreative()) && protectedBlock(level, pos)) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
        BlockEvent.PLACE.register((level, pos, state, entity) -> {
            if (entity instanceof Player p && (DungeonConfig.protectFromCreative || !p.isCreative()) && protectedBlock(level, pos)) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
    }

    /** True if the block is protected from breaking in the Spire. */
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

    /** Server start: clears chunk tickets left by an interrupted build. */
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

    /** Current overworld game time. */
    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    /** True if the player may use a portal now (not held, cooldown over). */
    public static boolean portalReady(ServerPlayer player) {
        return !WorkQueue.isHolding(player.getUUID())
                && now(player.level().getServer()) >= COOLDOWNS.getOrDefault(player.getUUID(), 0L);
    }

    /** Starts the player's portal cooldown. */
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
            // Resume at the highest floor reached; the way up past a guardian only opens once it has fallen.
            travelFloor(player, Math.max(1, store.progressOf(player.getUUID()).highest), WorkQueue.Dest.BOTTOM);
            return;
        }
        int total = store.ensureTower(server);
        DungeonStore.GateRef g = store.gateAt(from.dimension(), gatePos);
        if (g == null) {
            g = newGate(store, from.dimension(), gatePos, rank, false, now(server));
        } else if (!Spire.isGate(g.floor)) {
            assignDungeon(store, g, rank); // a gate from before gate dungeons existed
        }
        if (g.cleared) {
            player.sendSystemMessage(Component.literal(PREFIX + "This gate has been closed.").withStyle(ChatFormatting.GRAY));
            return;
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
        // One dungeon per gate, the same for everyone; arrivals are scattered (a party arrives together).
        int floor = instanceFloor(store, g, SHARED, r);
        player.sendSystemMessage(Component.literal(PREFIX + "You are pulled through the gate into a " + r.label() + " dungeon!")
                .withStyle(r.color()));
        travelFloor(player, floor, WorkQueue.Dest.RIFT);
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
        if (Spire.isGate(f)) {
            markPortalUsed(player);
            sendHome(player);
            return;
        }
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
        if (!Spire.isGate(floor)) {
            floor = Math.max(1, Math.min(total, floor));
        }
        if (server.getLevel(DungeonRegistries.DUNGEON) == null) {
            return;
        }
        DungeonStore.FloorState fs = store.floor(floor);
        WorkQueue.Job job = WorkQueue.find("floor:" + floor);
        boolean solo = server.getPlayerList().getPlayerCount() <= 1;
        if (job == null && fs.built && !needsRepopulate(fs, total, now(server), solo)) {
            completeTravel(server, player, floor, dest);
            return;
        }
        if (job == null) {
            // A boss floor whose guardian fell is rebuilt from scratch (sealed way up, fresh boss), not just repopulated.
            boolean bossReset = fs.built && fs.bossDead && isBossFloor(floor, total);
            job = new FloorJob(floor, bossReset || (!fs.built && !fs.upArrive.equals(BlockPos.ZERO)), fs.built && !bossReset,
                    PlayerDataStore.of(player).level);
            WorkQueue.enqueue(job);
        }
        job.waiting.put(player.getUUID(), dest);
        WorkQueue.hold(server);
        player.sendSystemMessage(Component.translatableWithFallback("gatebound.tower.forming", "The floor takes shape around you...")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC));
    }

    /** True for boss and final floors that are not gate floors. */
    static boolean isBossFloor(int floor, int total) {
        Spire.Kind kind = Spire.kind(floor, total);
        return !Spire.isGate(floor) && (kind == Spire.Kind.BOSS || kind == Spire.Kind.FINAL);
    }

    /** Boss floors come back 15-20 minutes after the guardian fell; the exact delay is fixed per kill. */
    static long bossDelay(DungeonStore.FloorState fs) {
        return DungeonConfig.bossRepopulateTicks + Math.floorMod(Spire.mix(fs.clearedAt, fs.floor, 7), DungeonConfig.bossRepopulateJitter + 1);
    }

    /**
     * Solo (one player online): a cleared floor or fallen boss resets as soon as the player returns.
     * Multiplayer: floors come back after a delay, regardless of who is online.
     */
    static boolean needsRepopulate(DungeonStore.FloorState fs, int total, long now, boolean solo) {
        Spire.Kind kind = Spire.kind(fs.floor, total);
        if (kind == Spire.Kind.REST) {
            return false;
        }
        if (fs.populatedAt <= 0) {
            return true;
        }
        if (!fs.cleared) {
            return false;
        }
        if (solo) {
            return true;
        }
        long delay = kind == Spire.Kind.NORMAL ? DungeonConfig.repopulateTicks : bossDelay(fs);
        return now - fs.clearedAt >= delay;
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
            case RIFT -> riftFor(player, fs);
        };
        if (target.equals(BlockPos.ZERO)) {
            target = fs.downArrive;
        }
        markPortalUsed(player);
        GatePull.grantImmunity(player);
        player.teleportTo(dungeon, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, Set.of(), -90f, 0f, true);

        if (Spire.isGate(floor)) {
            GateRank gr = Spire.rank(floor, store.totalFloors);
            player.sendSystemMessage(Component.literal(PREFIX + gr.label() + " gate dungeon. Slay the guardian to close the gate and stop the monsters leaking out.")
                    .withStyle(gr.color(), ChatFormatting.BOLD));
            Compat.overlay(player, Component.literal(gr.label() + " Gate Dungeon").withStyle(gr.color()));
            if (DungeonConfig.gateSpawnProtectTicks > 0) {
                player.removeEffect(MobEffects.RESISTANCE);
                player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DungeonConfig.gateSpawnProtectTicks + 10, 4, false, false));
                PROTECTED.put(player.getUUID(), now(server) + DungeonConfig.gateSpawnProtectTicks);
                Compat.overlay(player, Component.literal("Spawn protection: " + (DungeonConfig.gateSpawnProtectTicks + 19) / 20 + "s").withStyle(ChatFormatting.AQUA));
            }
            return;
        }
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
        Compat.overlay(player, Component.translatableWithFallback("gatebound.tower.floor_title", "Floor %s - %s",
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
            Compat.teleportToDefaultSpawn(player);
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

    /** Death hook: checks whether the floor or boss is done. */
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
            level.playSound(null, entity.blockPosition(), GateboundSounds.custom("boss.death"), SoundSource.HOSTILE, 2f, 1f);
            MobLoot.dropBoss(level, entity, f, total, playersOnFloor(level, f));
        } else if (entity.entityTags().contains(TAG_MOB)) {
            fs.kills++;
            store.setDirty();
            Component msg = Component.literal("Monsters slain: " + Math.min(fs.kills, fs.totalMobs) + "/" + fs.totalMobs)
                    .withStyle(Spire.rank(f, total).color());
            int killXp = DungeonConfig.mobKillXpBase + DungeonConfig.mobKillXpPerFloor * Spire.scaleFloor(f, total);
            List<ServerPlayer> here = playersOnFloor(level, f);
            for (ServerPlayer p : here) {
                Compat.overlay(p, msg);
                Progression.giveXp(p, Parties.share(killXp, Parties.shareCount(p, here)));
            }
            MobLoot.drop(level, entity, f, total, here);
            Spire.Kind kind = Spire.kind(f, total);
            if (!fs.cleared && fs.totalMobs > 0 && fs.kills >= fs.totalMobs && kind == Spire.Kind.NORMAL) {
                onFloorCleared(level, store, fs, f, total);
            }
        }
    }

    /** Players standing inside a floor's bounds. */
    static List<ServerPlayer> playersOnFloor(ServerLevel dungeon, int floor) {
        AABB box = Spire.bounds(floor);
        return dungeon.getPlayers(p -> box.contains(p.getX(), p.getY(), p.getZ()));
    }

    /** Marks a floor cleared and gives XP to the players on it. */
    private static void onFloorCleared(ServerLevel level, DungeonStore store, DungeonStore.FloorState fs, int f, int total) {
        fs.cleared = true;
        fs.clearedAt = now(level.getServer());
        store.setDirty();
        int xp = DungeonConfig.floorClearXpBase + DungeonConfig.floorClearXpPerFloor * f;
        List<ServerPlayer> here = playersOnFloor(level, f);
        for (ServerPlayer p : here) {
            p.sendSystemMessage(Component.literal(PREFIX + "Floor " + f + " cleared. The monsters will return in time.")
                    .withStyle(Spire.rank(f, total).color()));
            Progression.giveXp(p, Parties.share(xp, Parties.shareCount(p, here)));
        }
    }

    /** Boss died: rewards and weakens the floor's mana crystals. */
    private static void onBossDeath(ServerLevel level, DungeonStore store, DungeonStore.FloorState fs, int f, int total) {
        MinecraftServer server = level.getServer();
        Spire.Kind kind = Spire.kind(f, total);
        GateRank rank = Spire.rank(f, total);
        boolean gate = Spire.isGate(f);
        boolean fin = kind == Spire.Kind.FINAL || gate;
        fs.bossDead = true;
        for (net.minecraft.core.BlockPos cp : fs.crystals) {
            BlockState cs = level.getBlockState(cp);
            if (cs.is(DungeonRegistries.MANA_CRYSTAL.get())) {
                level.setBlock(cp, cs.setValue(ManaCrystalBlock.WEAKENED, true), 3);
                level.sendParticles(ParticleTypes.END_ROD, cp.getX() + 0.5, cp.getY() + 0.7, cp.getZ() + 0.5, 12, 0.3, 0.4, 0.3, 0.05);
            }
        }
        for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, Spire.bounds(f),
                x -> x.entityTags().contains(dev.gatebound.mob.MobAbilities.MINION))) {
            level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 1, m.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
            m.discard();
        }
        fs.cleared = true;
        fs.clearedAt = now(server);
        store.setDirty();

        if (gate) {
            closeGate(store, f);
        }

        BlockPos exit = fs.upPortal;
        if (!exit.equals(BlockPos.ZERO)) {
            // A gate dungeon gets a portal home at the guardian's lair, so you never have to walk back to the entrance.
            PortalPlacer.place(level, exit, fin ? GateExitBlock.Kind.HOME : GateExitBlock.Kind.UP, Direction.EAST);
            RandomSource rng = level.getRandom();
            int[] offsets = fin ? new int[]{-9, -6, -3, 3, 6, 9} : new int[]{-3, 3};
            for (int dz : offsets) {
                BlockPos pos = exit.offset(-2, 0, dz);
                BlockState existing = level.getBlockState(pos);
                if (existing.isAir() || existing.is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    FloorPopulator.placeChest(level, new FloorPlan.Chest(pos, Direction.WEST), f, total, rng, false);
                    if (fin && !gate) {
                        FloorPopulator.fillChest(level, pos, f + 10, total + 10, rng, false);
                    }
                }
            }
        }
        List<ServerPlayer> here = playersOnFloor(level, f);
        if (gate) {
            GateCollapse.start(level, fs, rank, here, now(server));
        }
        for (ServerPlayer p : here) {
            int ways = Parties.shareCount(p, here);
            if (gate) {
                gateCleared(p, rank, ways);
                continue;
            }
            p.sendSystemMessage(Component.literal(PREFIX + (fin ? "The Sovereign of the Spire has fallen!" : "The guardian of floor " + f + " has fallen!"))
                    .withStyle(rank.color(), ChatFormatting.BOLD));
            p.sendSystemMessage(Component.literal(PREFIX + (fin ? "A portal home and a trove of treasure appear." : "The way up opens, and treasure appears."))
                    .withStyle(ChatFormatting.GRAY));
            Compat.overlay(p, Component.literal(fin ? "THE SPIRE IS CONQUERED" : "GUARDIAN DEFEATED").withStyle(rank.color(), ChatFormatting.BOLD));
            p.level().playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), SoundSource.PLAYERS, 1f, 1f);
            DungeonStore.Progress prog = store.progressOf(p.getUUID());
            if (!prog.bosses.contains(f)) {
                prog.bosses.add(f);
                store.setDirty();
                int xp = Parties.share(fin ? DungeonConfig.finalBossXp : DungeonConfig.bossXpBase + DungeonConfig.bossXpPerFloor * f, ways);
                Progression.giveXp(p, xp);
                int shards = Parties.share(fin ? 25 : 3 + f / 5, ways);
                giveItem(p, new ItemStack(TradeItems.GATE_SHARD.get(), shards));
                giveItem(p, new ItemStack(TradeItems.ECHO_SHARD.get(), fin ? 3 : 1));
                p.sendSystemMessage(Component.literal(PREFIX + "First clear reward: " + xp + " XP, " + shards + " Gate Shards and " + (fin ? 3 : 1) + " Echo Shard" + (fin ? "s" : "") + ".")
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** Tells the player a gate was cleared. */
    private static void gateCleared(ServerPlayer p, GateRank rank, int ways) {
        p.sendSystemMessage(Component.literal(PREFIX + "The gate guardian has fallen! The gate is closing; no more monsters will leak out.")
                .withStyle(rank.color(), ChatFormatting.BOLD));
        p.sendSystemMessage(Component.literal(PREFIX + "A portal out and treasure appear. Take what you can and go.").withStyle(ChatFormatting.GRAY));
        Compat.overlay(p, Component.literal("GATE CLEARED").withStyle(rank.color(), ChatFormatting.BOLD));
        p.level().playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), SoundSource.PLAYERS, 1f, 1f);
        int xp = Parties.share(rank.xpReward(), ways);
        int shards = Parties.share(3 + rank.ordinal() * 3, ways);
        Progression.giveXp(p, xp);
        giveItem(p, new ItemStack(TradeItems.GATE_SHARD.get(), shards));
        giveItem(p, new ItemStack(DungeonRegistries.MANA_CRYSTAL_ITEM.get(), 1 + rank.ordinal() / 2));
        String extra = "";
        if (rank.ordinal() >= 2) {
            giveItem(p, new ItemStack(TradeItems.ECHO_SHARD.get(), 1));
            extra = " and an Echo Shard";
        }
        p.sendSystemMessage(Component.literal(PREFIX + "Clear reward: " + xp + " XP, " + shards + " Gate Shards" + extra + " and mana crystals.")
                .withStyle(ChatFormatting.GOLD));
    }

    /** Marks the gate that leads to this gate dungeon as cleared; it closes and stops leaking. */
    private static void closeGate(DungeonStore store, int floor) {
        for (DungeonStore.GateRef g : store.gates) {
            if ((g.floor == floor || g.instances.containsValue(floor)) && !g.cleared) {
                g.cleared = true;
                store.setDirty();
            }
        }
    }

    /** Registers a new gate and assigns it a dungeon. */
    static DungeonStore.GateRef newGate(DungeonStore store, net.minecraft.resources.ResourceKey<Level> dim, BlockPos pos,
                                        GateRank rank, boolean natural, long now) {
        DungeonStore.GateRef g = new DungeonStore.GateRef(dim, pos, 0, natural, now, 0);
        store.gates.add(g);
        assignDungeon(store, g, rank);
        return g;
    }

    /** Gives a gate its own gate dungeon (a free slot), reset to a fresh state. */
    static void assignDungeon(DungeonStore store, DungeonStore.GateRef g, GateRank rank) {
        g.floor = freeGateIndex(store, g, rank);
        g.instances.clear();
        store.floors.remove(g.floor);
        store.setDirty();
    }

    /** A gate-dungeon floor index no other gate or instance is using (or, if all are taken, the gate's own). */
    private static int freeGateIndex(DungeonStore store, DungeonStore.GateRef g, GateRank rank) {
        Set<Integer> used = new HashSet<>();
        for (DungeonStore.GateRef o : store.gates) {
            if (Spire.isGate(o.floor) && o != g) {
                used.add((o.floor - Spire.GATE_BASE) / 6);
            }
            for (int f : o.instances.values()) {
                used.add((f - Spire.GATE_BASE) / 6);
            }
        }
        if (Spire.isGate(g.floor)) {
            used.add((g.floor - Spire.GATE_BASE) / 6);
        }
        int slot = (int) Math.floorMod(Spire.mix(g.pos.asLong(), g.created + g.instances.size(), 11L + g.uses), (long) Spire.GATE_SLOTS);
        for (int i = 0; i < Spire.GATE_SLOTS && used.contains(slot); i++) {
            slot = (slot + 1) % Spire.GATE_SLOTS;
        }
        return used.contains(slot) ? g.floor : Spire.gateIndex(slot, rank);
    }

    /** The floor holding this owner's copy of a gate dungeon; a new copy is rebuilt from scratch. */
    static int instanceFloor(DungeonStore store, DungeonStore.GateRef g, UUID key, GateRank rank) {
        Integer existing = g.instances.get(key);
        if (existing != null) {
            return existing;
        }
        int floor = g.instances.containsValue(g.floor) ? freeGateIndex(store, g, rank) : g.floor;
        DungeonStore.FloorState fs = store.floors.get(floor);
        if (fs != null) {
            // The floor still holds an old run's layout, so it must be rebuilt (and cleared) before use.
            fs.built = false;
            fs.populatedAt = 0;
            fs.cleared = false;
            fs.bossDead = false;
            fs.collapseAt = 0;
            fs.kills = 0;
            fs.totalMobs = 0;
        }
        g.instances.put(key, floor);
        store.setDirty();
        return floor;
    }

    /** Releases the dungeon copies of finished or abandoned runs so their floor slots can be reused. */
    private static void freeInstances(DungeonStore store, Set<Integer> occupied, long now) {
        for (DungeonStore.GateRef g : store.gates) {
            if (g.instances.isEmpty()) {
                continue;
            }
            boolean changed = false;
            var it = g.instances.entrySet().iterator();
            while (it.hasNext()) {
                int f = it.next().getValue();
                if (occupied.contains(f) || WorkQueue.find("floor:" + f) != null) {
                    INSTANCE_SEEN.put(f, now);
                    continue;
                }
                long idle = now - INSTANCE_SEEN.computeIfAbsent(f, k -> now);
                DungeonStore.FloorState fs = store.floors.get(f);
                if (fs != null && fs.built && !fs.bossDead) {
                    continue; // an unfinished gate dungeon stays exactly as it is until its guardian falls
                }
                long limit = fs != null && fs.bossDead ? 1200 : DungeonConfig.gateInstanceIdleTicks;
                if (idle >= limit) {
                    it.remove();
                    INSTANCE_SEEN.remove(f);
                    changed = true;
                    if (Spire.isGate(f) && WorkQueue.find("wipe:" + f) == null) {
                        // The run is over: fill the gate floor back to bedrock. A later run rebuilds it from scratch.
                        if (fs != null) {
                            fs.built = false;
                        }
                        WorkQueue.enqueue(new GateWipeJob(f));
                    }
                }
            }
            if (changed) {
                store.setDirty();
            }
        }
    }

    /** Gives an item, dropping it if the inventory is full. */
    private static void giveItem(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.spawnAtLocation(player.level(), stack);
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Per-tick driver: work queue, gate pull, maintenance and respawns. */
    private static void tick(MinecraftServer server) {
        WorkQueue.tick(server);
        int tc = server.getTickCount();
        DungeonStore store = DungeonStore.get(server);
        GatePull.tick(server, store);
        if (tc % 100 == 3) {
            TowerHall.ensure(server, store);
        }
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
        if (!PROTECTED.isEmpty()) {
            long nowT = now(server);
            for (var it = PROTECTED.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
                long left = en.getValue() - nowT;
                if (p == null) {
                    it.remove();
                } else if (left <= 0) {
                    p.removeEffect(MobEffects.RESISTANCE);
                    Compat.overlay(p, Component.literal("GO!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                    p.level().playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), SoundSource.PLAYERS, 0.6f, 1.4f);
                    it.remove();
                } else {
                    Compat.overlay(p, Component.literal("Spawn protection: " + (left + 19) / 20 + "s").withStyle(ChatFormatting.AQUA));
                }
            }
        }
        maintainGates(server, store);
        GateLeak.tick(server, store, now(server));
        ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
        if (dungeon != null) {
            maintainDungeon(server, dungeon, store, tc);
        }
        if (DungeonConfig.naturalSpawn && tc % Math.max(20, DungeonConfig.spawnCheckTicks) == 0) {
            rollNaturalGate(server, store);
        }
    }

    /** The single shared instance key of a gate dungeon. */
    private static final UUID SHARED = new UUID(0L, 0L);
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();
    private static final Map<String, BlockPos> PARTY_RIFT = new HashMap<>();
    private static final Map<Integer, Integer> LAST_RIFT = new HashMap<>();

    /** Where a gate arrival lands: a random rift (not the one used last), or the rift the player's party already uses. */
    private static BlockPos riftFor(ServerPlayer player, DungeonStore.FloorState fs) {
        if (fs.rifts.isEmpty()) {
            return fs.downArrive;
        }
        Parties.Party party = Parties.of(player);
        String key = party == null ? null : fs.floor + ":" + party.id;
        if (key != null && PARTY_RIFT.containsKey(key)) {
            return PARTY_RIFT.get(key);
        }
        int n = fs.rifts.size();
        int i = player.getRandom().nextInt(n);
        if (n > 1 && i == LAST_RIFT.getOrDefault(fs.floor, -1)) {
            i = (i + 1 + player.getRandom().nextInt(n - 1)) % n;
        }
        LAST_RIFT.put(fs.floor, i);
        BlockPos pos = fs.rifts.get(i);
        if (key != null) {
            PARTY_RIFT.put(key, pos);
        }
        return pos;
    }

    private static final Set<UUID> GATE_DEATHS = new HashSet<>();

    /** Places a player who fell: home after a gate death, otherwise their last rest. */
    private static void respawnAfterFall(ServerPlayer p, DungeonStore store) {
        if (GATE_DEATHS.remove(p.getUUID())) {
            sendHome(p);
            return;
        }
        int rest = store.progressOf(p.getUUID()).lastRest;
        if (DungeonConfig.deathRespawnMode == 1 && rest > 0 && store.floor(rest).built) {
            p.sendSystemMessage(Component.literal(PREFIX + "You wake at the rest floor.").withStyle(ChatFormatting.GREEN));
            travelFloor(p, rest, WorkQueue.Dest.TOP);
        } else {
            exitTower(p);
        }
    }

    /** Periodic upkeep of the dungeon floors. */
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
        for (DungeonStore.FloorState fs : new ArrayList<>(store.floors.values())) {
            if (fs.collapseAt > 0 && Spire.isGate(fs.floor)) {
                GateCollapse.tick(dungeon, fs, Spire.rank(fs.floor, total), now);
                if (fs.collapseAt == 0) {
                    store.setDirty();
                }
            }
        }
        if (tc % 200 == 0) {
            for (int f : occupied) {
                checkClear(dungeon, store, f, total, now);
            }
        }
        if (tc % 200 == 50) {
            freeInstances(store, occupied, now);
        }
        // Multiplayer: an empty boss floor comes back on its own 15-20 minutes after the guardian fell.
        if (tc % 200 == 100 && server.getPlayerList().getPlayerCount() > 1) {
            for (DungeonStore.FloorState fs : new ArrayList<>(store.floors.values())) {
                if (fs.built && fs.bossDead && isBossFloor(fs.floor, total) && !occupied.contains(fs.floor)
                        && now - fs.clearedAt >= bossDelay(fs)) {
                    int level = 1;
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        level = Math.max(level, PlayerDataStore.of(p).level);
                    }
                    if (regenFloor(server, fs.floor, level, null)) {
                        Gatebound.LOGGER.info("Boss floor {} reset after {} ticks", fs.floor, now - fs.clearedAt);
                    }
                }
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
        placeGate(level, pos, rank, f);
    }

    /** Places a gate at the given position with its front facing the given direction. */
    public static void placeGate(ServerLevel level, BlockPos pos, GateRank rank, Direction facing) {
        level.setBlock(pos, DungeonRegistries.GATE.get().defaultBlockState()
                .setValue(GateBlock.RANK, rank).setValue(GateBlock.FACING, facing), 3);
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
        // The gate's front faces back at the player, so its pane stands across the way they are looking.
        placeGate(level, pos, rank, player.getDirection().getOpposite());
        store.gates.removeIf(g -> g.dim.equals(level.dimension()) && g.pos.equals(pos));
        floor = newGate(store, level.dimension(), pos, rank, false, now(server)).floor;
        store.setDirty();
        return floor;
    }

    /** Periodic upkeep of gates. */
    private static void maintainGates(MinecraftServer server, DungeonStore store) {
        long now = now(server);
        boolean changed = false;
        for (DungeonStore.GateRef g : new ArrayList<>(store.gates)) {
            ServerLevel l = server.getLevel(g.dim);
            if (l == null || !l.hasChunkAt(g.pos)) {
                continue;
            }
            boolean exists = l.getBlockState(g.pos).getBlock() instanceof GateBlock;
            boolean expired = g.cleared;
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
                l.playSound(null, g.pos, GateboundSounds.of(SoundEvents.BEACON_DEACTIVATE), SoundSource.AMBIENT, 1.0f, 0.7f);
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

    /** Rolls a chance for a natural gate to appear near each player. */
    private static void rollNaturalGate(MinecraftServer server, DungeonStore store) {
        ServerLevel level = server.overworld();
        List<ServerPlayer> players = level.players();
        for (ServerPlayer player : players) {
            int natural = 0;
            for (DungeonStore.GateRef g : store.gates) {
                if (g.natural) {
                    natural++;
                }
            }
            if (natural >= DungeonConfig.maxNaturalGates) {
                return;
            }
            rollGateNear(server, store, level, player);
        }
    }

    /** One spawn roll for a single player: a village gate if one is loaded nearby, otherwise open ground around them. */
    private static void rollGateNear(MinecraftServer server, DungeonStore store, ServerLevel level, ServerPlayer player) {
        RandomSource rng = level.getRandom();
        if (rng.nextDouble() >= DungeonConfig.spawnChance) {
            return;
        }
        for (DungeonStore.GateRef g : store.gates) {
            if (g.natural && g.dim.equals(level.dimension())) {
                double dx = g.pos.getX() - player.getX();
                double dz = g.pos.getZ() - player.getZ();
                if (dx * dx + dz * dz < (double) DungeonConfig.perPlayerRadius * DungeonConfig.perPlayerRadius) {
                    return;
                }
            }
        }

        if (rng.nextDouble() < DungeonConfig.villageGateChance && tryVillageGate(server, store, level, player, rng)) {
            return;
        }

        int span = Math.max(1, DungeonConfig.spawnMaxDistance - DungeonConfig.spawnMinDistance);
        for (int attempt = 0; attempt < 12; attempt++) {
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
            floor = newGate(store, level.dimension(), pos, rank, true, now(server)).floor;
            store.setDirty();
            player.sendSystemMessage(Component.literal(PREFIX + "A gate has appeared...").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            player.level().playSound(null, pos, GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), SoundSource.AMBIENT, 1.5f, 0.6f);
            Gatebound.LOGGER.info("Natural {} gate (floor {}) spawned at {} near {}", rank, floor, pos, player.getName().getString());
            return;
        }
    }

    /** Op command: places a gate in the nearest village right now. Returns false if no village is in range. */
    public static boolean spawnVillageGate(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return tryVillageGate(server, DungeonStore.get(server), player.level(), player, player.getRandom());
    }

    /** Places a natural gate in the nearest village: on open ground a short walk from its centre. */
    private static boolean tryVillageGate(MinecraftServer server, DungeonStore store, ServerLevel level, ServerPlayer player,
                                          RandomSource rng) {
        BlockPos village = level.findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, player.blockPosition(),
                Math.max(1, DungeonConfig.villageSearchChunks), false);
        if (village == null) {
            return false;
        }
        for (DungeonStore.GateRef g : store.gates) {
            if (g.dim.equals(level.dimension())) {
                double dx = g.pos.getX() - village.getX();
                double dz = g.pos.getZ() - village.getZ();
                if (dx * dx + dz * dz < 48 * 48) {
                    return false; // that village already has a gate
                }
            }
        }
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double dist = 8 + rng.nextInt(22);
            int x = village.getX() + (int) Math.floor(Math.cos(ang) * dist);
            int z = village.getZ() + (int) Math.floor(Math.sin(ang) * dist);
            BlockPos column = new BlockPos(x, level.getSeaLevel(), z);
            if (!level.hasChunkAt(column)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!validGateSpot(level, pos, true)) {
                continue;
            }
            int total = store.ensureTower(server);
            GateRank rank = pickRank(rng);
            int floor = Spire.pickFloor(rank, total, rng);
            placeGate(level, pos, rank, village.getX() + 0.5, village.getZ() + 0.5);
            floor = newGate(store, level.dimension(), pos, rank, true, now(server)).floor;
            store.setDirty();
            player.sendSystemMessage(Component.literal(PREFIX + "A gate has opened near a village...").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            level.playSound(null, pos, GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), SoundSource.AMBIENT, 1.5f, 0.6f);
            Gatebound.LOGGER.info("Village {} gate (floor {}) spawned at {} (village at {})", rank, floor, pos, village);
            return true;
        }
        return false;
    }

    /** True if the spot suits a gate (natural ground only). */
    private static boolean validGateSpot(ServerLevel level, BlockPos pos) {
        return validGateSpot(level, pos, false);
    }

    /** True if the spot suits a gate, optionally allowing structures. */
    private static boolean validGateSpot(ServerLevel level, BlockPos pos, boolean allowStructures) {
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        boolean naturalGround = ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(BlockTags.TERRACOTTA)
                || ground.is(BlockTags.BASE_STONE_OVERWORLD) || ground.is(BlockTags.SNOW)
                || (allowStructures && (ground.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                || ground.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || ground.is(BlockTags.PLANKS)));
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
        return allowStructures || !level.structureManager().getStructureWithPieceAt(pos, h -> true).isValid();
    }

    /** Picks a gate rank by the configured weights. */
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
        FloorJob job = new FloorJob(floor, !fs.upArrive.equals(BlockPos.ZERO), false, level);
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

    /** Test hook: clears portal cooldowns. */
    static void testClearCooldowns() {
        COOLDOWNS.clear();
    }

    /** Test hook: runs boss-death handling on a floor. */
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
        if (!PortalPlacer.exists(d, fs.downArrive.west(2), Direction.WEST)) {
            missingPortals++;
        }
        Spire.Kind kind = Spire.kind(floor, total);
        boolean upExpected = kind == Spire.Kind.NORMAL || kind == Spire.Kind.REST;
        if (upExpected && !PortalPlacer.exists(d, fs.upArrive.east(2), Direction.EAST)) {
            missingPortals++;
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

    /** Runs the staged self test. */
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
                            f, a.kind, a.theme.label(), open, a.decos.size(), a.mobSpots.size(), a.chests.size(), 0);
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
