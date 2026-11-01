package eu.sytxlabs.tsautocraft.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.tom.storagemod.inventory.InventoryCableNetwork;

import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity.RequestResult;
import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.RequesterBlockEntity;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.job.CraftingJob;

/**
 * Autocrafting access for Tom's Storage terminals (wired in by {@code StorageTerminalMenuMixin}).
 * The terminal only lists and requests; the job itself runs in an Autocrafting Terminal on the same cable network.
 */
public final class TerminalCrafting {
	private TerminalCrafting() {}

	/** Items the Pattern Providers on the terminal's network can craft. */
	public static List<ItemStack> craftables(Level level, BlockPos terminal) {
		List<PatternProviderBlockEntity> providers = new ArrayList<>();
		for (BlockEntity be : nodes(level, terminal)) {
			if (be instanceof PatternProviderBlockEntity p) providers.add(p);
		}
		return CraftingJob.craftables(providers);
	}

	/** Starts a job in the first Autocrafting Terminal on the network that accepts it. */
	public static RequestResult request(Level level, BlockPos terminal, ItemStack item, long amount) {
		RequestResult last = RequestResult.error("msg.tsautocraft.request.no_cpu");
		for (BlockEntity be : nodes(level, terminal)) {
			if (!(be instanceof AutocraftingTerminalBlockEntity cpu)) continue;
			last = cpu.request(item, amount);
			// "too many jobs" -> try the next Autocrafting Terminal
			if (!"msg.tsautocraft.request.too_many".equals(last.error())) return last;
		}
		return last;
	}

	/**
	 * An Importer at {@code pos} brought items into the network: credit them to the running jobs of the network
	 * (Autocrafting Terminals and Requesters), each item only once.
	 */
	public static void itemsArrived(Level level, BlockPos pos, ItemStack stack, long count) {
		if (stack.isEmpty() || count <= 0) return;
		ItemKey key = new ItemKey(stack);
		long left = count;
		for (BlockEntity be : nodes(level, pos)) {
			List<CraftingJob> jobs = be instanceof AutocraftingTerminalBlockEntity cpu ? cpu.getJobs()
					: be instanceof RequesterBlockEntity r ? r.activeJobs() : List.of();
			for (CraftingJob j : jobs) {
				long used = j.onItemArrived(key, left);
				if (used > 0) {
					left -= used;
					be.setChanged();
				}
				if (left <= 0) return;
			}
		}
	}

	/** Block entities of all functional nodes on the cable network of {@code pos}. */
	public static List<BlockEntity> networkBlockEntities(Level level, BlockPos pos) {
		return nodes(level, pos);
	}

	/** Material list from the first Autocrafting Terminal on the network, or null if there is none. */
	public static CraftingJob.PlanOutcome preview(Level level, BlockPos terminal, ItemStack item, long amount) {
		for (BlockEntity be : nodes(level, terminal)) {
			if (be instanceof AutocraftingTerminalBlockEntity cpu) return cpu.preview(item, amount);
		}
		return null;
	}

	private static List<BlockEntity> nodes(Level level, BlockPos terminal) {
		List<BlockEntity> out = new ArrayList<>();
		for (BlockPos p : InventoryCableNetwork.getNetwork(level).getNetworkNodes(terminal)) {
			if (!level.isLoaded(p)) continue;
			BlockEntity be = level.getBlockEntity(p);
			if (be != null) out.add(be);
		}
		return out;
	}
}
