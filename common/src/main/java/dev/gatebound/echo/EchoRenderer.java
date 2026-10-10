package dev.gatebound.echo;

//? if >=26.1 {
import dev.gatebound.compat.MobTypes;
import dev.gatebound.compat.ClientCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
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
        float visualScale = 1f;
        /** A soul body: drawn flat on its back instead of the vanilla death pose, which tips a mob onto its side. */
        boolean lying;
        float bodyYaw;
        float lift;
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
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElse(MobTypes.ZOMBIE);
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
        dummy.setId(echo.getId());
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
            living.deathTime = echo.isCorpse() ? 0f : echo.deathTime;
            state.bodyYaw = body;
            if (echo.isCorpse()) {
                living.walkAnimationSpeed = 0f;
                living.hasRedOverlay = false;
            }
            if (living instanceof net.minecraft.client.renderer.entity.state.ArmedEntityRenderState armed) {
                ClientCompat.applySwing(armed, echo, partialTick);
            }
        }
        float natural = Math.max(0.1f, dummy.getBbHeight() / 1.95f);
        state.visualScale = Math.max(0.5f, Math.min(4f, echo.getScale() / natural));
        state.lying = echo.isCorpse();
        state.lift = dummy.getBbWidth() * 0.5f;
        state.delegate = d;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.delegate != null) {
            // The pose is already at the Echo's position, so no extra translation is wanted.
            poseStack.pushPose();
            poseStack.scale(state.visualScale, state.visualScale, state.visualScale);
            if (state.lying) {
                // Lay the model on its back (face up) in its own frame, then keep its facing: the vanilla renderer applies
                // the yaw turn itself, so the same turn is wrapped around the tilt.
                float turn = 180f - state.bodyYaw;
                poseStack.translate(0.0, state.lift, 0.0);
                ClientCompat.rotateDegrees(poseStack, com.mojang.math.Axis.YP, turn);
                ClientCompat.rotateDegrees(poseStack, com.mojang.math.Axis.XP, -90f);
                ClientCompat.rotateDegrees(poseStack, com.mojang.math.Axis.YP, -turn);
            }
            dispatcher.submit(state.delegate, camera, 0.0, 0.0, 0.0, poseStack, collector);
            poseStack.popPose();
        }
        super.submit(state, poseStack, collector, camera);
    }
}
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.gatebound.compat.MobTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

// Pre-1.21.2 renderers read the entity directly, so the hidden dummy is simply kept in step with the Echo each frame.
public final class EchoRenderer extends EntityRenderer<EchoEntity> {
    private final EntityRenderDispatcher dispatcher;
    private final Map<String, Entity> dummies = new HashMap<>();
    private Level dummyLevel;

    public EchoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.dispatcher = context.getEntityRenderDispatcher();
    }

    @Override
    public ResourceLocation getTextureLocation(EchoEntity echo) {
        return ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png"); // never drawn: the dummy draws itself
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
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(id)).orElse(MobTypes.ZOMBIE);
            Entity made = type.create(echo.level());
            if (made != null) {
                dummies.put(id, made);
            }
            return made;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Field[] WALK = walkFields();

    private static Field[] walkFields() {
        try {
            Field[] fields = new Field[3];
            String[] names = {"speedOld", "speed", "position"};
            for (int i = 0; i < names.length; i++) {
                fields[i] = net.minecraft.world.entity.WalkAnimationState.class.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
            }
            return fields;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void copyWalk(EchoEntity from, LivingEntity to, boolean still) {
        if (WALK == null) {
            return;
        }
        try {
            for (Field f : WALK) {
                f.setFloat(to.walkAnimation, still && !"position".equals(f.getName()) ? 0f : f.getFloat(from.walkAnimation));
            }
        } catch (ReflectiveOperationException e) {
            // the walk cycle just stays still
        }
    }

    @Override
    public void render(EchoEntity echo, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light) {
        Entity dummy = dummyFor(echo);
        if (dummy == null) {
            return;
        }
        dummy.moveTo(echo.getX(), echo.getY(), echo.getZ(), echo.getYRot(), echo.getXRot());
        dummy.xo = echo.xo;
        dummy.yo = echo.yo;
        dummy.zo = echo.zo;
        dummy.yRotO = echo.yRotO;
        dummy.xRotO = echo.xRotO;
        dummy.setId(echo.getId());
        dummy.tickCount = echo.tickCount;
        if (dummy instanceof LivingEntity living) {
            living.yBodyRot = echo.yBodyRot;
            living.yBodyRotO = echo.yBodyRotO;
            living.yHeadRot = echo.yHeadRot;
            living.yHeadRotO = echo.yHeadRotO;
            living.hurtTime = echo.isCorpse() ? 0 : echo.hurtTime;
            living.deathTime = echo.isCorpse() ? 0 : echo.deathTime;
            living.swinging = echo.swinging;
            living.swingTime = echo.swingTime;
            living.swingingArm = echo.swingingArm;
            living.attackAnim = echo.attackAnim;
            living.oAttackAnim = echo.oAttackAnim;
            copyWalk(echo, living, echo.isCorpse());
        }
        float natural = Math.max(0.1f, dummy.getBbHeight() / 1.95f);
        float scale = Math.max(0.5f, Math.min(4f, echo.getScale() / natural));
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        if (echo.isCorpse()) {
            // A soul body lies flat on its back instead of the vanilla death pose, which tips a mob onto its side.
            float turn = 180f - echo.yBodyRot;
            poseStack.translate(0.0, dummy.getBbWidth() * 0.5f, 0.0);
            poseStack.mulPose(Axis.YP.rotationDegrees(turn));
            poseStack.mulPose(Axis.XP.rotationDegrees(-90f));
            poseStack.mulPose(Axis.YP.rotationDegrees(-turn));
        }
        // The pose is already at the Echo's position, so the dummy is drawn at the origin.
        dispatcher.render(dummy, 0.0, 0.0, 0.0, entityYaw, partialTick, poseStack, buffer, light);
        poseStack.popPose();
        super.render(echo, entityYaw, partialTick, poseStack, buffer, light);
    }
}
*///?}
