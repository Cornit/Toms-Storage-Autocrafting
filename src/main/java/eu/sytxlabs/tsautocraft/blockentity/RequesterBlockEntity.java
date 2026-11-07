package eu.sytxlabs.tsautocraft.blockentity;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
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
import eu.sytxlabs.tsautocraft.api.JobOwner;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.JobRunner;
import eu.sytxlabs.tsautocraft.job.JobScheduler;
import eu.sytxlabs.tsautocraft.job.JobText;
import eu.sytxlabs.tsautocraft.job.Reservations;
import eu.sytxlabs.tsautocraft.menu.RequesterMenu;
import eu.sytxlabs.tsautocraft.network.NetworkLink;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;
import eu.sytxlabs.tsautocraft.util.CustomName;
import eu.sytxlabs.tsautocraft.util.GhostContainer;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/**
 * Requester: keeps up to 6 items at a minimum stock. When the network stock drops below the target amount,
 * a crafting job for the difference is started automatically (only one per entry, so nothing is crafted twice while results are still inside machines).
 */
public class RequesterBlockEntity extends PlatformBlockEntity implements TickableServer, MenuProvider, JobOwner, StatusInfo {
	private final CustomName customName = new CustomName();
	public static final int ENTRIES = 6;
	public static final int MAX_AMOUNT = 1_000_000;

	// Check interval, batch size and replan delay come from TsAutocraftConfig (requester*)

	/** Status per entry (for display). */
	public static final int ST_OFF = 0, ST_STOCKED = 1, ST_CRAFTING = 2, ST_MISSING = 3, ST_NO_PATTERN = 4, ST_NO_NETWORK = 5;

	private final GhostContainer items = new GhostContainer(ENTRIES, this::setChanged);
	private final int[] amounts = new int[ENTRIES];
	private final CraftingJob[] jobs = new CraftingJob[ENTRIES];
	private final int[] cooldown = new int[ENTRIES];
	private final int[] status = new int[ENTRIES];
	private final ItemStack[] statusItem = new ItemStack[ENTRIES];
	private final NetworkLink network = new NetworkLink();
	private final JobRunner runner = new JobRunner();
	private int nextId = 1;

	private record SlotJob(int slot, CraftingJob job) {
		static final Codec<SlotJob> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("slot").forGetter(SlotJob::slot), CraftingJob.CODEC.fieldOf("job").forGetter(SlotJob::job)).apply(i, SlotJob::new));
	}

	public RequesterBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.REQUESTER_BE, pos, state);
		java.util.Arrays.fill(statusItem, ItemStack.EMPTY);
	}

	public GhostContainer getItems() {
		return items;
	}

	public int getAmount(int i) {
		return amounts[i];
	}

	public void setAmount(int i, int amount) {
		if (i < 0 || i >= ENTRIES) return;
		amounts[i] = Math.clamp(amount, 0, MAX_AMOUNT);
		cooldown[i] = 0;
		setChanged();
	}

	public int getStatus(int i) {
		return status[i];
	}

	public ItemStack getStatusItem(int i) {
		return statusItem[i];
	}

	@Override
	public void updateServer() {
		if (level == null) return;
		long t = level.getGameTime();
		boolean runJobs = JobRunner.due(level);
		boolean check = (t + Math.abs(worldPosition.hashCode())) % TsAutocraftConfig.get().requesterCheckTicks == 0;
		if (!runJobs && !check) return;

		IInventoryAccess net = check ? network.access(level, worldPosition) : null;
		var providers = check ? network.providers(level, worldPosition) : null;
		boolean connected = check && network.isConnected(level, worldPosition);
		Reservations res = check ? TerminalCrafting.reservations(level, worldPosition) : null;

		for (int i = 0; i < ENTRIES; i++) {
			ItemStack item = items.getItem(i);
			CraftingJob job = jobs[i];

			if (job != null && (item.isEmpty() || amounts[i] <= 0 || !ItemStack.isSameItemSameComponents(item, job.target))) {
				job.cancel();
				jobs[i] = null;
				job = null;
				setChanged();
			}

			if (job != null && runJobs) {
				if (job.state() == CraftingJob.State.RUNNING && !job.hasInFlight() && job.stalledTicks() > TsAutocraftConfig.get().requesterReplanSeconds * 20 && (job.reason() == CraftingJob.Reason.WAITING_ITEMS || job.reason() == CraftingJob.Reason.NO_PROVIDER)) {
					job.cancel();
				}
				if (job.isFinished()) {
					jobs[i] = null;
					cooldown[i] = 2;
					setChanged();
				}
			}
			if (!check) continue;

			if (item.isEmpty() || amounts[i] <= 0) {
				setStatus(i, ST_OFF, ItemStack.EMPTY);
				continue;
			}
			if (!connected) {
				setStatus(i, ST_NO_NETWORK, ItemStack.EMPTY);
				continue;
			}
			if (jobs[i] != null) {
				setStatus(i, ST_CRAFTING, jobs[i].reasonItem());
				continue;
			}
			if (cooldown[i] > 0) {
				cooldown[i]--;
				continue;
			}

			long have = StorageUtil.count(net, level, new ItemKey(item));
			if (have >= amounts[i]) {
				setStatus(i, ST_STOCKED, ItemStack.EMPTY);
				continue;
			}
			long deficit = Math.min(amounts[i] - have, TsAutocraftConfig.get().requesterMaxBatch);
			var outcome = CraftingJob.plan(nextId, level, net, providers, item, deficit, res);
			if (outcome.ok()) {
				nextId++;
				jobs[i] = outcome.job();
				JobScheduler.started(this, jobs[i]);
				setStatus(i, ST_CRAFTING, ItemStack.EMPTY);
				setChanged();
			} else {
				ItemKey self = new ItemKey(item);
				if (outcome.missing().containsKey(self)) {
					setStatus(i, ST_NO_PATTERN, ItemStack.EMPTY);
				} else {
					ItemKey first = outcome.missing().keySet().iterator().next();
					setStatus(i, ST_MISSING, first.create(1));
				}
				cooldown[i] = 5;
			}
		}
		if (runJobs && !activeJobs().isEmpty()) JobScheduler.submit(this);
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
	public List<CraftingJob> activeJobs() {
		List<CraftingJob> out = new java.util.ArrayList<>();
		for (CraftingJob j : jobs) if (j != null && !j.isFinished()) out.add(j);
		return out;
	}

	/** Cancel from the Autocrafting Terminal; the entry plans again after its cooldown if stock is still low. */
	@Override
	public void cancelJob(int uid) {
		for (int i = 0; i < ENTRIES; i++) {
			if (jobs[i] != null && jobs[i].uid == uid) {
				jobs[i].cancel();
				jobs[i] = null;
				cooldown[i] = 5;
				setChanged();
			}
		}
	}

	private void setStatus(int i, int st, ItemStack item) {
		status[i] = st;
		statusItem[i] = item;
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		customName.save(out);
		items.save(out, "Items");
		var am = out.list("Amounts", Codec.INT);
		for (int a : amounts) am.add(a);
		var js = out.list("Jobs", SlotJob.CODEC);
		for (int i = 0; i < ENTRIES; i++) if (jobs[i] != null && !jobs[i].isFinished()) js.add(new SlotJob(i, jobs[i]));
		out.putInt("NextId", nextId);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		customName.load(in);
		items.load(in, "Items");
		List<Integer> am = in.listOrEmpty("Amounts", Codec.INT).stream().toList();
		for (int i = 0; i < ENTRIES; i++) amounts[i] = i < am.size() ? am.get(i) : 0;
		java.util.Arrays.fill(jobs, null);
		in.listOrEmpty("Jobs", SlotJob.CODEC).stream().forEach(sj -> {
			if (sj.slot() >= 0 && sj.slot() < ENTRIES) jobs[sj.slot()] = sj.job();
		});
		nextId = in.getIntOr("NextId", 1);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new RequesterMenu(id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return customName.orElse(Component.translatable("block.tsautocraft.requester"));
	}

	public boolean stillValid(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this) return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
	}

	//region Anvil name

	/** Anvil name of the placed item, null without one. */
	public Component customName() {
		return customName.get();
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		customName.apply(components);
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		customName.collect(builder);
	}
	//endregion

	@Override
	public void appendStatus(Consumer<Component> lines) {
		int used = 0;
		for (int i = 0; i < ENTRIES; i++) if (!items.getItem(i).isEmpty() && amounts[i] > 0) used++;
		lines.accept(Component.translatable("jade.tsautocraft.entries", used, ENTRIES));
		for (CraftingJob j : activeJobs()) lines.accept(JobText.line(j.view(true)));
	}
}
