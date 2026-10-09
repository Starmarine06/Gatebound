package dev.gatebound.data;

import dev.gatebound.network.GrappleSyncPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client copy of the anchored grapples (see GrappleSyncPayload). */
public final class ClientGrappleData {
    private static volatile Map<UUID, GrappleSyncPayload.Line> lines = Map.of();

    private ClientGrappleData() {
    }

    public static void update(GrappleSyncPayload payload) {
        Map<UUID, GrappleSyncPayload.Line> next = new HashMap<>();
        for (GrappleSyncPayload.Line l : payload.lines()) {
            next.put(l.player(), l);
        }
        lines = next;
    }

    public static GrappleSyncPayload.Line of(UUID player) {
        return lines.get(player);
    }
}
