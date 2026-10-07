package dev.gatebound.echo;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import dev.gatebound.Gatebound;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Progression;
import dev.gatebound.data.Stat;
import dev.gatebound.skill.ManaState;
import dev.gatebound.skill.SkillHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.entity.ai.goal.RestrictSunGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Echoes: defeated mobs bound as allies by Summoners. Server-authoritative.
 * Extraction is automatic: when a Summoner kills a hostile mob there is a chance (Intellect, level, config) that it is bound.
 * The roster is saved in PlayerData; live entities are never persisted (they are respawned from the roster).
 */
public final class EchoManager {
    public static final String TAG = "gatebound_echo";
    private static final String TEAM = "gatebound_echo";
    public static final String SUMMONER = "summoner";

    /** Mobs that would grief, wander off, split into untracked children or summon untracked helpers. */
    private static final Set<String> BLOCKED = Set.of("wither", "ender_dragon");

    /** Slots of echoes that were out when their owner died; they come back free on respawn. */
    private static final Map<UUID, List<Integer>> DIED_WITH = new HashMap<>();

    public static final class Info {
        public final UUID owner;
        public final int slot;
        public double gx, gy, gz;

        Info(UUID owner, int slot, double x, double y, double z) {
            this.owner = owner;
            this.slot = slot;
            this.gx = x;
            this.gy = y;
            this.gz = z;
        }
    }

    /** Live echo entity UUID -> info. */
    private static final Map<UUID, Info> ACTIVE = new HashMap<>();

    /** Roster slot of spectral copies (Legion Call): they are not on the roster and never come back. */
    static final int TEMP_SLOT = -1;

    private EchoManager() {
    }

    public static void init() {
        EntityEvent.ADD.register((entity, level) -> {
            // An echo loaded from disk (or otherwise not created by us this session) is a leftover: remove it.
            if (!level.isClientSide() && entity.entityTags().contains(TAG) && !ACTIVE.containsKey(entity.getUUID())) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
        EntityEvent.LIVING_HURT.register(EchoManager::onHurt);
        EntityEvent.LIVING_DEATH.register(EchoManager::onDeath);
        TickEvent.SERVER_POST.register(server -> tick(server.getTickCount(), server));
        PlayerEvent.PLAYER_QUIT.register(player -> {
            dismissAll(player, false);
            FOUND.remove(player.getUUID());
            RALLY.remove(player.getUUID());
            SELECTED.remove(player.getUUID());
        });
        PlayerEvent.PLAYER_RESPAWN.register((player, end, reason) -> {
            dismissAll(player, false);
            List<Integer> slots = DIED_WITH.remove(player.getUUID());
            if (slots != null) {
                PlayerData data = PlayerDataStore.of(player);
                for (int slot : slots) {
                    if (slot < data.echo.entries.size() && slot < slots(data)) {
                        spawn(player, data, slot);
                    }
                }
            }
        });
        EchoNetwork.init();
        EchoView.init();
        EchoCommands.init();
        EnvExecutor.runInEnv(Env.CLIENT, () -> EchoClient::init);
    }

    // ---------------------------------------------------------------- queries

    public static Info info(Entity entity) {
        return ACTIVE.get(entity.getUUID());
    }

    public static boolean isEcho(Entity entity) {
        return ACTIVE.containsKey(entity.getUUID());
    }

    /** The order the echo in the given roster slot currently follows (its group's order). */
    public static int modeOf(ServerPlayer owner, int slot) {
        EchoRoster roster = PlayerDataStore.of(owner).echo;
        if (slot < 0 || slot >= roster.entries.size()) {
            return EchoRoster.GUARD;
        }
        return roster.groupModes[Math.max(0, Math.min(EchoRoster.GROUPS - 1, roster.entries.get(slot).group()))];
    }

    /** The order an echo follows right now: its Scout's Eye order if the owner is viewing it, else its group's order. */
    public static int effectiveMode(ServerPlayer owner, java.util.UUID echoId, int slot) {
        int override = EchoView.orderFor(echoId);
        if (override >= 0) {
            return override;
        }
        int personal = personalOrder(owner, slot);
        return personal >= 0 ? personal : modeOf(owner, slot);
    }

    /** Gives every echo of one group an order (group -1 = all groups). */
    public static void setGroupMode(ServerPlayer owner, int group, int mode) {
        EchoRoster roster = PlayerDataStore.of(owner).echo;
        int m = Math.max(EchoRoster.GUARD, Math.min(EchoRoster.GUIDE, mode));
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            if (group < 0 || group == g) {
                roster.groupModes[g] = m;
            }
        }
        PlayerDataStore.markDirty(owner);
    }

    /** Moves one echo into a group. */
    public static void setEchoGroup(ServerPlayer owner, int slot, int group) {
        List<EchoRoster.Entry> entries = PlayerDataStore.of(owner).echo.entries;
        if (slot >= 0 && slot < entries.size()) {
            entries.set(slot, entries.get(slot).withGroup(group));
            PlayerDataStore.markDirty(owner);
        }
    }

    /** Spreads all echoes evenly over the groups, in roster order. */
    public static void evenSplit(ServerPlayer owner) {
        List<EchoRoster.Entry> entries = PlayerDataStore.of(owner).echo.entries;
        for (int i = 0; i < entries.size(); i++) {
            entries.set(i, entries.get(i).withGroup(i % EchoRoster.GROUPS));
        }
        PlayerDataStore.markDirty(owner);
    }

    /** Puts every echo into one group. */
    public static void allToGroup(ServerPlayer owner, int group) {
        List<EchoRoster.Entry> entries = PlayerDataStore.of(owner).echo.entries;
        for (int i = 0; i < entries.size(); i++) {
            entries.set(i, entries.get(i).withGroup(group));
        }
        PlayerDataStore.markDirty(owner);
    }

    /** Echoes of the same mob type share a group; types are dealt out to the groups in order of first appearance. */
    public static void groupByType(ServerPlayer owner) {
        List<EchoRoster.Entry> entries = PlayerDataStore.of(owner).echo.entries;
        List<String> types = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String t = entries.get(i).type();
            if (!types.contains(t)) {
                types.add(t);
            }
            entries.set(i, entries.get(i).withGroup(types.indexOf(t) % EchoRoster.GROUPS));
        }
        PlayerDataStore.markDirty(owner);
    }

    // ---------------------------------------------------------------- gates found by echoes

    /** A gate an echo has found: where it is and what it leads to. Kept in memory for the session. */
    public record Found(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                        net.minecraft.core.BlockPos pos, String label, String by) {
    }

    private static final Map<UUID, List<Found>> FOUND = new HashMap<>();
    private static final Map<UUID, Found> SELECTED = new HashMap<>();

    /** The gate the seekers are currently gathering at (the latest one found), per owner. */
    private static final Map<UUID, Found> RALLY = new HashMap<>();

    static void setRally(ServerPlayer owner, Found gate) {
        RALLY.put(owner.getUUID(), gate);
    }

    /** Where this echo should gather: the rally gate, if it is in the echo's dimension (and on its floor) and still stands. */
    static net.minecraft.core.BlockPos rallyPoint(ServerPlayer owner, EchoEntity e) {
        Found f = RALLY.get(owner.getUUID());
        if (f == null || !f.dim().equals(e.level().dimension())) {
            return null;
        }
        if (!foundGates(owner).contains(f)) {
            RALLY.remove(owner.getUUID());
            return null;
        }
        if (e.level().dimension().equals(dev.gatebound.dungeon.DungeonRegistries.DUNGEON)) {
            net.minecraft.server.MinecraftServer server = e.level().getServer();
            int total = dev.gatebound.dungeon.DungeonStore.get(server).ensureTower(server);
            if (dev.gatebound.dungeon.Spire.floorAt(f.pos().getX() + 0.5, f.pos().getZ() + 0.5, total)
                    != dev.gatebound.dungeon.Spire.floorAt(e.getX(), e.getZ(), total)) {
                return null;
            }
        }
        return f.pos();
    }

    public static void addFound(ServerPlayer owner, Found gate) {
        List<Found> list = FOUND.computeIfAbsent(owner.getUUID(), id -> new ArrayList<>());
        boolean known = list.stream().anyMatch(f -> f.dim().equals(gate.dim()) && f.pos().equals(gate.pos()));
        if (!known) {
            list.add(gate);
            if (!SELECTED.containsKey(owner.getUUID())) {
                SELECTED.put(owner.getUUID(), gate);
            }
        }
    }

    /** Found gates that still exist. */
    public static List<Found> foundGates(ServerPlayer owner) {
        List<Found> list = FOUND.getOrDefault(owner.getUUID(), List.of());
        List<Found> alive = new ArrayList<>();
        for (Found f : list) {
            ServerLevel level = owner.level().getServer().getLevel(f.dim());
            if (level != null && (!level.hasChunkAt(f.pos())
                    || level.getBlockState(f.pos()).getBlock() instanceof dev.gatebound.dungeon.GateBlock
                    || level.getBlockState(f.pos()).getBlock() instanceof dev.gatebound.dungeon.GateExitBlock)) {
                alive.add(f);
            }
        }
        FOUND.put(owner.getUUID(), new ArrayList<>(alive));
        return alive;
    }

    public static Found selectedGate(ServerPlayer owner) {
        Found sel = SELECTED.get(owner.getUUID());
        List<Found> alive = foundGates(owner);
        if (sel != null && alive.contains(sel)) {
            return sel;
        }
        Found fallback = alive.isEmpty() ? null : alive.get(0);
        if (fallback == null) {
            SELECTED.remove(owner.getUUID());
        } else {
            SELECTED.put(owner.getUUID(), fallback);
        }
        return fallback;
    }

    public static void selectGate(ServerPlayer owner, int index) {
        List<Found> alive = foundGates(owner);
        if (index >= 0 && index < alive.size()) {
            SELECTED.put(owner.getUUID(), alive.get(index));
        }
    }

    /** Sends the roster to the client; open = true also opens the Echoes screen. */
    public static void sendRoster(ServerPlayer owner, boolean open) {
        PlayerData data = PlayerDataStore.of(owner);
        List<EchoRosterPayload.Row> rows = new ArrayList<>();
        for (int i = 0; i < data.echo.entries.size(); i++) {
            EchoRoster.Entry en = data.echo.entries.get(i);
            rows.add(new EchoRosterPayload.Row(en.name(), en.type(), en.group(), isActive(owner.getUUID(), i), i < slots(data),
                    SELECTED_SLOT.getOrDefault(owner.getUUID(), -99) == i, personalOrder(owner, i)));
        }
        List<Integer> modes = new ArrayList<>();
        for (int m : data.echo.groupModes) {
            modes.add(m);
        }
        Found selected = selectedGate(owner);
        List<EchoRosterPayload.GateRow> gates = new ArrayList<>();
        for (Found f : foundGates(owner)) {
            int dist = f.dim().equals(owner.level().dimension())
                    ? (int) Math.sqrt(owner.distanceToSqr(f.pos().getX() + 0.5, f.pos().getY(), f.pos().getZ() + 0.5)) : -1;
            gates.add(new EchoRosterPayload.GateRow(f.label(), f.by(), dist, f.equals(selected)));
        }
        NetworkManager.sendToPlayer(owner, new EchoRosterPayload(open, isSummoner(data), rows, modes, gates, EchoView.viewingName(owner)));
    }

    public static boolean isSummoner(PlayerData data) {
        return SUMMONER.equals(data.classId);
    }

    public static int slots(PlayerData data) {
        return EchoConfig.slots(data.get(Stat.INTELLECT), data.echo.bonusSlots);
    }

    public static int activeCount(UUID owner) {
        int n = 0;
        for (Info i : ACTIVE.values()) {
            if (i.owner.equals(owner)) {
                n++;
            }
        }
        return n;
    }

    /** The owner's echoes that are currently out in the owner's dimension. */
    static List<EchoEntity> echoesOf(ServerPlayer owner) {
        List<EchoEntity> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Info> en : ACTIVE.entrySet()) {
            if (en.getValue().owner.equals(owner.getUUID())
                    && owner.level() instanceof ServerLevel level
                    && level.getEntity(en.getKey()) instanceof EchoEntity echo && echo.isAlive()) {
                out.add(echo);
            }
        }
        return out;
    }

    private static boolean isActive(UUID owner, int slot) {
        for (Info i : ACTIVE.values()) {
            if (i.owner.equals(owner) && i.slot == slot) {
                return true;
            }
        }
        return false;
    }

    public static boolean eligible(LivingEntity entity) {
        if (!(entity instanceof Mob) || entity instanceof EchoEntity || entity instanceof dev.gatebound.trade.GateboundNpc
                || isEcho(entity) || entity.entityTags().contains(TAG)) {
            return false;
        }
        Identifier key = EntityType.getKey(entity.getType());
        return key != null && !BLOCKED.contains(key.getPath());
    }

    // ---------------------------------------------------------------- extraction and xp

    private static EventResult onDeath(LivingEntity victim, DamageSource source) {
        if (victim.level().isClientSide()) {
            return EventResult.pass();
        }
        if (victim instanceof ServerPlayer dead) {
            // Echoes are dismissed when their owner falls and come back for free when the owner respawns.
            List<Integer> slots = new ArrayList<>();
            for (Info i : ACTIVE.values()) {
                if (i.owner.equals(dead.getUUID()) && i.slot >= 0) {
                    slots.add(i.slot);
                }
            }
            if (!slots.isEmpty()) {
                DIED_WITH.put(dead.getUUID(), slots);
            }
            dismissAll(dead, false);
            return EventResult.pass();
        }
        Entity killer = source.getEntity();
        if (killer instanceof ServerPlayer player) {
            leaveBody(player, victim);
        } else if (killer != null && victim instanceof Enemy && !isEcho(victim)) {
            Info info = ACTIVE.get(killer.getUUID());
            if (info != null && killer.level() instanceof ServerLevel level) {
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(info.owner);
                if (owner != null) {
                    Progression.giveXp(owner, Math.max(1, (int) (victim.getMaxHealth() / 2 * EchoConfig.echoXpFraction)));
                }
            }
        }
        return EventResult.pass();
    }

    /** A Summoner's kill leaves the mob's body behind; the owner decides later whether to bind it (see EchoCorpse). */
    private static void leaveBody(ServerPlayer player, LivingEntity victim) {
        PlayerData data = PlayerDataStore.of(player);
        if (!isSummoner(data) || !eligible(victim) || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        EchoCorpse body = EchoEntities.CORPSE.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (body == null) {
            return;
        }
        Identifier key = EntityType.getKey(victim.getType());
        String name = victim.getType().getDescription().getString();
        body.configure(key.toString(), victim, 1, 1);
        body.setNoGravity(false);
        body.setOwner(player.getUUID(), name);
        body.setCustomName(Component.literal("Soul of " + name));
        body.setCustomNameVisible(true);
        body.setGlowingTag(true);
        body.snapTo(victim.getX(), victim.getY(), victim.getZ(), victim.getYRot(), 0);
        if (level.addFreshEntity(body)) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, victim.getX(), victim.getY() + victim.getBbHeight() / 2,
                    victim.getZ(), 25, 0.4, 0.6, 0.4, 0.05);
            player.sendOverlayMessage(Component.literal("The " + name + " leaves a soul body. Right-click it to bind it as an echo.")
                    .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        }
    }

    /** Server side of the bind menu: turns a soul body into an echo in the chosen group. */
    public static void bindCorpse(ServerPlayer player, int entityId, int group) {
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof EchoCorpse body) || !body.isAlive() || !player.getUUID().equals(body.owner())
                || body.distanceToSqr(player) > 10 * 10) {
            return;
        }
        PlayerData data = PlayerDataStore.of(player);
        if (!isSummoner(data)) {
            return;
        }
        if (data.echo.entries.size() >= slots(data)) {
            tell(player, Component.literal("No free echo slot. Use an Echo Shard or release an echo.")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        int g = Math.max(0, Math.min(EchoRoster.GROUPS - 1, group));
        data.echo.entries.add(new EchoRoster.Entry(body.mobType(), body.bodyName(), g));
        PlayerDataStore.markDirty(player);
        if (body.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, body.getX(), body.getY() + 0.8, body.getZ(), 30, 0.3, 0.5, 0.3, 0.2);
        }
        body.discard();
        tell(player, Component.translatable("gatebound.echo.bound", body.bodyName())
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        sendRoster(player, false);
    }

    // ---------------------------------------------------------------- damage rules

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (victim.level().isClientSide()) {
            return EventResult.pass();
        }
        Entity attacker = source.getEntity();
        boolean attackerEcho = attacker != null && isEcho(attacker);
        // Echoes never hurt players or each other.
        if (attackerEcho && (victim instanceof Player || isEcho(victim))) {
            return EventResult.interruptFalse();
        }
        if (isEcho(victim)) {
            if (victim instanceof EchoEntity echo && victim.level().getGameTime() < echo.invulnUntil) {
                return EventResult.interruptFalse();
            }
            // Players cannot hurt echoes (no looting your own allies).
            if (attacker instanceof Player) {
                return EventResult.interruptFalse();
            }
            // A lethal hit defeats the echo cleanly: no drops, no XP. It stays on the roster and can be summoned again.
            if (amount >= victim.getHealth() + victim.getAbsorptionAmount()) {
                defeat((Mob) victim);
                return EventResult.interruptFalse();
            }
        }
        return EventResult.pass();
    }

    private static void defeat(Mob mob) {
        Info info = ACTIVE.get(mob.getUUID());
        if (mob.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 0.8, mob.getZ(), 20, 0.3, 0.5, 0.3, 0.05);
            if (info != null) {
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(info.owner);
                if (owner != null) {
                    owner.sendSystemMessage(Component.translatable("gatebound.echo.fallen", mob.getType().getDescription())
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                }
            }
        }
        remove(mob);
    }

    private static void remove(Mob mob) {
        ACTIVE.remove(mob.getUUID());
        if (mob.level() instanceof ServerLevel level) {
            level.getScoreboard().removePlayerFromTeam(mob.getScoreboardName());
        }
        mob.discard();
    }

    // ---------------------------------------------------------------- summon / dismiss

    /** Toggles: if any echo is active dismiss all, else summon all. Used by the keybind. */
    public static void toggle(ServerPlayer player) {
        Gatebound.LOGGER.info("[echo] toggle received from {} (active echoes: {})",
                player.getName().getString(), activeCount(player.getUUID()));
        if (activeCount(player.getUUID()) > 0) {
            dismissAll(player, true);
        } else {
            summonAll(player);
        }
    }

    /** Tells the player something, in chat and as an overlay, so no failure path is ever silent. */
    private static void tell(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
        player.sendOverlayMessage(message);
    }

    private static Component msg(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    /** Summons every roster echo that is not already out. Returns how many were summoned. */
    public static int summonAll(ServerPlayer player) {
        String who = player.getName().getString();
        PlayerData data = PlayerDataStore.of(player);
        if (!isSummoner(data)) {
            Gatebound.LOGGER.info("[echo] summon for {} refused: class is '{}'", who, data.classId);
            tell(player, msg("gatebound.echo.not_summoner", "Only Summoners can command echoes."));
            return 0;
        }
        if (data.echo.entries.isEmpty()) {
            Gatebound.LOGGER.info("[echo] summon for {} refused: empty roster", who);
            tell(player, msg("gatebound.echo.none", "No echoes in your roster yet. Defeat monsters as a Summoner to bind them."));
            return 0;
        }
        int slotCount = slots(data);
        int summoned = 0;
        boolean anyPending = false;
        for (int slot = 0; slot < data.echo.entries.size() && slot < slotCount; slot++) {
            if (isActive(player.getUUID(), slot)) {
                continue;
            }
            anyPending = true;
            if (!ManaState.spend(player, EchoConfig.summonMana)) {
                Gatebound.LOGGER.info("[echo] summon for {} stopped: not enough mana (need {})", who, EchoConfig.summonMana);
                tell(player, msg("gatebound.echo.no_mana", "Not enough mana (need %s)", EchoConfig.summonMana));
                break;
            }
            String name = data.echo.entries.get(slot).name();
            Mob mob = null;
            try {
                mob = spawn(player, data, slot);
            } catch (RuntimeException e) {
                Gatebound.LOGGER.error("[echo] exception while summoning {} for {}", name, who, e);
            }
            if (mob != null) {
                summoned++;
            } else {
                ManaState.add(player, EchoConfig.summonMana);
                tell(player, msg("gatebound.echo.spawn_failed", "Could not spawn %s", name));
            }
        }
        if (!anyPending) {
            tell(player, msg("gatebound.echo.all_out", "All your echoes are already out."));
        }
        SkillHandler.sendMana(player, true);
        PlayerDataStore.markDirty(player);
        Gatebound.LOGGER.info("[echo] summoned {} echo(es) for {} (roster {}, slots {})",
                summoned, who, data.echo.entries.size(), slotCount);
        if (summoned > 0) {
            player.sendOverlayMessage(msg("gatebound.echo.summoned", "Summoned %s echo(es)", summoned));
        }
        return summoned;
    }

    /** Op self-test for /gatebound echo debug: same code path as the key, with a report. */
    static Component debug(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        StringBuilder sb = new StringBuilder();
        sb.append("class=").append(data.classId.isEmpty() ? "(none)" : data.classId)
                .append(" roster=").append(data.echo.entries.size()).append(" slots=").append(slots(data))
                .append(" mana=").append((int) ManaState.get(player)).append("/").append(ManaState.max(data))
                .append(" cost=").append(EchoConfig.summonMana);
        if (!isSummoner(data)) {
            return Component.literal(sb + " -> not a Summoner; nothing run.");
        }
        if (data.echo.entries.isEmpty()) {
            data.echo.entries.add(new EchoRoster.Entry("minecraft:zombie", "Zombie"));
            PlayerDataStore.markDirty(player);
            sb.append(" (roster was empty: added a test Zombie)");
        }
        dismissAll(player, false);
        int n = summonAll(player);
        sb.append(" -> summoned ").append(n).append(", active now ").append(activeCount(player.getUUID()));
        return Component.literal(sb.toString());
    }

    public static void dismissAll(ServerPlayer player, boolean message) {
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, Info> e : ACTIVE.entrySet()) {
            if (e.getValue().owner.equals(player.getUUID())) {
                ids.add(e.getKey());
            }
        }
        int count = 0;
        for (UUID id : ids) {
            Entity entity = findAnywhere(player.level().getServer(), id);
            if (entity instanceof Mob mob) {
                if (mob.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 0.8, mob.getZ(), 10, 0.3, 0.5, 0.3, 0.03);
                }
                remove(mob);
                count++;
            }
            ACTIVE.remove(id);
        }
        if (message && count > 0) {
            player.sendOverlayMessage(Component.translatable("gatebound.echo.dismissed", count));
        }
    }

    /** Removes one roster entry (and its live entity). */
    public static boolean release(ServerPlayer player, int index) {
        PlayerData data = PlayerDataStore.of(player);
        if (index < 0 || index >= data.echo.entries.size()) {
            return false;
        }
        // Slots are positional, so dismiss everything first and let the player summon again.
        dismissAll(player, false);
        SELECTED_SLOT.remove(player.getUUID());
        PERSONAL.remove(player.getUUID());
        data.echo.entries.remove(index);
        PlayerDataStore.markDirty(player);
        return true;
    }

    private static Mob spawn(ServerPlayer owner, PlayerData data, int slot) {
        return spawnEntry(owner, data, data.echo.entries.get(slot), slot, 0);
    }

    /** Legion Call: a spectral copy of every echo on the roster (up to the slot count) that fades after the given ticks. */
    static int spawnLegion(ServerPlayer owner, PlayerData data, int lifetimeTicks) {
        int n = 0;
        for (int i = 0; i < data.echo.entries.size() && i < slots(data); i++) {
            if (spawnEntry(owner, data, data.echo.entries.get(i), TEMP_SLOT, lifetimeTicks) != null) {
                n++;
            }
        }
        return n;
    }

    /** Removes an echo from the world without losing it: it stays on the roster (or, for a spectral copy, is simply gone). */
    static void consume(Mob mob) {
        if (mob.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 0.8, mob.getZ(), 20, 0.3, 0.5, 0.3, 0.05);
        }
        remove(mob);
    }

    private static Mob spawnEntry(ServerPlayer owner, PlayerData data, EchoRoster.Entry entry, int slot, int lifetimeTicks) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(entry.type()));
        ServerLevel level = owner.level();
        if (type.isEmpty()) {
            Gatebound.LOGGER.warn("[echo] unknown entity type in roster: {}", entry.type());
            return null;
        }
        // The bound mob is only a template for stats and size; it is never added to the world.
        Entity template = type.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (!(template instanceof LivingEntity proto)) {
            Gatebound.LOGGER.warn("[echo] {} is not a living entity (got {})", entry.type(), template);
            return null;
        }
        EchoEntity mob = EchoEntities.ECHO.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (mob == null) {
            return null;
        }
        double bonus = data.level * EchoConfig.healthPerLevel + data.get(Stat.INTELLECT) * EchoConfig.healthPerIntellect;
        mob.configure(entry.type(), proto, (1 + bonus) * (lifetimeTicks > 0 ? 0.6 : 1), 1 + bonus / 2);
        proto.discard();
        mob.setOwnerId(owner.getUUID());
        if (lifetimeTicks > 0) {
            mob.tempUntil = level.getGameTime() + lifetimeTicks;
        }
        Vec3 spot = freeSpot(mob, owner);
        mob.snapTo(spot.x, spot.y, spot.z, owner.getYRot(), 0);
        ACTIVE.put(mob.getUUID(), new Info(owner.getUUID(), slot, owner.getX(), owner.getY(), owner.getZ()));
        mob.addTag(TAG);
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);

        mob.setCustomName(Component.literal((lifetimeTicks > 0 ? "Spectral " : "Echo: ") + entry.name()));
        mob.setCustomNameVisible(true);
        mob.setGlowingTag(true);
        joinTeam(level, mob, false);
        if (slot == SELECTED_SLOT.getOrDefault(owner.getUUID(), -99)) {
            mob.setSelected(true);
            joinTeam(level, mob, true);
        }
        mob.setTarget(null);

        if (!level.addFreshEntity(mob)) {
            Gatebound.LOGGER.warn("[echo] addFreshEntity refused {} (vetoed by another handler?)", entry.type());
            ACTIVE.remove(mob.getUUID());
            return null;
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, mob.getX(), mob.getY() + 0.8, mob.getZ(), 25, 0.3, 0.6, 0.3, 0.2);
        return mob;
    }

    private static void scale(AttributeInstance attr, double mult) {
        if (attr != null) {
            attr.setBaseValue(attr.getBaseValue() * mult);
        }
    }

    private static void joinTeam(ServerLevel level, Mob mob, boolean selected) {
        Scoreboard sb = level.getScoreboard();
        String name = selected ? TEAM + "_sel" : TEAM;
        PlayerTeam team = sb.getPlayerTeam(name);
        if (team == null) {
            team = sb.addPlayerTeam(name);
            team.setColor(Optional.of(selected ? TeamColor.GOLD : TeamColor.DARK_PURPLE));
            team.setAllowFriendlyFire(false);
        }
        sb.addPlayerToTeam(mob.getScoreboardName(), team);
    }

    // ---------------------------------------------------------------- selecting one echo

    /** Roster slot of the echo each owner has picked with the select key (session only). */
    private static final Map<UUID, Integer> SELECTED_SLOT = new HashMap<>();
    /** Own orders for single echoes, by owner then roster slot; they override the echo's group order. */
    private static final Map<UUID, Map<Integer, Integer>> PERSONAL = new HashMap<>();
    private static final double SELECT_RANGE = 8;

    /** Distance along the line of sight to the entity's (slightly enlarged) box, or infinity if the line misses it. */
    private static double lookDistance(Entity e, Vec3 eye, Vec3 end) {
        return e.getBoundingBox().inflate(0.3).clip(eye, end).map(hit -> hit.distanceTo(eye)).orElse(Double.MAX_VALUE);
    }

    /** The echo's own order, or -1 when it follows its group. */
    static int personalOrder(ServerPlayer owner, int slot) {
        Map<Integer, Integer> m = PERSONAL.get(owner.getUUID());
        Integer v = m == null ? null : m.get(slot);
        return v == null ? -1 : v;
    }

    /** Gives one echo its own order (a value past Guide clears it, so the echo follows its group again). */
    public static void setPersonalOrder(ServerPlayer owner, int slot, int mode) {
        Map<Integer, Integer> m = PERSONAL.computeIfAbsent(owner.getUUID(), k -> new HashMap<>());
        if (mode < EchoRoster.GUARD || mode > EchoRoster.GUIDE) {
            m.remove(slot);
        } else {
            m.put(slot, mode);
        }
    }

    private static EchoEntity echoInSlot(ServerPlayer owner, int slot) {
        for (Map.Entry<UUID, Info> e : ACTIVE.entrySet()) {
            if (e.getValue().owner.equals(owner.getUUID()) && e.getValue().slot == slot
                    && findAnywhere(owner.level().getServer(), e.getKey()) instanceof EchoEntity echo) {
                return echo;
            }
        }
        return null;
    }

    private static void markSelected(EchoEntity echo, boolean on) {
        echo.setSelected(on);
        if (echo.level() instanceof ServerLevel level) {
            joinTeam(level, echo, on);
        }
    }

    /**
     * The select key. Beside a soul body of yours it opens the bind menu (sneak: bind straight into group 1); otherwise
     * it selects the nearest of your echoes within a few blocks, or deselects it if it was already the selected one.
     */
    public static void pressSelect(ServerPlayer player) {
        if (!isSummoner(PlayerDataStore.of(player))) {
            return;
        }
        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(SELECT_RANGE));
        net.minecraft.world.phys.AABB area = player.getBoundingBox().expandTowards(player.getLookAngle().scale(SELECT_RANGE)).inflate(1.5);
        // Whatever the crosshair is on: the nearest soul body or echo of yours along the line of sight.
        EchoCorpse body = null;
        double bodyD = Double.MAX_VALUE;
        for (EchoCorpse c : level.getEntitiesOfClass(EchoCorpse.class, area, c -> c.isAlive() && player.getUUID().equals(c.owner()))) {
            double d = lookDistance(c, eye, end);
            if (d < bodyD) {
                bodyD = d;
                body = c;
            }
        }
        EchoEntity echo = null;
        double echoD = Double.MAX_VALUE;
        for (EchoEntity c : level.getEntitiesOfClass(EchoEntity.class, area, c -> !c.isCorpse() && c.isAlive())) {
            Info info = info(c);
            if (info == null || !info.owner.equals(player.getUUID()) || info.slot < 0) {
                continue;
            }
            double d = lookDistance(c, eye, end);
            if (d < echoD) {
                echoD = d;
                echo = c;
            }
        }
        if (body != null && bodyD < Double.MAX_VALUE && bodyD <= echoD) {
            PlayerData data = PlayerDataStore.of(player);
            List<String> labels = new ArrayList<>();
            for (int g = 0; g < EchoRoster.GROUPS; g++) {
                int n = 0;
                for (EchoRoster.Entry en : data.echo.entries) {
                    if (en.group() == g) {
                        n++;
                    }
                }
                labels.add("Group " + (g + 1) + ": " + ORDER_NAMES[data.echo.groupModes[g]] + " (" + n + ")");
            }
            NetworkManager.sendToPlayer(player, new EchoWheelPayload(body.getId(), true, body.bodyName(), labels, -1));
            return;
        }
        if (echo == null || echoD == Double.MAX_VALUE) {
            player.sendOverlayMessage(Component.literal("Look at one of your echoes or a soul body and press the select key.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            return;
        }
        int slot = info(echo).slot;
        boolean selected = SELECTED_SLOT.getOrDefault(player.getUUID(), -99) == slot;
        int personal = personalOrder(player, slot);
        List<String> labels = new ArrayList<>(List.of(ORDER_NAMES));
        labels.add("Group order");
        labels.add(selected ? "Deselect" : "Select");
        NetworkManager.sendToPlayer(player, new EchoWheelPayload(echo.getId(), false, echo.mobName(), labels, personal >= 0 ? personal : 4));
    }

    private static final String[] ORDER_NAMES = {"Guard", "Attack", "Seek", "Guide"};

    /** Selects (or deselects) one echo, clearing the previous selection. */
    private static void setSelection(ServerPlayer player, EchoEntity echo, int slot, boolean on) {
        Integer current = SELECTED_SLOT.get(player.getUUID());
        if (current != null) {
            EchoEntity old = echoInSlot(player, current);
            if (old != null) {
                markSelected(old, false);
            }
            SELECTED_SLOT.remove(player.getUUID());
        }
        if (on) {
            SELECTED_SLOT.put(player.getUUID(), slot);
            markSelected(echo, true);
        }
    }

    /**
     * The player chose an entry on the C wheel. For a soul body the choice is the group to bind it into. For an echo,
     * 0 to 3 give it its own order (and select it), 4 hands it back to its group, 5 selects or deselects it.
     */
    static void wheelPick(ServerPlayer player, int entityId, int choice) {
        Entity e = player.level().getEntity(entityId);
        if (e instanceof EchoCorpse body) {
            if (choice >= 0 && choice < EchoRoster.GROUPS && player.getUUID().equals(body.owner())) {
                bindCorpse(player, entityId, choice);
            }
            return;
        }
        if (!(e instanceof EchoEntity echo) || echo.isCorpse() || echo.distanceToSqr(player) > 20 * 20) {
            return;
        }
        Info info = info(echo);
        if (info == null || !info.owner.equals(player.getUUID()) || info.slot < 0) {
            return;
        }
        int slot = info.slot;
        if (choice >= 0 && choice <= 3) {
            setPersonalOrder(player, slot, choice);
            setSelection(player, echo, slot, true);
            player.sendOverlayMessage(Component.literal(echo.mobName() + ": " + ORDER_NAMES[choice] + ".")
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
        } else if (choice == 4) {
            setPersonalOrder(player, slot, -1);
            player.sendOverlayMessage(Component.literal(echo.mobName() + " follows its group again.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        } else if (choice == 5) {
            boolean on = SELECTED_SLOT.getOrDefault(player.getUUID(), -99) != slot;
            setSelection(player, echo, slot, on);
            player.sendOverlayMessage(Component.literal((on ? "Selected " : "Deselected ") + echo.mobName() + ".")
                    .withStyle(on ? net.minecraft.ChatFormatting.GOLD : net.minecraft.ChatFormatting.GRAY));
        }
        sendRoster(player, false);
    }

    // ---------------------------------------------------------------- tick

    private static void tick(int tick, net.minecraft.server.MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Info> e : new ArrayList<>(ACTIVE.entrySet())) {
            Info info = e.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(info.owner);
            if (owner == null) {
                Entity stray = findAnywhere(server, e.getKey());
                if (stray instanceof Mob m) {
                    remove(m);
                }
                ACTIVE.remove(e.getKey());
                continue;
            }
            Entity entity = findAnywhere(server, e.getKey());
            if (!(entity instanceof Mob mob) || mob.isRemoved() || !mob.isAlive() || mob.level() != owner.level()) {
                // Lost: left behind in an unloaded chunk or another dimension. Drop the stale one and bring a fresh
                // copy to the owner for free. (Defeated echoes leave ACTIVE through defeat() and never reach here.)
                boolean lost = entity == null || (entity instanceof Mob m && m.isAlive());
                if (entity instanceof Mob m) {
                    remove(m);
                }
                ACTIVE.remove(e.getKey());
                PlayerData data = PlayerDataStore.of(owner);
                if (lost && info.slot >= 0 && info.slot < data.echo.entries.size() && info.slot < slots(data)) {
                    spawn(owner, data, info.slot);
                }
                continue;
            }
            if (mob instanceof EchoEntity temp && temp.tempUntil > 0 && mob.level().getGameTime() >= temp.tempUntil) {
                consume(mob);
                continue;
            }
            // Never burn from daylight (lava still hurts).
            if (mob.getRemainingFireTicks() > 0 && !mob.isInLava()) {
                mob.clearFire();
            }
            if (mob.isVehicle()) {
                continue;
            }
            int curMode = effectiveMode(owner, e.getKey(), info.slot);
            boolean seeker = curMode == EchoRoster.SEEK || curMode == EchoRoster.GOTO;
            double leash = seeker ? 150 : EchoConfig.teleportDistance;
            if (mob.level() != owner.level() || mob.distanceToSqr(owner) > leash * leash) {
                teleportTo(mob, owner);
            }
            if ((tick + info.slot) % 20 == 0 && mob.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5,
                        mob.getZ(), 2, 0.3, 0.4, 0.3, 0.01);
            }
        }
    }

    /** Looks for the entity in every loaded dimension, so a stale echo is always found and removed. */
    private static Entity findAnywhere(net.minecraft.server.MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** A dungeon floor was (re)built: echoes forget what they had explored. */
    public static void forgetExploration() {
        EchoBrain.resetExploration();
    }

    static void teleportTo(Mob mob, ServerPlayer owner) {
        Vec3 spot = freeSpot(mob, owner);
        mob.getNavigation().stop();
        mob.teleportTo(owner.level(), spot.x, spot.y, spot.z, Set.of(), owner.getYRot(), 0, false);
    }

    /**
     * A place next to the owner where the mob's body fits in open air (and, unless it flies, has ground under it).
     * Tries rings of increasing radius at random bearings and a few heights; falls back to the owner's own spot.
     */
    private static Vec3 freeSpot(Mob mob, ServerPlayer owner) {
        ServerLevel level = owner.level();
        boolean flier = mob instanceof EchoEntity ee && ee.isFlier();
        double[] radii = {2, 1.5, 3, 1, 4};
        double[] heights = {0, 1, -1, 2, -2};
        for (double r : radii) {
            double start = owner.getRandom().nextDouble() * Math.PI * 2;
            for (int i = 0; i < 12; i++) {
                double angle = start + i * Math.PI / 6;
                double x = owner.getX() + Math.cos(angle) * r;
                double z = owner.getZ() + Math.sin(angle) * r;
                for (double dy : heights) {
                    double y = owner.getY() + dy;
                    net.minecraft.world.phys.AABB box = mob.getBoundingBox().move(x - mob.getX(), y - mob.getY(), z - mob.getZ());
                    if (!level.noCollision(mob, box)) {
                        continue;
                    }
                    if (!flier && level.noCollision(mob, box.move(0, -1.0, 0))) {
                        continue; // nothing to stand on
                    }
                    BlockPos feet = BlockPos.containing(x, y, z);
                    if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) {
                        continue;
                    }
                    return new Vec3(x, y, z);
                }
            }
        }
        return owner.position();
    }
}
