package eu.sytxlabs.tsautocraft.job;

import java.util.Locale;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import eu.sytxlabs.tsautocraft.job.CraftingJob.JobView;

/** Texts for jobs, shared by the Autocrafting Terminal screen and the Jade tooltips (both sides). */
public final class JobText {
	private JobText() {}

	public static Component status(JobView j) {
		CraftingJob.State st = CraftingJob.State.values()[Mth.clamp(j.state(), 0, CraftingJob.State.values().length - 1)];
		if (st == CraftingJob.State.DONE) return Component.translatable("status.tsautocraft.done");
		if (st == CraftingJob.State.CANCELLED) return Component.translatable("status.tsautocraft.cancelled");
		String key = "reason.tsautocraft." + CraftingJob.Reason.values()[Mth.clamp(j.reason(), 0, CraftingJob.Reason.values().length - 1)].name().toLowerCase(Locale.ROOT);
		return j.reasonItem().isEmpty() ? Component.translatable(key) : Component.translatable(key, j.reasonItem().getHoverName());
	}

	public static Component line(JobView j) {
		return Component.translatable("label.tsautocraft.job_line", j.amount(), j.target().getHoverName(), j.done(), j.total(), status(j));
	}
}
