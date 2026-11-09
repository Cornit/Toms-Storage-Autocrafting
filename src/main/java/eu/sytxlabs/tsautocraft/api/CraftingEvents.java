package eu.sytxlabs.tsautocraft.api;

import java.util.List;


import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.CraftingJob.PlanEntry;
import eu.sytxlabs.tsautocraft.pattern.PatternData;

/**
 * Server side events of the autocrafting system (logging, achievements, integrations without mixins).
 * All of them fire on the server thread.
 */
public final class CraftingEvents {
	private CraftingEvents() {}

	/** A request was planned (preview, terminal request, Requester, Exporter). {@code ok} = nothing is missing. */
	public static final Event<PlanCreated> PLAN_CREATED = Event.createArrayBacked(PlanCreated.class, ls -> (level, target, amount, ok, materials) -> {
		for (PlanCreated l : ls) l.onPlan(level, target, amount, ok, materials);
	});

	/** A planned job was accepted by its owner and starts running. {@link CraftingJob#ownerPos()} is set. */
	public static final Event<JobStarted> JOB_STARTED = Event.createArrayBacked(JobStarted.class, ls -> (job, owner) -> {
		for (JobStarted l : ls) l.onStarted(job, owner);
	});

	/** A provider started {@code crafts} crafts of a step. */
	public static final Event<StepDispatched> STEP_DISPATCHED = Event.createArrayBacked(StepDispatched.class, ls -> (job, pattern, crafts, provider) -> {
		for (StepDispatched l : ls) l.onDispatch(job, pattern, crafts, provider);
	});

	/** A job ended: all results arrived ({@code cancelled} false) or it was cancelled. */
	public static final Event<JobFinished> JOB_FINISHED = Event.createArrayBacked(JobFinished.class, ls -> (job, cancelled) -> {
		for (JobFinished l : ls) l.onFinished(job, cancelled);
	});

	@FunctionalInterface
	public interface PlanCreated {
		void onPlan(Level level, ItemStack target, long amount, boolean ok, List<PlanEntry> materials);
	}

	@FunctionalInterface
	public interface JobStarted {
		void onStarted(CraftingJob job, JobOwner owner);
	}

	@FunctionalInterface
	public interface StepDispatched {
		void onDispatch(CraftingJob job, PatternData pattern, long crafts, CraftingProvider provider);
	}

	@FunctionalInterface
	public interface JobFinished {
		void onFinished(CraftingJob job, boolean cancelled);
	}
}
