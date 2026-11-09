package eu.sytxlabs.tsautocraft.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import eu.sytxlabs.tsautocraft.api.CraftingEvents;
import eu.sytxlabs.tsautocraft.api.JobOwner;

/**
 * Runs the jobs of all {@link JobOwner}s of a level in priority order at the end of the level tick,
 * so a high priority job gets a free Pattern Provider before a low priority one, no matter which block entity ticked first.
 */
public final class JobScheduler {
	private static final Map<Level, Set<JobOwner>> PENDING = new HashMap<>();
	/** Every owner that submitted itself (admin command); weak, so unloaded levels and block entities go away. */
	private static final Map<Level, Set<JobOwner>> KNOWN = new java.util.WeakHashMap<>();

	private JobScheduler() {}

	public static void submit(JobOwner owner) {
		if (!(owner instanceof BlockEntity be) || be.getLevel() == null || be.getLevel().isClientSide()) return;
		PENDING.computeIfAbsent(be.getLevel(), l -> new LinkedHashSet<>()).add(owner);
		KNOWN.computeIfAbsent(be.getLevel(), l -> java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>())).add(owner);
	}

	/** Owners of {@code level} that ran jobs since it was loaded and still exist. */
	public static List<JobOwner> knownOwners(Level level) {
		Set<JobOwner> set = KNOWN.get(level);
		List<JobOwner> out = new ArrayList<>();
		if (set == null) return out;
		for (JobOwner o : set) if (!(o instanceof BlockEntity be) || !be.isRemoved()) out.add(o);
		return out;
	}

	public static void started(JobOwner owner, CraftingJob job) {
		if (owner instanceof BlockEntity be) job.bind(be.getLevel(), be.getBlockPos());
		eu.sytxlabs.tsautocraft.network.NetworkCache.jobsChanged();
		CraftingEvents.JOB_STARTED.invoker().onStarted(job, owner);
	}

	private record Entry(JobOwner owner, CraftingJob job) {}

	/** End of a server level tick (hooked up by the loader entry point). */
	public static void tick(ServerLevel level) {
		Set<JobOwner> owners = PENDING.remove(level);
		if (owners == null) return;
		List<Entry> list = new ArrayList<>();
		for (JobOwner o : owners) {
			if (o instanceof BlockEntity be && be.isRemoved()) continue;
			for (CraftingJob j : o.activeJobs()) list.add(new Entry(o, j));
		}
		list.sort(Comparator.comparingInt((Entry e) -> -e.job().priority()).thenComparingInt(e -> e.job().uid));
		for (Entry e : list) {
			if (e.job().isFinished()) continue;
			if (e.owner() instanceof BlockEntity be) e.job().bind(level, be.getBlockPos());
			e.owner().tickJob(e.job());
		}
	}
}
