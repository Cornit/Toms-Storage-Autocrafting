package eu.sytxlabs.tsautocraft.blockentity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.InventorySlot;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.inventory.filter.ItemPredicate;
import com.tom.storagemod.item.IItemFilter;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.api.IOMode;
import eu.sytxlabs.tsautocraft.api.IOModes;
import eu.sytxlabs.tsautocraft.api.JobOwner;
import eu.sytxlabs.tsautocraft.api.Upgrades;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.JobRunner;
import eu.sytxlabs.tsautocraft.job.JobScheduler;
import eu.sytxlabs.tsautocraft.job.JobText;
import eu.sytxlabs.tsautocraft.job.Reservations;
import eu.sytxlabs.tsautocraft.menu.IOMenu;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/**
 * Exporter: takes filtered items from the network and pushes them into the inventory in front of it.
 * Without filters nothing happens (like AE2). Items reserved by crafting jobs are not exported.
 * <p>
 * With a Crafting Upgrade, a plain item filter whose item is not in storage (and that fits into the target) starts a crafting job for up to one operation's worth; one job per filter slot at a time.
 */
public class ExporterBlockEntity extends AbstractIOBlockEntity implements JobOwner, StatusInfo {
	/** Rounds (operations) to wait after a failed plan before trying again. */
	private static final int PLAN_COOLDOWN = 10;

	private final CraftingJob[] jobs = new CraftingJob[SLOTS];
	private final int[] cooldown = new int[SLOTS];
	private final int[] planFails = new int[SLOTS];
	private int nextId = 1;
	private final JobRunner runner = new JobRunner();

	private record SlotJob(int slot, CraftingJob job) {
		static final Codec<SlotJob> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("slot").forGetter(SlotJob::slot), CraftingJob.CODEC.fieldOf("job").forGetter(SlotJob::job)).apply(i, SlotJob::new));
	}

	public ExporterBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.EXPORTER_BE, pos, state);
	}

	@Override
	public Identifier upgradeHost() {
		return Upgrades.EXPORTER;
	}

	@Override
	protected IOMode defaultMode() {
		return IOModes.ROUND_ROBIN;
	}

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean canCraft() {
		return upgrades.count(ModContent.CRAFTING_UPGRADE) > 0;
	}

	@Override
	public void updateServer() {
		if (level == null) return;
		if (JobRunner.due(level)) tickJobs();
		super.updateServer();
	}

	private void tickJobs() {
		boolean any = false;
		for (int i = 0; i < SLOTS; i++) {
			CraftingJob j = jobs[i];
			if (j == null) continue;
			ItemStack f = filters.getItem(i);
			if (!canCraft() || f.isEmpty() || !ItemStack.isSameItemSameComponents(f, j.target)) j.cancel();
			if (j.isFinished()) {
				jobs[i] = null;
				setChanged();
			} else {
				any = true;
			}
		}
		if (any) JobScheduler.submit(this);
	}

	@Override
	public void tickJob(CraftingJob job) {
		runner.tick(this, network, job);
	}

	@Override
	public void jobsChanged() {
		setChanged();
	}

	@Override
	protected boolean work() {
		Storage<ItemVariant> target = StorageUtil.facingStorage(level, worldPosition, facing());
		if (target == null) return false;

		if (!buffer.isEmpty()) {
			int was = buffer.getCount();
			buffer = StorageUtil.insert(target, buffer);
			if (buffer.getCount() != was) setChanged();
			if (!buffer.isEmpty()) {
				backoff = 4;
				return true;
			}
		}

		IInventoryAccess net = network.access(level, worldPosition);
		if (net == PlatformInventoryAccess.EMPTY) return false;
		var tracker = net.tracker();
		tracker.getChangeTracker(level);
		Reservations res = TerminalCrafting.reservations(level, worldPosition);

		IOMode mode = getMode();
		for (int i : mode.order(modeContext, SLOTS)) {
			ItemPredicate pred = predicate(i);
			if (pred == null) continue;
			pred.updateState();

			InventorySlot slot = tracker.findSlot(pred, false);
			if (slot == null) {
				requestCraft(i, net, target);
				continue;
			}
			ItemStack proto = slot.getStack();
			if (proto.isEmpty()) continue;

			ItemKey key = new ItemKey(proto);
			long free = StorageUtil.count(net, level, key) - res.total(key);
			if (free <= 0) continue; // everything reserved by crafting jobs
			long room = StorageUtil.simulateInsert(target, proto, Math.min(free, Math.min(maxPerOp(), proto.getMaxStackSize())));
			if (room <= 0) continue;

			ItemStack pulled = StorageUtil.pull(net, proto, room);
			if (pulled.isEmpty()) continue;

			ItemStack rest = StorageUtil.insert(target, pulled);
			if (!rest.isEmpty()) {
				rest = StorageUtil.push(net, rest);
				buffer = rest;
			}
			mode.moved(modeContext, i);
			setChanged();
			return true;
		}
		return false;
	}

	/** Crafting Upgrade: nothing of the filter item {@code i} in storage -> craft what fits into the target. */
	private void requestCraft(int i, IInventoryAccess net, Storage<ItemVariant> target) {
		if (!canCraft() || jobs[i] != null) return;
		if (cooldown[i] > 0) {
			cooldown[i]--;
			return;
		}
		ItemStack f = filters.getItem(i);
		if (f.isEmpty() || f.getItem() instanceof IItemFilter) return; // only plain items can be crafted
		long room = StorageUtil.simulateInsert(target, f, Math.min(maxPerOp(), f.getMaxStackSize()));
		if (room <= 0) return;
		var providers = network.providers(level, worldPosition);
		if (!hasPatternFor(providers, new ItemKey(f))) {
			failPlan(i); // no pattern at all: no need to plan
			return;
		}
		var outcome = CraftingJob.plan(nextId, level, net, providers, f, room, TerminalCrafting.reservations(level, worldPosition));
		if (outcome.ok()) {
			nextId++;
			jobs[i] = outcome.job();
			planFails[i] = 0;
			JobScheduler.started(this, jobs[i]);
			setChanged();
		} else {
			failPlan(i);
		}
	}

	/** Failed plans wait longer each time (up to 8x), so a missing ingredient does not replan every few ticks. */
	private void failPlan(int i) {
		cooldown[i] = PLAN_COOLDOWN << Math.min(planFails[i], 3);
		if (planFails[i] < 3) planFails[i]++;
	}

	private static boolean hasPatternFor(List<eu.sytxlabs.tsautocraft.api.CraftingProvider> providers, ItemKey key) {
		for (var p : providers) {
			for (var d : p.getPatterns()) if (d.outputTotals().containsKey(key)) return true;
		}
		return false;
	}

	@Override
	public List<CraftingJob> activeJobs() {
		List<CraftingJob> out = new ArrayList<>();
		for (CraftingJob j : jobs) if (j != null && !j.isFinished()) out.add(j);
		return out;
	}

	@Override
	public void cancelJob(int uid) {
		for (int i = 0; i < SLOTS; i++) {
			if (jobs[i] != null && jobs[i].uid == uid) {
				jobs[i].cancel();
				jobs[i] = null;
				cooldown[i] = PLAN_COOLDOWN;
				setChanged();
			}
		}
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		var js = out.list("Jobs", SlotJob.CODEC);
		for (int i = 0; i < SLOTS; i++) if (jobs[i] != null && !jobs[i].isFinished()) js.add(new SlotJob(i, jobs[i]));
		out.putInt("NextId", nextId);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		Arrays.fill(jobs, null);
		in.listOrEmpty("Jobs", SlotJob.CODEC).stream().forEach(sj -> {
			if (sj.slot() >= 0 && sj.slot() < SLOTS) jobs[sj.slot()] = sj.job();
		});
		nextId = in.getIntOr("NextId", 1);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new IOMenu(ModContent.EXPORTER_MENU, id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return customName.orElse(Component.translatable("block.tsautocraft.exporter"));
	}

	@Override
	public void appendStatus(Consumer<Component> lines) {
		for (CraftingJob j : activeJobs()) lines.accept(JobText.line(j.view(true)));
	}
}
