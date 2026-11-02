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
 * Something on the network that can run crafts of its patterns: Pattern Provider, Crafting Emitter, or a block of
 * another mod. A {@link BlockEntity} implementing this is found automatically if its block is a node of Tom's cable
 * network: the block implements Tom's {@code IInventoryCable} (and marks the network invalid on place/remove, see
 * {@code NodeBlock}).
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

	/** Position shown in the job status ("provider at x, y, z is busy"); null if it has none. */
	default BlockPos providerPos() {
		return this instanceof BlockEntity be ? be.getBlockPos() : null;
	}

	/** Name shown in the job status instead of the position (e.g. an anvil name), null for none. */
	default Component providerName() {
		return null;
	}
}
