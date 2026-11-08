package eu.sytxlabs.tsautocraft.job;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eu.sytxlabs.tsautocraft.util.ItemKey;

/**
 * Items the running jobs of a network have reserved: raw materials taken from storage at planning time and intermediates their own crafts brought back.
 * Other jobs, new plans and Exporters only use stock minus this. Either a snapshot ({@link #of}) or a live view over the
 * jobs ({@link #live}), which stays exact while jobs dispatch and unreserve during the tick.
 */
public final class Reservations {
	public static final Reservations NONE = new Reservations(Map.of());

	private final Map<ItemKey, Long> total;
	private final List<CraftingJob> jobs;
	private Map<ItemKey, Long> cached;
	private int cachedVersion;

	private Reservations(Map<ItemKey, Long> total) {
		this.total = total;
		this.jobs = null;
	}

	private Reservations(List<CraftingJob> jobs) {
		this.total = null;
		this.jobs = jobs;
	}

	/** Live view over {@code jobs}: totals are summed again only after any job's reservations changed. */
	public static Reservations live(List<CraftingJob> jobs) {
		return jobs.isEmpty() ? NONE : new Reservations(jobs);
	}

	public static Reservations of(Collection<CraftingJob> jobs) {
		Map<ItemKey, Long> m = new HashMap<>();
		for (CraftingJob j : jobs) {
			if (!j.isFinished()) j.reserved().forEach((k, c) -> m.merge(k, c, Long::sum));
		}
		return new Reservations(m);
	}

	/** Reserved by all jobs. */
	public long total(ItemKey k) {
		if (jobs == null) return total.getOrDefault(k, 0L);
		int v = CraftingJob.reservationVersion();
		if (cached == null || cachedVersion != v) {
			Map<ItemKey, Long> m = new HashMap<>();
			for (CraftingJob j : jobs) if (!j.isFinished()) j.reserved().forEach((key, c) -> m.merge(key, c, Long::sum));
			cached = m;
			cachedVersion = v;
		}
		return cached.getOrDefault(k, 0L);
	}

	/** Reserved by all jobs except {@code self}. */
	public long others(CraftingJob self, ItemKey k) {
		return Math.max(0, total(k) - (self != null ? self.reserved().getOrDefault(k, 0L) : 0));
	}
}
