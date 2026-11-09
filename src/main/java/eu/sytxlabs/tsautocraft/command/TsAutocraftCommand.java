package eu.sytxlabs.tsautocraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.api.JobOwner;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.JobScheduler;
import eu.sytxlabs.tsautocraft.job.JobText;

/**
 * {@code /tsautocraft reload | jobs | cancel <uid>} for server admins (permission level 2). Registered by the loader entry
 * points (Fabric {@code CommandRegistrationCallback}, NeoForge {@code RegisterCommandsEvent}).
 */
public final class TsAutocraftCommand {
	private TsAutocraftCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("tsautocraft")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("reload").executes(TsAutocraftCommand::reload))
				.then(Commands.literal("jobs").executes(TsAutocraftCommand::jobs))
				.then(Commands.literal("cancel").then(Commands.argument("uid", IntegerArgumentType.integer(1)).executes(TsAutocraftCommand::cancel))));
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		if (TsAutocraftConfig.reload()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.tsautocraft.reload.ok"), true);
			return 1;
		}
		ctx.getSource().sendFailure(Component.translatable("command.tsautocraft.reload.failed"));
		return 0;
	}

	private record Found(ServerLevel level, JobOwner owner, CraftingJob job) {}

	private static java.util.List<Found> allJobs(CommandSourceStack src) {
		java.util.List<Found> out = new java.util.ArrayList<>();
		for (ServerLevel level : src.getServer().getAllLevels()) {
			for (JobOwner o : JobScheduler.knownOwners(level)) {
				for (CraftingJob j : o.activeJobs()) if (!j.isFinished()) out.add(new Found(level, o, j));
			}
		}
		out.sort(java.util.Comparator.comparingInt(f -> f.job().uid));
		return out;
	}

	private static int jobs(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		var list = allJobs(src);
		if (list.isEmpty()) {
			src.sendSuccess(() -> Component.translatable("command.tsautocraft.jobs.none"), false);
			return 0;
		}
		src.sendSuccess(() -> Component.translatable("command.tsautocraft.jobs.header", list.size()), false);
		for (Found f : list) src.sendSuccess(() -> line(f), false);
		return list.size();
	}

	private static Component line(Found f) {
		CraftingJob j = f.job();
		CraftingJob.JobView v = j.view(false);
		BlockPos pos = f.owner() instanceof BlockEntity be ? be.getBlockPos() : j.ownerPos();
		Component ownerName = f.owner() instanceof BlockEntity be ? be.getBlockState().getBlock().getName() : Component.literal("?");
		MutableComponent where;
		if (pos != null) {
			String dim = f.level().dimension().identifier().toString();
			String tp = "/execute in " + dim + " run tp @s " + pos.getX() + " " + (pos.getY() + 1) + " " + pos.getZ();
			where = ComponentUtils.wrapInSquareBrackets(Component.translatable("command.tsautocraft.jobs.pos", pos.getX(), pos.getY(), pos.getZ(), dim))
					.withStyle(s -> s.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.SuggestCommand(tp)).withHoverEvent(new HoverEvent.ShowText(Component.translatable("command.tsautocraft.jobs.teleport"))));
		} else {
			where = Component.literal("?");
		}
		MutableComponent uid = ComponentUtils.wrapInSquareBrackets(Component.literal("#" + j.uid))
				.withStyle(s -> s.withColor(ChatFormatting.YELLOW).withClickEvent(new ClickEvent.SuggestCommand("/tsautocraft cancel " + j.uid)).withHoverEvent(new HoverEvent.ShowText(Component.translatable("command.tsautocraft.jobs.cancel_hint"))));
		return Component.translatable("command.tsautocraft.jobs.line", uid, j.amount, j.target.getHoverName(), v.done(), v.total(), JobText.status(v), ownerName, where);
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) {
		int uid = IntegerArgumentType.getInteger(ctx, "uid");
		for (Found f : allJobs(ctx.getSource())) {
			if (f.job().uid != uid) continue;
			f.owner().cancelJob(uid);
			f.owner().jobsChanged();
			ctx.getSource().sendSuccess(() -> Component.translatable("command.tsautocraft.cancel.ok", uid, f.job().target.getHoverName()), true);
			return 1;
		}
		ctx.getSource().sendFailure(Component.translatable("command.tsautocraft.cancel.unknown", uid));
		return 0;
	}
}
