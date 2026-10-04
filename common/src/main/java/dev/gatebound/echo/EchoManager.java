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
        PlayerEvent.PLAYER_QUIT.register(player -> dismissAll(player, false));
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

    public static boolean isGuard(ServerPlayer owner) {
        return PlayerDataStore.of(owner).echo.guard;
    }

    public static void setGuardPost(ServerPlayer owner) {
        for (Info i : ACTIVE.values()) {
            if (i.owner.equals(owner.getUUID())) {
                i.gx = owner.getX();
                i.gy = owner.getY();
                i.gz = owner.getZ();
            }
        }
    }

    public static boolean isSummoner(PlayerData data) {
        return SUMMONER.equals(data.classId);
    }

    public static int slots(PlayerData data) {
        return EchoConfig.slots(data.get(Stat.INTELLECT));
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
                if (i.owner.equals(dead.getUUID())) {
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
            tryExtract(player, victim);
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

    private static void tryExtract(ServerPlayer player, LivingEntity victim) {
        PlayerData data = PlayerDataStore.of(player);
        if (!isSummoner(data) || !eligible(victim) || data.echo.entries.size() >= slots(data)) {
            return;
        }
        double chance = EchoConfig.chancePercent(data.get(Stat.INTELLECT), data.level);
        if (player.getRandom().nextDouble() * 100 >= chance) {
            return;
        }
        Identifier key = EntityType.getKey(victim.getType());
        String name = victim.getType().getDescription().getString();
        data.echo.entries.add(new EchoRoster.Entry(key.toString(), name));
        PlayerDataStore.markDirty(player);
        if (victim.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, victim.getX(), victim.getY() + victim.getBbHeight() / 2,
                    victim.getZ(), 40, 0.4, 0.6, 0.4, 0.05);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, victim.getX(), victim.getY() + 0.5, victim.getZ(),
                    30, 0.3, 0.5, 0.3, 0.2);
        }
        player.sendSystemMessage(Component.translatable("gatebound.echo.bound", name)
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        player.sendOverlayMessage(Component.translatable("gatebound.echo.bound", name)
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
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
            Entity entity = player.level().getEntityInAnyDimension(id);
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
        data.echo.entries.remove(index);
        PlayerDataStore.markDirty(player);
        return true;
    }

    private static Mob spawn(ServerPlayer owner, PlayerData data, int slot) {
        EchoRoster.Entry entry = data.echo.entries.get(slot);
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
        mob.configure(entry.type(), proto, 1 + bonus, 1 + bonus / 2);
        proto.discard();
        double angle = owner.getRandom().nextDouble() * Math.PI * 2;
        double x = owner.getX() + Math.cos(angle) * 2;
        double z = owner.getZ() + Math.sin(angle) * 2;
        mob.snapTo(x, owner.getY(), z, owner.getYRot(), 0);
        ACTIVE.put(mob.getUUID(), new Info(owner.getUUID(), slot, owner.getX(), owner.getY(), owner.getZ()));
        mob.addTag(TAG);
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);

        mob.setCustomName(Component.literal("Echo: " + entry.name()));
        mob.setCustomNameVisible(true);
        mob.setGlowingTag(true);
        joinTeam(level, mob);
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

    private static void joinTeam(ServerLevel level, Mob mob) {
        Scoreboard sb = level.getScoreboard();
        PlayerTeam team = sb.getPlayerTeam(TEAM);
        if (team == null) {
            team = sb.addPlayerTeam(TEAM);
            team.setColor(Optional.of(TeamColor.DARK_PURPLE));
            team.setAllowFriendlyFire(false);
        }
        sb.addPlayerToTeam(mob.getScoreboardName(), team);
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
                Entity stray = server.overworld().getEntityInAnyDimension(e.getKey());
                if (stray instanceof Mob m) {
                    remove(m);
                }
                ACTIVE.remove(e.getKey());
                continue;
            }
            Entity entity = owner.level().getEntityInAnyDimension(e.getKey());
            if (!(entity instanceof Mob mob) || mob.isRemoved() || !mob.isAlive() || mob.level() != owner.level()) {
                // Lost: left behind in an unloaded chunk or another dimension. Drop the stale one and bring a fresh
                // copy to the owner for free. (Defeated echoes leave ACTIVE through defeat() and never reach here.)
                boolean lost = entity == null || (entity instanceof Mob m && m.isAlive());
                if (entity instanceof Mob m) {
                    remove(m);
                }
                ACTIVE.remove(e.getKey());
                PlayerData data = PlayerDataStore.of(owner);
                if (lost && info.slot < data.echo.entries.size() && info.slot < slots(data)) {
                    spawn(owner, data, info.slot);
                }
                continue;
            }
            // Never burn from daylight (lava still hurts).
            if (mob.getRemainingFireTicks() > 0 && !mob.isInLava()) {
                mob.clearFire();
            }
            boolean guard = isGuard(owner);
            if (mob.level() != owner.level()) {
                teleportTo(mob, owner);
                info.gx = owner.getX();
                info.gy = owner.getY();
                info.gz = owner.getZ();
            } else if (!guard && mob.distanceToSqr(owner) > EchoConfig.teleportDistance * EchoConfig.teleportDistance) {
                teleportTo(mob, owner);
            } else if (guard && mob.distanceToSqr(owner) > 64 * 64) {
                teleportTo(mob, owner);
                info.gx = owner.getX();
                info.gy = owner.getY();
                info.gz = owner.getZ();
            }
            if ((tick + info.slot) % 5 == 0) {
                retarget(mob, owner, info, guard);
            }
            if ((tick + info.slot) % 20 == 0 && mob.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5,
                        mob.getZ(), 2, 0.3, 0.4, 0.3, 0.01);
            }
        }
    }

    private static void teleportTo(Mob mob, ServerPlayer owner) {
        double angle = owner.getRandom().nextDouble() * Math.PI * 2;
        mob.getNavigation().stop();
        mob.teleportTo(owner.level(), owner.getX() + Math.cos(angle) * 2, owner.getY(), owner.getZ() + Math.sin(angle) * 2,
                Set.of(), owner.getYRot(), 0, false);
    }

    private static boolean validTarget(Mob mob, LivingEntity target) {
        return target.isAlive() && !(target instanceof Player) && !isEcho(target) && target instanceof Enemy
                && target.level() == mob.level();
    }

    private static void retarget(Mob mob, ServerPlayer owner, Info info, boolean guard) {
        LivingEntity current = mob.getTarget();
        if (current != null && !validTarget(mob, current)) {
            mob.setTarget(null);
            current = null;
        }
        if (current != null) {
            return;
        }
        LivingEntity pick = null;
        LivingEntity hit = owner.getLastHurtMob();
        if (hit != null && owner.tickCount - owner.getLastHurtMobTimestamp() < 100 && validTarget(mob, hit)) {
            pick = hit;
        }
        LivingEntity hurtBy = owner.getLastHurtByMob();
        if (pick == null && hurtBy != null && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100
                && validTarget(mob, hurtBy)) {
            pick = hurtBy;
        }
        if (pick == null && mob.level() instanceof ServerLevel level) {
            double cx = guard ? info.gx : mob.getX();
            double cz = guard ? info.gz : mob.getZ();
            double best = Double.MAX_VALUE;
            for (Monster m : level.getEntitiesOfClass(Monster.class, mob.getBoundingBox().inflate(12, 4, 12),
                    m -> validTarget(mob, m))) {
                if (guard && m.distanceToSqr(cx, m.getY(), cz) > 14 * 14) {
                    continue;
                }
                double d = mob.distanceToSqr(m);
                if (d < best) {
                    best = d;
                    pick = m;
                }
            }
        }
        if (pick != null && guard && pick.distanceToSqr(info.gx, pick.getY(), info.gz) > 20 * 20) {
            pick = null;
        }
        if (pick != null) {
            mob.setTarget(pick);
        }
    }
}
