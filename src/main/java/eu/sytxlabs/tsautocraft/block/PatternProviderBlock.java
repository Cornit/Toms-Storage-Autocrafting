package eu.sytxlabs.tsautocraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;

/**
 * Pattern Provider, full block without a front: supplies every crafter/machine around it (round robin), cables connect on any
 * side. {@link #POWERED} lights it for a moment after a delivery. A redstone signal (crafter trigger "redstone", Redstone
 * Upgrade, machine profile) only goes to the machine that just got its items. The connector variant has a front instead.
 */
public class PatternProviderBlock extends NodeBlock {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public PatternProviderBlock(Block.Properties properties, BEFactory factory) {
		super(properties, factory);
		registerDefaultState(defaultBlockState().setValue(POWERED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	@Override
	public boolean isSignalSource(BlockState state) {
		return true;
	}

	// side = direction from the receiving block towards us, so the receiver is on side.getOpposite()
	@Override
	public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
		return level.getBlockEntity(pos) instanceof PatternProviderBlockEntity p && p.emitsSignal(side.getOpposite()) ? 15 : 0;
	}

	@Override
	public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
		return 0;
	}
}
