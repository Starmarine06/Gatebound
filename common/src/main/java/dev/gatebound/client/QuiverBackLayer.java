package dev.gatebound.client;

//? if >=26.1 {
import dev.gatebound.compat.ClientCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.gatebound.data.ClientQuiverData;
import dev.gatebound.item.QuiverItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the quiver on a player's back, as part of the player model like armour (client side; registered per loader).
 * Which players carry one comes from the server (ClientQuiverData).
 */
public final class QuiverBackLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private final ItemStackRenderState item = new ItemStackRenderState();
    private ItemStack stack;

    public QuiverBackLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible || state.isSpectator) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        Entity owner = level.getEntity(state.id);
        if (owner == null || !ClientQuiverData.has(owner.getUUID())) {
            return;
        }
        if (stack == null) {
            stack = new ItemStack(QuiverItem.QUIVER.get());
        }
        item.clear();
        mc.getItemModelResolver().updateForNonLiving(item, stack, ItemDisplayContext.NONE, owner);
        if (item.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack); // follows the torso, including crouching
        poseStack.translate(0.0f, 0.40f, 0.36f); // down the spine and behind the body (this space has y down, back at +z)
        ClientCompat.rotateDegrees(poseStack, Axis.ZP, 180.0f); // stand the model upright
        poseStack.scale(1.1f, 1.1f, 1.1f);
        item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }
}
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.gatebound.data.ClientQuiverData;
import dev.gatebound.item.QuiverItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

// Pre-1.21.2 renderers have no render states: the layer reads the player directly.
public final class QuiverBackLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private ItemStack stack;

    public QuiverBackLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (player.isInvisible() || player.isSpectator() || !ClientQuiverData.has(player.getUUID())) {
            return;
        }
        if (stack == null) {
            stack = new ItemStack(QuiverItem.QUIVER.get());
        }
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack); // follows the torso, including crouching
        poseStack.translate(0.0f, 0.40f, 0.36f); // down the spine and behind the body
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f)); // stand the model upright
        poseStack.scale(1.1f, 1.1f, 1.1f);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, poseStack, buffer, player.level(), player.getId());
        poseStack.popPose();
    }
}
*///?}
