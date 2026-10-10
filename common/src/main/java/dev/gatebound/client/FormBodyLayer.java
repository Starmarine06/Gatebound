package dev.gatebound.client;

//? if >=26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.gatebound.Gatebound;
import dev.gatebound.compat.ClientCompat;
import dev.gatebound.data.ClientFormData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Dresses a player while a Mage level 100 form is active, as part of the player model like armour so it follows the body exactly:
 * the posed model is drawn again with an animated skin-format texture of the element (lava, crystal, lightning, wind, stone, gold
 * light, void), and the form's wings, halo, horns, crown, spikes, pauldrons and tendrils are effect models pinned to the torso and
 * head. Which players are in a form comes from the server (ClientFormData). Client side only, registered per loader like QuiverBackLayer.
 */
public final class FormBodyLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final int FRAMES = 4;
    private static final int FULL_BRIGHT = 15728880;
    private static final Map<String, Identifier[]> TEXTURES = new HashMap<>();
    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private final ItemStackRenderState item = new ItemStackRenderState();

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

    /** A plain item that shows one of the mod's effect models (assets/gatebound/items/fx/NAME.json). */
    private static ItemStack stack(String model) {
        return STACKS.computeIfAbsent(model, m -> {
            ItemStack s = new ItemStack(Items.PAPER);
            s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "fx/" + m));
            return s;
        });
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
        if (owner == null) {
            return;
        }
        Set<String> tags = ClientFormData.tags(owner.getUUID());
        if (tags.isEmpty()) {
            return;
        }
        float t = state.ageInTicks;
        for (String tag : tags) {
            if (tag.startsWith("form:")) {
                String element = tag.substring(5);
                int frame = (int) ((level.getGameTime() / 3) % FRAMES);
                renderColoredCutoutModel(getParentModel(), texture(element, frame), poseStack, collector, FULL_BRIGHT, state, -1, 1);
                attachments(poseStack, collector, state, owner, element, t);
            } else {
                effect(poseStack, collector, state, owner, tag, t);
            }
        }
    }

    // ---------------------------------------------------------------- attachments (torso space has y down and the back at +z)

    private void attachments(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, String element, float t) {
        PlayerModel m = getParentModel();
        // every form stands in its own big circle
        draw(ps, c, state, owner, null, "circle_" + element, 0.0f, 1.45f, 0.0f, 0, t * 1.5f, 0, 5.5f, 5.5f, 5.5f);
        switch (element) {
            case "fire" -> {
                wings(ps, c, state, owner, m, "wings_fire", 3.8f, t);
                horns(ps, c, state, owner, m, "horns_fire", 1.0f);
            }
            case "ice" -> {
                wings(ps, c, state, owner, m, "wings_ice", 4.0f, t);
                horns(ps, c, state, owner, m, "crown_ice", 0.8f);
                spike(ps, c, state, owner, m, 1);
                spike(ps, c, state, owner, m, -1);
            }
            case "rogue" -> {
                wings(ps, c, state, owner, m, "wings_rogue", 4.2f, t);
                halo(ps, c, state, owner, m, "halo_rogue", 1.1f, t * 5.0f);
                for (int i = 0; i < 4; i++) { // the shadow arms, reaching out behind the shoulders
                    float sway = (float) Math.sin(t * 0.13f + i * 1.7f);
                    draw(ps, c, state, owner, m.body, "tendril", (i - 1.5f) * 0.3f, 0.2f, 0.36f, 30.0f + sway * 16.0f, 0, sway * 14.0f, 0.8f, 2.6f, 0.8f);
                }
            }
            case "summoner" -> {
                wings(ps, c, state, owner, m, "wings_summoner", 4.2f, t);
                horns(ps, c, state, owner, m, "crown_summoner", 0.9f);
                for (int i = 0; i < 3; i++) { // hooded spirits circling the sovereign
                    float a = t * 0.12f + i * 2.0944f;
                    draw(ps, c, state, owner, null, "soul_ghost", (float) Math.cos(a) * 1.7f, 0.3f + (float) Math.sin(t * 0.08f + i) * 0.2f,
                            (float) Math.sin(a) * 1.7f, 0, 90.0f - (float) Math.toDegrees(a), 0, 1.7f, 1.7f, 1.7f);
                }
                for (int i = 0; i < 4; i++) { // wisps spiralling up the body
                    float a = -t * 0.25f + i * 1.5708f;
                    draw(ps, c, state, owner, m.body, "soul_wisp", (float) Math.cos(a) * 0.9f, 0.6f - ((t * 0.02f + i * 0.25f) % 1.0f) * 1.4f,
                            (float) Math.sin(a) * 0.9f, 0, -(float) Math.toDegrees(a), 0, 1.0f, 1.0f, 1.0f);
                }
            }
            case "ranger" -> {
                wings(ps, c, state, owner, m, "wings_ranger", 4.2f, t);
                horns(ps, c, state, owner, m, "antlers_ranger", 1.3f);
                for (int i = 0; i < 6; i++) { // glowing arrows circling the predator, points outward
                    float a = t * 0.2f + i * 1.0472f;
                    draw(ps, c, state, owner, null, "arrow_glow", (float) Math.cos(a) * 1.5f, 0.1f + (float) Math.sin(t * 0.1f + i) * 0.2f,
                            (float) Math.sin(a) * 1.5f, 0, -(float) Math.toDegrees(a), 0, 2.2f, 0.8f, 0.8f);
                }
                draw(ps, c, state, owner, null, "hawk", (float) Math.cos(t * 0.05f) * 3.4f, -1.4f + (float) Math.sin(t * 0.07f) * 0.3f,
                        (float) Math.sin(t * 0.05f) * 3.4f, 0, -(float) Math.toDegrees(t * 0.05f) - 90.0f, 0, 2.6f, 2.6f, 2.6f);
            }
            case "warrior" -> {
                wings(ps, c, state, owner, m, "wings_warrior", 4.4f, t);
                horns(ps, c, state, owner, m, "horns_fire", 1.0f);
                draw(ps, c, state, owner, m.body, "steel_plate", 0.58f, -0.05f, 0.0f, 0, 15.0f, 0, 2.3f, 2.3f, 2.3f);
                draw(ps, c, state, owner, m.body, "steel_plate", -0.58f, -0.05f, 0.0f, 0, -15.0f, 0, 2.3f, 2.3f, 2.3f);
                draw(ps, c, state, owner, m.body, "sword", 0.2f, 0.1f, 0.34f, 0, 0, 30.0f, 1.8f, 3.4f, 1.8f); // greatsword across the back
                draw(ps, c, state, owner, m.body, "blade_ring", 0.0f, 0.7f, 0.0f, 0, t * 40.0f, 0, 5.5f, 5.5f, 5.5f);
            }
            case "lightning" -> {
                wings(ps, c, state, owner, m, "wings_lightning", 4.2f, t);
                halo(ps, c, state, owner, m, "halo_lightning", 1.3f, t * 7.0f);
                for (int i = 0; i < 4; i++) { // bolts circling the body
                    float a = t * 0.3f + i * 1.5708f;
                    draw(ps, c, state, owner, m.body, "bolt", (float) Math.cos(a) * 1.0f, 0.35f, (float) Math.sin(a) * 1.0f, 0, 0, 0, 0.9f, 2.4f, 0.9f);
                }
            }
            case "wind" -> {
                wings(ps, c, state, owner, m, "wings_wind", 4.2f, t);
                draw(ps, c, state, owner, null, "tornado", 0.0f, 0.75f, 0.0f, 0, t * 22.0f, 0, 3.4f, 2.6f, 3.4f);
                for (int i = 0; i < 4; i++) { // blades circling the body
                    float a = t * 0.22f + i * 1.5708f;
                    draw(ps, c, state, owner, m.body, "wind_blade", (float) Math.cos(a) * 1.3f, 0.4f + (float) Math.sin(t * 0.1f + i) * 0.15f,
                            (float) Math.sin(a) * 1.3f, 0, 90.0f - (float) Math.toDegrees(a), 0, 1.8f, 1.8f, 1.8f);
                }
            }
            case "earth" -> {
                wings(ps, c, state, owner, m, "wings_earth", 4.0f, t);
                pauldron(ps, c, state, owner, m, 1);
                pauldron(ps, c, state, owner, m, -1);
                for (int i = 0; i < 5; i++) {
                    float a = t * 0.07f + i * 1.2566f;
                    draw(ps, c, state, owner, null, "boulder", (float) Math.cos(a) * 1.8f, 0.2f + (float) Math.sin(t * 0.09f + i) * 0.25f,
                            (float) Math.sin(a) * 1.8f, t * 3.0f, t * 4.0f + i * 60.0f, 0, 0.95f, 0.95f, 0.95f);
                }
            }
            case "light" -> {
                wings(ps, c, state, owner, m, "wings_light", 4.4f, t);
                halo(ps, c, state, owner, m, "halo_light", 1.2f, t * 3.0f);
            }
            case "dark" -> {
                wings(ps, c, state, owner, m, "wings_dark", 4.0f, t);
                halo(ps, c, state, owner, m, "halo_dark", 1.2f, -t * 4.0f);
                for (int i = 0; i < 3; i++) {
                    tendril(ps, c, state, owner, m, i, t);
                }
            }
            default -> {
            }
        }
    }

    /** Draws an effect model on a body part: translate, tilt, scale (a model is 1 block across before scaling). */
    private void draw(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, ModelPart part, String model,
                      float tx, float ty, float tz, float rotX, float rotY, float rotZ, float sx, float sy, float sz) {
        item.clear();
        Minecraft.getInstance().getItemModelResolver().updateForNonLiving(item, stack(model), ItemDisplayContext.NONE, owner);
        if (item.isEmpty()) {
            return;
        }
        ps.pushPose();
        if (part != null) {
            part.translateAndRotate(ps);
        }
        ps.translate(tx, ty, tz);
        ClientCompat.rotateDegrees(ps, Axis.YP, rotY);
        ClientCompat.rotateDegrees(ps, Axis.XP, rotX);
        ClientCompat.rotateDegrees(ps, Axis.ZP, 180.0f + rotZ); // stand the model upright in this y-down space
        ps.scale(sx, sy, sz);
        item.submit(ps, c, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor);
        ps.popPose();
    }

    /** Warrior and other body effects, by tag. Torso space: y down, the front at -z; feet are 1.5 below the neck. */
    private void effect(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, String tag, float t) {
        PlayerModel m = getParentModel();
        String[] a = tag.split(":");
        switch (a[0]) {
            case "circle" -> { // a circle lying under the feet: circle:<key>:<size>
                float size = a.length > 2 ? Float.parseFloat(a[2]) : 3.0f;
                float pulse = 1.0f + 0.04f * (float) Math.sin(t * 0.25f);
                draw(ps, c, state, owner, null, "circle_" + a[1], 0.0f, 1.45f, 0.0f, 0, t * 2.0f, 0, size * pulse, size * pulse, size * pulse);
            }
            case "iron_skin" -> {
                for (int i = 0; i < 4; i++) {
                    float ang = t * 0.17f + i * 1.5708f;
                    draw(ps, c, state, owner, m.body, "steel_plate", (float) Math.cos(ang) * 0.95f,
                            0.3f + (float) Math.sin(t * 0.12f + i) * 0.1f, (float) Math.sin(ang) * 0.95f, 0,
                            90.0f - (float) Math.toDegrees(ang), 0, 1.5f, 1.5f, 1.5f);
                }
            }
            case "bulwark" -> {
                float pulse = 2.3f + 0.08f * (float) Math.sin(t * 0.2f);
                draw(ps, c, state, owner, m.body, "shield_sigil", 0.0f, 0.35f, -1.05f, 0, 0, 0, pulse, pulse, pulse);
            }
            case "whirlwind" -> draw(ps, c, state, owner, m.body, "blade_ring", 0.0f, 0.55f, 0.0f, 0, t * 63.0f, 0, 7.0f, 7.0f, 7.0f);
            case "overcharge" -> {
                for (int i = 0; i < 4; i++) { // bolts circling the arms
                    float ang = t * 0.35f + i * 1.5708f;
                    draw(ps, c, state, owner, m.body, "bolt", (float) Math.cos(ang) * 0.9f, 0.35f, (float) Math.sin(ang) * 0.9f, 0, 0, 0, 0.8f, 2.0f, 0.8f);
                }
            }
            case "stone_armor" -> {
                pauldron(ps, c, state, owner, m, 1);
                pauldron(ps, c, state, owner, m, -1);
                for (int i = 0; i < 3; i++) {
                    float ang = t * 0.08f + i * 2.0944f;
                    draw(ps, c, state, owner, null, "boulder", (float) Math.cos(ang) * 1.5f, 0.2f + (float) Math.sin(t * 0.09f + i) * 0.2f,
                            (float) Math.sin(ang) * 1.5f, t * 3.0f, t * 4.0f + i * 60.0f, 0, 0.8f, 0.8f, 0.8f);
                }
            }
            case "trance" -> {
                for (int i = 0; i < 5; i++) { // leaves spiralling up the body
                    float ang = t * 0.12f + i * 1.2566f;
                    float rise = (t * 0.02f + i * 0.2f) % 1.0f;
                    draw(ps, c, state, owner, null, "leaf", (float) Math.cos(ang) * 1.4f, 1.2f - rise * 1.8f, (float) Math.sin(ang) * 1.4f,
                            0, -(float) Math.toDegrees(ang) + t * 6.0f, 0, 1.0f, 1.0f, 1.0f);
                }
            }
            case "wind_wings" -> wings(ps, c, state, owner, m, "wings_wind", 4.0f, t);
            case "wind_barrier" -> draw(ps, c, state, owner, null, "tornado", 0.0f, 0.75f, 0.0f, 0, t * 22.0f, 0, 3.6f, 2.8f, 3.6f);
            case "eclipse" -> halo(ps, c, state, owner, m, "halo_dark", 1.2f, t * 4.0f);
            case "avatar" -> {
                draw(ps, c, state, owner, m.head, "slash", 0.22f, -0.62f, 0.0f, 90.0f, 35.0f, 0, 1.1f, 1.1f, 1.1f);
                draw(ps, c, state, owner, m.head, "slash", -0.22f, -0.62f, 0.0f, 90.0f, -35.0f, 0, 1.1f, 1.1f, 1.1f);
            }
            default -> {
            }
        }
    }

    /** Wings spread across the upper back; the root sits just under the neck. */
    private void wings(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, PlayerModel m, String model, float span, float t) {
        float flap = 1.0f + 0.08f * (float) Math.sin(t * 0.3f);
        draw(ps, c, state, owner, m.body, model, 0.0f, 0.15f - 0.086f * span, 0.2f, 0, 0, 0, span * flap, span, span);
    }

    private void halo(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, PlayerModel m, String model, float size, float spinDeg) {
        draw(ps, c, state, owner, m.head, model, 0.0f, -0.62f, 0.0f, 0, spinDeg, 0, size, size, size);
    }

    /** A plane model standing on the head (its texture's base line sits a little above the plane's middle). */
    private void horns(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, PlayerModel m, String model, float size) {
        draw(ps, c, state, owner, m.head, model, 0.0f, -0.38f + 0.047f * size, 0.0f, 0, 0, 0, size, size, size);
    }

    private void spike(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, PlayerModel m, int sign) {
        draw(ps, c, state, owner, m.body, "ice_spike", 0.46f * sign, -0.12f, 0.0f, 0, 0, 25.0f * sign, 0.9f, 0.9f, 0.9f);
    }

    private void pauldron(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, PlayerModel m, int sign) {
        draw(ps, c, state, owner, m.body, "boulder", 0.5f * sign, -0.02f, 0.0f, 0, 20.0f * sign, 0, 0.7f, 0.55f, 0.7f);
    }

    private void tendril(PoseStack ps, SubmitNodeCollector c, AvatarRenderState state, Entity owner, PlayerModel m, int i, float t) {
        float sway = (float) Math.sin(t * 0.11f + i * 2.1f);
        draw(ps, c, state, owner, m.body, "tendril", (i - 1) * 0.16f, 0.9f, 0.3f, 20.0f + sway * 14.0f, 0, sway * 10.0f, 0.7f, 2.2f, 0.7f);
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

// Pre-1.21.2 renderers have no render states: the layer reads the player directly. Effect models need the item_model
// component (1.21.2+), so on this version only the body skin is drawn.
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
