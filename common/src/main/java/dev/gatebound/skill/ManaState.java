package dev.gatebound.skill;

import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mana helpers. The value itself lives in PlayerData.mana, so it is saved with the player and carries across
 * sessions, deaths and rejoining. A brand new player (mana unset) starts full, and never refills for free after that.
 */
public final class ManaState {
    private static final Map<UUID, int[]> LAST_SENT = new HashMap<>();

    private ManaState() {
    }

    public static int max(PlayerData data) {
        return 20 + 4 * data.get(Stat.INTELLECT);
    }

    /** Ticks for the pool to refill on its own: half a Minecraft day. Sleeping or a mana potion refills it much faster. */
    public static final int FULL_REGEN_TICKS = 12000;

    public static double regenPerTick(PlayerData data) {
        return max(data) / (double) FULL_REGEN_TICKS;
    }

    public static double get(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        if (data.mana == PlayerData.MANA_UNSET) {
            data.mana = max(data);
        }
        return Math.min(data.mana, max(data));
    }

    public static void set(ServerPlayer player, double value) {
        PlayerData data = PlayerDataStore.of(player);
        data.mana = Math.max(0, Math.min(max(data), value));
    }

    public static void add(ServerPlayer player, double amount) {
        set(player, get(player) + amount);
    }

    public static boolean spend(ServerPlayer player, int cost) {
        double mana = get(player);
        if (mana < cost) {
            return false;
        }
        set(player, mana - cost);
        return true;
    }

    /** Returns {mana, max} if it changed since the last send (or force is set), otherwise null. */
    public static int[] changedSnapshot(ServerPlayer player, boolean force) {
        int[] now = {(int) get(player), max(PlayerDataStore.of(player))};
        int[] last = LAST_SENT.get(player.getUUID());
        if (!force && last != null && last[0] == now[0] && last[1] == now[1]) {
            return null;
        }
        LAST_SENT.put(player.getUUID(), now);
        return now;
    }

    public static void forget(UUID id) {
        LAST_SENT.remove(id);
    }
}
