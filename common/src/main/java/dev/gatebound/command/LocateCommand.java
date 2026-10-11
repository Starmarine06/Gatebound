package dev.gatebound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.gatebound.dungeon.DungeonStore;
import dev.gatebound.dungeon.GateRank;
import dev.gatebound.dungeon.Spire;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** /gatebound locate [spire|gate]: where the Spire's entrance and the nearest open gates are. */
final class LocateCommand {
    /** Static holder; not instantiable. */
    private LocateCommand() {
    }

    /** Builds the locate command node. */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("locate").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> run(ctx.getSource().getPlayerOrException(), ctx.getSource(), true, true))
                .then(Commands.literal("spire").executes(ctx -> run(ctx.getSource().getPlayerOrException(), ctx.getSource(), true, false)))
                .then(Commands.literal("gate").executes(ctx -> run(ctx.getSource().getPlayerOrException(), ctx.getSource(), false, true)));
    }

    /**
     * Adds gatebound:spire and gatebound:gate to vanilla /locate structure. The Spire is built by the mod rather than by
     * world generation, so it is not in the structure registry; these are literal nodes next to the registry argument.
     */
    static void attachToVanilla(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        var locate = dispatcher.getRoot().getChild("locate");
        var structure = locate == null ? null : locate.getChild("structure");
        if (structure == null) {
            return;
        }
        structure.addChild(Commands.literal("gatebound:spire")
                .executes(ctx -> vanilla(ctx.getSource(), true)).build());
        structure.addChild(Commands.literal("gatebound:gate")
                .executes(ctx -> vanilla(ctx.getSource(), false)).build());
    }

    /** Runs vanilla /locate for the Spire or a gate. */
    private static int vanilla(CommandSourceStack src, boolean spire) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        DungeonStore store = DungeonStore.get(player.level().getServer());
        BlockPos pos = null;
        if (spire) {
            if (store.towerBuilt && !store.towerExit.equals(BlockPos.ZERO) && player.level().dimension().equals(Level.OVERWORLD)) {
                pos = store.towerExit;
            }
        } else {
            double best = Double.MAX_VALUE;
            for (DungeonStore.GateRef g : store.gates) {
                if (!g.cleared && g.dim.equals(player.level().dimension()) && Spire.isGate(g.floor)
                        && g.pos.distSqr(player.blockPosition()) < best) {
                    best = g.pos.distSqr(player.blockPosition());
                    pos = g.pos;
                }
            }
        }
        if (pos == null) {
            src.sendFailure(Component.literal("Could not find a structure of type \"gatebound:" + (spire ? "spire" : "gate")
                    + "\" in this dimension"));
            return 0;
        }
        BlockPos at = pos;
        String tp = "/tp @s " + at.getX() + " " + at.getY() + " " + at.getZ();
        int dist = (int) Math.round(Math.sqrt((double) at.distSqr(new BlockPos(player.blockPosition().getX(), at.getY(), player.blockPosition().getZ()))));
        src.sendSuccess(() -> Component.literal("The nearest gatebound:" + (spire ? "spire" : "gate") + " is at ")
                .append(coords(at, tp))
                .append(Component.literal(" (" + dist + " blocks away)")), false);
        return dist;
    }

    /** The coordinates, green and underlined; clicking teleports the (op) player there, as vanilla /locate does. */
    private static Component coords(BlockPos p, String tp) {
        return Component.literal("[" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]")
                .withStyle(dev.gatebound.compat.Compat.clickCommand(
                        net.minecraft.network.chat.Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true), tp, "Click to teleport"));
    }

    /** Finds and prints the nearest Spire entrance or open gates. */
    private static int run(ServerPlayer player, CommandSourceStack src, boolean spire, boolean gates) {
        DungeonStore store = DungeonStore.get(player.level().getServer());
        int found = 0;
        if (spire) {
            if (store.towerBuilt && !store.towerExit.equals(BlockPos.ZERO)) {
                found++;
                src.sendSuccess(() -> line("The Spire", Level.OVERWORLD, store.towerExit, player, ChatFormatting.LIGHT_PURPLE), false);
            } else {
                src.sendSuccess(() -> Component.literal("The Spire has not been built yet.").withStyle(ChatFormatting.GRAY), false);
            }
        }
        if (gates) {
            int total = store.totalFloors;
            DungeonStore.GateRef best = null;
            double bestD = Double.MAX_VALUE;
            for (DungeonStore.GateRef g : store.gates) {
                if (g.cleared || !g.dim.equals(player.level().dimension()) || !Spire.isGate(g.floor)) {
                    continue;
                }
                double d = g.pos.distSqr(player.blockPosition());
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
            if (best != null) {
                found++;
                GateRank rank = Spire.rank(best.floor, total);
                BlockPos pos = best.pos;
                src.sendSuccess(() -> line(rank.label() + " gate", player.level().dimension(), pos, player, rank.color()), false);
            } else {
                src.sendSuccess(() -> Component.literal("No open gate in this dimension.").withStyle(ChatFormatting.GRAY), false);
            }
        }
        return found;
    }

    /** Formats one result line with clickable coordinates. */
    private static Component line(String what, net.minecraft.resources.ResourceKey<Level> dim, BlockPos pos, ServerPlayer p, ChatFormatting colour) {
        if (!p.level().dimension().equals(dim)) {
            return Component.literal(what + " is in another dimension.").withStyle(colour);
        }
        int dx = pos.getX() - p.blockPosition().getX();
        int dz = pos.getZ() - p.blockPosition().getZ();
        int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        String compass = Math.abs(dx) > Math.abs(dz) * 2 ? (dx > 0 ? "east" : "west")
                : Math.abs(dz) > Math.abs(dx) * 2 ? (dz > 0 ? "south" : "north")
                : (dz > 0 ? "south" : "north") + (dx > 0 ? "-east" : "-west");
        return Component.literal(what + " at ").withStyle(colour)
                .append(coords(pos, "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                .append(Component.literal(", " + dist + " blocks " + compass).withStyle(colour));
    }
}
