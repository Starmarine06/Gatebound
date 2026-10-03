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
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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

/** Orchestrates gate runs: creation, scaling, kill tracking, clearing, rewards, leaving, cleanup, natural gates. */
public final class DungeonManager {
    public static final String TAG_MOB = "gatebound_mob";
    public static final String TAG_BOSS = "gatebound_boss";
    private static final String PREFIX = "[Gatebound] ";

    /** In-memory only; all of this is safe to lose on restart (see join handling). */
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
    private static final Set<UUID> PENDING_HOME = new HashSet<>();
    private static final Set<UUID> FAILED = new HashSet<>();
    private static final Map<Integer, Long> CLEANING = new HashMap<>();

    private DungeonManager() {
    }

    public static void init() {
        DungeonRegistries.init();

        TickEvent.SERVER_POST.register(DungeonManager::tick);
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            COOLDOWNS.clear();
            PENDING_HOME.clear();
            FAILED.clear();
            CLEANING.clear();
        });

        PlayerEvent.PLAYER_JOIN.register(player -> {
            // A player who logs in inside a dungeon whose run no longer exists is sent home.
            if (player.level().dimension().equals(DungeonRegistries.DUNGEON)) {
                DungeonStore store = DungeonStore.get(player.level().getServer());
                if (!store.runs.containsKey(DungeonBuilder.plotOf(player.getX()))) {
                    PENDING_HOME.add(player.getUUID());
                }
            }
        });
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> {
            if (FAILED.remove(player.getUUID())) {
                PENDING_HOME.add(player.getUUID());
            }
        });
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (!entity.level().isClientSide() && entity.level().dimension().equals(DungeonRegistries.DUNGEON)) {
                if (entity instanceof ServerPlayer sp) {
                    FAILED.add(sp.getUUID());
                    sp.sendSystemMessage(Component.literal(PREFIX + "The gate run has failed. You will be returned when you respawn.")
                            .withStyle(ChatFormatting.RED));
                } else if (entity instanceof Mob && entity.level() instanceof ServerLevel level) {
                    onMobDeath(level, entity);
                }
            }
            return EventResult.pass();
        });
        // Dungeon walls belong to the dungeon: survival players cannot dig out or build in.
        BlockEvent.BREAK.register((level, pos, state, player) -> {
            if (level.dimension().equals(DungeonRegistries.DUNGEON) && player != null && !player.isCreative()) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
        BlockEvent.PLACE.register((level, pos, state, entity) -> {
            if (level.dimension().equals(DungeonRegistries.DUNGEON) && entity instanceof Player p && !p.isCreative()) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
    }

    // ------------------------------------------------------------------ portals

    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    public static boolean portalReady(ServerPlayer player) {
        return now(player.level().getServer()) >= COOLDOWNS.getOrDefault(player.getUUID(), 0L);
    }

    public static void markPortalUsed(ServerPlayer player) {
        COOLDOWNS.put(player.getUUID(), now(player.level().getServer()) + DungeonConfig.portalCooldownTicks);
    }

    /** Starts (or joins) the run belonging to the gate at pos, then teleports the player into the dungeon. */
    public static void enterGate(ServerPlayer player, BlockPos gatePos, GateRank rank) {
        if (!portalReady(player)) {
            return;
        }
        ServerLevel from = player.level();
        MinecraftServer server = from.getServer();
        ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
        if (dungeon == null) {
            player.sendSystemMessage(Component.literal(PREFIX + "The dungeon dimension is not loaded.").withStyle(ChatFormatting.RED));
            return;
        }
        if (from.dimension().equals(DungeonRegistries.DUNGEON)) {
            return;
        }
        DungeonStore store = DungeonStore.get(server);
        DungeonRun run = store.runByGate(from.dimension(), gatePos);
        if (run != null && run.cleared) {
            player.sendSystemMessage(Component.literal(PREFIX + "This gate is closing...").withStyle(ChatFormatting.GRAY));
            return;
        }
        boolean fresh = run == null;
        if (fresh) {
            run = createRun(player, dungeon, store, rank, from, gatePos);
        }

        // Return a little in front of the gate so walking out does not immediately re-trigger it.
        double rx = player.getX();
        double rz = player.getZ();
        double dx = rx - (gatePos.getX() + 0.5);
        double dz = rz - (gatePos.getZ() + 0.5);
        if (Math.abs(player.getY() - gatePos.getY()) < 2.0 && dx * dx + dz * dz < 1.0) {
            BlockState st = from.getBlockState(gatePos);
            Direction f = st.getBlock() instanceof GateBlock ? st.getValue(GateBlock.FACING) : Direction.SOUTH;
            rx = gatePos.getX() + 0.5 + f.getStepX() * 1.6;
            rz = gatePos.getZ() + 0.5 + f.getStepZ() * 1.6;
        }
        store.returns.put(player.getUUID(), new DungeonStore.Return(player.getUUID(), from.dimension(),
                rx, player.getY(), rz, player.getYRot()));
        if (!run.members.contains(player.getUUID())) {
            run.members.add(player.getUUID());
        }
        run.lastInside = now(server);
        store.setDirty();

        markPortalUsed(player);
        player.teleportTo(dungeon, run.spawn.getX() + 0.5, run.spawn.getY(), run.spawn.getZ() + 0.5,
                Set.of(), -90f, 0f, true);
        GateRank r = run.gateRank();
        player.sendSystemMessage(Component.literal(PREFIX + "You step through the gate. " + r.label() + " dungeon - "
                + run.totalMobs + " monsters and a guardian await.").withStyle(r.color()));
    }

    private static DungeonRun createRun(ServerPlayer player, ServerLevel dungeon, DungeonStore store, GateRank rank,
                                        ServerLevel gateLevel, BlockPos gatePos) {
        MinecraftServer server = dungeon.getServer();
        int plot = store.allocatePlot();
        RandomSource rng = RandomSource.create();
        DungeonBuilder.Layout layout = DungeonBuilder.build(dungeon, plot, rank, rng);

        int playerLevel = PlayerDataStore.of(player).level;
        int r = rank.ordinal();
        double hpMult = (1.0 + 0.4 * r) * (1.0 + 0.03 * playerLevel);
        double dmgMult = 1.0 + 0.12 * r;
        int mobCount = 6 + 3 * r + playerLevel / 10;

        List<EntityType<? extends Mob>> pool = rank.mobPool();
        int spawned = 0;
        for (int i = 0; i < mobCount && !layout.mobRooms().isEmpty(); i++) {
            int[] room = layout.mobRooms().get(i % layout.mobRooms().size());
            int x = room[0] + rng.nextInt(room[2] - room[0] + 1);
            int z = room[1] + rng.nextInt(room[3] - room[1] + 1);
            BlockPos pos = new BlockPos(x, DungeonBuilder.FLOOR_Y + 1, z);
            Mob mob = pool.get(rng.nextInt(pool.size())).spawn(dungeon, pos, EntitySpawnReason.EVENT);
            if (mob == null) {
                continue;
            }
            scale(mob, hpMult, dmgMult, 1.0);
            mob.addTag(TAG_MOB);
            mob.setPersistenceRequired();
            spawned++;
        }

        Mob boss = rank.bossType().spawn(dungeon, layout.boss(), EntitySpawnReason.EVENT);
        if (boss != null) {
            double bossHp = 80.0 * (1.0 + 0.9 * r) * (1.0 + 0.04 * playerLevel);
            setMaxHealth(boss, bossHp);
            scaleDamage(boss, 1.0 + 0.2 * r);
            AttributeInstance size = boss.getAttribute(Attributes.SCALE);
            if (size != null) {
                size.setBaseValue(rank.bossScale());
            }
            boss.addTag(TAG_BOSS);
            boss.addTag(TAG_MOB);
            boss.setPersistenceRequired();
            boss.setCustomName(Component.literal("Gate Guardian (" + rank.label() + ")").withStyle(rank.color()));
            boss.setCustomNameVisible(true);
            boss.setGlowingTag(true);
        } else {
            Gatebound.LOGGER.warn("Gate boss failed to spawn for plot {}", plot);
        }

        long t = now(server);
        DungeonRun run = new DungeonRun(plot, r, gateLevel.dimension(), gatePos, layout.spawn(), layout.boss(),
                layout.exit(), spawned, 0, false, t, 0L, t, List.of());
        store.runs.put(plot, run);
        store.setDirty();
        Gatebound.LOGGER.info("Gate run created: plot {} rank {} mobs {} playerLevel {}", plot, rank, spawned, playerLevel);
        return run;
    }

    private static void scale(Mob mob, double hpMult, double dmgMult, double unused) {
        setMaxHealth(mob, mob.getMaxHealth() * hpMult);
        scaleDamage(mob, dmgMult);
    }

    private static void setMaxHealth(Mob mob, double value) {
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(value);
            mob.setHealth((float) value);
        }
    }

    private static void scaleDamage(Mob mob, double mult) {
        AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.setBaseValue(dmg.getBaseValue() * mult);
        }
    }

    // ------------------------------------------------------------------ leaving

    /** Teleports a player back to where they entered (or world spawn if that is unknown). */
    public static void sendHome(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        DungeonStore store = DungeonStore.get(server);
        DungeonStore.Return ret = store.returns.remove(player.getUUID());
        store.setDirty();
        markPortalUsed(player);
        ServerLevel target = ret == null ? null : server.getLevel(ret.dim());
        if (target == null) {
            player.teleport(TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING));
        } else {
            player.teleportTo(target, ret.x(), ret.y(), ret.z(), Set.of(), ret.yaw(), 0f, true);
        }
        player.sendSystemMessage(Component.literal(PREFIX + "You return from the gate.").withStyle(ChatFormatting.GRAY));
    }

    /** /gatebound gate leave */
    public static boolean leave(ServerPlayer player) {
        if (!player.level().dimension().equals(DungeonRegistries.DUNGEON)) {
            return false;
        }
        sendHome(player);
        return true;
    }

    // ------------------------------------------------------------------ kills and clear

    private static void onMobDeath(ServerLevel level, Entity entity) {
        DungeonStore store = DungeonStore.get(level.getServer());
        DungeonRun run = store.runs.get(DungeonBuilder.plotOf(entity.getX()));
        if (run == null || run.cleared) {
            return;
        }
        if (entity.entityTags().contains(TAG_BOSS)) {
            onCleared(level, store, run);
        } else if (entity.entityTags().contains(TAG_MOB)) {
            run.kills++;
            store.setDirty();
            Component msg = Component.literal("Monsters slain: " + run.kills + "/" + run.totalMobs)
                    .withStyle(run.gateRank().color());
            for (ServerPlayer p : playersInside(level, run)) {
                p.sendOverlayMessage(msg);
            }
        }
    }

    private static List<ServerPlayer> playersInside(ServerLevel dungeon, DungeonRun run) {
        return dungeon.getPlayers(p -> DungeonBuilder.plotOf(p.getX()) == run.plot);
    }

    private static void onCleared(ServerLevel dungeon, DungeonStore store, DungeonRun run) {
        MinecraftServer server = dungeon.getServer();
        GateRank rank = run.gateRank();
        run.cleared = true;
        run.clearedAt = now(server);
        store.setDirty();

        // Exit portal and rank-scaled loot chests in the boss room.
        BlockPos exit = run.exit;
        dungeon.setBlock(exit, DungeonRegistries.GATE_EXIT.get().defaultBlockState(), 3);
        List<BlockPos> chests = new ArrayList<>();
        chests.add(exit.offset(3, 0, -4));
        chests.add(exit.offset(3, 0, 4));
        if (rank.ordinal() >= GateRank.B.ordinal()) {
            chests.add(exit.offset(3, 0, 0));
        }
        fillChests(dungeon, chests, rank, dungeon.getRandom());

        // Reward everyone still in the dungeon.
        for (ServerPlayer p : playersInside(dungeon, run)) {
            p.sendSystemMessage(Component.literal(PREFIX + rank.label() + " gate cleared! The Gate Guardian has fallen.")
                    .withStyle(rank.color(), ChatFormatting.BOLD));
            p.sendSystemMessage(Component.literal(PREFIX + "Loot chests and an exit portal have appeared in the arena.")
                    .withStyle(ChatFormatting.GRAY));
            p.sendOverlayMessage(Component.literal("GATE CLEARED").withStyle(rank.color(), ChatFormatting.BOLD));
            p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
            Progression.giveXp(p, rank.xpReward());
        }

        // The gate that led here closes.
        ServerLevel gateLevel = server.getLevel(run.gateDim);
        if (gateLevel != null && gateLevel.getBlockState(run.gatePos).getBlock() instanceof GateBlock) {
            gateLevel.removeBlock(run.gatePos, false);
        }
        store.naturalGates.removeIf(g -> g.dim().equals(run.gateDim) && g.pos().equals(run.gatePos));
    }

    private static void fillChests(ServerLevel level, List<BlockPos> chestPositions, GateRank rank, RandomSource rng) {
        int r = rank.ordinal();
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Items.IRON_INGOT, 6 + 3 * r + rng.nextInt(5)));
        loot.add(new ItemStack(Items.COOKED_BEEF, 8 + 2 * r));
        loot.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 3 + 3 * r));
        loot.add(new ItemStack(Items.EMERALD, 2 + 2 * r + rng.nextInt(3)));
        if (r >= 1) {
            loot.add(new ItemStack(Items.GOLD_INGOT, 3 + 2 * r));
            loot.add(new ItemStack(Items.DIAMOND, r + rng.nextInt(2)));
            loot.add(new ItemStack(Items.GOLDEN_APPLE, 1 + r / 2));
        }
        if (r >= 3) {
            loot.add(new ItemStack(Items.NETHERITE_SCRAP, r - 2));
            loot.add(new ItemStack(Items.DIAMOND, 2 + rng.nextInt(3)));
        }
        if (r >= 5) {
            loot.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
        }
        if (rng.nextDouble() < 0.03 + 0.12 * r) {
            loot.add(new ItemStack(GateboundRegistries.AWAKENING_STONE.get(), 1));
        }

        List<List<Integer>> free = new ArrayList<>();
        for (BlockPos pos : chestPositions) {
            level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST), 3);
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < 27; i++) {
                slots.add(i);
            }
            java.util.Collections.shuffle(slots, new java.util.Random(rng.nextLong()));
            free.add(slots);
        }
        int chest = 0;
        for (ItemStack stack : loot) {
            for (int tries = 0; tries < chestPositions.size(); tries++) {
                int idx = (chest + tries) % chestPositions.size();
                List<Integer> slots = free.get(idx);
                if (!slots.isEmpty() && level.getBlockEntity(chestPositions.get(idx)) instanceof ChestBlockEntity c) {
                    c.setItem(slots.remove(slots.size() - 1), stack);
                    break;
                }
            }
            chest++;
        }
    }

    // ------------------------------------------------------------------ maintenance

    private static void tick(MinecraftServer server) {
        if (!PENDING_HOME.isEmpty()) {
            for (UUID id : new ArrayList<>(PENDING_HOME)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) {
                    PENDING_HOME.remove(id);
                    // Only players still stranded (dimension check) or respawned from a failed run get moved.
                    sendHome(p);
                }
            }
        }
        int tc = server.getTickCount();
        if (tc % 20 != 0) {
            return;
        }
        ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
        if (dungeon != null) {
            maintainRuns(server, dungeon);
            cleanPlots(server, dungeon);
        }
        if (DungeonConfig.naturalSpawn && tc % Math.max(20, DungeonConfig.spawnCheckTicks) == 0) {
            rollNaturalGate(server);
        }
    }

    private static void maintainRuns(MinecraftServer server, ServerLevel dungeon) {
        DungeonStore store = DungeonStore.get(server);
        long now = now(server);
        for (DungeonRun run : new ArrayList<>(store.runs.values())) {
            boolean inside = !playersInside(dungeon, run).isEmpty();
            if (inside) {
                run.lastInside = now;
            }
            boolean expire;
            if (now - run.created > DungeonConfig.runTimeoutTicks) {
                expire = true;
            } else if (run.cleared) {
                expire = (!inside && now - run.clearedAt > 100) || now - run.clearedAt > DungeonConfig.clearedLingerTicks;
            } else {
                expire = !inside && now - run.lastInside > DungeonConfig.abandonedTicks;
            }
            if (expire) {
                freeRun(server, dungeon, store, run);
            }
        }
    }

    private static void freeRun(MinecraftServer server, ServerLevel dungeon, DungeonStore store, DungeonRun run) {
        for (ServerPlayer p : playersInside(dungeon, run)) {
            sendHome(p);
        }
        store.runs.remove(run.plot);
        store.dirtyPlots.add(run.plot);
        store.setDirty();
        Gatebound.LOGGER.info("Gate run on plot {} closed (cleared={})", run.plot, run.cleared);
    }

    /** Two-phase plot reset: force-load, wait for entities to load, then discard entities and clear blocks. */
    private static void cleanPlots(MinecraftServer server, ServerLevel dungeon) {
        DungeonStore store = DungeonStore.get(server);
        long now = now(server);
        for (int plot : new ArrayList<>(store.dirtyPlots)) {
            Long start = CLEANING.get(plot);
            if (start == null) {
                forceChunks(dungeon, plot, true);
                CLEANING.put(plot, now);
            } else if (now - start >= 40) {
                AABB box = DungeonBuilder.bounds(plot);
                for (Entity e : dungeon.getEntitiesOfClass(Entity.class, box)) {
                    if (e instanceof ServerPlayer sp) {
                        sendHome(sp);
                    } else {
                        e.discard();
                    }
                }
                DungeonBuilder.clear(dungeon, plot);
                forceChunks(dungeon, plot, false);
                CLEANING.remove(plot);
                store.dirtyPlots.remove(plot);
                store.setDirty();
            }
        }
    }

    private static void forceChunks(ServerLevel level, int plot, boolean force) {
        AABB b = DungeonBuilder.bounds(plot);
        for (int cx = (int) b.minX >> 4; cx <= (int) b.maxX >> 4; cx++) {
            for (int cz = (int) b.minZ >> 4; cz <= (int) b.maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, force);
            }
        }
    }

    // ------------------------------------------------------------------ natural gates

    /** Places a gate at the given position facing the given player; returns false if the spot is unusable. */
    public static void placeGate(ServerLevel level, BlockPos pos, GateRank rank, double towardX, double towardZ) {
        double dx = towardX - (pos.getX() + 0.5);
        double dz = towardZ - (pos.getZ() + 0.5);
        Direction f = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        level.setBlock(pos, DungeonRegistries.GATE.get().defaultBlockState()
                .setValue(GateBlock.RANK, rank).setValue(GateBlock.FACING, f), 3);
    }

    private static void rollNaturalGate(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        DungeonStore store = DungeonStore.get(server);
        store.naturalGates.removeIf(g -> {
            ServerLevel l = server.getLevel(g.dim());
            return l != null && l.hasChunkAt(g.pos()) && !(l.getBlockState(g.pos()).getBlock() instanceof GateBlock);
        });
        if (store.naturalGates.size() >= DungeonConfig.maxNaturalGates) {
            return;
        }
        RandomSource rng = level.getRandom();
        if (rng.nextDouble() >= DungeonConfig.spawnChance) {
            return;
        }
        ServerPlayer player = players.get(rng.nextInt(players.size()));
        for (DungeonStore.GateRef g : store.naturalGates) {
            if (g.dim().equals(level.dimension())) {
                double dx = g.pos().getX() - player.getX();
                double dz = g.pos().getZ() - player.getZ();
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
            GateRank rank = pickRank(rng);
            placeGate(level, pos, rank, player.getX(), player.getZ());
            store.naturalGates.add(new DungeonStore.GateRef(level.dimension(), pos));
            store.setDirty();
            player.sendSystemMessage(Component.literal(PREFIX + "A gate has appeared...").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
            player.level().playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.AMBIENT, 1.5f, 0.6f);
            Gatebound.LOGGER.info("Natural {} gate spawned at {} near {}", rank, pos, player.getName().getString());
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
        return !level.structureManager().getStructureWithPieceAt(pos, h -> true).isValid();
    }

    private static GateRank pickRank(RandomSource rng) {
        int total = 0;
        for (int w : DungeonConfig.rankWeights) {
            total += Math.max(0, w);
        }
        int roll = rng.nextInt(Math.max(1, total));
        for (int i = 0; i < GateRank.values().length; i++) {
            roll -= Math.max(0, DungeonConfig.rankWeights[i]);
            if (roll < 0) {
                return GateRank.byIndex(i);
            }
        }
        return GateRank.E;
    }
}
