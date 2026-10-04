package dev.gatebound.echo;

import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.server.level.ServerPlayer;

final class EchoNetwork {
    private EchoNetwork() {
    }

    static void init() {
        NetworkManager.registerC2S(EchoTogglePayload.TYPE, EchoTogglePayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player && player.isAlive()) {
                        EchoManager.toggle(player);
                        EchoManager.sendRoster(player, false);
                    }
                }));
        NetworkManager.registerC2S(EchoMenuPayload.TYPE, EchoMenuPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        EchoManager.sendRoster(player, true);
                    }
                }));
        NetworkManager.registerC2S(EchoOrderPayload.TYPE, EchoOrderPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        EchoManager.setMode(player, payload.slot(), payload.mode());
                        EchoManager.sendRoster(player, false);
                    }
                }));
        NetworkManager.registerS2C(EchoRosterPayload.TYPE, EchoRosterPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> EchoClient.onRoster(payload))));
    }
}
