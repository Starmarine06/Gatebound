package dev.gatebound.trade;

import com.mojang.serialization.Codec;
import dev.gatebound.Gatebound;
import dev.gatebound.data.Stat;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player count of stat tomes read, kept in its own SavedData (separate from PlayerData so this feature
 * merges cleanly). Saved with the overworld like PlayerDataStore.
 */
public final class TradeStore extends SavedData {
    private static final Codec<TradeStore> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT.listOf())
            .xmap(TradeStore::new, store -> store.toSaved());

    public static final SavedDataType<TradeStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "trade_data"),
            TradeStore::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, int[]> tomes = new HashMap<>();

    public TradeStore() {
    }

    private TradeStore(Map<UUID, List<Integer>> loaded) {
        loaded.forEach((id, list) -> {
            int[] arr = new int[Stat.values().length];
            for (int n = 0; n < arr.length && n < list.size(); n++) {
                arr[n] = Math.max(0, list.get(n));
            }
            tomes.put(id, arr);
        });
    }

    private Map<UUID, List<Integer>> toSaved() {
        Map<UUID, List<Integer>> out = new HashMap<>();
        tomes.forEach((id, arr) -> {
            List<Integer> list = new ArrayList<>();
            for (int v : arr) {
                list.add(v);
            }
            out.put(id, list);
        });
        return out;
    }

    private static TradeStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static int tomesRead(ServerPlayer player, Stat stat) {
        int[] arr = get(player.level().getServer()).tomes.get(player.getUUID());
        return arr == null ? 0 : arr[stat.ordinal()];
    }

    public static void addTome(ServerPlayer player, Stat stat) {
        TradeStore store = get(player.level().getServer());
        store.tomes.computeIfAbsent(player.getUUID(), id -> new int[Stat.values().length])[stat.ordinal()]++;
        store.setDirty();
    }
}
