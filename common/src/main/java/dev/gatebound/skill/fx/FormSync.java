package dev.gatebound.skill.fx;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.Element;
import dev.gatebound.network.FormSyncPayload;
import dev.gatebound.skill.mage.MageCore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Tells every client which players are in a Mage form, so the client body layer (client/FormBodyLayer) can skin them.
 * Re-sent when the set changes, when someone joins, and every two seconds so a client that was still loading never misses it.
 */
public final class FormSync {
    private static Map<UUID, Element> last = Map.of();
    private static boolean dirty = true;

    private FormSync() {
    }

    public static void init() {
        TickEvent.SERVER_POST.register(FormSync::tick);
        PlayerEvent.PLAYER_JOIN.register(player -> dirty = true);
        PlayerEvent.PLAYER_QUIT.register(player -> dirty = true);
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 5 != 0) {
            return;
        }
        Map<UUID, Element> forms = new TreeMap<>(MageCore.activeForms(now));
        if (dirty || !forms.equals(last) || now % 40 == 0) {
            dirty = false;
            last = forms;
            List<UUID> players = new ArrayList<>();
            List<String> elements = new ArrayList<>();
            for (Map.Entry<UUID, Element> e : forms.entrySet()) {
                players.add(e.getKey());
                elements.add(e.getValue().name().toLowerCase(java.util.Locale.ROOT));
            }
            FormSyncPayload payload = new FormSyncPayload(players, elements);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                NetworkManager.sendToPlayer(p, payload);
            }
        }
    }
}
