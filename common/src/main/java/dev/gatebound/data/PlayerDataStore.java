package dev.gatebound.data;

import com.mojang.serialization.Codec;
import dev.gatebound.Gatebound;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-level save of every player's PlayerData, stored with the overworld.
 * Using vanilla SavedData keeps persistence identical on Fabric and NeoForge.
 */
public final class PlayerDataStore extends SavedData {
    private static final Codec<PlayerDataStore> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, PlayerData.CODEC)
            .xmap(PlayerDataStore::new, store -> store.players);

    public static final SavedDataType<PlayerDataStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "player_data"),
            PlayerDataStore::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PlayerData> players;

    public PlayerDataStore() {
        this.players = new HashMap<>();
    }

    private PlayerDataStore(Map<UUID, PlayerData> loaded) {
        this.players = new HashMap<>(loaded);
    }

    public static PlayerDataStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static PlayerData of(ServerPlayer player) {
        return get(player.level().getServer()).players.computeIfAbsent(player.getUUID(), id -> new PlayerData());
    }

    /**
     * Vanilla only writes saved data on a clean quit or the 5-minute autosave, so a stopped or crashed client lost
     * everything. Flush anything dirty (player data, tower, trade stock) about once a second instead.
     */
    public static void initAutoFlush() {
        dev.architectury.event.events.common.TickEvent.SERVER_POST.register(server -> {
            if (server.getTickCount() % 20 == 0) {
                dev.gatebound.compat.Compat.flushSavedData(server);
            }
        });
        dev.architectury.event.events.common.PlayerEvent.PLAYER_QUIT.register(player ->
                dev.gatebound.compat.Compat.flushSavedData(player.level().getServer()));
    }

    public static void markDirty(ServerPlayer player) {
        get(player.level().getServer()).setDirty();
    }
}
