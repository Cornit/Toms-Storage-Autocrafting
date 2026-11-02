package eu.sytxlabs.tsautocraft.api;

import java.util.List;

import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.JobScheduler;

/**
 * Block entity on Tom's cable network that runs crafting jobs (Autocrafting Terminal, Requester, Exporter with Crafting
 * Upgrade, or a block of another mod). Its jobs show up in the Autocrafting Terminal and receive Importer reports.
 * <p>
 * Every few ticks the owner hands itself to {@link JobScheduler#submit}; at the end of the level tick the scheduler calls {@link #tickJob} for the jobs of all owners,
 * highest {@link CraftingJob#priority()} first. New jobs are announced with {@link JobScheduler#started}.
 */
public interface JobOwner {
	/** Jobs that are not finished yet. */
	List<CraftingJob> activeJobs();

	/** Cancel by {@link CraftingJob#uid}; does nothing if the job is not one of ours. */
	void cancelJob(int uid);

	/** Runs one execution step of {@code job} (one of {@link #activeJobs()}). */
	void tickJob(CraftingJob job);

	/** Persist the owner's jobs (pause/priority changed from a terminal). */
	void jobsChanged();

	/** Jobs of this owner are listed in the Autocrafting Terminal as "automatic" (blue). */
	default boolean automatic() {
		return true;
	}

	/** Active job by {@link CraftingJob#uid}, or null. */
	default CraftingJob findJob(int uid) {
		for (CraftingJob j : activeJobs()) if (j.uid == uid) return j;
		return null;
	}
}
