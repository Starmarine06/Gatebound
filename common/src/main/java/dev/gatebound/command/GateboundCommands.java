package dev.gatebound.command;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.PingPayload;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class GateboundCommands {
    private GateboundCommands() {
    }

    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) ->
                dispatcher.register(Commands.literal("gatebound")
                        .then(Commands.literal("ping")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    NetworkManager.sendToPlayer(player, new PingPayload("pong from the server"));
                                    ctx.getSource().sendSuccess(() -> Component.literal("Ping sent"), false);
                                    return 1;
                                }))));
    }
}
