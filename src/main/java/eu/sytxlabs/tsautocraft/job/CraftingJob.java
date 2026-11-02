package eu.sytxlabs.tsautocraft.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;

import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.api.CraftingEvents;
import eu.sytxlabs.tsautocraft.api.CraftingProvider.Dispatch;
import eu.sytxlabs.tsautocraft.api.CraftingProvider.DispatchResult;
import eu.sytxlabs.tsautocraft.api.CraftingProvider;
import eu.sytxlabs.tsautocraft.api.PlannerHooks;
import eu.sytxlabs.tsautocraft.pattern.PatternData;
import eu.sytxlabs.tsautocraft.planner.CraftingPlanner;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/**
 * A running crafting job. Stored in the block entity that created it
 * (Autocrafting Terminal or Requester), so it survives restarts and chunk unloading.
 * <p>
 * Flow: RUNNING (steps are dispatched to free providers once the ingredients are in the network) → WAITING (everything dispatched, wait until the result has arrived in the network) → DONE.
 * Raw materials taken from storage at planning time (and intermediates the job's own crafts bring back through an Importer) are {@linkplain #reserved() reserved}: other jobs, new plans and Exporters do not use them.
 * Players can still take them out of a terminal; the job then waits until they are available again.
 */
public class CraftingJob {
	public enum State { RUNNING, WAITING, DONE, CANCELLED }
	public enum Reason { NONE, WAITING_ITEMS, PROVIDER_BUSY, NO_PROVIDER, NO_TARGET, NO_SPACE, SLOT_DISABLED, WAITING_RESULT, NO_NETWORK, PAUSED }
	public static final int PRIORITY_LOW = -1, PRIORITY_NORMAL = 0, PRIORITY_HIGH = 1;

	public static final class Step {
		public static final Codec<Step> CODEC = RecordCodecBuilder.create(i -> i.group(PatternData.CODEC.fieldOf("pattern").forGetter(s -> s.pattern), Codec.LONG.fieldOf("total").forGetter(s -> s.total), Codec.LONG.fieldOf("remaining").forGetter(s -> s.remaining), Codec.LONG.optionalFieldOf("inflight", 0L).forGetter(s -> s.inFlight), Codec.LONG.optionalFieldOf("partial", 0L).forGetter(s -> s.partial)).apply(i, Step::new));
		public final PatternData pattern;
		public final long total;
		public long remaining;
		public long inFlight;
		public long partial;
		Reason reason = Reason.NONE;
		ItemStack reasonItem = ItemStack.EMPTY;
		long need, have;
		BlockPos provider;
		net.minecraft.network.chat.Component providerName;

		void clearReason() {
			reason = Reason.NONE;
			reasonItem = ItemStack.EMPTY;
			need = have = 0;
			provider = null;
			providerName = null;
		}

		/** First input (or substitute group) that is short for one craft: item, needed and usable amount. */
		void missingInput(Level level, IInventoryAccess net, ToLongFunction<ItemKey> available) {
			for (var e : pattern.inputTotals().entrySet()) {
				long a = Math.max(0, available.applyAsLong(e.getKey()));
				if (a < e.getValue() && !hasSubstituteFor(e.getKey(), e.getValue(), available)) {
					reasonItem = e.getKey().create(1);
					need = e.getValue();
					have = a;
					return;
				}
			}
			reasonItem = pattern.primaryOutput();
			need = have = 0;
		}

		private boolean hasSubstituteFor(ItemKey key, long count, ToLongFunction<ItemKey> available) {
			for (PatternData.Entry en : pattern.inputs()) {
				if (en.isEmpty() || !key.matches(en.item())) continue;
				for (ItemStack alt : en.alternatives()) if (available.applyAsLong(new ItemKey(alt)) >= count) return true;
			}
			return false;
		}

		public Reason reason() {
			return reason;
		}

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

	/** Item + amount (ItemStack counts are limited to 99 in codecs). */
	public record Amount(ItemStack item, long count) {
		public static final Codec<Amount> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(Amount::item), Codec.LONG.fieldOf("c").forGetter(Amount::count)).apply(i, Amount::new));
	}

	public static final Codec<CraftingJob> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("id").forGetter(j -> j.id), ItemStack.OPTIONAL_CODEC.fieldOf("target").forGetter(j -> j.target), Codec.LONG.fieldOf("amount").forGetter(j -> j.amount), Step.CODEC.listOf().fieldOf("steps").forGetter(j -> j.steps), Codec.INT.fieldOf("state").forGetter(j -> j.state.ordinal()), Codec.LONG.fieldOf("baseline").forGetter(j -> j.baseline), Codec.INT.optionalFieldOf("wait", 0).forGetter(j -> j.waitTicks), Amount.CODEC.listOf().optionalFieldOf("reserved", List.of()).forGetter(CraftingJob::reservedList), Codec.INT.optionalFieldOf("prio", PRIORITY_NORMAL).forGetter(j -> j.priority), Codec.BOOL.optionalFieldOf("paused", false).forGetter(j -> j.paused)).apply(i, CraftingJob::new));
	private static final AtomicInteger NEXT_UID = new AtomicInteger();
	public final int id;
	public final int uid = NEXT_UID.incrementAndGet();
	public final ItemStack target;
	public final long amount;
	private final List<Step> steps;
	private State state;
	private final long baseline;
	private int waitTicks;
	private final Map<ItemKey, Long> reserved = new LinkedHashMap<>();
	private boolean tracked;
	private long lastCount = -1;
	private int idleTicks;
	private int stalledTicks;
	private Reason reason = Reason.NONE;
	private ItemStack reasonItem = ItemStack.EMPTY;
	private int priority;
	private boolean paused;
	private Level level;
	private BlockPos ownerPos;

	private CraftingJob(int id, ItemStack target, long amount, List<Step> steps, int state, long baseline, int waitTicks, List<Amount> reserved, int priority, boolean paused) {
		this.id = id;
		this.priority = Math.clamp(priority, PRIORITY_LOW, PRIORITY_HIGH);
		this.paused = paused;
		this.target = target.copyWithCount(1);
		this.amount = amount;
		this.steps = new ArrayList<>(steps);
		this.state = State.values()[Math.clamp(state, 0, State.values().length - 1)];
		this.baseline = baseline;
		this.waitTicks = waitTicks;
		for (Amount a : reserved) if (!a.item().isEmpty() && a.count() > 0) this.reserved.merge(new ItemKey(a.item()), a.count(), Long::sum);
	}

	private List<Amount> reservedList() {
		List<Amount> l = new ArrayList<>(reserved.size());
		reserved.forEach((k, c) -> l.add(new Amount(k.create(1), c)));
		return l;
	}

	public Map<ItemKey, Long> reserved() {
		return paused ? Map.of() : reserved;
	}

	public int priority() {
		return priority;
	}

	public void setPriority(int priority) {
		this.priority = Math.clamp(priority, PRIORITY_LOW, PRIORITY_HIGH);
	}

	public boolean paused() {
		return paused;
	}

	public void setPaused(boolean paused) {
		if (isFinished()) return;
		this.paused = paused;
		setReason(paused ? Reason.PAUSED : Reason.NONE, ItemStack.EMPTY);
	}

	/** Called by the {@link JobScheduler} before every tick. */
	void bind(Level level, BlockPos ownerPos) {
		this.level = level;
		this.ownerPos = ownerPos;
	}

	public Level level() {
		return level;
	}

	public BlockPos ownerPos() {
		return ownerPos;
	}

	public List<Step> steps() {
		return java.util.Collections.unmodifiableList(steps);
	}

	//region Planning

	/**
	 * Result of planning: either a job or the missing amounts, plus the material list for the preview. {@code possible} = largest amount that could be crafted right now (only computed for previews that fail, else -1).
	 */
	public record PlanOutcome(CraftingJob job, Map<ItemKey, Long> missing, List<PlanEntry> materials, long possible) {
		public boolean ok() {
			return job != null;
		}

		public PlanOutcome withPossible(long possible) {
			return new PlanOutcome(job, missing, materials, possible);
		}
	}

	/** One line of the AE2 style material list: taken from storage, crafted (all steps), missing. */
	public record PlanEntry(ItemStack item, long stored, long crafted, long missing) {
		public static final Codec<PlanEntry> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(PlanEntry::item), Codec.LONG.fieldOf("s").forGetter(PlanEntry::stored), Codec.LONG.fieldOf("c").forGetter(PlanEntry::crafted), Codec.LONG.fieldOf("m").forGetter(PlanEntry::missing)).apply(i, PlanEntry::new));
	}

	private static List<PlanEntry> materials(CraftingPlanner.Result<ItemKey, PatternData> result) {
		Map<ItemKey, long[]> m = new LinkedHashMap<>();
		for (var s : result.steps()) {
			s.pattern().outputTotals().forEach((k, per) -> m.computeIfAbsent(k, _ -> new long[3])[1] += per * s.crafts());
		}
		result.fromStock().forEach((k, c) -> m.computeIfAbsent(k, _ -> new long[3])[0] += c);
		result.missing().forEach((k, c) -> m.computeIfAbsent(k, _ -> new long[3])[2] += c);
		List<PlanEntry> list = new ArrayList<>(m.size());
		m.forEach((k, v) -> list.add(new PlanEntry(k.create(1), v[0], v[1], v[2])));
		list.sort((a, b) -> Boolean.compare(b.missing() > 0, a.missing() > 0));
		return list;
	}

	/**
	 * Plans to craft {@code amount} NEW items of {@code target} (existing stock of the target does not count, intermediates and raw materials from storage do).
	 */
	public static PlanOutcome plan(int id, Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, ItemStack target, long amount, Reservations res) {
		return plan(id, level, net, providers, target, amount, res, true);
	}

	private static PlanOutcome plan(int id, Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, ItemStack target, long amount, Reservations res, boolean notify) {
		Map<ItemKey, List<PatternData>> byOutput = new HashMap<>();
		LinkedHashSet<PatternData> all = new LinkedHashSet<>();
		for (CraftingProvider p : providers) {
			for (PatternData d : p.getPatterns()) if (PlannerHooks.isCraftable(d.primaryOutput())) all.add(d);
		}
		for (PatternData d : all) {
			for (ItemKey out : d.outputTotals().keySet()) byOutput.computeIfAbsent(out, _ -> new ArrayList<>()).add(d);
		}
		var order = PlannerHooks.patternOrder();
		if (order != null) byOutput.values().forEach(l -> l.sort(order));

		ToLongFunction<ItemKey> stock = k -> Math.max(0, StorageUtil.count(net, level, k) - res.total(k));
		Map<PatternData, Map<ItemKey, Long>> inputs = new HashMap<>();
		for (PatternData d : all) {
			if (d.hasSubstitutes()) inputs.put(d, resolvedInputs(d, stock));
		}

		CraftingPlanner<ItemKey, PatternData> planner = new CraftingPlanner<>(stock, byOutput::get, d -> inputs.getOrDefault(d, d.inputTotals()), PatternData::outputTotals);

		ItemKey key = new ItemKey(target);
		var result = planner.plan(key, amount, false);
		List<PlanEntry> materials = materials(result);
		if (notify) CraftingEvents.PLAN_CREATED.invoker().onPlan(level, target, amount, result.isComplete(), materials);
		if (!result.isComplete()) return new PlanOutcome(null, new LinkedHashMap<>(result.missing()), materials, -1);

		List<Step> steps = new ArrayList<>();
		result.steps().forEach(s -> steps.add(new Step(s.pattern(), s.crafts(), s.crafts())));
		long baseline = StorageUtil.count(net, level, key);
		List<Amount> reserve = new ArrayList<>();
		result.fromStock().forEach((k, c) -> reserve.add(new Amount(k.create(1), c)));
		return new PlanOutcome(new CraftingJob(id, target, amount, steps, State.RUNNING.ordinal(), baseline, 0, reserve, PRIORITY_NORMAL, false), Map.of(), materials, -1);
	}

	/** Largest amount below {@code amount} that can be crafted right now (binary search over complete plans), 0 if none. */
	public static long maxPossible(Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, ItemStack target, long amount, Reservations res) {
		long lo = 0, hi = amount - 1;
		while (lo < hi) {
			long mid = (lo + hi + 1) >>> 1;
			if (plan(0, level, net, providers, target, mid, res, false).ok()) lo = mid;
			else hi = mid - 1;
		}
		return lo;
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
	public static List<ItemStack> craftables(List<? extends CraftingProvider> providers) {
		LinkedHashSet<ItemKey> keys = new LinkedHashSet<>();
		for (CraftingProvider p : providers) {
			for (PatternData d : p.getPatterns()) {
				if (!PlannerHooks.isCraftable(d.primaryOutput())) continue;
				for (ItemKey k : d.outputTotals().keySet()) if (PlannerHooks.isCraftable(k.template())) keys.add(k);
			}
		}
		List<ItemStack> l = new ArrayList<>(keys.size());
		keys.forEach(k -> l.add(k.create(1)));
		return l;
	}
	//endregion

	//region Execution

	/** Run one execution step. Called every few ticks by the {@link JobScheduler} (higher priorities first). */
	public void tick(Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, int elapsedTicks, Reservations res) {
		if (state == State.DONE || state == State.CANCELLED) return;
		if (paused) {
			setReason(Reason.PAUSED, ItemStack.EMPTY);
			return;
		}
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
			TsAutocraftConfig cfg = TsAutocraftConfig.get();
			boolean idle = idleTicks > cfg.jobIdleSeconds * 20 && steps.stream().noneMatch(s -> s.pattern.inputs().isEmpty());
			boolean done = tracked ? allArrived : (allArrived || count >= baseline + amount || idle);
			if (done || waitTicks > cfg.jobResultTimeoutSeconds * 20) {
				finish(State.DONE);
			} else {
				setReason(Reason.WAITING_RESULT, target);
			}
			return;
		}

		boolean progressed = false;
		Reason why = Reason.NONE;
		ItemStack whyItem = ItemStack.EMPTY;

		ToLongFunction<ItemKey> available = k -> StorageUtil.count(net, level, k) - res.others(this, k);
		for (Step step : steps) {
			if (step.remaining <= 0) {
				step.clearReason();
				continue;
			}
			boolean anyProvider = false;
			boolean stepProgressed = false;
			Reason stepWhy = Reason.NONE;
			for (CraftingProvider prov : providers) {
				if (step.remaining <= 0) break;
				if (!prov.hasPattern(step.pattern)) continue;
				anyProvider = true;
				Dispatch d = prov.dispatch(step.pattern, net, available, step.remaining);
				DispatchResult r = d.result();
				if (d.ok()) {
					long n = Math.min(d.crafts(), step.remaining);
					step.remaining -= n;
					step.inFlight += n;
					d.consumed().forEach(this::unreserve);
					progressed = stepProgressed = true;
					CraftingEvents.STEP_DISPATCHED.invoker().onDispatch(this, step.pattern, n, prov);
				} else if (stepWhy == Reason.NONE || stepWhy.ordinal() < toReason(r).ordinal()) {
					stepWhy = toReason(r);
					step.provider = prov.providerPos();
					step.providerName = prov.providerName();
					if (r == DispatchResult.MISSING) {
						step.missingInput(level, net, available);
					} else {
						step.reasonItem = step.pattern.primaryOutput();
						step.need = step.have = 0;
					}
				}
			}
			if (!anyProvider) {
				stepWhy = Reason.NO_PROVIDER;
				step.reasonItem = step.pattern.primaryOutput();
				step.provider = null;
				step.providerName = null;
			}
			step.reason = stepProgressed ? Reason.NONE : stepWhy;
			if (!stepProgressed && (why == Reason.NONE || why.ordinal() < stepWhy.ordinal())) {
				why = stepWhy;
				whyItem = step.reasonItem;
			}
		}

		stalledTicks = progressed ? 0 : stalledTicks + elapsedTicks;
		if (steps.stream().allMatch(s -> s.remaining <= 0)) {
			state = State.WAITING;
			reserved.clear(); // everything is pulled; leftovers (surplus) are free again
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

	private void finish(State end) {
		if (isFinished()) return;
		state = end;
		reserved.clear();
		paused = false;
		setReason(Reason.NONE, ItemStack.EMPTY);
		CraftingEvents.JOB_FINISHED.invoker().onFinished(this, end == State.CANCELLED);
	}

	/**
	 * An Importer brought {@code count} items of {@code key} into the network. Credits them to crafts of this job that are still in flight (by the pattern's primary output).
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
			if (!key.equals(new ItemKey(target)) && needsLater(key)) reserved.merge(key, used, Long::sum);
		}
		return used;
	}

	private boolean needsLater(ItemKey key) {
		for (Step s : steps) {
			if (s.remaining <= 0) continue;
			for (PatternData.Entry e : s.pattern.inputs()) {
				if (!e.isEmpty() && (key.matches(e.item()) || e.alternatives().stream().anyMatch(key::matches))) return true;
			}
		}
		return false;
	}

	private void unreserve(ItemKey k, long n) {
		Long cur = reserved.get(k);
		if (cur == null) return;
		if (cur <= n) reserved.remove(k);
		else reserved.put(k, cur - n);
	}

	/** Crafts of {@code p} not finished yet (dispatched or not), used by the Crafting Emitter. */
	public long pendingFor(PatternData p) {
		if (isFinished()) return 0;
		long n = 0;
		for (Step s : steps) if (s.pattern.equals(p)) n += s.remaining + s.inFlight;
		return n;
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
		if (state == State.DONE) return;
		if (isFinished()) {
			reserved.clear();
			return;
		}
		finish(State.CANCELLED);
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
		List<StepView> sv = new ArrayList<>(steps.size());
		for (Step s : steps) {
			sv.add(new StepView(s.pattern.primaryOutput().copyWithCount(1), s.total - s.remaining - (tracked ? s.inFlight : 0), s.total, s.reason.ordinal(), s.reasonItem, s.need, s.have, java.util.Optional.ofNullable(s.provider), java.util.Optional.ofNullable(s.providerName)));
		}
		return new JobView(uid, target, amount, doneCrafts(), totalCrafts(), state.ordinal(), reason.ordinal(), reasonItem, requester, sv, priority, paused);
	}

	/** Progress of one step: crafts done of total, plus why it is stuck (missing input: need per craft / usable). */
	public record StepView(ItemStack output, long done, long total, int reason, ItemStack reasonItem, long need, long have, java.util.Optional<BlockPos> provider, java.util.Optional<net.minecraft.network.chat.Component> providerName) {
		public static final Codec<StepView> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("o").forGetter(StepView::output), Codec.LONG.fieldOf("d").forGetter(StepView::done), Codec.LONG.fieldOf("n").forGetter(StepView::total), Codec.INT.optionalFieldOf("r", 0).forGetter(StepView::reason), ItemStack.OPTIONAL_CODEC.optionalFieldOf("ri", ItemStack.EMPTY).forGetter(StepView::reasonItem), Codec.LONG.optionalFieldOf("nd", 0L).forGetter(StepView::need), Codec.LONG.optionalFieldOf("hv", 0L).forGetter(StepView::have), BlockPos.CODEC.optionalFieldOf("p").forGetter(StepView::provider), net.minecraft.network.chat.ComponentSerialization.CODEC.optionalFieldOf("pn").forGetter(StepView::providerName)).apply(i, StepView::new));
	}

	public record JobView(int id, ItemStack target, long amount, long done, long total, int state, int reason, ItemStack reasonItem, boolean requester, List<StepView> steps, int priority, boolean paused) {
		public static final Codec<JobView> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("id").forGetter(JobView::id), ItemStack.OPTIONAL_CODEC.fieldOf("t").forGetter(JobView::target), Codec.LONG.fieldOf("a").forGetter(JobView::amount), Codec.LONG.fieldOf("d").forGetter(JobView::done), Codec.LONG.fieldOf("n").forGetter(JobView::total), Codec.INT.fieldOf("s").forGetter(JobView::state), Codec.INT.fieldOf("r").forGetter(JobView::reason), ItemStack.OPTIONAL_CODEC.fieldOf("ri").forGetter(JobView::reasonItem), Codec.BOOL.optionalFieldOf("rq", false).forGetter(JobView::requester), StepView.CODEC.listOf().optionalFieldOf("st", List.of()).forGetter(JobView::steps), Codec.INT.optionalFieldOf("pr", PRIORITY_NORMAL).forGetter(JobView::priority), Codec.BOOL.optionalFieldOf("pa", false).forGetter(JobView::paused)).apply(i, JobView::new));
	}
	//endregion
}
