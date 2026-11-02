package eu.sytxlabs.tsautocraft.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

	private JobScheduler() {}

	public static void init() {
		ServerTickEvents.END_LEVEL_TICK.register(JobScheduler::run);
	}

	public static void submit(JobOwner owner) {
		if (!(owner instanceof BlockEntity be) || be.getLevel() == null || be.getLevel().isClientSide()) return;
		PENDING.computeIfAbsent(be.getLevel(), l -> new LinkedHashSet<>()).add(owner);
	}

	public static void started(JobOwner owner, CraftingJob job) {
		if (owner instanceof BlockEntity be) job.bind(be.getLevel(), be.getBlockPos());
		CraftingEvents.JOB_STARTED.invoker().onStarted(job, owner);
	}

	private record Entry(JobOwner owner, CraftingJob job) {}

	private static void run(ServerLevel level) {
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
