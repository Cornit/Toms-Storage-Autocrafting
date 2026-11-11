package eu.sytxlabs.tsautocraft.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.tom.storagemod.inventory.IInventoryAccess;

import eu.sytxlabs.tsautocraft.util.ItemKey;

/**
 * Extra stock of a network that is not in Tom's inventories (API 4). Example: Tom's Storage Network reports filled fluid
 * containers as available while its tanks hold the fluid and the network has empty containers, and fills them when a
 * job pulls them. Every stock count, plan and pull of Autocrafting goes through these extensions, so the planner needs
 * no fluid support of its own.
 * <p>
 * Register on both sides in the {@code ModInitializer} / mod constructor. Extensions are asked in order; an extension
 * only adds to what is in Tom's storage, never replaces it.
 */
public final class StockExtensions {
	private static final List<StockExtension> EXTENSIONS = new CopyOnWriteArrayList<>();

	private StockExtensions() {}

	public static void register(StockExtension e) {
		EXTENSIONS.add(e);
	}

	public static List<StockExtension> all() {
		return EXTENSIONS;
	}

	/** One network storage during a count / pull / push. */
	public interface Context {
		/** Tom's merged inventory of the network. */
		IInventoryAccess network();

		Level level();

		/** Amount of {@code key} in Tom's storage alone (no extensions). */
		long baseCount(ItemKey key);

		/** Pulls from Tom's storage alone (no extensions). */
		ItemStack basePull(ItemStack stack, long max);

		/** Pushes into Tom's storage alone. @return what did not fit */
		ItemStack basePush(ItemStack stack);
	}

	public interface StockExtension {
		/** Additional amount of {@code key} this extension can deliver for the network (0 = none). */
		long count(Context ctx, ItemKey key);

		/** Delivers up to {@code max} of {@code stack}'s item (called only for what Tom's storage lacked). */
		ItemStack pull(Context ctx, ItemStack stack, long max);

		/**
		 * Takes a stack before it goes into Tom's storage (e.g. a filled container: fluid into tanks, the empty container
		 * into Tom's storage). @return what the extension did not take (the whole stack by default)
		 */
		default ItemStack push(Context ctx, ItemStack stack) {
			return stack;
		}
	}
}
