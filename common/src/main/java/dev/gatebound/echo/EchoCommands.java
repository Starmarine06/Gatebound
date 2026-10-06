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

/** /gatebound echo list|summon|dismiss|release <index>|mode guard|attack|chance <percent>. */
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
            echo.then(Commands.literal("add").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("entity", net.minecraft.commands.arguments.ResourceArgument.resource(
                                    registry, net.minecraft.core.registries.Registries.ENTITY_TYPE))
                            .executes(ctx -> add(ctx.getSource(), net.minecraft.commands.arguments.ResourceArgument
                                    .getSummonableEntityType(ctx, "entity").value()))));
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
                    .then(Commands.literal("guard").executes(ctx -> setMode(ctx.getSource().getPlayerOrException(), false)))
                    .then(Commands.literal("attack").executes(ctx -> setMode(ctx.getSource().getPlayerOrException(), true))));
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

    /** Op command: adds a mob straight to the roster, raising the slot count if the roster is full. */
    private static int add(CommandSourceStack source, net.minecraft.world.entity.EntityType<?> type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        net.minecraft.world.entity.Entity probe = type.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        boolean ok = probe instanceof net.minecraft.world.entity.LivingEntity living && EchoManager.eligible(living);
        if (probe != null) {
            probe.discard();
        }
        if (!ok) {
            source.sendFailure(Component.literal("That entity cannot be an echo."));
            return 0;
        }
        PlayerData data = PlayerDataStore.of(player);
        String id = net.minecraft.world.entity.EntityType.getKey(type).toString();
        String name = type.getDescription().getString();
        data.echo.entries.add(new EchoRoster.Entry(id, name));
        int need = data.echo.entries.size();
        if (EchoManager.slots(data) < need) {
            data.echo.bonusSlots += need - EchoManager.slots(data);
        }
        PlayerDataStore.markDirty(player);
        EchoManager.sendRoster(player, false);
        source.sendSuccess(() -> Component.literal("Added " + name + " to your echoes (" + need + " in roster, "
                + EchoManager.slots(data) + " slots). Press R to summon."), true);
        return 1;
    }

    private static int setMode(ServerPlayer player, boolean attack) {
        EchoManager.setGroupMode(player, -1, attack ? EchoRoster.ATTACK : EchoRoster.GUARD);
        player.sendSystemMessage(Component.literal("All echoes will now " + (attack ? "attack nearby enemies." : "guard you.")));
        return 1;
    }
}
