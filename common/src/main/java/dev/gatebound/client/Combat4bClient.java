package dev.gatebound.client;

import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.gatebound.data.ClientStaminaData;
import dev.gatebound.network.DodgePayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Client side of dodge, stamina and player posture. Client-only; reach it from ClientHooks.init(). */
public final class Combat4bClient {
    /** GLFW_KEY_LEFT_ALT. */
    private static final int KEY_LEFT_ALT = 342;
    public static final KeyMapping DODGE = new KeyMapping("key.gatebound.dodge", KEY_LEFT_ALT, ClientHooks.CATEGORY);

    private Combat4bClient() {
    }

    public static void init() {
        KeyMappingRegistry.register(DODGE);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (DODGE.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) {
                    int forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
                    int strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
                    NetworkManager.sendToServer(new DodgePayload(forward, strafe));
                }
            }
        });
    }

    /** Stamina bar under the mana bar, in the status panel. */
    public static void drawStamina(GuiGraphicsExtractor g, int x, int y, int w) {
        int filled = w * ClientStaminaData.stamina / Math.max(1, ClientStaminaData.maxStamina);
        g.fill(x, y, x + w, y + 4, 0xFF1C1C26);
        g.fill(x, y, x + Math.min(w, filled), y + 4, 0xFFE8C23A);
        // Dodge cooldown sweep as a thin line under the bar.
        long elapsed = System.currentTimeMillis() - ClientStaminaData.dodgeStartMs;
        if (ClientStaminaData.dodgeCooldownMs > 0 && elapsed < ClientStaminaData.dodgeCooldownMs) {
            int cd = (int) (w * (1.0 - (double) elapsed / ClientStaminaData.dodgeCooldownMs));
            g.fill(x, y + 4, x + cd, y + 5, 0xFFB0B0B0);
        }
        g.outline(x, y, w, 4, 0xFF0B0B10);
    }

    /** Player posture bar near the crosshair, only visible when there is posture damage or while staggered. */
    public static void renderHud(GuiGraphicsExtractor g, Minecraft mc) {
        boolean recent = System.currentTimeMillis() - ClientStaminaData.lastPostureMs < 6000;
        if (ClientStaminaData.posture <= 0 && !ClientStaminaData.staggered && !recent) {
            return;
        }
        int w = 70;
        int x = mc.getWindow().getGuiScaledWidth() / 2 - w / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 22;
        g.fill(x, y, x + w, y + 5, 0xB0000000);
        int filled = ClientStaminaData.staggered ? w : w * ClientStaminaData.posture / Math.max(1, ClientStaminaData.maxPosture);
        int color = ClientStaminaData.staggered ? 0xFFFF3030 : 0xFFF08A30;
        g.fill(x, y, x + Math.min(w, filled), y + 5, color);
        g.outline(x, y, w, 5, 0xFF0B0B10);
        g.centeredText(mc.font, ClientStaminaData.staggered ? "STAGGERED" : "POSTURE", x + w / 2, y + 7, 0xFFE0E0E0);
    }
}
