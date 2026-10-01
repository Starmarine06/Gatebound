package dev.gatebound.skill;

import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-side mana. Not saved: it refills on join and respawn. */
public final class ManaState {
    private static final Map<UUID, Double> MANA = new HashMap<>();
    private static final Map<UUID, int[]> LAST_SENT = new HashMap<>();

    private ManaState() {
    }

    public static int max(PlayerData data) {
        return 20 + 4 * data.get(Stat.INTELLECT);
    }

    public static double regenPerTick(PlayerData data) {
        return (1.0 + 0.1 * data.get(Stat.INTELLECT)) / 20.0;
    }

    public static double get(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        double mana = MANA.getOrDefault(player.getUUID(), (double) max(data));
        return Math.min(mana, max(data));
    }

    public static void set(ServerPlayer player, double value) {
        MANA.put(player.getUUID(), Math.max(0, Math.min(max(PlayerDataStore.of(player)), value)));
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

    public static void fill(ServerPlayer player) {
        set(player, max(PlayerDataStore.of(player)));
        LAST_SENT.remove(player.getUUID());
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
        MANA.remove(id);
        LAST_SENT.remove(id);
    }
}
