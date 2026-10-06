package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Puts the Spire's portals on walls. A floor plan only names a nominal spot in the middle of a cleared area; the portal
 * itself goes on the nearest wall of that area (looking first in the preferred direction, which is away from where the
 * player arrives), as a two-block-tall pane facing into the room. The choice depends only on the surrounding blocks, so
 * asking again later finds the same spot.
 */
public final class PortalPlacer {
    /** How far from the nominal spot a wall is looked for. */
    private static final int REACH = 9;

    public record Spot(BlockPos pos, Direction facing) {
    }

    private PortalPlacer() {
    }

    private static boolean clear(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || s.getBlock() instanceof GateExitBlock || s.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean wall(ServerLevel level, BlockPos pos, Direction towardWall) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, towardWall.getOpposite());
    }

    /** The wall spot for a portal near the nominal position, or the nominal position itself (standing free) if no wall is near. */
    public static Spot spot(ServerLevel level, BlockPos nominal, Direction prefer) {
        Direction[] order = {prefer, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST};
        for (Direction d : order) {
            for (int k = 1; k <= REACH; k++) {
                BlockPos w = nominal.relative(d, k);
                if (!level.hasChunkAt(w)) {
                    break;
                }
                if (clear(level, w)) {
                    continue;
                }
                // First obstacle: a usable wall needs to be solid two blocks high with room in front of it.
                BlockPos pane = w.relative(d.getOpposite());
                if (k >= 2 && wall(level, w, d) && wall(level, w.above(), d) && clear(level, pane) && clear(level, pane.above())) {
                    return new Spot(pane.immutable(), d.getOpposite());
                }
                break;
            }
        }
        return new Spot(nominal, prefer.getOpposite());
    }

    /** Builds the portal (both halves) and returns where its lower half is. */
    public static BlockPos place(ServerLevel level, BlockPos nominal, GateExitBlock.Kind kind, Direction prefer) {
        Spot s = spot(level, nominal, prefer);
        level.setBlock(s.pos(), GateExitBlock.state(kind, s.facing(), DoubleBlockHalf.LOWER), 3);
        level.setBlock(s.pos().above(), GateExitBlock.state(kind, s.facing(), DoubleBlockHalf.UPPER), 3);
        return s.pos();
    }

    /** Where the portal for this nominal spot stands (the lower half), whether or not it has been built yet. */
    public static BlockPos where(ServerLevel level, BlockPos nominal, Direction prefer) {
        return spot(level, nominal, prefer).pos();
    }

    /** True if a portal block stands at the wall spot for this nominal position. */
    public static boolean exists(ServerLevel level, BlockPos nominal, Direction prefer) {
        return level.getBlockState(where(level, nominal, prefer)).getBlock() instanceof GateExitBlock;
    }
}
