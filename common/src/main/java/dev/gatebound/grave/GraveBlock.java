package dev.gatebound.grave;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.gatebound.dungeon.DungeonConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A gravestone holding what a player had when they died. Only the owner can open it for the first few minutes, then
 * anyone can. Opening returns the items and experience, and the grave crumbles away. It cannot be broken.
 */
public final class GraveBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Shapes.or(Block.box(1, 0, 2, 15, 2, 14), Block.box(4, 2, 7, 12, 13, 9),
            Block.box(6, 13, 7, 10, 15, 9));

    public GraveBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GraveBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)
                || !(level.getBlockEntity(pos) instanceof GraveBlockEntity grave)) {
            return InteractionResult.PASS;
        }
        long age = sl.getServer().overworld().getGameTime() - grave.created;
        boolean owner = sp.getUUID().equals(grave.owner);
        if (!owner && age < DungeonConfig.graveOwnerTicks && !sp.isCreative()) {
            long left = (DungeonConfig.graveOwnerTicks - age) / 20;
            Compat.overlay(sp, Component.literal("This is " + grave.ownerName + "'s grave. It opens for others in "
                    + (left / 60) + "m " + (left % 60) + "s.").withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        for (ItemStack stack : grave.items) {
            if (!stack.isEmpty() && !sp.getInventory().add(stack.copy())) {
                sp.spawnAtLocation(sl, stack.copy());
            }
        }
        if (grave.xp > 0) {
            ExperienceOrb.award(sl, sp.position(), grave.xp);
        }
        grave.items.clear();
        sl.removeBlock(pos, false);
        sl.playSound(null, pos, GateboundSounds.custom("grave.open"), SoundSource.BLOCKS, 1f, 1f);
        Compat.overlay(sp, Component.literal(owner ? "You reclaim your belongings." : "You take what " + grave.ownerName + " left behind.")
                .withStyle(ChatFormatting.GREEN));
        return InteractionResult.SUCCESS;
    }
}
