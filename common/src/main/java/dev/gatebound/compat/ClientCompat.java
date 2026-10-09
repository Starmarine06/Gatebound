package dev.gatebound.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
//? if >=26.1
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** Client-side Minecraft API differences between versions; see {@link Compat}. */
public final class ClientCompat {
    private ClientCompat() {
    }

    /** The open screen, or null. Lives on {@code Minecraft.gui} from 26.2. */
    public static net.minecraft.client.gui.screens.Screen screen(net.minecraft.client.Minecraft mc) {
        //? if >=26.2 {
        return mc.gui.screen();
        //?} else {
        /*return mc.screen;
        *///?}
    }

    public static void rotateDegrees(PoseStack poseStack, Axis axis, float degrees) {
        //? if >=26.3 {
        poseStack.rotateDegrees(axis, degrees);
        //?} else {
        /*poseStack.mulPose(axis.rotationDegrees(degrees));
        *///?}
    }

    /** True while the key is physically held. Read straight from the keyboard, not from key events (which repeat). */
    public static boolean keyHeld(KeyMapping mapping) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        //? if >=26.3 {
        return key.getType() == InputConstants.Type.KEYBOARD ? InputConstants.isKeyDown(key.getValue()) : mapping.isDown();
        //?} else if >=26.1 {
        /*return key.getType() == InputConstants.Type.KEYSYM
                ? InputConstants.isKeyDown(net.minecraft.client.Minecraft.getInstance().getWindow(), key.getValue())
                : mapping.isDown();
        *///?} else {
        /*return key.getType() == InputConstants.Type.KEYSYM
                ? InputConstants.isKeyDown(net.minecraft.client.Minecraft.getInstance().getWindow().getWindow(), key.getValue())
                : mapping.isDown();
        *///?}
    }

    //? if >=26.1 {
    /** Copies the swing pose of {@code entity} onto a render state we built by hand. */
    public static void applySwing(ArmedEntityRenderState state, LivingEntity entity, float partialTick) {
        //? if >=26.3 {
        state.currentSwing = entity.getCurrentSwing();
        state.swingAnimation = entity.getSwingAnimation(partialTick);
        //?} else {
        /*state.attackTime = entity.getAttackAnim(partialTick);
        *///?}
    }
    //?}

    /** The player's active post-process effects. Only settable through the public API from 26.3; older versions have none. */
    public static List<Identifier> postEffects(LocalPlayer player) {
        //? if >=26.3 {
        return player.getActivePostEffects();
        //?} else {
        /*return List.of();
        *///?}
    }

    public static void setPostEffects(LocalPlayer player, List<Identifier> effects) {
        //? if >=26.3 {
        player.setActivePostEffects(effects);
        //?}
    }
}
