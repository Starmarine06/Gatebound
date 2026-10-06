package dev.gatebound.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.gatebound.Gatebound;
import dev.gatebound.network.ParryPayload;
import dev.gatebound.network.UseSkillPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Entry points that touch client-only classes. Only call these through EnvExecutor on the client. */
public final class ClientHooks {
    // Defaults match the keybinds saved in the Fabric dev instance, so both loaders start identical:
    // status = Tab, parry = mouse button 5 (the side button), skills = Z X C then V B N.
    /** GLFW_KEY_TAB. */
    private static final int KEY_TAB = 258;
    /** Mouse button index 4 is shown in the options file as key.mouse.5. */
    private static final int MOUSE_BUTTON_5 = 4;

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "main"));
    public static final KeyMapping OPEN_STATUS = new KeyMapping("key.gatebound.status", KEY_TAB, CATEGORY);
    public static final KeyMapping PARRY = new KeyMapping("key.gatebound.parry", InputConstants.Type.MOUSE, MOUSE_BUTTON_5, CATEGORY);

    /** GLFW_KEY_Z. Tap to cast the selected skill; hold to open the wheel, point, and release to cast that skill. */
    public static final KeyMapping WHEEL = new KeyMapping("key.gatebound.skill_wheel", 90, CATEGORY);

    private ClientHooks() {
    }

    public static void init() {
        KeyMappingRegistry.register(OPEN_STATUS);
        KeyMappingRegistry.register(PARRY);
        Combat4bClient.init();
        NpcClient.init();
        ClientGuiEvent.RENDER_HUD.register(GateboundHud::render);
        KeyMappingRegistry.register(WHEEL);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (WHEEL.consumeClick()) {
                if (mc.gui.screen() != null || mc.player == null || SkillWheelScreen.selectedSkill() == null) {
                    continue;
                }
                if (WHEEL.isDown()) {
                    mc.setScreenAndShow(new SkillWheelScreen()); // still held: the wheel, until the key is released
                } else {
                    NetworkManager.sendToServer(new UseSkillPayload(SkillWheelScreen.selectedSlot())); // a quick tap
                }
            }
        });
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (PARRY.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) {
                    NetworkManager.sendToServer(new ParryPayload());
                }
            }
        });
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

    public static void openElement() {
        Minecraft.getInstance().setScreenAndShow(new ElementScreen());
    }
}
