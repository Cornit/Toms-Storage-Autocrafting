package eu.sytxlabs.tsautocraft.network;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import com.tom.storagemod.block.entity.IInventoryConnector;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.PlatformInventoryAccess;

import eu.sytxlabs.tsautocraft.api.CraftingProvider;

/**
 * Connection of an addon block to Tom's cable network. Like Tom's Inventory Interface it finds the Inventory Connector through {@link InventoryCableNetwork}
 * and also collects all crafting providers (Pattern Providers, Crafting Emitters) on the same network. The lookups are
 * shared by all blocks of a network through {@link NetworkCache}.
 */
public class NetworkLink {
	/** Rescan immediately (e.g. after placement). */
	public void invalidate() {
		NetworkCache.invalidate();
	}

	/** Merged storage of the network, or {@link PlatformInventoryAccess#EMPTY}. */
	public IInventoryAccess access(Level level, BlockPos pos) {
		IInventoryConnector c = NetworkCache.of(level, pos).connector();
		return c != null ? c.getMergedHandler() : PlatformInventoryAccess.EMPTY;
	}

	public boolean isConnected(Level level, BlockPos pos) {
		return access(level, pos) != PlatformInventoryAccess.EMPTY;
	}

	/**
	 * Is {@code storage} (a Fabric item storage found next to us) one of the inventories of the network itself?
	 * Compared by identity of the underlying storage, so wrappers built anew on every lookup (double chests) are not recognized.
	 */
	public boolean isNetworkStorage(Level level, BlockPos pos, Object storage) {
		return storage != null && NetworkCache.of(level, pos).storages().contains(storage);
	}

	/** Crafting providers of the network, highest priority first. Read only. */
	public List<CraftingProvider> providers(Level level, BlockPos pos) {
		return NetworkCache.of(level, pos).providers();
	}
}
