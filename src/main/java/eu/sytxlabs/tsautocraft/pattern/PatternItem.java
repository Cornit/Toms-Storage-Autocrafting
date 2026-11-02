package eu.sytxlabs.tsautocraft.pattern;

import java.util.function.BooleanSupplier;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import eu.sytxlabs.tsautocraft.ModContent;

/** Encoded pattern. The data lives in the {@code tsautocraft:pattern} component, which also renders the tooltip. */
public class PatternItem extends Item {
	/** Set by the client entrypoint; Shift shows the substitutes in the tooltip. */
	public static BooleanSupplier shiftDown = () -> false;

	public PatternItem(Item.Properties properties) {
		super(properties);
	}

	public static PatternData get(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof PatternItem)) return null;
		PatternData d = stack.get(ModContent.PATTERN_COMPONENT);
		return d != null && d.isValid() ? d : null;
	}
}
