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
    private static final Map<UUID, Map<String, Long>> SEEN = new HashMap<>(); // when each tag first appeared (client thread)

    /** Static holder; not instantiable. */
    private ClientFormData() {
    }

    /** Stores the latest form tags per player, keeping when each first appeared. */
    public static void update(FormSyncPayload payload) {
        Map<UUID, Set<String>> next = new HashMap<>();
        for (int i = 0; i < payload.players().size() && i < payload.elements().size(); i++) {
            next.computeIfAbsent(payload.players().get(i), k -> new HashSet<>()).add(payload.elements().get(i));
        }
        long now = System.currentTimeMillis();
        Map<UUID, Map<String, Long>> seen = new HashMap<>();
        for (Map.Entry<UUID, Set<String>> e : next.entrySet()) {
            Map<String, Long> old = SEEN.getOrDefault(e.getKey(), Map.of());
            Map<String, Long> fresh = new HashMap<>();
            for (String tag : e.getValue()) {
                fresh.put(tag, old.getOrDefault(tag, now));
            }
            seen.put(e.getKey(), fresh);
        }
        SEEN.clear();
        SEEN.putAll(seen);
        tags = next;
    }

    /** A timer bar for the player ("bar:<name>:<ticks>" tag): its name and the fraction still left. */
    public record Bar(String name, float fraction) {
    }

    /** The active timer bar of a player, or null. */
    public static Bar bar(UUID player) {
        for (String tag : tags(player)) {
            if (tag.startsWith("bar:")) {
                String[] a = tag.split(":");
                long total = a.length > 2 ? Long.parseLong(a[2]) : 200;
                long started = SEEN.getOrDefault(player, Map.of()).getOrDefault(tag, System.currentTimeMillis());
                double left = 1.0 - (System.currentTimeMillis() - started) / (total * 50.0);
                return new Bar(a[1], (float) Math.max(0.0, Math.min(1.0, left)));
            }
        }
        return null;
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
