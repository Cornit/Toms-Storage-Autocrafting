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
		long flightSince = Long.MAX_VALUE;
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

		/** Enough usable stock for one craft? Patterns with substitutes may mix options per position: left to the provider. */
		boolean canCraftOnce(ToLongFunction<ItemKey> available) {
			if (pattern.hasSubstitutes()) return true;
			for (var e : pattern.inputTotals().entrySet()) {
				if (available.applyAsLong(e.getKey()) < e.getValue()) return false;
			}
			return true;
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

	public static final Codec<CraftingJob> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("id").forGetter(j -> j.id), ItemStack.OPTIONAL_CODEC.fieldOf("target").forGetter(j -> j.target), Codec.LONG.fieldOf("amount").forGetter(j -> j.amount), Step.CODEC.listOf().fieldOf("steps").forGetter(j -> j.steps), Codec.INT.fieldOf("state").forGetter(j -> j.state.ordinal()), Codec.LONG.fieldOf("baseline").forGetter(j -> j.baseline), Codec.INT.optionalFieldOf("wait", 0).forGetter(j -> j.waitTicks), Amount.CODEC.listOf().optionalFieldOf("reserved", List.of()).forGetter(CraftingJob::reservedList), Codec.INT.optionalFieldOf("prio", PRIORITY_NORMAL).forGetter(j -> j.priority), Codec.BOOL.optionalFieldOf("paused", false).forGetter(j -> j.paused), Codec.BOOL.optionalFieldOf("tracked", false).forGetter(j -> j.tracked), Codec.LONG.optionalFieldOf("start", -1L).forGetter(j -> j.startTime)).apply(i, CraftingJob::new));
	private static final AtomicInteger NEXT_UID = new AtomicInteger();
	public final int id;
	public final int uid = NEXT_UID.incrementAndGet();
	public final ItemStack target;
	private ItemKey targetKey;
	public final long amount;
	private final List<Step> steps;
	private State state;
	private final long baseline;
	private int waitTicks;
	private final Map<ItemKey, Long> reserved = new LinkedHashMap<>();
	/** Bumped on every change of any job's reservations, so {@link Reservations} can cache its totals. */
	private static int reservationVersion;

	public static int reservationVersion() {
		return reservationVersion;
	}
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
	/** Game time the job was started (first bound to its owner), -1 = unknown. */
	private long startTime = -1;
	private final JobEta eta = new JobEta();

	private CraftingJob(int id, ItemStack target, long amount, List<Step> steps, int state, long baseline, int waitTicks, List<Amount> reserved, int priority, boolean paused, boolean tracked, long startTime) {
		this(id, target, amount, steps, state, baseline, waitTicks, reserved, priority, paused, tracked);
		this.startTime = startTime;
	}

	private CraftingJob(int id, ItemStack target, long amount, List<Step> steps, int state, long baseline, int waitTicks, List<Amount> reserved, int priority, boolean paused, boolean tracked) {
		this.id = id;
		this.priority = Math.clamp(priority, PRIORITY_LOW, PRIORITY_HIGH);
		this.paused = paused;
		this.tracked = tracked;
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
		reservationVersion++;
		setReason(paused ? Reason.PAUSED : Reason.NONE, ItemStack.EMPTY);
	}

	/** Called by the {@link JobScheduler} before every tick. */
	void bind(Level level, BlockPos ownerPos) {
		this.level = level;
		this.ownerPos = ownerPos;
		if (startTime < 0 && level != null) startTime = level.getGameTime();
	}

	/** Game time the job started, -1 if unknown. */
	public long startTime() {
		return startTime;
	}

	/** Estimated remaining seconds (smoothed rate of finished crafts), -1 = unknown. */
	public long etaSeconds() {
		return isFinished() ? 0 : eta.seconds(doneCrafts(), totalCrafts());
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
	public record PlanOutcome(CraftingJob job, Map<ItemKey, Long> missing, List<PlanEntry> materials, long possible, List<TreeLine> tree) {
		public PlanOutcome(CraftingJob job, Map<ItemKey, Long> missing, List<PlanEntry> materials, long possible) {
			this(job, missing, materials, possible, List.of());
		}

		public boolean ok() {
			return job != null;
		}

		public PlanOutcome withPossible(long possible) {
			return new PlanOutcome(job, missing, materials, possible, tree);
		}
	}

	/**
	 * One node of the recipe tree in pre-order ({@code depth} 0 = target): {@code amount} needed, covered by {@code stored}, {@code crafts} of a pattern making {@code made}, and {@code missing}.
	 * An empty {@code item} marks the end of a truncated tree ({@code amount} = nodes left out).
	 */
	public record TreeLine(ItemStack item, long amount, int depth, long stored, long crafts, long made, long missing) {
		public static final int MAX_LINES = 200, MAX_DEPTH = 16;
		public static final Codec<TreeLine> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(TreeLine::item), Codec.LONG.fieldOf("a").forGetter(TreeLine::amount), Codec.INT.fieldOf("d").forGetter(TreeLine::depth), Codec.LONG.optionalFieldOf("s", 0L).forGetter(TreeLine::stored), Codec.LONG.optionalFieldOf("c", 0L).forGetter(TreeLine::crafts), Codec.LONG.optionalFieldOf("o", 0L).forGetter(TreeLine::made), Codec.LONG.optionalFieldOf("m", 0L).forGetter(TreeLine::missing)).apply(i, TreeLine::new));
	}

	/** Flattens the planner's tree (bounded by {@link TreeLine#MAX_LINES} and {@link TreeLine#MAX_DEPTH}). */
	private static List<TreeLine> treeLines(CraftingPlanner.Node<ItemKey, PatternData> root) {
		List<TreeLine> out = new ArrayList<>();
		if (root == null) return out;
		long[] skipped = {0};
		flatten(root, 0, out, skipped);
		if (skipped[0] > 0) out.add(new TreeLine(ItemStack.EMPTY, skipped[0], 0, 0, 0, 0, 0));
		return out;
	}

	private static void flatten(CraftingPlanner.Node<ItemKey, PatternData> n, int depth, List<TreeLine> out, long[] skipped) {
		if (out.size() >= TreeLine.MAX_LINES || depth > TreeLine.MAX_DEPTH) {
			skipped[0]++;
			for (var c : n.children) flatten(c, depth + 1, out, skipped);
			return;
		}
		long made = n.pattern != null ? n.pattern.outputTotals().getOrDefault(n.item, 0L) * n.crafts : 0;
		out.add(new TreeLine(n.item.create(1), n.amount, depth, n.fromStock, n.crafts, made, n.missing));
		for (var c : n.children) flatten(c, depth + 1, out, skipped);
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

	/**
	 * Patterns by output plus stock and substitute choices, built once per planning (and shared by the binary search of
	 * {@link #maxPossible}). Substitutes are resolved only for patterns the planner actually visits.
	 */
	private static final class PlanIndex {
		final Map<ItemKey, List<PatternData>> byOutput = new HashMap<>();
		final Map<ItemKey, Long> stockMemo = new HashMap<>();
		final Map<PatternData, Map<ItemKey, Long>> resolved = new HashMap<>();
		final ToLongFunction<ItemKey> stock;
		/** Second try: substitutes prefer an option that can be crafted over one that is only partly in stock. */
		final boolean preferCraftable;

		PlanIndex(Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, Reservations res) {
			this(level, net, providers, res, false);
		}

		PlanIndex(Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, Reservations res, boolean preferCraftable) {
			this.preferCraftable = preferCraftable;
			LinkedHashSet<PatternData> all = new LinkedHashSet<>();
			for (CraftingProvider p : providers) {
				for (PatternData d : p.getPatterns()) if (PlannerHooks.isCraftable(d.primaryOutput())) all.add(d);
			}
			for (PatternData d : all) {
				for (ItemKey out : d.outputTotals().keySet()) byOutput.computeIfAbsent(out, _ -> new ArrayList<>()).add(d);
			}
			var order = PlannerHooks.patternOrder();
			if (order != null) byOutput.values().forEach(l -> l.sort(order));
			stock = k -> stockMemo.computeIfAbsent(k, x -> Math.max(0, StorageUtil.count(net, level, x) - res.total(x)));
		}

		Map<ItemKey, Long> inputsOf(PatternData d) {
			if (!d.hasSubstitutes()) return d.inputTotals();
			Map<ItemKey, Long> m = resolved.get(d);
			if (m == null) resolved.put(d, m = resolvedInputs(d, stock, byOutput::containsKey, preferCraftable));
			return m;
		}

		/** Did the plan pick substitutes anywhere (then a second try with {@link #preferCraftable} may succeed)? */
		boolean usedSubstitutes() {
			return !resolved.isEmpty();
		}
	}

	private static PlanOutcome plan(int id, Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, ItemStack target, long amount, Reservations res, boolean notify) {
		PlanIndex first = new PlanIndex(level, net, providers, res);
		PlanOutcome r = plan(id, level, net, first, target, amount, false);
		if (!r.ok() && first.usedSubstitutes()) {
			// e.g. a chest pattern with "any planks": no planks in stock, but oak planks can be crafted from logs
			PlanIndex craftable = new PlanIndex(level, net, providers, res, true);
			PlanOutcome second = plan(id, level, net, craftable, target, amount, false);
			if (second.ok()) return notify ? plan(id, level, net, craftable, target, amount, true) : second; // again for event + tree
		}
		return notify ? plan(id, level, net, first, target, amount, true) : r;
	}

	private static PlanOutcome plan(int id, Level level, IInventoryAccess net, PlanIndex index, ItemStack target, long amount, boolean notify) {
		CraftingPlanner<ItemKey, PatternData> planner = new CraftingPlanner<>(index.stock, index.byOutput::get, index::inputsOf, PatternData::outputTotals);

		ItemKey key = new ItemKey(target);
		var result = planner.plan(key, amount, false, notify);
		List<PlanEntry> materials = materials(result);
		if (notify) CraftingEvents.PLAN_CREATED.invoker().onPlan(level, target, amount, result.isComplete(), materials);
		if (!result.isComplete()) return new PlanOutcome(null, new LinkedHashMap<>(result.missing()), materials, -1, treeLines(result.tree()));

		List<Step> steps = new ArrayList<>();
		result.steps().forEach(s -> steps.add(new Step(s.pattern(), s.crafts(), s.crafts())));
		long baseline = StorageUtil.count(net, level, key);
		List<Amount> reserve = new ArrayList<>();
		result.fromStock().forEach((k, c) -> reserve.add(new Amount(k.create(1), c)));
		return new PlanOutcome(new CraftingJob(id, target, amount, steps, State.RUNNING.ordinal(), baseline, 0, reserve, PRIORITY_NORMAL, false, false), Map.of(), materials, -1, treeLines(result.tree()));
	}

	/** Largest amount below {@code amount} that can be crafted right now (binary search over complete plans), 0 if none. */
	public static long maxPossible(Level level, IInventoryAccess net, List<? extends CraftingProvider> providers, ItemStack target, long amount, Reservations res) {
		PlanIndex index = new PlanIndex(level, net, providers, res);
		PlanIndex craftable = null;
		long lo = 0, hi = amount - 1;
		while (lo < hi) {
			long mid = (lo + hi + 1) >>> 1;
			boolean ok = plan(0, level, net, index, target, mid, false).ok();
			if (!ok && index.usedSubstitutes()) {
				if (craftable == null) craftable = new PlanIndex(level, net, providers, res, true);
				ok = plan(0, level, net, craftable, target, mid, false).ok();
			}
			if (ok) lo = mid;
			else hi = mid - 1;
		}
		return lo;
	}

	/**
	 * Input totals with every position replaced by one option: the best-stocked one (the item itself on a tie); with none in
	 * stock, the first one that can be crafted. With {@code preferCraftable}, a craftable option wins over stock of a
	 * non-craftable one (the stocked amount may not be enough for the whole job).
	 */
	private static Map<ItemKey, Long> resolvedInputs(PatternData d, ToLongFunction<ItemKey> stock, java.util.function.Predicate<ItemKey> craftable, boolean preferCraftable) {
		Map<ItemKey, Long> m = new LinkedHashMap<>();
		var mode = d.modeType();
		for (int i = 0; i < d.inputs().size(); i++) {
			PatternData.Entry e = d.inputs().get(i);
			if (e.isEmpty() || mode.optionalInput(d, i)) continue; // e.g. fuel: delivered when needed, not planned
			List<ItemKey> options = new ArrayList<>(1 + e.alternatives().size());
			options.add(new ItemKey(e.item()));
			for (ItemStack alt : e.alternatives()) options.add(new ItemKey(alt));
			ItemKey best = null;
			long bestScore = -1;
			for (ItemKey k : options) {
				// Score: stock, plus a bonus above any stock for craftable options in the second try
				long score = stock.applyAsLong(k);
				if (preferCraftable && craftable.test(k)) score = score + Long.MAX_VALUE / 2;
				if (score > bestScore) {
					best = k;
					bestScore = score;
				}
			}
			if (bestScore == 0) {
				for (ItemKey k : options) {
					if (craftable.test(k)) {
						best = k;
						break;
					}
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
		if (level != null) eta.sample(level.getGameTime(), doneCrafts(), paused);
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
			long count = StorageUtil.count(net, level, targetKey());
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
			// Not even one craft possible: every provider would only count the inputs again and say MISSING
			if (!step.canCraftOnce(available)) {
				for (CraftingProvider prov : providers) {
					if (!prov.hasPattern(step.pattern)) continue;
					anyProvider = true;
					stepWhy = Reason.WAITING_ITEMS;
					step.provider = prov.providerPos();
					step.providerName = prov.providerName();
					step.missingInput(level, net, available);
					break;
				}
			} else for (CraftingProvider prov : providers) {
				if (step.remaining <= 0) break;
				if (!prov.hasPattern(step.pattern)) continue;
				anyProvider = true;
				Dispatch d = prov.dispatch(step.pattern, net, available, step.remaining, uid);
				DispatchResult r = d.result();
				if (d.ok()) {
					long n = Math.min(d.crafts(), step.remaining);
					step.remaining -= n;
					if (step.inFlight <= 0) step.flightSince = level != null ? level.getGameTime() : 0;
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
			reservationVersion++;
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

	private ItemKey targetKey() {
		if (targetKey == null) targetKey = new ItemKey(target);
		return targetKey;
	}

	private void finish(State end) {
		if (isFinished()) return;
		state = end;
		reserved.clear();
		reservationVersion++;
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
			if (!key.equals(s.pattern.primaryKey())) continue;
			long per = Math.max(1, s.pattern.outputTotals().getOrDefault(key, 1L));
			long take = Math.min(s.inFlight * per - s.partial, count - used);
			if (take <= 0) continue;
			s.partial += take;
			used += take;
			long crafts = s.partial / per;
			s.partial -= crafts * per;
			s.inFlight -= crafts;
			if (s.inFlight <= 0) s.flightSince = Long.MAX_VALUE;
		}
		if (used > 0) {
			tracked = true;
			waitTicks = 0;
			if (!key.equals(targetKey()) && needsLater(key)) {
				reserved.merge(key, used, Long::sum);
				reservationVersion++;
			}
		}
		return used;
	}

	/** Since when this job waits for results of {@code key} (oldest craft in flight), {@link Long#MAX_VALUE} if it does not. */
	public long waitingSince(ItemKey key) {
		long since = Long.MAX_VALUE;
		for (Step s : steps) {
			if (s.inFlight <= 0) continue;
			if (key.equals(s.pattern.primaryKey())) since = Math.min(since, s.flightSince == Long.MAX_VALUE ? 0 : s.flightSince);
		}
		return since;
	}

	/** Are crafts of this job still inside machines? */
	public boolean hasInFlight() {
		return steps.stream().anyMatch(s -> s.inFlight > 0);
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
		reservationVersion++;
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
			reservationVersion++;
			return;
		}
		boolean inMachines = hasInFlight();
		finish(State.CANCELLED);
		// Inputs still waiting in crafters/machines go back to the network (results of crafts already running are not credited any more)
		if (inMachines && TsAutocraftConfig.get().returnInputsOnCancel && level != null && !level.isClientSide() && ownerPos != null) {
			eu.sytxlabs.tsautocraft.network.TerminalCrafting.returnInputs(level, ownerPos, uid);
		}
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
		return new JobView(uid, target, amount, doneCrafts(), totalCrafts(), state.ordinal(), reason.ordinal(), reasonItem, requester, sv, priority, paused, etaSeconds());
	}

	/** Progress of one step: crafts done of total, plus why it is stuck (missing input: need per craft / usable). */
	public record StepView(ItemStack output, long done, long total, int reason, ItemStack reasonItem, long need, long have, java.util.Optional<BlockPos> provider, java.util.Optional<net.minecraft.network.chat.Component> providerName) {
		public static final Codec<StepView> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("o").forGetter(StepView::output), Codec.LONG.fieldOf("d").forGetter(StepView::done), Codec.LONG.fieldOf("n").forGetter(StepView::total), Codec.INT.optionalFieldOf("r", 0).forGetter(StepView::reason), ItemStack.OPTIONAL_CODEC.optionalFieldOf("ri", ItemStack.EMPTY).forGetter(StepView::reasonItem), Codec.LONG.optionalFieldOf("nd", 0L).forGetter(StepView::need), Codec.LONG.optionalFieldOf("hv", 0L).forGetter(StepView::have), BlockPos.CODEC.optionalFieldOf("p").forGetter(StepView::provider), net.minecraft.network.chat.ComponentSerialization.CODEC.optionalFieldOf("pn").forGetter(StepView::providerName)).apply(i, StepView::new));
	}

	public record JobView(int id, ItemStack target, long amount, long done, long total, int state, int reason, ItemStack reasonItem, boolean requester, List<StepView> steps, int priority, boolean paused, long eta) {
		public static final Codec<JobView> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("id").forGetter(JobView::id), ItemStack.OPTIONAL_CODEC.fieldOf("t").forGetter(JobView::target), Codec.LONG.fieldOf("a").forGetter(JobView::amount), Codec.LONG.fieldOf("d").forGetter(JobView::done), Codec.LONG.fieldOf("n").forGetter(JobView::total), Codec.INT.fieldOf("s").forGetter(JobView::state), Codec.INT.fieldOf("r").forGetter(JobView::reason), ItemStack.OPTIONAL_CODEC.fieldOf("ri").forGetter(JobView::reasonItem), Codec.BOOL.optionalFieldOf("rq", false).forGetter(JobView::requester), StepView.CODEC.listOf().optionalFieldOf("st", List.of()).forGetter(JobView::steps), Codec.INT.optionalFieldOf("pr", PRIORITY_NORMAL).forGetter(JobView::priority), Codec.BOOL.optionalFieldOf("pa", false).forGetter(JobView::paused), Codec.LONG.optionalFieldOf("eta", -1L).forGetter(JobView::eta)).apply(i, JobView::new));
	}
	//endregion
}
