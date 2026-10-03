package dev.gatebound.echo;

import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.gatebound.client.ClientHooks;
import net.minecraft.client.KeyMapping;

/** Client-only. Reached only through EnvExecutor from EchoManager.init(). */
final class EchoClient {
    /** GLFW_KEY_R. Not G: vanilla 26.x binds G to Quick Actions, which swallowed the key press. */
    private static final int KEY_G = 82;
    private static final KeyMapping TOGGLE = new KeyMapping("key.gatebound.echo_toggle", KEY_G, ClientHooks.CATEGORY);

    private EchoClient() {
    }

    static void init() {
        KeyMappingRegistry.register(TOGGLE);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (TOGGLE.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) {
                    if (!NetworkManager.canServerReceive(EchoTogglePayload.TYPE)) {
                        mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                                "Echoes: the server does not accept the echo packet"));
                        continue;
                    }
                    NetworkManager.sendToServer(new EchoTogglePayload());
                }
            }
        });
    }
}
