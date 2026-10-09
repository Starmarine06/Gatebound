package dev.gatebound.neoforge;

//? if >=26.1 {
import dev.gatebound.Gatebound;
import dev.gatebound.client.QuiverBackLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = Gatebound.MOD_ID, value = Dist.CLIENT)
public final class GateboundNeoForgeClient {
    private GateboundNeoForgeClient() {
    }

    @SubscribeEvent
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            AvatarRenderer<AbstractClientPlayer> renderer = event.getPlayerRenderer(skin);
            if (renderer != null) {
                renderer.addLayer(new QuiverBackLayer(renderer));
                renderer.addLayer(new dev.gatebound.client.GrappleLeashLayer(renderer));
            }
        }
    }
}
//?} else {
/*import dev.gatebound.Gatebound;
import dev.gatebound.client.GrappleLeashLayer;
import dev.gatebound.client.QuiverBackLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = Gatebound.MOD_ID, value = Dist.CLIENT)
public final class GateboundNeoForgeClient {
    private GateboundNeoForgeClient() {
    }

    @SubscribeEvent
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            if (event.getSkin(skin) instanceof PlayerRenderer renderer) {
                renderer.addLayer(new QuiverBackLayer(renderer));
                renderer.addLayer(new GrappleLeashLayer(renderer));
            }
        }
    }
}
*///?}
