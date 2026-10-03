package dev.gatebound.dungeon;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.gatebound.data.PlayerDataStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** /gatebound gate spawn|leave (via build(), attached by GateboundCommands) and /gatebound tower ... (registered here). */
public final class DungeonCommands {
    private DungeonCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> spawn = Commands.literal("spawn")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (GateRank rank : GateRank.values()) {
            spawn.then(Commands.literal(rank.id()).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                int floor = DungeonManager.spawnGate(player, rank, 0);
                ctx.getSource().sendSuccess(() -> Component.literal("Placed a " + rank.label() + " gate leading to floor " + floor), true);
                return 1;
            }));
        }
        spawn.then(Commands.literal("floor").then(Commands.argument("n", IntegerArgumentType.integer(1, 500)).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            int floor = DungeonManager.spawnGate(player, GateRank.E, IntegerArgumentType.getInteger(ctx, "n"));
            ctx.getSource().sendSuccess(() -> Component.literal("Placed a gate leading to floor " + floor), true);
            return 1;
        })));
        return Commands.literal("gate")
                .then(spawn)
                .then(Commands.literal("leave").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!DungeonManager.leave(player)) {
                        ctx.getSource().sendFailure(Component.literal("You are not inside a dungeon"));
                        return 0;
                    }
                    return 1;
                }));
    }

    /** Registers /gatebound tower ...; the root literal merges with the one in GateboundCommands. */
    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            java.util.function.Predicate<CommandSourceStack> op = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
            LiteralArgumentBuilder<CommandSourceStack> tower = Commands.literal("tower");

            tower.then(Commands.literal("info").executes(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                DungeonStore store = DungeonStore.get(server);
                int total = store.ensureTower(server);
                int built = 0;
                for (DungeonStore.FloorState f : store.floors.values()) {
                    if (f.built) {
                        built++;
                    }
                }
                StringBuilder sb = new StringBuilder("The Spire has " + total + " floors (" + built + " generated). Tower in the overworld: ");
                sb.append(store.towerBuilt ? "built at " + store.towerPos.toShortString() : "not built");
                if (WorkQueue.size() > 0) {
                    sb.append(". Build queue: ").append(WorkQueue.size());
                }
                if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                    DungeonStore.Progress prog = store.progressOf(p.getUUID());
                    sb.append(". Your highest floor: ").append(prog.highest).append(", last rest floor: ")
                            .append(prog.lastRest > 0 ? prog.lastRest : "none");
                }
                ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                return 1;
            }));

            tower.then(Commands.literal("floor").requires(op)
                    .then(Commands.argument("n", IntegerArgumentType.integer(1, 500)).executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        int n = IntegerArgumentType.getInteger(ctx, "n");
                        int total = DungeonStore.get(ctx.getSource().getServer()).ensureTower(ctx.getSource().getServer());
                        if (n > total) {
                            ctx.getSource().sendFailure(Component.literal("The Spire only has " + total + " floors"));
                            return 0;
                        }
                        DungeonManager.travelFloor(player, n, WorkQueue.Dest.BOTTOM);
                        return 1;
                    })));

            tower.then(Commands.literal("regen").requires(op)
                    .then(Commands.argument("n", IntegerArgumentType.integer(1, 500)).executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        int n = IntegerArgumentType.getInteger(ctx, "n");
                        int level = ctx.getSource().getEntity() instanceof ServerPlayer p ? PlayerDataStore.of(p).level : 1;
                        if (!DungeonManager.regenFloor(server, n, level, () -> ctx.getSource().sendSuccess(
                                () -> Component.literal("Floor " + n + " has been rebuilt"), true))) {
                            ctx.getSource().sendFailure(Component.literal("No such floor, or it is being built right now"));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("Rebuilding floor " + n + " (players on it are sent home)"), true);
                        return 1;
                    })));

            tower.then(Commands.literal("prebuild").requires(op)
                    .then(Commands.argument("n", IntegerArgumentType.integer(1, 500)).executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        int n = IntegerArgumentType.getInteger(ctx, "n");
                        int level = ctx.getSource().getEntity() instanceof ServerPlayer p ? PlayerDataStore.of(p).level : 1;
                        if (!DungeonManager.prebuildFloor(server, n, level, () -> ctx.getSource().sendSuccess(
                                () -> Component.literal("Floor " + n + " is ready"), false))) {
                            ctx.getSource().sendFailure(Component.literal("No such floor"));
                            return 0;
                        }
                        return 1;
                    })));

            tower.then(Commands.literal("build").requires(op)
                    .executes(ctx -> startTower(ctx.getSource(), null))
                    .then(Commands.literal("here").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        return startTower(ctx.getSource(), new int[]{player.getBlockX(), player.getBlockZ()});
                    })));

            dispatcher.register(Commands.literal("gatebound").then(tower));
        });
    }

    private static int startTower(CommandSourceStack source, int[] xz) {
        MinecraftServer server = source.getServer();
        if (!DungeonManager.buildTower(server, xz, () -> source.sendSuccess(
                () -> Component.literal("The tower stands at " + DungeonStore.get(server).towerPos.toShortString()), true))) {
            source.sendFailure(Component.literal("The tower is already being built"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Building the tower... (this takes a few seconds)"), true);
        return 1;
    }
}
