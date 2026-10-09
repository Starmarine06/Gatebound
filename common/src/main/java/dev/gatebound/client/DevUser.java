package dev.gatebound.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Dev-only: the dev client starts with a random offline name (Player123) whatever launch arguments say, which gives a new
 * player UUID, and so a fresh Gatebound character, on every launch. An unauthenticated "PlayerNNN" user is renamed to a
 * fixed name before the world loads. Real accounts are never touched.
 */
final class DevUser {
    static final String NAME = "Player896";

    private DevUser() {
    }

    static void pin() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            Field field = Minecraft.class.getDeclaredField("user");
            field.setAccessible(true);
            User old = (User) field.get(mc);
            if (old == null || !old.getName().matches("Player[0-9]{1,3}") || old.getName().equals(NAME)) {
                return;
            }
            String token = old.getAccessToken();
            if (token != null && token.length() > 8) {
                return; // a real session
            }
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + NAME).getBytes(StandardCharsets.UTF_8));
            field.set(mc, new User(NAME, id, token, Optional.empty(), Optional.empty()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Cosmetic dev helper: never block startup.
        }
    }
}
