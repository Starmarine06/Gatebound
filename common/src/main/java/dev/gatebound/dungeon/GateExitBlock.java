package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Flat floor portal used throughout the Spire. HOME returns a player to where they entered (escape rift),
 * UP climbs to the next floor and DOWN descends to the previous one (or leaves the tower from floor 1).
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
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);

    public GateExitBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, Kind.HOME));
    }

    public static BlockState state(Kind kind) {
        return DungeonRegistries.GATE_EXIT.get().defaultBlockState().setValue(KIND, kind);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
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
