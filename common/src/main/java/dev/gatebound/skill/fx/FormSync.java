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
 * Tells every client which body effects are active on which players (Mage forms as "form:fire", and the FxState tags), so the
 * client layer (client/FormBodyLayer) can draw them on the real rendered body. Re-sent when something changes, when someone
 * joins, and every two seconds so a client that was still loading never misses it.
 */
public final class FormSync {
    private static Map<UUID, List<String>> last = Map.of();
    private static boolean dirty = true;

    private FormSync() {
    }

    public static void init() {
        FxState.init();
        TickEvent.SERVER_POST.register(FormSync::tick);
        PlayerEvent.PLAYER_JOIN.register(player -> dirty = true);
        PlayerEvent.PLAYER_QUIT.register(player -> dirty = true);
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 2 != 0) {
            return;
        }
        Map<UUID, List<String>> all = new TreeMap<>(FxState.snapshot(now));
        for (Map.Entry<UUID, Element> e : MageCore.activeForms(now).entrySet()) {
            List<String> tags = new ArrayList<>(all.getOrDefault(e.getKey(), List.of()));
            tags.add("form:" + e.getValue().name().toLowerCase(java.util.Locale.ROOT));
            all.put(e.getKey(), tags);
        }
        if (dirty || !all.equals(last) || now % 40 == 0) {
            dirty = false;
            last = all;
            List<UUID> players = new ArrayList<>();
            List<String> tags = new ArrayList<>();
            for (Map.Entry<UUID, List<String>> e : all.entrySet()) {
                for (String tag : e.getValue()) {
                    players.add(e.getKey());
                    tags.add(tag);
                }
            }
            FormSyncPayload payload = new FormSyncPayload(players, tags);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                NetworkManager.sendToPlayer(p, payload);
            }
        }
    }
}
