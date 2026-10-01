package dev.gatebound.data;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.OpenAwakeningPayload;
import dev.gatebound.network.SyncDataPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.monster.Enemy;

/** Server-side progression logic: kill XP, level-ups, applying stats and syncing to the client. */
public final class Progression {
    private Progression() {
    }

    public static void init() {
        PlayerEvent.PLAYER_JOIN.register(player -> {
            refresh(player);
            openAwakeningIfNeeded(player);
        });
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> refresh(player));
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer player) {
                giveXp(player, Math.max(1, (int) (entity.getMaxHealth() / 2)));
            }
            return EventResult.pass();
        });
    }

    /** Applies a class choice. Ignored (with a message) if the player already has a class or the id is unknown. */
    public static void chooseClass(ServerPlayer player, String classId) {
        PlayerData data = PlayerDataStore.of(player);
        GateboundClass chosen = GateboundClass.byId(classId);
        if (chosen == null || !data.classId.isEmpty()) {
            player.sendSystemMessage(Component.literal("[Gatebound] You cannot choose a class right now."));
            return;
        }
        data.classId = chosen.id();
        for (Stat s : Stat.values()) {
            data.stats[s.ordinal()] += chosen.bonus(s);
        }
        save(player, data);
        player.sendSystemMessage(Component.literal("[Gatebound] You awakened as a ")
                .append(Component.translatable(chosen.nameKey())).append("."));
    }

    /** Server-validated stat spending; used by both the GUI packet and the command. */
    public static boolean spendStat(ServerPlayer player, String statId, int points) {
        Stat stat = Stat.byId(statId);
        PlayerData data = PlayerDataStore.of(player);
        if (stat == null || !data.spend(stat, points)) {
            return false;
        }
        save(player, data);
        return true;
    }

    /** Wipes the class and spent stat points, refunding points for the current level. */
    public static void resetClass(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        data.classId = "";
        java.util.Arrays.fill(data.stats, Stat.BASE_VALUE);
        data.statPoints = (data.level - 1) * PlayerData.STAT_POINTS_PER_LEVEL;
        save(player, data);
        openAwakeningIfNeeded(player);
    }

    public static void openAwakeningIfNeeded(ServerPlayer player) {
        if (PlayerDataStore.of(player).classId.isEmpty()) {
            NetworkManager.sendToPlayer(player, new OpenAwakeningPayload());
        }
    }

    public static void giveXp(ServerPlayer player, int amount) {
        PlayerData data = PlayerDataStore.of(player);
        int gained = data.addXp(amount);
        if (gained > 0) {
            player.sendSystemMessage(Component.literal("[Gatebound] Level up! You are now level " + data.level
                    + " (+" + gained * PlayerData.STAT_POINTS_PER_LEVEL + " stat points)"));
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
        }
        save(player, data);
    }

    /** Persist, re-apply attribute effects and push a fresh snapshot to the client. */
    public static void save(ServerPlayer player, PlayerData data) {
        PlayerDataStore.markDirty(player);
        refresh(player);
    }

    public static void refresh(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        StatEffects.apply(player, data);
        NetworkManager.sendToPlayer(player, SyncDataPayload.of(data));
    }
}
