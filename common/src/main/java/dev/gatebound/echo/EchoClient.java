package dev.gatebound.echo;

import dev.gatebound.compat.Compat;
import dev.gatebound.compat.ClientCompat;
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

    /** Static holder; not instantiable. */
    private EchoClient() {
    }

    /** True while the select key is physically held (read from the keyboard, not from repeating key events). */
    static boolean selectHeld() {
        return dev.gatebound.compat.ClientCompat.keyHeld(SELECT);
    }

    /** Opens the echo order wheel. */
    static void onWheel(EchoWheelPayload payload) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new EchoWheelScreen(payload));
    }

    /** Opens the Echoes screen, or refreshes it when it is already showing. */
    static void onRoster(EchoRosterPayload payload) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (ClientCompat.screen(mc) instanceof EchoScreen screen) {
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

    /** Opens the bind menu for echoes. */
    static void onBindMenu(EchoBindMenuPayload payload) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new EchoBindScreen(payload));
    }

    /** Registers the echo HUD, keys, renderers and key handlers. */
    static void init() {
        dev.architectury.event.events.client.ClientGuiEvent.RENDER_HUD.register(EchoHud::render);
        KeyMappingRegistry.register(TOGGLE);
        KeyMappingRegistry.register(MENU);
        KeyMappingRegistry.register(SELECT);
        dev.architectury.registry.client.level.entity.EntityRendererRegistry.register(EchoEntities.ECHO, EchoRenderer::new);
        dev.architectury.registry.client.level.entity.EntityRendererRegistry.register(EchoEntities.CORPSE, EchoRenderer::new);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (MENU.consumeClick()) {
                if (ClientCompat.screen(mc) == null && mc.player != null && NetworkManager.canServerReceive(EchoMenuPayload.TYPE)) {
                    NetworkManager.sendToServer(new EchoMenuPayload());
                }
            }
            while (SELECT.consumeClick()) {
                if (ClientCompat.screen(mc) == null && mc.player != null && NetworkManager.canServerReceive(EchoSelectPayload.TYPE)) {
                    NetworkManager.sendToServer(new EchoSelectPayload());
                }
            }
            while (TOGGLE.consumeClick()) {
                if (ClientCompat.screen(mc) == null && mc.player != null) {
                    if (!NetworkManager.canServerReceive(EchoTogglePayload.TYPE)) {
                        Compat.overlay(mc.player, net.minecraft.network.chat.Component.literal(
                                "Echoes: the server does not accept the echo packet"));
                        continue;
                    }
                    NetworkManager.sendToServer(new EchoTogglePayload());
                }
            }
        });
    }
}
