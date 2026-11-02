package eu.sytxlabs.tsautocraft.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Recursively resolves a crafting request into a list of steps (pattern x count).
 * <p>
 * Intentionally free of Minecraft dependencies so it can be tested without the game.
 * {@code K} is the item key (equals/hashCode must be correct), {@code P} the pattern.
 * <ul>
 *   <li>Stock is used for intermediates and raw materials, but only once (virtual storage).</li>
 *   <li>Surplus of a pattern (e.g. 4 planks instead of 1) goes into virtual storage and is reused.</li>
 *   <li>Several patterns for the same item: the first without missing items wins, otherwise the first one.</li>
 *   <li>Cycles (A needs B needs A) and too deep trees are reported as missing instead of looping forever.</li>
 * </ul>
 */
public final class CraftingPlanner<K, P> {
	public static final int MAX_DEPTH = 48;
	/** Guard against absurd requests that would otherwise create millions of steps. */
	public static final long MAX_CRAFTS_PER_STEP = 1_000_000L;

	public record Step<P>(P pattern, long crafts) {}

	public record Result<K, P>(List<Step<P>> steps, Map<K, Long> missing, Map<K, Long> fromStock) {
		public boolean isComplete() {
			return missing.isEmpty();
		}
	}

	private final ToLongFunction<K> stock;
	private final Function<K, List<P>> patternsFor;
	private final Function<P, Map<K, Long>> inputsOf;
	private final Function<P, Map<K, Long>> outputsOf;

	// State of one planning run
	private Map<K, Long> virtual;
	private Map<K, Long> fromStock;
	private LinkedHashMap<P, Long> steps;
	private Map<K, Long> missing;
	private Set<K> inProgress;

	/**
	 * @param stock       current stock in the network
	 * @param patternsFor all patterns that have {@code K} as an output (order = priority)
	 * @param inputsOf    inputs of a pattern per single crafting operation
	 * @param outputsOf   outputs of a pattern per single crafting operation
	 */
	public CraftingPlanner(ToLongFunction<K> stock, Function<K, List<P>> patternsFor, Function<P, Map<K, Long>> inputsOf, Function<P, Map<K, Long>> outputsOf) {
		this.stock = stock;
		this.patternsFor = patternsFor;
		this.inputsOf = inputsOf;
		this.outputsOf = outputsOf;
	}

	/** @param useStockForTarget true = existing stock of the target counts (requester), false = craft exactly {@code amount} new items (terminal request) */
	public Result<K, P> plan(K target, long amount, boolean useStockForTarget) {
		virtual = new HashMap<>();
		fromStock = new HashMap<>();
		steps = new LinkedHashMap<>();
		missing = new LinkedHashMap<>();
		inProgress = new HashSet<>();

		if (amount > 0) {
			if (useStockForTarget) need(target, amount, 0);
			else craft(target, amount, 0);
		}

		List<Step<P>> list = new ArrayList<>(steps.size());
		steps.forEach((p, c) -> list.add(new Step<>(p, c)));
		return new Result<>(Collections.unmodifiableList(list), Collections.unmodifiableMap(missing), Collections.unmodifiableMap(fromStock));
	}

	private long available(K k) {
		return virtual.computeIfAbsent(k, stock::applyAsLong);
	}

	/** Cover a need: first from (virtual) storage, craft the rest. */
	private void need(K k, long amount, int depth) {
		long avail = available(k);
		long take = Math.min(avail, amount);
		if (take > 0) {
			virtual.put(k, avail - take);
			fromStock.merge(k, take, Long::sum);
			amount -= take;
		}
		if (amount > 0) craft(k, amount, depth);
	}

	/** Craft {@code amount} of {@code k} using patterns. */
	private void craft(K k, long amount, int depth) {
		List<P> candidates = patternsFor.apply(k);
		if (candidates == null || candidates.isEmpty() || depth > MAX_DEPTH || inProgress.contains(k)) {
			missing.merge(k, amount, Long::sum);
			return;
		}
		if (candidates.size() == 1) {
			applyPattern(k, amount, candidates.getFirst(), depth);
			return;
		}
		for (P p : candidates) {
			Snapshot s = snapshot();
			int missingBefore = missing.size();
			long missingSumBefore = sum(missing);
			applyPattern(k, amount, p, depth);
			if (missing.size() == missingBefore && sum(missing) == missingSumBefore) return;
			restore(s);
		}
		applyPattern(k, amount, candidates.getFirst(), depth);
	}

	private void applyPattern(K k, long amount, P p, int depth) {
		long outPer = outputsOf.apply(p).getOrDefault(k, 0L);
		if (outPer <= 0) {
			missing.merge(k, amount, Long::sum);
			return;
		}
		long crafts = ceilDiv(amount, outPer);
		if (crafts > MAX_CRAFTS_PER_STEP) {
			missing.merge(k, amount, Long::sum);
			return;
		}
		inProgress.add(k);
		for (Map.Entry<K, Long> in : inputsOf.apply(p).entrySet()) {
			need(in.getKey(), Math.multiplyExact(in.getValue(), crafts), depth + 1);
		}
		inProgress.remove(k);
		steps.merge(p, crafts, Long::sum);
		for (Map.Entry<K, Long> out : outputsOf.apply(p).entrySet()) {
			long produced = Math.multiplyExact(out.getValue(), crafts);
			if (out.getKey().equals(k)) produced -= amount;
			if (produced > 0) virtual.merge(out.getKey(), produced, Long::sum);
		}
	}

	private static long ceilDiv(long a, long b) {
		return (a + b - 1) / b;
	}

	private static <K> long sum(Map<K, Long> m) {
		long s = 0;
		for (long v : m.values()) s += v;
		return s;
	}

	private record Snapshot(Map<?, Long> virtual, Map<?, Long> fromStock, LinkedHashMap<?, Long> steps, Map<?, Long> missing) {}

	private Snapshot snapshot() {
		return new Snapshot(new HashMap<>(virtual), new HashMap<>(fromStock), new LinkedHashMap<>(steps), new LinkedHashMap<>(missing));
	}

	@SuppressWarnings("unchecked")
	private void restore(Snapshot s) {
		virtual = (Map<K, Long>) s.virtual();
		fromStock = (Map<K, Long>) s.fromStock();
		steps = (LinkedHashMap<P, Long>) s.steps();
		missing = (Map<K, Long>) s.missing();
	}
}
