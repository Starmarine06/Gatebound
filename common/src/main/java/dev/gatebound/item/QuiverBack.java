package dev.gatebound.item;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.QuiverSyncPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Tells every client which players carry a quiver, so the client layer (client/QuiverBackLayer) can draw it on their back
 * like armour. The list is re-sent when it changes, when someone joins, and every two seconds so a client that was still loading never misses it.
 */
public final class QuiverBack {
    private static final String OLD_DISPLAY_TAG = "gatebound_quiver_display";

    private static List<UUID> last = List.of();
    private static boolean dirty = true;

    private QuiverBack() {
    }

    public static void init() {
        TickEvent.SERVER_POST.register(QuiverBack::tick);
        PlayerEvent.PLAYER_JOIN.register(player -> dirty = true);
        PlayerEvent.PLAYER_QUIT.register(player -> dirty = true);
        // Earlier versions drew the quiver with a floating display entity; sweep any that were saved in a world.
        EntityEvent.ADD.register((entity, world) -> {
            if (entity.entityTags().contains(OLD_DISPLAY_TAG)) {
                entity.discard();
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 10 != 0) {
            return;
        }
        List<UUID> carriers = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isAlive() && !p.isInvisible() && !p.isSpectator() && QuiverItem.carriesQuiver(p)) {
                carriers.add(p.getUUID());
            }
        }
        carriers.sort(Comparator.naturalOrder());
        if (dirty || !carriers.equals(last) || now % 40 == 0) {
            dirty = false;
            last = carriers;
            QuiverSyncPayload payload = new QuiverSyncPayload(carriers);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                NetworkManager.sendToPlayer(p, payload);
            }
        }
    }
}
