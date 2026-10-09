package dev.gatebound.client;

//? if >=26.1 {
import dev.gatebound.compat.ClientCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.gatebound.data.ClientGrappleData;
import dev.gatebound.network.GrappleSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the compound bow's grapple as a real lead: the game's own leash renderer, from the player's chest to the anchor.
 * It sags while the line is slack and runs straight when taut, exactly like a lead. Registered per loader like QuiverBackLayer.
 */
public final class GrappleLeashLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public GrappleLeashLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible || state.isSpectator || state.deathTime > 0 || state.isUpsideDown
                || !(state.hasPose(Pose.STANDING) || state.hasPose(Pose.CROUCHING))) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Entity owner = level.getEntity(state.id);
        GrappleSyncPayload.Line line = owner == null ? null : ClientGrappleData.of(owner.getUUID());
        if (line == null) {
            return;
        }
        Vec3 anchor = new Vec3(line.x(), line.y(), line.z());
        Vec3 offset = new Vec3(0.0, state.hasPose(Pose.CROUCHING) ? 0.95 : 1.15, 0.0); // about the chest
        Vec3 start = new Vec3(state.x, state.y, state.z).add(offset);

        EntityRenderState.LeashState leash = new EntityRenderState.LeashState();
        leash.offset = offset;
        leash.start = start;
        leash.end = anchor;
        leash.slack = start.distanceTo(anchor) < line.rope() - 0.4; // slack hangs low, taut runs straight
        leash.startBlockLight = 12;
        leash.endBlockLight = 12;
        leash.startSkyLight = 15;
        leash.endSkyLight = 15;

        // The layer's pose is the player model's (turned with the body, flipped, scaled); undo that to get the entity's own
        // world-aligned pose, which is what the leash renderer expects.
        poseStack.pushPose();
        poseStack.translate(0.0f, 1.501f, 0.0f);
        poseStack.scale(1.0f / 0.9375f, 1.0f / 0.9375f, 1.0f / 0.9375f);
        poseStack.scale(-1.0f, -1.0f, 1.0f);
        ClientCompat.rotateDegrees(poseStack, Axis.YP, -(180.0f - state.bodyRot));
        if (state.scale != 0.0f && state.scale != 1.0f) {
            float inv = 1.0f / state.scale;
            poseStack.scale(inv, inv, inv);
        }
        collector.submitLeash(poseStack, leash);
        poseStack.popPose();
    }
}
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.gatebound.data.ClientGrappleData;
import dev.gatebound.network.GrappleSyncPayload;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

// Pre-1.21.2: no leash render state, so the rope is drawn the way MobRenderer draws a lead (a ribbon of 24 segments).
public final class GrappleLeashLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final int SEGMENTS = 24;

    public GrappleLeashLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        Pose pose = player.getPose();
        if (player.isInvisible() || player.isSpectator() || player.deathTime > 0 || player.isFullyFrozen()
                || !(pose == Pose.STANDING || pose == Pose.CROUCHING)) {
            return;
        }
        GrappleSyncPayload.Line line = ClientGrappleData.of(player.getUUID());
        if (line == null) {
            return;
        }
        double px = Mth.lerp(partialTick, player.xo, player.getX());
        double py = Mth.lerp(partialTick, player.yo, player.getY());
        double pz = Mth.lerp(partialTick, player.zo, player.getZ());
        double chest = pose == Pose.CROUCHING ? 0.95 : 1.15; // about the chest
        Vec3 anchor = new Vec3(line.x(), line.y(), line.z());
        float dx = (float) (anchor.x - px);
        float dy = (float) (anchor.y - (py + chest));
        float dz = (float) (anchor.z - pz);
        boolean slack = Math.sqrt(dx * dx + dy * dy + dz * dz) < line.rope() - 0.4; // slack hangs low, taut runs straight

        // The layer's pose is the player model's (turned with the body, flipped, scaled); undo that to get world axes.
        poseStack.pushPose();
        poseStack.translate(0.0f, 1.501f, 0.0f);
        poseStack.scale(1.0f / 0.9375f, 1.0f / 0.9375f, 1.0f / 0.9375f);
        poseStack.scale(-1.0f, -1.0f, 1.0f);
        poseStack.mulPose(Axis.YP.rotationDegrees(-(180.0f - Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot))));
        poseStack.translate(0.0, chest, 0.0);

        VertexConsumer consumer = buffer.getBuffer(RenderType.leash());
        Matrix4f matrix = poseStack.last().pose();
        float horizontal = Mth.invSqrt(dx * dx + dz * dz) * 0.025f / 2.0f;
        float sideX = dz * horizontal;
        float sideZ = dx * horizontal;
        int lightCoords = LightTexture.pack(12, 15);
        for (int i = 0; i <= SEGMENTS; i++) {
            vertexPair(consumer, matrix, dx, dy, dz, lightCoords, 0.025f, 0.025f, sideX, sideZ, i, false, slack);
        }
        for (int i = SEGMENTS; i >= 0; i--) {
            vertexPair(consumer, matrix, dx, dy, dz, lightCoords, 0.025f, 0.0f, sideX, sideZ, i, true, slack);
        }
        poseStack.popPose();
    }

    private static void vertexPair(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, int light,
                                   float yOffset, float dy, float sideX, float sideZ, int index, boolean back, boolean slack) {
        float f = (float) index / SEGMENTS;
        float shade = index % 2 == (back ? 1 : 0) ? 0.7f : 1.0f;
        float r = 0.5f * shade;
        float g = 0.4f * shade;
        float b = 0.3f * shade;
        float px = x * f;
        float py = slack ? (y > 0.0f ? y * f * f : y - y * (1.0f - f) * (1.0f - f)) : y * f;
        float pz = z * f;
        consumer.addVertex(matrix, px - sideX, py + dy, pz + sideZ).setColor(r, g, b, 1.0f).setLight(light);
        consumer.addVertex(matrix, px + sideX, py + yOffset - dy, pz - sideZ).setColor(r, g, b, 1.0f).setLight(light);
    }
}
*///?}
