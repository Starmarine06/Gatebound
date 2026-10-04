package dev.gatebound.echo;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws an Echo with the vanilla model of the mob it was bound from. A hidden client-side dummy of that mob is kept per
 * type; its render state is extracted with the vanilla renderer and then overwritten with the Echo's own pose and animation.
 */
public final class EchoRenderer extends EntityRenderer<EchoEntity, EchoRenderer.State> {
    public static final class State extends EntityRenderState {
        EntityRenderState delegate;
    }

    private final EntityRenderDispatcher dispatcher;
    private final Map<String, Entity> dummies = new HashMap<>();
    private Level dummyLevel;

    public EchoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.dispatcher = context.getEntityRenderDispatcher();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    private Entity dummyFor(EchoEntity echo) {
        if (dummyLevel != echo.level()) {
            dummies.clear();
            dummyLevel = echo.level();
        }
        String id = echo.mobType();
        Entity cached = dummies.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElse(net.minecraft.world.entity.EntityTypes.ZOMBIE);
            Entity made = type.create(echo.level(), EntitySpawnReason.LOAD);
            if (made != null) {
                dummies.put(id, made);
            }
            return made;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void extractRenderState(EchoEntity echo, State state, float partialTick) {
        super.extractRenderState(echo, state, partialTick);
        state.delegate = null;
        Entity dummy = dummyFor(echo);
        if (dummy == null) {
            return;
        }
        dummy.snapTo(echo.getX(), echo.getY(), echo.getZ(), echo.getYRot(), echo.getXRot());
        dummy.tickCount = echo.tickCount;
        EntityRenderState d = dispatcher.extractEntity(dummy, partialTick);
        d.x = state.x;
        d.y = state.y;
        d.z = state.z;
        d.ageInTicks = echo.tickCount + partialTick;
        d.lightCoords = state.lightCoords;
        d.nameTag = null;
        d.outlineColor = state.outlineColor;
        d.isInvisible = state.isInvisible;
        d.displayFireAnimation = false;
        if (d instanceof LivingEntityRenderState living) {
            float body = Mth.rotLerp(partialTick, echo.yBodyRotO, echo.yBodyRot);
            float head = Mth.rotLerp(partialTick, echo.yHeadRotO, echo.yHeadRot);
            living.bodyRot = body;
            living.yRot = Mth.wrapDegrees(head - body);
            living.xRot = Mth.lerp(partialTick, echo.xRotO, echo.getXRot());
            living.walkAnimationPos = echo.walkAnimation.position(partialTick);
            living.walkAnimationSpeed = echo.walkAnimation.speed(partialTick);
            living.hasRedOverlay = echo.hurtTime > 0;
            living.deathTime = echo.deathTime;
            if (living instanceof net.minecraft.client.renderer.entity.state.ArmedEntityRenderState armed) {
                armed.currentSwing = echo.getCurrentSwing();
                armed.swingAnimation = echo.getSwingAnimation(partialTick);
            }
        }
        state.delegate = d;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.delegate != null) {
            // The pose is already at the Echo's position, so ask for zero offset by passing the state's own coordinates.
            dispatcher.submit(state.delegate, camera, state.delegate.x, state.delegate.y, state.delegate.z, poseStack, collector);
        }
        super.submit(state, poseStack, collector, camera);
    }
}
