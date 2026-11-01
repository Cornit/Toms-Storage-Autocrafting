package eu.sytxlabs.tsautocraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.tom.storagemod.block.IConfiguratorHighlight;
import com.tom.storagemod.block.IPaintable;
import com.tom.storagemod.item.PaintKitItem;
import com.tom.storagemod.block.entity.PaintedBlockEntity;

/**
 * Framed variant: full block hitbox, can be painted with Tom's Paint Kit and scraped clean with an axe,
 * exactly like Tom's Framed Inventory Cable Connector. The block entity must extend {@link PaintedBlockEntity}.
 */
public class FramedConnectorNodeBlock extends ConnectorNodeBlock implements IPaintable, IConfiguratorHighlight {

	public FramedConnectorNodeBlock(Block.Properties properties, NodeBlock.BEFactory factory, boolean emitsPulse) {
		super(properties, factory, emitsPulse);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack item, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (tryScrape(item, state, world, pos, player, hand)) return InteractionResult.SUCCESS;
		if (item.getItem() instanceof PaintKitItem) return InteractionResult.PASS;
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	public boolean paint(Level world, BlockPos pos, BlockState to) {
		return world.getBlockEntity(pos) instanceof PaintedBlockEntity te && te.setPaintedBlockState(to);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.block();
	}

	@Override
	public int getHighlightColor() {
		return 0xFFFF00;
	}

	@Override
	public VoxelShape getHighlightShape(BlockState state, BlockGetter level, BlockPos pos) {
		return connectorShape(state);
	}
}
