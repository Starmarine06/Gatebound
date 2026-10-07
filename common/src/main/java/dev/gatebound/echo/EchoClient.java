package dev.gatebound.echo;

import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.gatebound.client.ClientHooks;
import net.minecraft.client.KeyMapping;

/** Client-only. Reached only through EnvExecutor from EchoManager.init(). */
final class EchoClient {
    /** H opens the Echoes command screen. */
    private static final KeyMapping MENU = new KeyMapping("key.gatebound.echo_menu",
            com.mojang.blaze3d.platform.InputConstants.KEY_H, ClientHooks.CATEGORY);
    /** R summons and dismisses echoes. Not G: vanilla 26.x binds G to Quick Actions, which swallowed the key press. */
    private static final KeyMapping TOGGLE = new KeyMapping("key.gatebound.echo_toggle",
            com.mojang.blaze3d.platform.InputConstants.KEY_R, ClientHooks.CATEGORY);

    /** C binds the soul body beside you, or selects/deselects the echo beside you. */
    private static final KeyMapping SELECT = new KeyMapping("key.gatebound.echo_select",
            com.mojang.blaze3d.platform.InputConstants.KEY_C, ClientHooks.CATEGORY);

    private EchoClient() {
    }

    /** True while the select key is physically held (read from the keyboard, not from repeating key events). */
    static boolean selectHeld() {
        com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(SELECT.saveString());
        return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD
                ? com.mojang.blaze3d.platform.InputConstants.isKeyDown(key.getValue()) : SELECT.isDown();
    }

    static void onWheel(EchoWheelPayload payload) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new EchoWheelScreen(payload));
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
        KeyMappingRegistry.register(SELECT);
        dev.architectury.registry.client.level.entity.EntityRendererRegistry.register(EchoEntities.ECHO, EchoRenderer::new);
        dev.architectury.registry.client.level.entity.EntityRendererRegistry.register(EchoEntities.CORPSE, EchoRenderer::new);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (MENU.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null && NetworkManager.canServerReceive(EchoMenuPayload.TYPE)) {
                    NetworkManager.sendToServer(new EchoMenuPayload());
                }
            }
            while (SELECT.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null && NetworkManager.canServerReceive(EchoSelectPayload.TYPE)) {
                    NetworkManager.sendToServer(new EchoSelectPayload());
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
