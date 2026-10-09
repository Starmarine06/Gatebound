package dev.gatebound.client;

import dev.gatebound.compat.ClientCompat;
import dev.architectury.registry.client.level.entity.EntityRendererRegistry;
import dev.gatebound.trade.NpcEntities;
import dev.gatebound.trade.NpcOffersPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.VillagerRenderer;

/** Client-only NPC glue: the renderer (vanilla villager look) and the trade screen opener. */
public final class NpcClient {
    private NpcClient() {
    }

    /** Called from ClientHooks.init(). */
    public static void init() {
        EntityRendererRegistry.register(NpcEntities.NPC, VillagerRenderer::new);
    }

    /** Opens the trade screen, or refreshes it when it is already open for the same NPC. */
    public static void handle(NpcOffersPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (ClientCompat.screen(mc) instanceof NpcTradeScreen screen && screen.npcId() == payload.entityId()) {
            screen.update(payload);
        } else {
            mc.setScreenAndShow(new NpcTradeScreen(payload));
        }
    }
}
