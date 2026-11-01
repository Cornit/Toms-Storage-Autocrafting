package eu.sytxlabs.tsautocraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import eu.sytxlabs.tsautocraft.TsAutocraftConfig;

/**
 * Pattern Provider. {@link #POWERED} lights the front for a moment after a crafter delivery. Only with
 * {@code "crafterTrigger": "redstone"} in the config is that also a redstone pulse, and ONLY to the block in front (the crafter).
 */
public class PatternProviderBlock extends DirectionalNodeBlock {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public PatternProviderBlock(Block.Properties properties, BEFactory factory) {
		super(properties, factory);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(POWERED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED);
	}

	@Override
	public boolean isSignalSource(BlockState state) {
		return TsAutocraftConfig.providerEmitsRedstone();
	}

	// side = direction from the receiving block towards us. The crafter is at pos + FACING,
	// so from its point of view we are in direction FACING.getOpposite().
	@Override
	public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
		return TsAutocraftConfig.providerEmitsRedstone() && state.getValue(POWERED) && state.getValue(FACING).getOpposite() == side ? 15 : 0;
	}

	// No strong signal: otherwise the crafter would also power redstone around it.
	@Override
	public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
		return 0;
	}
}
