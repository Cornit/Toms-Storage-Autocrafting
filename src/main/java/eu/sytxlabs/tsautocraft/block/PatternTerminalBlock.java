package eu.sytxlabs.tsautocraft.block;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.tom.storagemod.block.AbstractStorageTerminalBlock;

import eu.sytxlabs.tsautocraft.blockentity.PatternTerminalBlockEntity;

/**
 * Pattern Terminal: a Tom's Storage terminal (placed on an inventory or connected by cable, same shape
 * and placement rules as the Crafting Terminal) with a pattern encoding area instead of the crafting grid.
 * Tooltip comes from {@link eu.sytxlabs.tsautocraft.TooltipBlockItem}.
 */
public class PatternTerminalBlock extends AbstractStorageTerminalBlock {

	public PatternTerminalBlock(Block.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new PatternTerminalBlockEntity(pos, state);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, Consumer<Component> tooltip, TooltipFlag flag) {
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
		Containers.updateNeighboursAfterDestroy(state, level, pos);
	}
}
