package eu.sytxlabs.tsautocraft.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;

/**
 * A finished or cancelled job in the Autocrafting Terminal's history (persisted in the terminal and synced to the screen).
 * {@code ticks} = duration in game ticks (-1 unknown), {@code endMillis} = wall clock time it ended, {@code automatic} = job of a Requester/Exporter/Level Maintainer.
 */
public record JobHistoryEntry(ItemStack target, long amount, boolean cancelled, long done, long total, long ticks, long endMillis, boolean automatic) {
	public static final Codec<JobHistoryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("t").forGetter(JobHistoryEntry::target), Codec.LONG.fieldOf("a").forGetter(JobHistoryEntry::amount), Codec.BOOL.optionalFieldOf("x", false).forGetter(JobHistoryEntry::cancelled), Codec.LONG.optionalFieldOf("d", 0L).forGetter(JobHistoryEntry::done), Codec.LONG.optionalFieldOf("n", 0L).forGetter(JobHistoryEntry::total), Codec.LONG.optionalFieldOf("dt", -1L).forGetter(JobHistoryEntry::ticks), Codec.LONG.optionalFieldOf("e", 0L).forGetter(JobHistoryEntry::endMillis), Codec.BOOL.optionalFieldOf("au", false).forGetter(JobHistoryEntry::automatic)).apply(i, JobHistoryEntry::new));

	/** Entry for {@code job} (finished) at game time {@code now}. */
	public static JobHistoryEntry of(CraftingJob job, long now, boolean automatic) {
		long start = job.startTime();
		return new JobHistoryEntry(job.target.copyWithCount(1), job.amount, job.state() == CraftingJob.State.CANCELLED, job.doneCrafts(), job.totalCrafts(), start >= 0 && now >= start ? now - start : -1, System.currentTimeMillis(), automatic);
	}
}
