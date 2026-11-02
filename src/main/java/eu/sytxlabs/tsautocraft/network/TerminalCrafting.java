package eu.sytxlabs.tsautocraft.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.tom.storagemod.inventory.InventoryCableNetwork;

import eu.sytxlabs.tsautocraft.api.CraftingProvider;
import eu.sytxlabs.tsautocraft.api.JobOwner;
import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity.RequestResult;
import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.Reservations;
import eu.sytxlabs.tsautocraft.util.ItemKey;

/**
 * Autocrafting access for Tom's Storage terminals (wired in by {@code StorageTerminalMenuMixin}).
 * The terminal only lists and requests; the job itself runs in an Autocrafting Terminal on the same cable network.
 */
public final class TerminalCrafting {
	private TerminalCrafting() {}

	/** Items the Pattern Providers (and Crafting Emitters) on the terminal's network can craft. */
	public static List<ItemStack> craftables(Level level, BlockPos terminal) {
		List<CraftingProvider> providers = new ArrayList<>();
		for (BlockEntity be : nodes(level, terminal)) {
			if (be instanceof CraftingProvider p) providers.add(p);
		}
		return CraftingJob.craftables(providers);
	}

	/** Block entities on the network of {@code pos} that run jobs. */
	public static List<JobOwner> jobOwners(Level level, BlockPos pos) {
		List<JobOwner> out = new ArrayList<>();
		for (BlockEntity be : nodes(level, pos)) if (be instanceof JobOwner o) out.add(o);
		return out;
	}

	/** What all running jobs of the network of {@code pos} have reserved. */
	public static Reservations reservations(Level level, BlockPos pos) {
		List<CraftingJob> jobs = new ArrayList<>();
		for (JobOwner o : jobOwners(level, pos)) jobs.addAll(o.activeJobs());
		return Reservations.of(jobs);
	}

	/** Starts a job in the first Autocrafting Terminal on the network that accepts it. */
	public static RequestResult request(Level level, BlockPos terminal, ItemStack item, long amount) {
		RequestResult last = RequestResult.error("msg.tsautocraft.request.no_cpu");
		for (BlockEntity be : nodes(level, terminal)) {
			if (!(be instanceof AutocraftingTerminalBlockEntity cpu)) continue;
			last = cpu.request(item, amount);
			if (!"msg.tsautocraft.request.too_many".equals(last.error())) return last;
		}
		return last;
	}

	/** An Importer at {@code pos} brought items into the network: credit them to the running jobs of the network (Autocrafting Terminals, Requesters, Exporters), each item only once. */
	public static void itemsArrived(Level level, BlockPos pos, ItemStack stack, long count) {
		if (stack.isEmpty() || count <= 0) return;
		ItemKey key = new ItemKey(stack);
		long left = count;
		for (BlockEntity be : nodes(level, pos)) {
			if (!(be instanceof JobOwner owner)) continue;
			for (CraftingJob j : owner.activeJobs()) {
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
