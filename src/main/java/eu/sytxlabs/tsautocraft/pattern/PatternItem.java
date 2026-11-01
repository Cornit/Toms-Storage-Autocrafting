package eu.sytxlabs.tsautocraft.pattern;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import eu.sytxlabs.tsautocraft.ModContent;

/** Encoded pattern. The data lives in the {@code tsautocraft:pattern} component. */
public class PatternItem extends Item {

	public PatternItem(Item.Properties properties) {
		super(properties);
	}

	public static PatternData get(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof PatternItem)) return null;
		PatternData d = stack.get(ModContent.PATTERN_COMPONENT);
		return d != null && d.isValid() ? d : null;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		PatternData d = stack.get(ModContent.PATTERN_COMPONENT);
		if (d == null) {
			tooltip.accept(Component.translatable("tooltip.tsautocraft.pattern.empty").withStyle(ChatFormatting.GRAY));
			return;
		}
		tooltip.accept(Component.translatable(d.crafting() ? "tooltip.tsautocraft.pattern.crafting" : "tooltip.tsautocraft.pattern.processing")
				.withStyle(ChatFormatting.AQUA));
		if (d.hasSubstitutes()) tooltip.accept(Component.translatable("tooltip.tsautocraft.pattern.substitutes").withStyle(ChatFormatting.GREEN));
		tooltip.accept(Component.translatable("tooltip.tsautocraft.pattern.outputs").withStyle(ChatFormatting.GOLD));
		for (PatternData.Entry e : d.outputs()) {
			if (!e.isEmpty()) tooltip.accept(line(e));
		}
		tooltip.accept(Component.translatable("tooltip.tsautocraft.pattern.inputs").withStyle(ChatFormatting.GOLD));
		d.inputTotals().forEach((k, c) -> tooltip.accept(entry(c, k.template())));
	}

	private static Component line(PatternData.Entry e) {
		return entry(e.count(), e.item());
	}

	private static Component entry(long count, ItemStack item) {
		return Component.translatable("tooltip.tsautocraft.pattern.entry", count, item.getHoverName()).withStyle(ChatFormatting.GRAY);
	}
}
