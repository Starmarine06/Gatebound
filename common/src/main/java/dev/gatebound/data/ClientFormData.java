package dev.gatebound.data;

import dev.gatebound.network.FormSyncPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client copy of which players are in a Mage form (element id), see FormSyncPayload. */
public final class ClientFormData {
    private static volatile Map<UUID, String> forms = Map.of();

    private ClientFormData() {
    }

    public static void update(FormSyncPayload payload) {
        Map<UUID, String> next = new HashMap<>();
        for (int i = 0; i < payload.players().size() && i < payload.elements().size(); i++) {
            next.put(payload.players().get(i), payload.elements().get(i));
        }
        forms = next;
    }

    /** The element id of the player's form, or null when they are not in one. */
    public static String of(UUID player) {
        return forms.get(player);
    }
}
