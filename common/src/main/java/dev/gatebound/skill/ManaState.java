package dev.gatebound.skill;

import dev.gatebound.data.GateboundClass;
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

    /** Static holder; not instantiable. */
    private ManaState() {
    }

    /** Maximum mana from the class's primary stat. */
    public static int max(PlayerData data) {
        Stat primary = RogueConfig.agilityMana && GateboundClass.ROGUE.id().equals(data.classId) ? Stat.AGILITY
                : RangerConfig.agilityMana && GateboundClass.RANGER.id().equals(data.classId) ? Stat.AGILITY
                : WarriorConfig.strengthMana && GateboundClass.WARRIOR.id().equals(data.classId) ? Stat.STRENGTH : Stat.INTELLECT;
        return 20 + 4 * data.get(primary);
    }

    /** Ticks for the pool to refill on its own: half a Minecraft day. Sleeping or a mana potion refills it much faster. */
    public static final int FULL_REGEN_TICKS = 12000;

    /** Mana regenerated per tick. */
    public static double regenPerTick(PlayerData data) {
        return max(data) / (double) FULL_REGEN_TICKS;
    }

    /** Current mana (initialised to full on first use). */
    public static double get(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        if (data.mana == PlayerData.MANA_UNSET) {
            data.mana = max(data);
        }
        return Math.min(data.mana, max(data));
    }

    /** Sets mana, clamped to 0..max. */
    public static void set(ServerPlayer player, double value) {
        PlayerData data = PlayerDataStore.of(player);
        data.mana = Math.max(0, Math.min(max(data), value));
    }

    /** Adds (or removes) mana. */
    public static void add(ServerPlayer player, double amount) {
        set(player, get(player) + amount);
    }

    /** Spends mana; false if there is not enough. */
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

    /** Forgets the last-sent mana snapshot for a player. */
    public static void forget(UUID id) {
        LAST_SENT.remove(id);
    }
}
