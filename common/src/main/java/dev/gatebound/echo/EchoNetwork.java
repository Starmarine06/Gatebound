package dev.gatebound.echo;

import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;

final class EchoNetwork {
    private EchoNetwork() {
    }

    static void init() {
        NetworkManager.registerC2S(EchoTogglePayload.TYPE, EchoTogglePayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player && player.isAlive()) {
                        EchoManager.toggle(player);
                    }
                }));
    }
}
