package eu.sytxlabs.tsautocraft.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;

import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;
import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity.DispatchResult;
import eu.sytxlabs.tsautocraft.pattern.PatternData;
import eu.sytxlabs.tsautocraft.planner.CraftingPlanner;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/**
 * A running crafting job. Stored in the block entity that created it
 * (Autocrafting Terminal or Requester), so it survives restarts and chunk unloading.
 * <p>
 * Flow: RUNNING (steps are dispatched to free providers once the ingredients are in the network)
 * → WAITING (everything dispatched, wait until the result has arrived in the network) → DONE.
 * There is intentionally no item reservation (AE2-light): if ingredients are used elsewhere in the meantime,
 * the job simply waits until they are available again.
 */
public class CraftingJob {
	// WAITING timeouts come from TsAutocraftConfig (jobResultTimeoutSeconds, jobIdleSeconds)

	public enum State { RUNNING, WAITING, DONE, CANCELLED }

	/** Why the job is currently stuck (for display). */
	public enum Reason { NONE, WAITING_ITEMS, PROVIDER_BUSY, NO_PROVIDER, NO_TARGET, NO_SPACE, SLOT_DISABLED, WAITING_RESULT, NO_NETWORK }

	public static final class Step {
		public static final Codec<Step> CODEC = RecordCodecBuilder.create(i -> i.group(
				PatternData.CODEC.fieldOf("pattern").forGetter(s -> s.pattern),
				Codec.LONG.fieldOf("total").forGetter(s -> s.total),
				Codec.LONG.fieldOf("remaining").forGetter(s -> s.remaining),
				Codec.LONG.optionalFieldOf("inflight", 0L).forGetter(s -> s.inFlight),
				Codec.LONG.optionalFieldOf("partial", 0L).forGetter(s -> s.partial)
				).apply(i, Step::new));

		public final PatternData pattern;
		public final long total;
		/** Crafts not dispatched yet. */
		public long remaining;
		/** Crafts dispatched whose result has not come back into the network yet. */
		public long inFlight;
		/** Result items that arrived for a craft that is not complete yet (outputs per craft > 1). */
		public long partial;

		public Step(PatternData pattern, long total, long remaining) {
			this(pattern, total, remaining, 0, 0);
		}

		public Step(PatternData pattern, long total, long remaining, long inFlight, long partial) {
			this.pattern = pattern;
			this.total = total;
			this.remaining = remaining;
			this.inFlight = inFlight;
			this.partial = partial;
		}
	}

	public static final Codec<CraftingJob> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("id").forGetter(j -> j.id),
			ItemStack.OPTIONAL_CODEC.fieldOf("target").forGetter(j -> j.target),
			Codec.LONG.fieldOf("amount").forGetter(j -> j.amount),
			Step.CODEC.listOf().fieldOf("steps").forGetter(j -> j.steps),
			Codec.INT.fieldOf("state").forGetter(j -> j.state.ordinal()),
			Codec.LONG.fieldOf("baseline").forGetter(j -> j.baseline),
			Codec.INT.optionalFieldOf("wait", 0).forGetter(j -> j.waitTicks)
			).apply(i, CraftingJob::new));

	private static final AtomicInteger NEXT_UID = new AtomicInteger();

	/** Persisted id, unique per owner block entity only. */
	public final int id;
	/** Runtime id, unique across all owners (used by the Autocrafting Terminal to cancel any job of the network). */
	public final int uid = NEXT_UID.incrementAndGet();
	public final ItemStack target;
	public final long amount;
	private final List<Step> steps;
	private State state;
	private final long baseline;
	private int waitTicks;
	// Not persisted: progress watch for WAITING (results taken out of storage) and RUNNING (inputs taken out)
	/** True once an Importer reported a result for this job: then the in-flight counters are reliable. */
	private boolean tracked;
	private long lastCount = -1;
	private int idleTicks;
	private int stalledTicks;
	private Reason reason = Reason.NONE;
	private ItemStack reasonItem = ItemStack.EMPTY;

	private CraftingJob(int id, ItemStack target, long amount, List<Step> steps, int state, long baseline, int waitTicks) {
		this.id = id;
		this.target = target.copyWithCount(1);
		this.amount = amount;
		this.steps = new ArrayList<>(steps);
		this.state = State.values()[Math.max(0, Math.min(state, State.values().length - 1))];
		this.baseline = baseline;
		this.waitTicks = waitTicks;
	}

	// ------------------------------------------------------------------ Planning

	/** Result of planning: either a job or the missing amounts, plus the material list for the preview. */
	public record PlanOutcome(CraftingJob job, Map<ItemKey, Long> missing, List<PlanEntry> materials) {
		public boolean ok() {
			return job != null;
		}
	}

	/** One line of the AE2 style material list: taken from storage, crafted (all steps), missing. */
	public record PlanEntry(ItemStack item, long stored, long crafted, long missing) {
		public static final Codec<PlanEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(PlanEntry::item),
				Codec.LONG.fieldOf("s").forGetter(PlanEntry::stored),
				Codec.LONG.fieldOf("c").forGetter(PlanEntry::crafted),
				Codec.LONG.fieldOf("m").forGetter(PlanEntry::missing)
				).apply(i, PlanEntry::new));
	}

	private static List<PlanEntry> materials(CraftingPlanner.Result<ItemKey, PatternData> result) {
		Map<ItemKey, long[]> m = new LinkedHashMap<>();
		// Crafted first (in dependency order), then raw materials from storage, then missing
		for (var s : result.steps()) {
			s.pattern().outputTotals().forEach((k, per) -> m.computeIfAbsent(k, x -> new long[3])[1] += per * s.crafts());
		}
		result.fromStock().forEach((k, c) -> m.computeIfAbsent(k, x -> new long[3])[0] += c);
		result.missing().forEach((k, c) -> m.computeIfAbsent(k, x -> new long[3])[2] += c);
		List<PlanEntry> list = new ArrayList<>(m.size());
		m.forEach((k, v) -> list.add(new PlanEntry(k.create(1), v[0], v[1], v[2])));
		// Missing on top, like AE2
		list.sort((a, b) -> Boolean.compare(b.missing() > 0, a.missing() > 0));
		return list;
	}

	/**
	 * Plans to craft {@code amount} NEW items of {@code target} (existing stock of the target does not count,
	 * intermediates and raw materials from storage do).
	 */
	public static PlanOutcome plan(int id, Level level, IInventoryAccess net, List<PatternProviderBlockEntity> providers,
			ItemStack target, long amount) {
		Map<ItemKey, List<PatternData>> byOutput = new HashMap<>();
		LinkedHashSet<PatternData> all = new LinkedHashSet<>();
		for (PatternProviderBlockEntity p : providers) all.addAll(p.getPatterns());
		for (PatternData d : all) {
			for (ItemKey out : d.outputTotals().keySet()) byOutput.computeIfAbsent(out, k -> new ArrayList<>()).add(d);
		}

		// Patterns with substitutes: plan with the variant that has the most stock per position (e.g. birch planks
		// when there are more of them than oak). The provider still picks per craft whatever is in stock.
		Map<PatternData, Map<ItemKey, Long>> inputs = new HashMap<>();
		for (PatternData d : all) {
			if (d.hasSubstitutes()) inputs.put(d, resolvedInputs(d, k -> StorageUtil.count(net, level, k)));
		}

		CraftingPlanner<ItemKey, PatternData> planner = new CraftingPlanner<>(
				k -> StorageUtil.count(net, level, k),
				byOutput::get,
				d -> inputs.getOrDefault(d, d.inputTotals()),
				PatternData::outputTotals);

		ItemKey key = new ItemKey(target);
		var result = planner.plan(key, amount, false);
		List<PlanEntry> materials = materials(result);
		if (!result.isComplete()) return new PlanOutcome(null, new LinkedHashMap<>(result.missing()), materials);

		List<Step> steps = new ArrayList<>();
		result.steps().forEach(s -> steps.add(new Step(s.pattern(), s.crafts(), s.crafts())));
		long baseline = StorageUtil.count(net, level, key);
		return new PlanOutcome(new CraftingJob(id, target, amount, steps, State.RUNNING.ordinal(), baseline, 0), Map.of(), materials);
	}

	/** Input totals with every position replaced by its best-stocked option (the item itself on a tie). */
	private static Map<ItemKey, Long> resolvedInputs(PatternData d, java.util.function.ToLongFunction<ItemKey> stock) {
		Map<ItemKey, Long> m = new LinkedHashMap<>();
		for (PatternData.Entry e : d.inputs()) {
			if (e.isEmpty()) continue;
			ItemKey best = new ItemKey(e.item());
			long bestStock = stock.applyAsLong(best);
			for (ItemStack alt : e.alternatives()) {
				ItemKey k = new ItemKey(alt);
				long s = stock.applyAsLong(k);
				if (s > bestStock) {
					best = k;
					bestStock = s;
				}
			}
			m.merge(best, (long) e.count(), Long::sum);
		}
		return m;
	}

	/** All outputs craftable in the network (for the terminal list). */
	public static List<ItemStack> craftables(List<PatternProviderBlockEntity> providers) {
		LinkedHashSet<ItemKey> keys = new LinkedHashSet<>();
		for (PatternProviderBlockEntity p : providers) {
			for (PatternData d : p.getPatterns()) keys.addAll(d.outputTotals().keySet());
		}
		List<ItemStack> l = new ArrayList<>(keys.size());
		keys.forEach(k -> l.add(k.create(1)));
		return l;
	}

	// ------------------------------------------------------------------ Execution

	/** Run one execution step. Called every few ticks by the owner. */
	public void tick(Level level, IInventoryAccess net, List<PatternProviderBlockEntity> providers, int elapsedTicks) {
		if (state == State.DONE || state == State.CANCELLED) return;
		if (net == PlatformInventoryAccess.EMPTY) {
			setReason(Reason.NO_NETWORK, ItemStack.EMPTY);
			return;
		}

		if (state == State.WAITING) {
			waitTicks += elapsedTicks;
			boolean allArrived = steps.stream().allMatch(s -> s.inFlight <= 0);
			long count = StorageUtil.count(net, level, new ItemKey(target));
			if (count > lastCount) idleTicks = 0;
			else idleTicks += elapsedTicks;
			lastCount = count;
			// Results reported by an Importer: done exactly when everything came back (waitTicks restarts on every arrival).
			// Otherwise (results reach storage another way) fall back to the stock: target reached, or no new results
			// for WAIT_IDLE ticks (an Exporter or a player may take items out, so the stock alone is not enough).
			TsAutocraftConfig cfg = TsAutocraftConfig.get();
			boolean done = tracked ? allArrived : (allArrived || count >= baseline + amount || idleTicks > cfg.jobIdleSeconds * 20);
			if (done || waitTicks > cfg.jobResultTimeoutSeconds * 20) {
				state = State.DONE;
				setReason(Reason.NONE, ItemStack.EMPTY);
			} else {
				setReason(Reason.WAITING_RESULT, target);
			}
			return;
		}

		boolean progressed = false;
		Reason why = Reason.NONE;
		ItemStack whyItem = ItemStack.EMPTY;

		for (Step step : steps) {
			if (step.remaining <= 0) continue;
			boolean anyProvider = false;
			for (PatternProviderBlockEntity prov : providers) {
				if (step.remaining <= 0) break;
				if (!prov.hasPattern(step.pattern)) continue;
				anyProvider = true;
				DispatchResult r = prov.dispatch(step.pattern, net);
				if (r == DispatchResult.OK) {
					step.remaining--;
					step.inFlight++;
					progressed = true;
				} else if (why.ordinal() < toReason(r).ordinal() || why == Reason.NONE) {
					why = toReason(r);
					whyItem = r == DispatchResult.MISSING ? firstMissingInput(level, net, step.pattern) : step.pattern.primaryOutput();
				}
			}
			if (!anyProvider) {
				why = Reason.NO_PROVIDER;
				whyItem = step.pattern.primaryOutput();
			}
		}

		stalledTicks = progressed ? 0 : stalledTicks + elapsedTicks;
		if (steps.stream().allMatch(s -> s.remaining <= 0)) {
			state = State.WAITING;
			waitTicks = 0;
			idleTicks = 0;
			lastCount = -1;
			setReason(Reason.WAITING_RESULT, target);
		} else if (progressed) {
			setReason(Reason.NONE, ItemStack.EMPTY);
		} else {
			setReason(why, whyItem);
		}
	}

	/**
	 * An Importer brought {@code count} items of {@code key} into the network. Credits them to crafts of this job
	 * that are still in flight (by the pattern's primary output).
	 * @return how many of the items this job used up (the rest may belong to other jobs)
	 */
	public long onItemArrived(ItemKey key, long count) {
		if (isFinished() || count <= 0) return 0;
		long used = 0;
		for (Step s : steps) {
			if (s.inFlight <= 0 || used >= count) continue;
			ItemStack primary = s.pattern.primaryOutput();
			if (primary.isEmpty() || !key.equals(new ItemKey(primary))) continue;
			long per = Math.max(1, s.pattern.outputTotals().getOrDefault(key, 1L));
			long take = Math.min(s.inFlight * per - s.partial, count - used);
			if (take <= 0) continue;
			s.partial += take;
			used += take;
			long crafts = s.partial / per;
			s.partial -= crafts * per;
			s.inFlight -= crafts;
		}
		if (used > 0) {
			tracked = true;
			waitTicks = 0;
		}
		return used;
	}

	private static ItemStack firstMissingInput(Level level, IInventoryAccess net, PatternData p) {
		for (var e : p.inputTotals().entrySet()) {
			if (StorageUtil.count(net, level, e.getKey()) < e.getValue()) return e.getKey().create(1);
		}
		return p.primaryOutput();
	}

	private static Reason toReason(DispatchResult r) {
		return switch (r) {
		case OK -> Reason.NONE;
		case BUSY -> Reason.PROVIDER_BUSY;
		case MISSING -> Reason.WAITING_ITEMS;
		case NO_TARGET -> Reason.NO_TARGET;
		case NO_SPACE -> Reason.NO_SPACE;
		case SLOT_DISABLED -> Reason.SLOT_DISABLED;
		};
	}

	private void setReason(Reason r, ItemStack item) {
		reason = r;
		reasonItem = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
	}

	public void cancel() {
		if (state != State.DONE) state = State.CANCELLED;
	}

	public State state() {
		return state;
	}

	public boolean isFinished() {
		return state == State.DONE || state == State.CANCELLED;
	}

	public Reason reason() {
		return reason;
	}

	/** Ticks without a dispatch while RUNNING. */
	public int stalledTicks() {
		return stalledTicks;
	}

	public ItemStack reasonItem() {
		return reasonItem;
	}

	public long totalCrafts() {
		return steps.stream().mapToLong(s -> s.total).sum();
	}

	/** Crafts whose result came back (once an Importer reports results), otherwise crafts dispatched. */
	public long doneCrafts() {
		return steps.stream().mapToLong(s -> s.total - s.remaining - (tracked ? s.inFlight : 0)).sum();
	}

	/** Compact view for the client; {@code id} is the runtime {@link #uid}, {@code requester} marks Requester jobs. */
	public JobView view(boolean requester) {
		return new JobView(uid, target, amount, doneCrafts(), totalCrafts(), state.ordinal(), reason.ordinal(), reasonItem, requester);
	}

	public record JobView(int id, ItemStack target, long amount, long done, long total, int state, int reason, ItemStack reasonItem,
			boolean requester) {
		public static final Codec<JobView> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("id").forGetter(JobView::id),
				ItemStack.OPTIONAL_CODEC.fieldOf("t").forGetter(JobView::target),
				Codec.LONG.fieldOf("a").forGetter(JobView::amount),
				Codec.LONG.fieldOf("d").forGetter(JobView::done),
				Codec.LONG.fieldOf("n").forGetter(JobView::total),
				Codec.INT.fieldOf("s").forGetter(JobView::state),
				Codec.INT.fieldOf("r").forGetter(JobView::reason),
				ItemStack.OPTIONAL_CODEC.fieldOf("ri").forGetter(JobView::reasonItem),
				Codec.BOOL.optionalFieldOf("rq", false).forGetter(JobView::requester)
				).apply(i, JobView::new));
	}
}
