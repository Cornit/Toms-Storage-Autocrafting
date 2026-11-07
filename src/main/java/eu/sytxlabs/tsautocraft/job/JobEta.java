package eu.sytxlabs.tsautocraft.job;

/**
 * Estimated remaining time of a running job (transient): the crafts done per tick are measured over windows of
 * {@value #WINDOW_TICKS} ticks and smoothed (exponential moving average). Paused time does not count.
 */
final class JobEta {
	static final int WINDOW_TICKS = 100;
	private static final double SMOOTHING = 0.3;
	private long windowStart = -1;
	private long windowDone;
	/** Crafts per tick, negative = no full window measured yet. */
	private double rate = -1;

	/** Called on every job tick with the current game time and {@link CraftingJob#doneCrafts()}. */
	void sample(long now, long done, boolean paused) {
		if (paused || windowStart < 0 || now < windowStart) {
			windowStart = now;
			windowDone = done;
			return;
		}
		long dt = now - windowStart;
		if (dt < WINDOW_TICKS) return;
		double r = Math.max(0, done - windowDone) / (double) dt;
		rate = rate < 0 ? r : SMOOTHING * r + (1 - SMOOTHING) * rate;
		windowStart = now;
		windowDone = done;
	}

	/** Remaining seconds, -1 = unknown (no measured progress yet). */
	long seconds(long done, long total) {
		if (total - done <= 0) return 0;
		if (rate <= 0 || done <= 0) return -1;
		return Math.min(359_999, Math.round((total - done) / rate / 20));
	}
}
