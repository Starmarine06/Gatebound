package dev.gatebound.client;

//? if >=26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.gatebound.Gatebound;
import dev.gatebound.data.ClientFormData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Re-skins the whole player model while a Mage level 100 form is active: the already posed model is drawn again with an animated
 * skin-format texture of the element (lava, crystal, lightning, wind, stone, gold light, void), fully bright, so it follows every
 * pose and animation like armour does. Which players are in a form comes from the server (ClientFormData). Client side only,
 * registered per loader like QuiverBackLayer.
 */
public final class FormBodyLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final int FRAMES = 4;
    private static final int FULL_BRIGHT = 15728880;
    private static final Map<String, Identifier[]> TEXTURES = new HashMap<>();

    public FormBodyLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    private static Identifier texture(String element, int frame) {
        return TEXTURES.computeIfAbsent(element, key -> {
            Identifier[] list = new Identifier[FRAMES];
            for (int i = 0; i < FRAMES; i++) {
                list[i] = Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "textures/entity/form/" + key + "_" + i + ".png");
            }
            return list;
        })[frame];
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible || state.isSpectator) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Entity owner = level.getEntity(state.id);
        String element = owner == null ? null : ClientFormData.of(owner.getUUID());
        if (element == null) {
            return;
        }
        int frame = (int) ((level.getGameTime() / 3) % FRAMES);
        renderColoredCutoutModel(getParentModel(), texture(element, frame), poseStack, collector, FULL_BRIGHT, state, -1, 1);
    }
}
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import dev.gatebound.Gatebound;
import dev.gatebound.data.ClientFormData;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

// Pre-1.21.2 renderers have no render states: the layer reads the player directly.
public final class FormBodyLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final int FRAMES = 4;
    private static final int FULL_BRIGHT = 15728880;
    private static final Map<String, ResourceLocation[]> TEXTURES = new HashMap<>();

    public FormBodyLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    private static ResourceLocation texture(String element, int frame) {
        return TEXTURES.computeIfAbsent(element, key -> {
            ResourceLocation[] list = new ResourceLocation[FRAMES];
            for (int i = 0; i < FRAMES; i++) {
                list[i] = ResourceLocation.fromNamespaceAndPath(Gatebound.MOD_ID, "textures/entity/form/" + key + "_" + i + ".png");
            }
            return list;
        })[frame];
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        String element = player.isInvisible() || player.isSpectator() ? null : ClientFormData.of(player.getUUID());
        if (element == null) {
            return;
        }
        int frame = (int) ((player.level().getGameTime() / 3) % FRAMES);
        renderColoredCutoutModel(getParentModel(), texture(element, frame), poseStack, buffer, FULL_BRIGHT, player, -1);
    }
}
*///?}
