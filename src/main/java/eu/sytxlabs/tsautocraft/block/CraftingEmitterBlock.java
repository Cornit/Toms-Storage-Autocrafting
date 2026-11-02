package eu.sytxlabs.tsautocraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Crafting Emitter: full block node, weak redstone signal (15) on all sides while {@link #POWERED}. The directional variant is a {@link ConnectorNodeBlock} (signal only out of its front). */
public class CraftingEmitterBlock extends NodeBlock {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public CraftingEmitterBlock(Block.Properties properties, BEFactory factory) {
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

	@Override
	public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
		return state.getValue(POWERED) ? 15 : 0;
	}
}
