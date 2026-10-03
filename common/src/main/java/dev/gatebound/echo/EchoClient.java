package dev.gatebound.echo;

import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.gatebound.client.ClientHooks;
import net.minecraft.client.KeyMapping;

/** Client-only. Reached only through EnvExecutor from EchoManager.init(). */
final class EchoClient {
    /** GLFW_KEY_G. */
    private static final int KEY_G = 71;
    private static final KeyMapping TOGGLE = new KeyMapping("key.gatebound.echo_toggle", KEY_G, ClientHooks.CATEGORY);

    private EchoClient() {
    }

    static void init() {
        KeyMappingRegistry.register(TOGGLE);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (TOGGLE.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) {
                    NetworkManager.sendToServer(new EchoTogglePayload());
                }
            }
        });
    }
}
