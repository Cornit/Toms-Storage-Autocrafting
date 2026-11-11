package eu.sytxlabs.tsautocraft.compat;

import java.lang.reflect.Method;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import eu.sytxlabs.tsautocraft.platform.Platform;

/**
 * Fluid ingredients of JEI/REI recipes (e.g. Create mixing or filling) as filled buckets, so a "+" transfer keeps them:
 * the amount is rounded up to whole buckets. Works on the viewers' fluid objects by reflection ({@code getFluid()},
 * {@code getAmount()}: JEI's Fabric fluid ingredient and REI/Architectury fluid stacks count in droplets on Fabric,
 * NeoForge fluid stacks in mB), so the shared code needs no loader classes. Fluids without a bucket are dropped.
 */
public final class FluidIngredients {
	private static final long BUCKET = "fabric".equals(Platform.LOADER) ? 81000 : 1000;

	private FluidIngredients() {}

	/** Filled buckets for a viewer fluid object, or {@link ItemStack#EMPTY}. */
	public static ItemStack toBuckets(Object fluidIngredient) {
		if (fluidIngredient == null || fluidIngredient instanceof ItemStack) return ItemStack.EMPTY;
		try {
			Object fluid = call(fluidIngredient, "getFluid");
			Object amount = call(fluidIngredient, "getAmount");
			if (!(fluid instanceof Fluid f) || !(amount instanceof Number n)) return ItemStack.EMPTY;
			// API 4: with an extension that stores fluid units, keep the exact amount in mB
			if (eu.sytxlabs.tsautocraft.api.FluidUnits.enabled()) {
				long mb = (n.longValue() * 1000 + BUCKET - 1) / BUCKET;
				return eu.sytxlabs.tsautocraft.api.FluidUnits.stack(f, (int) Math.clamp(mb, 1, Integer.MAX_VALUE));
			}
			Item bucket = f.getBucket();
			if (bucket == null || bucket == Items.AIR || bucket == Items.BUCKET) return ItemStack.EMPTY;
			long count = Math.max(1, (n.longValue() + BUCKET - 1) / BUCKET);
			return new ItemStack(bucket, (int) Math.min(count, bucket.getDefaultMaxStackSize() * 9L));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	private static Object call(Object o, String name) throws ReflectiveOperationException {
		Method m = o.getClass().getMethod(name);
		return m.invoke(o);
	}
}
