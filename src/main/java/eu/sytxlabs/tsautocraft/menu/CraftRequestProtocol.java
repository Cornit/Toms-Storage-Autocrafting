package eu.sytxlabs.tsautocraft.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;

import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity.RequestResult;
import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.job.CraftingJob.PlanEntry;
import eu.sytxlabs.tsautocraft.job.CraftingJob.PlanOutcome;
import eu.sytxlabs.tsautocraft.menu.AutocraftingTerminalMenu.Missing;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;

/**
 * Server side of {@code CraftRequestScreen} (sent through Tom's data packet to the open menu).
 * <ul>
 *   <li>{@code tsac_plan} + {@code tsac_amt}: plan only, answer {@code tsac_preview}, {@code tsac_ok}, {@code tsac_mat} (material list), {@code tsac_tree} (recipe tree, pre-order, bounded), {@code tsac_max} (largest amount possible right now, if not ok).</li>
 *   <li>{@code tsac_req} + {@code tsac_amt}: start the job, answer {@code tsac_res} (+ {@code missing}).</li>
 *   <li>{@value #BATCH}: list of items (count = amount), one job each ("craft missing" from the recipe viewer, one rate limit token for the whole batch), answer as actionbar message.</li>
 * </ul>
 */
public final class CraftRequestProtocol {
	private CraftRequestProtocol() {}

	/** Batch request key and its maximum size (a crafting grid has 9 inputs). */
	public static final String BATCH = "tsac_reqs";
	public static final int MAX_BATCH = 9;

	/** Plans are expensive (a failed preview runs a binary search of plans): a few at once, then one every {@value #PLAN_REFILL_TICKS} ticks per player. */
	private static final int PLAN_BURST = 4, PLAN_REFILL_TICKS = 5;
	private static final java.util.Map<Player, long[]> PLAN_BUDGET = new java.util.WeakHashMap<>();

	/** Takes one plan from the player's budget; false if the player sends too many. */
	private static boolean takePlan(Player player) {
		long now = player.level().getGameTime();
		long[] b = PLAN_BUDGET.computeIfAbsent(player, p -> new long[] {PLAN_BURST, now}); // tokens, last refill
		long refill = (now - b[1]) / PLAN_REFILL_TICKS;
		if (refill > 0) {
			b[0] = Math.min(PLAN_BURST, b[0] + refill);
			b[1] += refill * PLAN_REFILL_TICKS;
		}
		if (b[0] <= 0) return false;
		b[0]--;
		return true;
	}

	public interface Target {
		/** null = no Autocrafting Terminal available. */
		PlanOutcome preview(ItemStack item, long amount);

		RequestResult request(ItemStack item, long amount);
	}

	/** Target for Tom's terminals: the Autocrafting Terminals on the terminal's cable network. */
	public static Target network(Level level, BlockPos terminal) {
		return new Target() {
			@Override
			public PlanOutcome preview(ItemStack item, long amount) {
				return TerminalCrafting.preview(level, terminal, item, amount);
			}

			@Override
			public RequestResult request(ItemStack item, long amount) {
				return TerminalCrafting.request(level, terminal, item, amount);
			}
		};
	}

	/** @return true if the message was a request message */
	public static boolean receive(ValueInput in, Player player, Target target) {
		if (player.isSpectator()) return false;
		long amount = Math.clamp(in.getLongOr("tsac_amt", 1), 1, AutocraftingTerminalBlockEntity.maxRequest());
		List<ItemStack> batch = in.listOrEmpty(BATCH, ItemStack.CODEC).stream().filter(s -> !s.isEmpty()).limit(MAX_BATCH).toList();
		if (!batch.isEmpty()) {
			receiveBatch(batch, player, target);
			return true;
		}
		var plan = in.read("tsac_plan", ItemStack.CODEC);
		if (plan.isPresent()) {
			if (!takePlan(player)) {
				MenuUtil.sendToClient(player, out -> out.putString("tsac_res", "msg.tsautocraft.request.too_fast"));
				return true;
			}
			PlanOutcome o = target.preview(plan.get(), amount);
			MenuUtil.sendToClient(player, out -> {
				if (o == null) {
					out.putString("tsac_res", "msg.tsautocraft.request.no_cpu");
					return;
				}
				out.putBoolean("tsac_preview", true);
				out.putBoolean("tsac_ok", o.ok());
				out.putLong("tsac_max", o.possible());
				var l = out.list("tsac_mat", PlanEntry.CODEC);
				o.materials().forEach(l::add);
				var t = out.list("tsac_tree", eu.sytxlabs.tsautocraft.job.CraftingJob.TreeLine.CODEC);
				o.tree().forEach(t::add);
			});
			return true;
		}
		var req = in.read("tsac_req", ItemStack.CODEC);
		if (req.isPresent()) {
			if (!takePlan(player)) {
				MenuUtil.sendToClient(player, out -> out.putString("tsac_res", "msg.tsautocraft.request.too_fast"));
				return true;
			}
			RequestResult r = target.request(req.get(), amount);
			List<Missing> miss = new ArrayList<>();
			r.missing().forEach((k, c) -> miss.add(new Missing(k.create(1), c)));
			String msg = r.ok() ? "msg.tsautocraft.request.started" : r.error() != null ? r.error() : "msg.tsautocraft.request.missing";
			MenuUtil.sendToClient(player, out -> {
				out.putString("tsac_res", msg);
				var m = out.list("missing", Missing.CODEC);
				miss.forEach(m::add);
			});
			return true;
		}
		return false;
	}

	/** One job per item; the result (started / failed items, first failure reason) goes to the actionbar, the terminal screen stays open. */
	private static void receiveBatch(List<ItemStack> batch, Player player, Target target) {
		if (!takePlan(player)) {
			player.sendOverlayMessage(Component.translatable("msg.tsautocraft.request.too_fast"));
			return;
		}
		List<Component> started = new ArrayList<>(), failed = new ArrayList<>();
		String reason = null;
		for (ItemStack s : batch) {
			long amt = Math.clamp(s.getCount(), 1, AutocraftingTerminalBlockEntity.maxRequest());
			RequestResult r = target.request(s.copyWithCount(1), amt);
			Component name = Component.translatable("msg.tsautocraft.craft_missing.entry", amt, s.getHoverName());
			if (r.ok()) started.add(name);
			else {
				failed.add(name);
				if (reason == null) reason = r.error() != null ? r.error() : "msg.tsautocraft.craft_missing.no_materials";
			}
		}
		Component sep = Component.literal(", ");
		Component msg;
		if (failed.isEmpty()) msg = Component.translatable("msg.tsautocraft.craft_missing.started", ComponentUtils.formatList(started, sep));
		else if (started.isEmpty()) msg = Component.translatable("msg.tsautocraft.craft_missing.failed", ComponentUtils.formatList(failed, sep), Component.translatable(reason));
		else msg = Component.translatable("msg.tsautocraft.craft_missing.partial", ComponentUtils.formatList(started, sep), ComponentUtils.formatList(failed, sep), Component.translatable(reason));
		player.sendOverlayMessage(msg);
	}
}
