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
    /** GLFW_KEY_H: opens the Echoes command screen. */
    private static final KeyMapping MENU = new KeyMapping("key.gatebound.echo_menu", 72, ClientHooks.CATEGORY);
    private static final KeyMapping TOGGLE = new KeyMapping("key.gatebound.echo_toggle", KEY_G, ClientHooks.CATEGORY);

    private EchoClient() {
    }

    /** Opens the Echoes screen, or refreshes it when it is already showing. */
    static void onRoster(EchoRosterPayload payload) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.gui.screen() instanceof EchoScreen screen) {
            screen.update(payload);
        } else if (payload.open()) {
            mc.setScreenAndShow(new EchoScreen(payload));
        }
    }

    /** Moves the camera into an echo's eyes, or back to the player when the id is -1. */
    static void onView(EchoViewPayload payload) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        net.minecraft.world.entity.Entity target = payload.entityId() >= 0 ? mc.level.getEntity(payload.entityId()) : null;
        mc.setCameraEntity(target != null ? target : mc.player);
    }

    static void onBindMenu(EchoBindMenuPayload payload) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new EchoBindScreen(payload));
    }

    static void init() {
        dev.architectury.event.events.client.ClientGuiEvent.RENDER_HUD.register(EchoHud::render);
        KeyMappingRegistry.register(TOGGLE);
        KeyMappingRegistry.register(MENU);
        dev.architectury.registry.client.level.entity.EntityRendererRegistry.register(EchoEntities.ECHO, EchoRenderer::new);
        dev.architectury.registry.client.level.entity.EntityRendererRegistry.register(EchoEntities.CORPSE, EchoRenderer::new);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (MENU.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null && NetworkManager.canServerReceive(EchoMenuPayload.TYPE)) {
                    NetworkManager.sendToServer(new EchoMenuPayload());
                }
            }
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
