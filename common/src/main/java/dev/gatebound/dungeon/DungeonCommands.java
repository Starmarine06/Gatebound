package dev.gatebound.dungeon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** /gatebound gate spawn <rank> (op) and /gatebound gate leave. */
public final class DungeonCommands {
    private DungeonCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> spawn = Commands.literal("spawn")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (GateRank rank : GateRank.values()) {
            spawn.then(Commands.literal(rank.id()).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                ServerLevel level = player.level();
                BlockPos pos = player.blockPosition();
                // Grace period so the player standing inside the new gate is not pulled in at once.
                DungeonManager.markPortalUsed(player);
                DungeonManager.placeGate(level, pos, rank, player.getX() + 0.0, player.getZ() + 1.0);
                ctx.getSource().sendSuccess(() -> Component.literal("Placed a " + rank.label() + " gate"), true);
                return 1;
            }));
        }
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
}
