package eu.sytxlabs.tsautocraft;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import com.tom.storagemod.block.IPaintable;

/**
 * Block item that shows the translatable description {@code tooltip.tsautocraft.<block id>} and, for framed blocks, Tom's "Can be painted" line.
 */
public class TooltipBlockItem extends BlockItem {

	public TooltipBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		if (getBlock() instanceof IPaintable) {
			tooltip.accept(Component.translatable("tooltip.toms_storage.paintable").withStyle(ChatFormatting.GRAY));
		}
		String id = BuiltInRegistries.BLOCK.getKey(getBlock()).getPath();
		tooltip.accept(Component.translatable("tooltip.tsautocraft." + id.replace("_framed", "")).withStyle(ChatFormatting.GRAY));
	}
}
