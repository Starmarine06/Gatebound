package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A mana crystal growing in the dungeon. While the floor's guardian lives it is much harder to break than obsidian; once the
 * guardian falls the crystals of that floor turn {@code weakened} and break noticeably faster. The state, not the floor
 * record, drives the speed so the client predicts the breaking time correctly.
 */
public class ManaCrystalBlock extends Block {
    public static final BooleanProperty WEAKENED = BooleanProperty.create("weakened");
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 13, 14);

    /** Creates the mana crystal block (starts strong). */
    public ManaCrystalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WEAKENED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WEAKENED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Far harder than obsidian until weakened, then {@link DungeonConfig#crystalWeakHardness}. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float hardness = state.getValue(WEAKENED) ? (float) DungeonConfig.crystalWeakHardness : (float) DungeonConfig.crystalHardness;
        float speed = Math.min(player.getDestroySpeed(state), (float) DungeonConfig.crystalMaxMineSpeed);
        return speed / hardness / (player.hasCorrectToolForDrops(state) ? 30.0f : 100.0f);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(4) == 0) {
            level.addParticle(ParticleTypes.END_ROD, pos.getX() + 0.3 + random.nextDouble() * 0.4,
                    pos.getY() + 0.3 + random.nextDouble() * 0.8, pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                    0, 0.02, 0);
        }
    }
}
