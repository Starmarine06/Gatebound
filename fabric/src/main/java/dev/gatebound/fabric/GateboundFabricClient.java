package dev.gatebound.fabric;

//? if >=26.1 {
import dev.gatebound.client.QuiverBackLayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public final class GateboundFabricClient implements ClientModInitializer {
    @Override
    @SuppressWarnings("unchecked")
    public void onInitializeClient() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?>) {
                helper.register(new QuiverBackLayer((RenderLayerParent<AvatarRenderState, PlayerModel>) renderer));
                helper.register(new dev.gatebound.client.GrappleLeashLayer((RenderLayerParent<AvatarRenderState, PlayerModel>) renderer));
            }
        });
    }
}
//?} else {
/*import dev.gatebound.client.GrappleLeashLayer;
import dev.gatebound.client.QuiverBackLayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

public final class GateboundFabricClient implements ClientModInitializer {
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void onInitializeClient() {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
            if (renderer instanceof PlayerRenderer player) {
                helper.register((RenderLayer) new QuiverBackLayer(player));
                helper.register((RenderLayer) new GrappleLeashLayer(player));
            }
        });
    }
}
*///?}
