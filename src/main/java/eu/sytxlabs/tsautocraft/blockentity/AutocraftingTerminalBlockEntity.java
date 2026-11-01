package eu.sytxlabs.tsautocraft.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.platform.PlatformBlockEntity;
import com.tom.storagemod.util.TickerUtil.TickableServer;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.menu.AutocraftingTerminalMenu;
import eu.sytxlabs.tsautocraft.network.NetworkLink;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/** Autocrafting Terminal: lists craftable items, accepts requests and runs the jobs. */
public class AutocraftingTerminalBlockEntity extends PlatformBlockEntity implements TickableServer, MenuProvider {
	/** Largest amount per request (config {@code maxRequestAmount}). */
	public static long maxRequest() {
		return TsAutocraftConfig.get().maxRequestAmount;
	}
	private static final int JOB_INTERVAL = 2;

	private final NetworkLink network = new NetworkLink();
	private final List<CraftingJob> jobs = new ArrayList<>();
	private int nextId = 1;

	public AutocraftingTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.AUTOCRAFTING_TERMINAL_BE, pos, state);
	}

	@Override
	public void updateServer() {
		if (level.getGameTime() % JOB_INTERVAL != 0 || jobs.isEmpty()) return;
		IInventoryAccess net = network.access(level, worldPosition);
		var providers = network.providers(level, worldPosition);
		boolean changed = false;
		for (int i = jobs.size() - 1; i >= 0; i--) {
			CraftingJob j = jobs.get(i);
			if (!j.isFinished()) {
				long before = j.doneCrafts();
				CraftingJob.State st = j.state();
				j.tick(level, net, providers, JOB_INTERVAL);
				if (before != j.doneCrafts() || st != j.state()) changed = true;
			}
			// Done or cancelled: off the list right away (the player got the result in storage)
			if (j.isFinished()) {
				jobs.remove(i);
				changed = true;
			}
		}
		if (changed) setChanged();
	}

	/** Plans and starts a job. Returns missing amounts (empty = started). */
	public RequestResult request(ItemStack item, long amount) {
		if (item.isEmpty() || amount <= 0) return RequestResult.error("msg.tsautocraft.request.invalid");
		if (jobs.stream().filter(j -> !j.isFinished()).count() >= TsAutocraftConfig.get().maxJobsPerTerminal) return RequestResult.error("msg.tsautocraft.request.too_many");
		IInventoryAccess net = network.access(level, worldPosition);
		if (!network.isConnected(level, worldPosition)) return RequestResult.error("msg.tsautocraft.no_network");

		var outcome = CraftingJob.plan(nextId, level, net, network.providers(level, worldPosition), item, Math.min(amount, maxRequest()));
		if (!outcome.ok()) return new RequestResult(null, outcome.missing());
		nextId++;
		jobs.add(outcome.job());
		setChanged();
		return new RequestResult(null, Map.of());
	}

	/** Plans without starting: material list for the request preview. */
	public CraftingJob.PlanOutcome preview(ItemStack item, long amount) {
		IInventoryAccess net = network.access(level, worldPosition);
		return CraftingJob.plan(0, level, net, network.providers(level, worldPosition), item, Math.min(amount, maxRequest()));
	}

	/** Cancels a job of this terminal or of a Requester on the network (by {@link CraftingJob#uid}). */
	public void cancel(int uid) {
		for (CraftingJob j : jobs) if (j.uid == uid) j.cancel();
		for (RequesterBlockEntity r : requesters()) r.cancelJob(uid);
		setChanged();
	}

	public List<CraftingJob> getJobs() {
		return jobs;
	}

	/** Own jobs followed by the running jobs of all Requesters on the same network. */
	public List<CraftingJob.JobView> jobViews() {
		List<CraftingJob.JobView> views = new ArrayList<>();
		for (CraftingJob j : jobs) views.add(j.view(false));
		for (RequesterBlockEntity r : requesters()) {
			for (CraftingJob j : r.activeJobs()) views.add(j.view(true));
		}
		return views;
	}

	private List<RequesterBlockEntity> requesters() {
		List<RequesterBlockEntity> out = new ArrayList<>();
		for (var be : TerminalCrafting.networkBlockEntities(level, worldPosition)) {
			if (be instanceof RequesterBlockEntity r) out.add(r);
		}
		return out;
	}

	/** Craftable items. */
	public List<ItemStack> craftables() {
		return CraftingJob.craftables(network.providers(level, worldPosition));
	}

	public long stockOf(ItemStack st) {
		IInventoryAccess net = network.access(level, worldPosition);
		return StorageUtil.count(net, level, new ItemKey(st));
	}

	public record RequestResult(String error, Map<ItemKey, Long> missing) {
		public static RequestResult error(String key) {
			return new RequestResult(key, Map.of());
		}

		public boolean ok() {
			return error == null && missing.isEmpty();
		}
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		var list = out.list("Jobs", CraftingJob.CODEC);
		for (CraftingJob j : jobs) if (!j.isFinished()) list.add(j);
		out.putInt("NextId", nextId);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		jobs.clear();
		in.listOrEmpty("Jobs", CraftingJob.CODEC).stream().forEach(jobs::add);
		nextId = in.getIntOr("NextId", 1);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new AutocraftingTerminalMenu(id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.tsautocraft.autocrafting_terminal");
	}

	public boolean stillValid(Player player) {
		if (level.getBlockEntity(worldPosition) != this) return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
	}
}
