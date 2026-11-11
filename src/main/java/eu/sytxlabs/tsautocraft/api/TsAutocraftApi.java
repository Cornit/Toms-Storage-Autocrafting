package eu.sytxlabs.tsautocraft.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;
import eu.sytxlabs.tsautocraft.util.ItemKey;

/**
 * Entry point for other mods (server side). {@code pos} is any block on Tom's cable network.
 * <p>
 * Everything in {@code eu.sytxlabs.tsautocraft.api} is the public API: {@link PatternMode}s, {@link IOMode}s,
 * {@link CraftingProvider}, {@link JobOwner}, {@link CraftingEvents}, {@link Upgrades}, {@link MachineProfiles} and
 * {@link PlannerHooks}. It is also published as a separate jar (classifier {@code api}) to compile against.
 */
public final class TsAutocraftApi {
	/**
	 * Raised on API changes (3: {@link CraftingEvents} use the loader independent {@link Event}; 4: {@link StockExtensions},
	 * {@link NetworkBridges}, {@link TerminalReach} (incl. {@link TerminalReach#registerDeny}), {@link FluidUnits},
	 * {@link #itemsArrived}, {@link #activeAmount}, {@link PatternMode#maxInputs()} / {@link PatternMode#maxOutputs()}, all backward compatible).
	 */
	public static final int VERSION = 4;

	private TsAutocraftApi() {}

	/** Items the Pattern Providers and Crafting Emitters on the network can craft. */
	public static List<ItemStack> craftables(Level level, BlockPos pos) {
		List<ItemStack> l = new java.util.ArrayList<>();
		for (ItemStack st : TerminalCrafting.craftables(level, pos)) l.add(st.copy()); // the cached list is shared
		return l;
	}

	/** Running jobs of all job owners on the network. */
	public static List<CraftingJob> jobs(Level level, BlockPos pos) {
		List<CraftingJob> l = new ArrayList<>();
		for (JobOwner o : TerminalCrafting.jobOwners(level, pos)) l.addAll(o.activeJobs());
		return l;
	}

	/** Amount of {@code item} the unfinished jobs of the network are crafting right now (API 4; avoids double requests). */
	public static long activeAmount(Level level, BlockPos pos, ItemStack item) {
		long n = 0;
		for (CraftingJob j : jobs(level, pos)) {
			if (!j.isFinished() && ItemStack.isSameItemSameComponents(j.target(), item)) n += j.amount();
		}
		return n;
	}

	/**
	 * Starts a job in an Autocrafting Terminal of the network.
	 * @return null if it started, otherwise a translation key of the error ({@code msg.tsautocraft.request.*}); missing ingredients give {@code msg.tsautocraft.request.missing}
	 */
	public static String request(Level level, BlockPos pos, ItemStack item, long amount) {
		var r = TerminalCrafting.request(level, pos, item, amount);
		if (r.ok()) return null;
		return r.error() != null ? r.error() : "msg.tsautocraft.request.missing";
	}

	/**
	 * Results of crafts reached the network some other way than through an Importer (e.g. another mod moved a machine's
	 * fluid into tanks that count as filled containers): credits {@code count} of {@code stack} to the running jobs
	 * waiting for it, like an Importer at {@code pos} would (API 4).
	 */
	public static void itemsArrived(Level level, BlockPos pos, ItemStack stack, long count) {
		TerminalCrafting.itemsArrived(level, pos, stack, count);
	}

	/** What a request would be missing right now (empty = it can be crafted), null without an Autocrafting Terminal. */
	public static Map<ItemKey, Long> missing(Level level, BlockPos pos, ItemStack item, long amount) {
		var o = TerminalCrafting.preview(level, pos, item, amount);
		return o == null ? null : o.missing();
	}
}
