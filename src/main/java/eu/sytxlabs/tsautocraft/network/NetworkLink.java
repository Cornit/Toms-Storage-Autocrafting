package eu.sytxlabs.tsautocraft.network;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.tom.storagemod.block.entity.IInventoryConnector;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.PlatformInventoryAccess;

import eu.sytxlabs.tsautocraft.api.CraftingProvider;

/**
 * Connection of an addon block to Tom's cable network. Like Tom's Inventory Interface it finds the Inventory Connector through {@link InventoryCableNetwork}
 * and also collects all crafting providers (Pattern Providers, Crafting Emitters) on the same network.
 */
public class NetworkLink {
	private static final int RESCAN_TICKS = 20;

	private WeakReference<IInventoryConnector> connector;
	private List<WeakReference<CraftingProvider>> providers = Collections.emptyList();
	private long lastScan;
	private boolean dirty = true;

	private void scanIfNeeded(Level level, BlockPos pos) {
		long t = level.getGameTime();
		// t < lastScan: game time was reset -> rescan
		if (!dirty && t >= lastScan && t - lastScan < RESCAN_TICKS) return;
		dirty = false;
		lastScan = t;

		connector = null;
		List<WeakReference<CraftingProvider>> provs = new ArrayList<>();
		Collection<BlockPos> nodes = InventoryCableNetwork.getNetwork(level).getNetworkNodes(pos);
		for (BlockPos p : nodes) {
			if (!level.isLoaded(p)) continue;
			BlockEntity be = level.getBlockEntity(p);
			if (connector == null && be instanceof IInventoryConnector c && c.hasConnectedInventories()) {
				connector = new WeakReference<>(c);
			} else if (be instanceof CraftingProvider pp) {
				provs.add(new WeakReference<>(pp));
			}
		}
		providers = provs;
	}

	/** Rescan immediately (e.g. after placement). */
	public void invalidate() {
		dirty = true;
	}

	/** Merged storage of the network, or {@link PlatformInventoryAccess#EMPTY}. */
	public IInventoryAccess access(Level level, BlockPos pos) {
		scanIfNeeded(level, pos);
		IInventoryConnector c = connector != null ? connector.get() : null;
		if (c != null && c.hasConnectedInventories()) return c.getMergedHandler();
		return PlatformInventoryAccess.EMPTY;
	}

	public boolean isConnected(Level level, BlockPos pos) {
		return access(level, pos) != PlatformInventoryAccess.EMPTY;
	}

	public List<CraftingProvider> providers(Level level, BlockPos pos) {
		scanIfNeeded(level, pos);
		List<CraftingProvider> out = new ArrayList<>(providers.size());
		for (var ref : providers) {
			CraftingProvider p = ref.get();
			if (p instanceof BlockEntity be && !be.isRemoved()) out.add(p);
		}
		return out;
	}
}
