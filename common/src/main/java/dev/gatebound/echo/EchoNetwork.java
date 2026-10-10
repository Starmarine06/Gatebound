package dev.gatebound.echo;

import dev.gatebound.compat.Compat;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.server.level.ServerPlayer;

final class EchoNetwork {
    /** Static holder; not instantiable. */
    private EchoNetwork() {
    }

    /** Registers the echo network packets. */
    static void init() {
        Compat.registerC2S(EchoTogglePayload.TYPE, EchoTogglePayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player && player.isAlive()) {
                        EchoManager.toggle(player);
                        EchoManager.sendRoster(player, false);
                    }
                }));
        Compat.registerC2S(EchoMenuPayload.TYPE, EchoMenuPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        EchoManager.sendRoster(player, true);
                    }
                }));
        Compat.registerC2S(EchoSelectPayload.TYPE, EchoSelectPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player && player.isAlive()) {
                        EchoManager.pressSelect(player);
                    }
                }));
        Compat.registerC2S(EchoOrderPayload.TYPE, EchoOrderPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        switch (payload.kind()) {
                            case 0 -> EchoManager.setGroupMode(player, payload.a(), payload.b());
                            case 1 -> EchoManager.setEchoGroup(player, payload.a(), payload.b());
                            case 2 -> EchoManager.selectGate(player, payload.a());
                            case 3 -> EchoView.order(player, payload.a());
                            case 4 -> EchoManager.evenSplit(player);
                            case 5 -> EchoManager.allToGroup(player, payload.a());
                            case 6 -> EchoManager.groupByType(player);
                            case 7 -> EchoManager.setPersonalOrder(player, payload.a(), payload.b());
                            default -> {
                            }
                        }
                        EchoManager.sendRoster(player, false);
                    }
                }));
        Compat.registerC2S(EchoBindPayload.TYPE, EchoBindPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        EchoManager.bindCorpse(player, payload.entityId(), payload.group());
                    }
                }));
        Compat.registerC2S(EchoWheelPickPayload.TYPE, EchoWheelPickPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player && player.isAlive()) {
                        EchoManager.wheelPick(player, payload.entityId(), payload.choice());
                    }
                }));
        Compat.registerS2C(EchoWheelPayload.TYPE, EchoWheelPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> EchoClient.onWheel(payload))));
        Compat.registerS2C(EchoBindMenuPayload.TYPE, EchoBindMenuPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> EchoClient.onBindMenu(payload))));
        Compat.registerS2C(EchoViewPayload.TYPE, EchoViewPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> EchoClient.onView(payload))));
        Compat.registerS2C(EchoRosterPayload.TYPE, EchoRosterPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> EchoClient.onRoster(payload))));
    }
}
