package eu.sytxlabs.tsautocraft.util;

import net.minecraft.network.chat.Component;

/**
 * Smoothed rate of a block (crafts or items per minute) for Jade, transient. Like the job ETA: amounts are summed over
 * windows of {@value #WINDOW_TICKS} ticks and smoothed (exponential moving average); idle windows count as zero.
 */
public final class Throughput {
	private static final int WINDOW_TICKS = 100;
	private static final double SMOOTHING = 0.3;
	private long windowStart = Long.MIN_VALUE;
	private long windowAmount;
	/** Per tick. */
	private double rate;

	public void add(long now, long amount) {
		roll(now);
		if (amount > 0) windowAmount += amount;
	}

	/** Smoothed amount per minute at game time {@code now}. */
	public double perMinute(long now) {
		roll(now);
		return rate * 1200;
	}

	private void roll(long now) {
		if (windowStart == Long.MIN_VALUE || now < windowStart) {
			windowStart = now;
			windowAmount = 0;
			return;
		}
		long windows = (now - windowStart) / WINDOW_TICKS;
		if (windows <= 0) return;
		rate = SMOOTHING * (windowAmount / (double) WINDOW_TICKS) + (1 - SMOOTHING) * rate;
		// Further windows without anything moved
		for (long i = 1; i < Math.min(windows, 30); i++) rate *= 1 - SMOOTHING;
		if (windows >= 30) rate = 0;
		windowStart += windows * WINDOW_TICKS;
		windowAmount = 0;
	}

	/** Jade line ({@code key} gets the formatted rate), or null when nothing moved recently. */
	public Component line(String key, long now) {
		double v = perMinute(now);
		if (v < 0.05) return null;
		String s = v >= 10 ? Long.toString(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.1f", v);
		return Component.translatable(key, s);
	}
}
