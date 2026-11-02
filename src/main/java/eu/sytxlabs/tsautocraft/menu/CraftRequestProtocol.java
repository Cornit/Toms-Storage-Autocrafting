package eu.sytxlabs.tsautocraft.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
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
 *   <li>{@code tsac_plan} + {@code tsac_amt}: plan only, answer {@code tsac_preview}, {@code tsac_ok}, {@code tsac_mat} (material list), {@code tsac_max} (largest amount possible right now, if not ok).</li>
 *   <li>{@code tsac_req} + {@code tsac_amt}: start the job, answer {@code tsac_res} (+ {@code missing}).</li>
 * </ul>
 */
public final class CraftRequestProtocol {
	private CraftRequestProtocol() {}

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
		var plan = in.read("tsac_plan", ItemStack.CODEC);
		if (plan.isPresent()) {
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
			});
			return true;
		}
		var req = in.read("tsac_req", ItemStack.CODEC);
		if (req.isPresent()) {
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
}
