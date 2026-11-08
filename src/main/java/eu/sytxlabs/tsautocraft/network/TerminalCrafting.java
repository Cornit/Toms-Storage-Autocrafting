package eu.sytxlabs.tsautocraft.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

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
		return NetworkCache.of(level, terminal).craftables();
	}

	/** Block entities on the network of {@code pos} that run jobs. */
	public static List<JobOwner> jobOwners(Level level, BlockPos pos) {
		return NetworkCache.of(level, pos).owners();
	}

	/** What all running jobs of the network of {@code pos} have reserved (live view). */
	public static Reservations reservations(Level level, BlockPos pos) {
		return NetworkCache.of(level, pos).reservations(level);
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

	/**
	 * An Importer at {@code pos} brought items into the network: credit them to the running jobs of the network (Autocrafting Terminals, Requesters, Exporters), each item only once.
	 * The job that has waited longest for this item comes first (results leave the machines in dispatch order).
	 */
	public static void itemsArrived(Level level, BlockPos pos, ItemStack stack, long count) {
		if (stack.isEmpty() || count <= 0) return;
		ItemKey key = new ItemKey(stack);
		record Waiting(BlockEntity owner, CraftingJob job, long since) {}
		List<Waiting> waiting = new ArrayList<>();
		NetworkCache.Snapshot snap = NetworkCache.of(level, pos);
		providerResults(snap.providers(), key, count);
		for (JobOwner owner : snap.owners()) {
			if (!(owner instanceof BlockEntity be)) continue;
			for (CraftingJob j : owner.activeJobs()) {
				long since = j.waitingSince(key);
				if (since != Long.MAX_VALUE) waiting.add(new Waiting(be, j, since));
			}
		}
		waiting.sort(java.util.Comparator.comparingLong(Waiting::since).thenComparingInt(w -> w.job().uid));
		long left = count;
		for (Waiting w : waiting) {
			long used = w.job().onItemArrived(key, left);
			if (used > 0) {
				left -= used;
				w.owner().setChanged();
			}
			if (left <= 0) return;
		}
	}

	/**
	 * Lock mode "until result returned": the arrived results unlock the providers waiting for them, the one that has
	 * waited longest first. Independent of the jobs (results of a cancelled job still unlock the machine).
	 */
	private static void providerResults(List<CraftingProvider> providers, ItemKey key, long count) {
		record Awaiting(CraftingProvider provider, long since) {}
		List<Awaiting> awaiting = new ArrayList<>();
		for (CraftingProvider p : providers) {
			long since = p.awaitingSince(key);
			if (since != Long.MAX_VALUE) awaiting.add(new Awaiting(p, since));
		}
		awaiting.sort(java.util.Comparator.comparingLong(Awaiting::since));
		long left = count;
		for (Awaiting a : awaiting) {
			left -= a.provider().resultsArrived(key, left);
			if (left <= 0) return;
		}
	}

	/** A job with id {@code jobUid} on the network of {@code pos} was cancelled: providers give back its undelivered inputs. */
	public static void returnInputs(Level level, BlockPos pos, int jobUid) {
		NetworkCache.Snapshot s = NetworkCache.of(level, pos);
		var c = s.connector();
		if (c == null) return;
		var net = c.getMergedHandler();
		for (CraftingProvider p : s.providers()) p.returnInputs(jobUid, net);
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
		for (BlockEntity be : NetworkCache.of(level, terminal).nodes()) if (!be.isRemoved()) out.add(be);
		return out;
	}
}
