package eu.sytxlabs.tsautocraft.job;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import eu.sytxlabs.tsautocraft.util.ItemKey;

/**
 * Items the running jobs of a network have reserved: raw materials taken from storage at planning time and intermediates their own crafts brought back.
 * Other jobs, new plans and Exporters only use stock minus this. Snapshot, taken once per owner tick.
 */
public final class Reservations {
	public static final Reservations NONE = new Reservations(Map.of());

	private final Map<ItemKey, Long> total;

	private Reservations(Map<ItemKey, Long> total) {
		this.total = total;
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
		return total.getOrDefault(k, 0L);
	}

	/** Reserved by all jobs except {@code self}. */
	public long others(CraftingJob self, ItemKey k) {
		return Math.max(0, total(k) - (self != null ? self.reserved().getOrDefault(k, 0L) : 0));
	}
}
