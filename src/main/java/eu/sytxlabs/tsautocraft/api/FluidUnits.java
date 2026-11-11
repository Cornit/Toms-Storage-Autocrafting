package eu.sytxlabs.tsautocraft.api;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import eu.sytxlabs.tsautocraft.ModContent;

/**
 * Exact fluid amounts in patterns (API 4): a stack of the virtual item {@code tsautocraft:fluid_unit} with the fluid in
 * its component {@code tsautocraft:fluid_unit} stands for "count" millibuckets of that fluid. It behaves like a fluid
 * container holding 1 mB per item, so deliveries empty it into the machine's tank. It never goes into Tom's storage:
 * a {@link StockExtensions} extension (e.g. the tanks of Tom's Storage Network) counts, pulls and takes it and calls
 * {@link #setEnabled}; without one, JEI/REI fluid ingredients stay filled buckets.
 */
public final class FluidUnits {
	private static volatile boolean enabled;

	private FluidUnits() {}

	/** An extension provides fluid units: JEI/REI encode fluids in exact mB from now on. */
	public static void setEnabled(boolean on) {
		enabled = on;
	}

	public static boolean enabled() {
		return enabled;
	}

	/** {@code mb} millibuckets of {@code fluid}. */
	public static ItemStack stack(Fluid fluid, int mb) {
		ItemStack st = new ItemStack(ModContent.FLUID_UNIT, Math.max(1, mb));
		st.set(ModContent.FLUID_UNIT_COMPONENT, BuiltInRegistries.FLUID.getKey(fluid));
		return st;
	}

	public static boolean is(ItemStack stack) {
		return !stack.isEmpty() && stack.is(ModContent.FLUID_UNIT);
	}

	/** The fluid of a fluid unit stack, null for any other stack. */
	@Nullable
	public static Fluid fluid(ItemStack stack) {
		if (!is(stack)) return null;
		Identifier id = stack.get(ModContent.FLUID_UNIT_COMPONENT);
		if (id == null) return null;
		Fluid f = BuiltInRegistries.FLUID.getValue(id);
		return f == null || f == Fluids.EMPTY ? null : f;
	}
}
