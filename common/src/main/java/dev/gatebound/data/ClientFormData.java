package dev.gatebound.data;

import dev.gatebound.network.FormSyncPayload;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client copy of the body effects active on each player (see FormSyncPayload): "form:fire" for a Mage form, and tags such as
 * "iron_skin" or "circle:warrior:2.6" for Warrior effects.
 */
public final class ClientFormData {
    private static volatile Map<UUID, Set<String>> tags = Map.of();

    private ClientFormData() {
    }

    public static void update(FormSyncPayload payload) {
        Map<UUID, Set<String>> next = new HashMap<>();
        for (int i = 0; i < payload.players().size() && i < payload.elements().size(); i++) {
            next.computeIfAbsent(payload.players().get(i), k -> new HashSet<>()).add(payload.elements().get(i));
        }
        tags = next;
    }

    /** The active tags of a player, or an empty set. */
    public static Set<String> tags(UUID player) {
        return tags.getOrDefault(player, Set.of());
    }

    /** The element id of the player's Mage form, or null when they are not in one. */
    public static String of(UUID player) {
        for (String tag : tags(player)) {
            if (tag.startsWith("form:")) {
                return tag.substring(5);
            }
        }
        return null;
    }
}
