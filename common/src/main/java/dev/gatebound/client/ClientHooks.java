package dev.gatebound.client;

import dev.gatebound.compat.ClientCompat;
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

    //? if >=26.1 {
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "main"));
    //?} else {
    /*public static final String CATEGORY = "key.categories.gatebound";
    *///?}
    public static final KeyMapping OPEN_STATUS = new KeyMapping("key.gatebound.status", InputConstants.KEY_TAB, CATEGORY);
    public static final KeyMapping PARRY = new KeyMapping("key.gatebound.parry", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_5, CATEGORY);

    /** Z. Hold to open the wheel, point, and release to select a skill (it is not cast). */
    public static final KeyMapping WHEEL = new KeyMapping("key.gatebound.skill_wheel", InputConstants.KEY_Z, CATEGORY);
    /** R + Space: Rogue power jump. */
    public static final KeyMapping POWER_JUMP = new KeyMapping("key.gatebound.power_jump", InputConstants.KEY_R, CATEGORY);
    /** X. Casts the selected skill. */
    public static final KeyMapping CAST = new KeyMapping("key.gatebound.cast_skill", InputConstants.KEY_X, CATEGORY);

    private ClientHooks() {
    }

    /** True while the wheel key is physically held. Read straight from the keyboard, not from key events (which repeat). */
    public static boolean wheelHeld() {
        return ClientCompat.keyHeld(WHEEL);
    }

    public static void init() {
        DevUser.pin();
        KeyMappingRegistry.register(OPEN_STATUS);
        KeyMappingRegistry.register(PARRY);
        Combat4bClient.init();
        NpcClient.init();
        SpireMobClient.init();
        ClientGuiEvent.RENDER_HUD.register(GateboundHud::render);
        KeyMappingRegistry.register(WHEEL);
        KeyMappingRegistry.register(CAST);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (WHEEL.consumeClick()) {
                if (ClientCompat.screen(mc) != null || mc.player == null || SkillWheelScreen.selectedSkill() == null) {
                    continue;
                }
                if (wheelHeld()) {
                    mc.setScreenAndShow(new SkillWheelScreen()); // the wheel, until the key is released
                }
            }
            while (CAST.consumeClick()) {
                if (ClientCompat.screen(mc) == null && mc.player != null && SkillWheelScreen.selectedSkill() != null) {
                    NetworkManager.sendToServer(new UseSkillPayload(SkillWheelScreen.selectedSlot()));
                }
            }
        });
        // Invisible players leave no sprint dust: clear the flag before the entity tick spawns the particles.
        ClientTickEvent.CLIENT_PRE.register(mc -> {
            if (mc.player != null && mc.player.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY)) {
                mc.player.setSprinting(false);
            }
        });
        // Rangers draw bows faster; the same speed-up runs on the client so the pull animation matches the server.
        ClientTickEvent.CLIENT_POST.register(mc -> {
            if (mc.player != null && mc.player.isUsingItem()
                    && dev.gatebound.data.GateboundClass.RANGER.id().equals(dev.gatebound.data.ClientPlayerData.classId)) {
                dev.gatebound.skill.RangerSkills.boostDraw(mc.player);
            }
        });
        ClientTickEvent.CLIENT_POST.register(RangerAimFx::tick);
        KeyMappingRegistry.register(POWER_JUMP);
        ClientTickEvent.CLIENT_PRE.register(ClientHooks::notePowerJump);
        ClientTickEvent.CLIENT_POST.register(ClientHooks::applyPowerJump);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (PARRY.consumeClick()) {
                if (ClientCompat.screen(mc) == null && mc.player != null) {
                    NetworkManager.sendToServer(new ParryPayload());
                }
            }
        });
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (OPEN_STATUS.consumeClick()) {
                if (ClientCompat.screen(mc) == null && mc.player != null) {
                    mc.setScreenAndShow(new StatusScreen());
                }
            }
        });
    }

    private static boolean wasGrounded;

    private static void notePowerJump(Minecraft mc) {
        wasGrounded = mc.player != null && mc.player.onGround();
    }

    /**
     * Rogue power jump: hold R and press Space. The jump itself is the vanilla one, so there is no delay; right after it
     * the upward speed is raised. Other classes ignore the key.
     */
    private static void applyPowerJump(Minecraft mc) {
        net.minecraft.client.player.LocalPlayer player = mc.player;
        if (player == null || !wasGrounded || !POWER_JUMP.isDown() || !mc.options.keyJump.isDown()
                || !dev.gatebound.data.GateboundClass.ROGUE.id().equals(dev.gatebound.data.ClientPlayerData.classId)) {
            return;
        }
        net.minecraft.world.phys.Vec3 dm = player.getDeltaMovement();
        if (dm.y > 0.2 && !player.isInWater() && !player.getAbilities().flying) {
            player.setDeltaMovement(dm.x, dev.gatebound.skill.RogueConfig.powerJump, dm.z);
            wasGrounded = false;
        }
    }

    public static void openAwakening() {
        Minecraft.getInstance().setScreenAndShow(new AwakeningScreen());
    }

    public static void openSpellPick(java.util.List<String> options) {
        Minecraft.getInstance().setScreenAndShow(new SpellPickScreen(options));
    }

    public static void openElement() {
        Minecraft.getInstance().setScreenAndShow(new ElementScreen());
    }
}
