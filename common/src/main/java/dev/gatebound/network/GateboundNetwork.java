package dev.gatebound.network;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.Gatebound;
import net.minecraft.network.chat.Component;

public final class GateboundNetwork {
    private GateboundNetwork() {
    }

    public static void init() {
        NetworkManager.registerS2C(PingPayload.TYPE, PingPayload.CODEC, (payload, context) -> {
            context.queue(() -> context.getPlayer().sendSystemMessage(Component.literal("[Gatebound] Server says: " + payload.message())));
            NetworkManager.sendToServer(new PongPayload());
        });

        NetworkManager.registerC2S(PongPayload.TYPE, PongPayload.CODEC, (payload, context) ->
                context.queue(() -> Gatebound.LOGGER.info("Received pong from {}", context.getPlayer().getName().getString())));
    }
}
