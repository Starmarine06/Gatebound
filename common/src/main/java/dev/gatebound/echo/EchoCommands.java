package dev.gatebound.echo;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /gatebound echo list|summon|dismiss|release <index>|mode follow|guard|chance <percent>. */
final class EchoCommands {
    private EchoCommands() {
    }

    static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> echo = Commands.literal("echo");

            echo.then(Commands.literal("list").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                PlayerData d = PlayerDataStore.of(player);
                int slots = EchoManager.slots(d);
                StringBuilder sb = new StringBuilder("Echoes " + d.echo.entries.size() + "/" + slots + " (mode: "
                        + "per-echo orders (press H)" + ", bind chance "
                        + String.format("%.1f", EchoConfig.chancePercent(d.get(Stat.INTELLECT), d.level)) + "%)");
                for (int i = 0; i < d.echo.entries.size(); i++) {
                    sb.append("\n  ").append(i + 1).append(". ").append(d.echo.entries.get(i).name());
                }
                ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                return 1;
            }));
            echo.then(Commands.literal("summon").executes(ctx -> {
                EchoManager.summonAll(ctx.getSource().getPlayerOrException());
                return 1;
            }));
            echo.then(Commands.literal("debug").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                Component report = EchoManager.debug(ctx.getSource().getPlayerOrException());
                ctx.getSource().sendSuccess(() -> report, false);
                return 1;
            }));
            echo.then(Commands.literal("dismiss").executes(ctx -> {
                EchoManager.dismissAll(ctx.getSource().getPlayerOrException(), true);
                return 1;
            }));
            echo.then(Commands.literal("release").then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        int index = IntegerArgumentType.getInteger(ctx, "index");
                        if (!EchoManager.release(player, index - 1)) {
                            ctx.getSource().sendFailure(Component.literal("No echo at index " + index));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("Echo " + index
                                + " released. Remaining echoes were dismissed; summon them again."), false);
                        return 1;
                    })));
            echo.then(Commands.literal("mode")
                    .then(Commands.literal("follow").executes(ctx -> setMode(ctx.getSource().getPlayerOrException(), false)))
                    .then(Commands.literal("guard").executes(ctx -> setMode(ctx.getSource().getPlayerOrException(), true))));
            echo.then(Commands.literal("chance").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("percent", DoubleArgumentType.doubleArg(0, 100)).executes(ctx -> {
                        double v = DoubleArgumentType.getDouble(ctx, "percent");
                        EchoConfig.chanceOverridePercent = v;
                        ctx.getSource().sendSuccess(() -> Component.literal("Echo bind chance fixed at " + v + "%"), true);
                        return 1;
                    }))
                    .then(Commands.literal("reset").executes(ctx -> {
                        EchoConfig.chanceOverridePercent = -1;
                        ctx.getSource().sendSuccess(() -> Component.literal("Echo bind chance back to the formula"), true);
                        return 1;
                    })));

            dispatcher.register(Commands.literal("gatebound").then(echo));
        });
    }

    private static int setMode(ServerPlayer player, boolean guard) {
        EchoManager.setMode(player, -1, guard ? EchoRoster.Entry.GUARD : EchoRoster.Entry.FOLLOW);
        player.sendSystemMessage(Component.literal("All echoes will now " + (guard ? "guard you." : "follow you.")));
        return 1;
    }
}
