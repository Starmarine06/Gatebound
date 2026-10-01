package dev.gatebound.client;

import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.gatebound.Gatebound;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Entry points that touch client-only classes. Only call these through EnvExecutor on the client. */
public final class ClientHooks {
    /** GLFW_KEY_CAPS_LOCK. Rebindable in Controls. */
    private static final int KEY_CAPS_LOCK = 280;

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "main"));
    public static final KeyMapping OPEN_STATUS = new KeyMapping("key.gatebound.status", KEY_CAPS_LOCK, CATEGORY);

    private ClientHooks() {
    }

    public static void init() {
        KeyMappingRegistry.register(OPEN_STATUS);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (OPEN_STATUS.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) {
                    mc.setScreenAndShow(new StatusScreen());
                }
            }
        });
    }

    public static void openAwakening() {
        Minecraft.getInstance().setScreenAndShow(new AwakeningScreen());
    }
}
