package eu.sytxlabs.tsautocraft.api;

import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.tom.storagemod.inventory.IInventoryAccess;

import eu.sytxlabs.tsautocraft.pattern.PatternData;
import eu.sytxlabs.tsautocraft.util.ItemKey;

/**
 * Something on the network that can run crafts of its patterns: Pattern Provider, Crafting Emitter, or a block of another mod.
 * A {@link BlockEntity} implementing this is found automatically if its block is a node of Tom's cable network: the block implements Tom's {@code IInventoryCable} (and marks the network invalid on place/remove, see {@code NodeBlock}).
 */
public interface CraftingProvider {

	enum DispatchResult {
		OK, BUSY, NO_TARGET, MISSING, NO_SPACE, SLOT_DISABLED
	}

	/**
	 * @param crafts   crafts started (0 unless {@link DispatchResult#OK})
	 * @param consumed items taken out of the network for them (counts against the job's reservation)
	 */
	record Dispatch(DispatchResult result, long crafts, Map<ItemKey, Long> consumed) {
		public static Dispatch fail(DispatchResult result) {
			return new Dispatch(result, 0, Map.of());
		}

		public boolean ok() {
			return result == DispatchResult.OK && crafts > 0;
		}
	}

	List<PatternData> getPatterns();

	default boolean hasPattern(PatternData p) {
		return getPatterns().contains(p);
	}

	/**
	 * Starts up to {@code wanted} crafts of {@code p}.
	 * @param available usable network stock per item (stock minus what other jobs reserved)
	 */
	Dispatch dispatch(PatternData p, IInventoryAccess net, ToLongFunction<ItemKey> available, long wanted);

	/**
	 * Like {@link #dispatch(PatternData, IInventoryAccess, ToLongFunction, long)}, called by jobs with their runtime id
	 * ({@code jobUid}, 0 = unknown) so the provider can find the job's inputs again in {@link #returnInputs}.
	 */
	default Dispatch dispatch(PatternData p, IInventoryAccess net, ToLongFunction<ItemKey> available, long wanted, int jobUid) {
		return dispatch(p, net, available, wanted);
	}

	/**
	 * The job {@code jobUid} was cancelled: give inputs of it that were delivered but not processed yet back to
	 * {@code net} where that is safely possible (never items of other jobs or of the player).
	 * @return number of items returned
	 */
	default long returnInputs(int jobUid, IInventoryAccess net) {
		return 0;
	}

	/** Providers with a higher priority get the crafts of a pattern first (equal priority: network order). */
	default int providerPriority() {
		return 0;
	}

	/**
	 * Importer results of {@code key} reached the network ({@code count} items; lock mode "until result returned").
	 * @return how many of them this provider was waiting for
	 */
	default long resultsArrived(ItemKey key, long count) {
		return 0;
	}

	/** Since when this provider waits for results of {@code key}, {@link Long#MAX_VALUE} if it does not (FIFO between providers). */
	default long awaitingSince(ItemKey key) {
		return Long.MAX_VALUE;
	}

	/** Position shown in the job status ("provider at x, y, z is busy"); null if it has none. */
	default BlockPos providerPos() {
		return this instanceof BlockEntity be ? be.getBlockPos() : null;
	}

	/** Name shown in the job status instead of the position (e.g. an anvil name), null for none. */
	default Component providerName() {
		return null;
	}
}
