package dev.gatebound.data;

import dev.gatebound.network.QuiverSyncPayload;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Client copy of which players carry a quiver on their back (see QuiverSyncPayload). */
public final class ClientQuiverData {
    private static volatile Set<UUID> carriers = Set.of();

    private ClientQuiverData() {
    }

    public static void update(QuiverSyncPayload payload) {
        carriers = new HashSet<>(payload.carriers());
    }

    public static boolean has(UUID player) {
        return carriers.contains(player);
    }
}
