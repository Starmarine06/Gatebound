package dev.gatebound.client;

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
    /** GLFW_KEY_CAPS_LOCK. Rebindable in Controls. */
    private static final int KEY_CAPS_LOCK = 280;

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "main"));
    public static final KeyMapping OPEN_STATUS = new KeyMapping("key.gatebound.status", KEY_CAPS_LOCK, CATEGORY);

    /** GLFW_KEY_R. */
    private static final int KEY_R = 82;
    public static final KeyMapping PARRY = new KeyMapping("key.gatebound.parry", KEY_R, CATEGORY);

    /** GLFW keys Z, X, C: skill slots 1 to 3. */
    private static final int[] SKILL_DEFAULT_KEYS = {90, 88, 67};
    public static final KeyMapping[] SKILL_KEYS = new KeyMapping[3];

    static {
        for (int i = 0; i < SKILL_KEYS.length; i++) {
            SKILL_KEYS[i] = new KeyMapping("key.gatebound.skill" + (i + 1), SKILL_DEFAULT_KEYS[i], CATEGORY);
        }
    }

    private ClientHooks() {
    }

    public static void init() {
        KeyMappingRegistry.register(OPEN_STATUS);
        KeyMappingRegistry.register(PARRY);
        ClientGuiEvent.RENDER_HUD.register(GateboundHud::render);
        for (KeyMapping key : SKILL_KEYS) {
            KeyMappingRegistry.register(key);
        }
        ClientTickEvent.CLIENT_POST.register(mc -> {
            for (int slot = 0; slot < SKILL_KEYS.length; slot++) {
                while (SKILL_KEYS[slot].consumeClick()) {
                    if (mc.gui.screen() == null && mc.player != null) {
                        NetworkManager.sendToServer(new UseSkillPayload(slot));
                    }
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
}
