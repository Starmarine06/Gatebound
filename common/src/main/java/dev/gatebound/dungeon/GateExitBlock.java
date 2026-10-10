package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A portal pane mounted on a wall, two blocks tall (lower and upper half), so nobody steps on one by accident. It faces
 * into the room; walking into the pane or right-clicking it uses it. HOME returns a player to where they entered
 * (escape rift), UP climbs to the next floor and DOWN descends to the previous one (or leaves the tower from floor 1).
 * See PortalPlacer for how a wall spot is found.
 */
public class GateExitBlock extends Block {
    public enum Kind implements StringRepresentable {
        HOME("home"), UP("up"), DOWN("down");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
    /** The direction the pane faces (into the room); the wall is on the opposite side. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /** Creates the exit portal block. */
    public GateExitBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, Kind.HOME).setValue(FACING, Direction.SOUTH)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    /** State of an exit portal of a kind. */
    public static BlockState state(Kind kind) {
        return state(kind, Direction.SOUTH, DoubleBlockHalf.LOWER);
    }

    /** State of an exit portal of a kind, facing and half. */
    public static BlockState state(Kind kind, Direction facing, DoubleBlockHalf half) {
        return DungeonRegistries.GATE_EXIT.get().defaultBlockState()
                .setValue(KIND, kind).setValue(FACING, facing).setValue(HALF, half);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND, FACING, HALF);
    }

    /** A thin pane against the wall behind the block. {@code depth} is its thickness in sixteenths. */
    private static VoxelShape pane(Direction facing, int depth) {
        return switch (facing) {
            case NORTH -> Block.box(0, 0, 16 - depth, 16, 16, 16);
            case EAST -> Block.box(0, 0, 0, depth, 16, 16);
            case WEST -> Block.box(16 - depth, 0, 0, 16, 16, 16);
            default -> Block.box(0, 0, 0, 16, 16, depth);
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return pane(state.getValue(FACING), 3);
    }

    /** Only the slice against the wall counts as inside, so walking past the wall does not trigger it. */
    @Override
    protected VoxelShape getEntityInsideCollisionShape(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return pane(state.getValue(FACING), 5);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) {
            DungeonManager.usePortal(sp, pos, state.getValue(KIND));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier applier, boolean intersects) {
        if (entity instanceof ServerPlayer sp && DungeonManager.portalReady(sp)) {
            DungeonManager.usePortal(sp, pos, state.getValue(KIND));
        }
    }
}
