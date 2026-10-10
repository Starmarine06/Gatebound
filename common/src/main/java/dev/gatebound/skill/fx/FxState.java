package dev.gatebound.skill.fx;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Effects that stay on a player's body (orbiting plates, a held shield, a ring of blades, horns, a circle under the feet) are drawn
 * by the client from the real rendered body, so they never lag. The server only records which of them are active on whom;
 * FormSync sends that to every client. A tag is a name with optional arguments ("circle:warrior:2.6"). Memory only.
 */
public final class FxState {
    private static final Map<UUID, Map<String, Long>> ACTIVE = new HashMap<>();

    /** Static holder; not instantiable. */
    private FxState() {
    }

    /** Registers the client-effect tag upkeep. */
    public static void init() {
        PlayerEvent.PLAYER_QUIT.register(player -> ACTIVE.remove(player.getUUID()));
        PlayerEvent.PLAYER_RESPAWN.register((player, conquered, reason) -> ACTIVE.remove(player.getUUID()));
        LifecycleEvent.SERVER_STOPPED.register(server -> ACTIVE.clear());
        TickEvent.SERVER_POST.register(FxState::prune);
    }

    /** Shows {@code tag} on the player for {@code ticks} more ticks (a repeat call refreshes it). */
    public static void set(ServerPlayer p, String tag, int ticks) {
        ACTIVE.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(tag, (long) p.level().getServer().getTickCount() + ticks);
    }

    /** Removes one client-effect tag from a player. */
    public static void clear(ServerPlayer p, String tag) {
        Map<String, Long> map = ACTIVE.get(p.getUUID());
        if (map != null) {
            map.remove(tag);
        }
    }

    /** Drops expired tags every second. */
    private static void prune(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 20 != 0) {
            return;
        }
        ACTIVE.values().removeIf(map -> {
            map.values().removeIf(until -> until <= now);
            return map.isEmpty();
        });
    }

    /** Every active tag per player at tick {@code now}, in a stable order. */
    public static Map<UUID, List<String>> snapshot(long now) {
        Map<UUID, List<String>> out = new TreeMap<>();
        for (Map.Entry<UUID, Map<String, Long>> e : ACTIVE.entrySet()) {
            List<String> tags = new ArrayList<>();
            for (Map.Entry<String, Long> t : e.getValue().entrySet()) {
                if (t.getValue() > now) {
                    tags.add(t.getKey());
                }
            }
            if (!tags.isEmpty()) {
                tags.sort(null);
                out.put(e.getKey(), tags);
            }
        }
        return out;
    }
}
