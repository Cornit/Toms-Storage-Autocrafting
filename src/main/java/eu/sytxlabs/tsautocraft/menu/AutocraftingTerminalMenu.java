package eu.sytxlabs.tsautocraft.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.api.Upgrades;
import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.job.CraftingJob.JobView;
import eu.sytxlabs.tsautocraft.job.JobHistoryEntry;
import eu.sytxlabs.tsautocraft.util.UpgradeContainer;

/**
 * Autocrafting Terminal. No item slots besides the player inventory; the craftable list and the jobs are synced through Tom's data packet (every 10 ticks).
 * <p>Client -> server: preview/request through {@link CraftRequestProtocol}, {@code cancel}, {@code pause}, {@code prio} (job uid), {@code hclear} (clear history).
 * <br>Server -> client: {@code craft} (list), {@code jobs} (list), {@code hist} (finished jobs, newest first).
 */
public class AutocraftingTerminalMenu extends AbstractContainerMenu implements IDataReceiver {
	public static final int PLAYER_Y = 174, WIDTH = 176, UPGRADES = 36;

	public record Craftable(ItemStack item, long stock) {
		public static final Codec<Craftable> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(Craftable::item), Codec.LONG.fieldOf("s").forGetter(Craftable::stock)).apply(i, Craftable::new));
	}

	/** Missing ingredient of a request (used by {@link CraftRequestProtocol}). */
	public record Missing(ItemStack item, long count) {
		public static final Codec<Missing> CODEC = RecordCodecBuilder.create(i -> i.group(ItemStack.OPTIONAL_CODEC.fieldOf("i").forGetter(Missing::item), Codec.LONG.fieldOf("c").forGetter(Missing::count)).apply(i, Missing::new));
	}

	private final AutocraftingTerminalBlockEntity be;
	private final Inventory inv;
	private int syncTimer;

	// Client state
	public final List<Craftable> craftables = new ArrayList<>();
	public final List<JobView> jobs = new ArrayList<>();
	public final List<JobHistoryEntry> history = new ArrayList<>();
	public Runnable onUpdate;

	public AutocraftingTerminalMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public AutocraftingTerminalMenu(int id, Inventory inv, AutocraftingTerminalBlockEntity be) {
		super(ModContent.AUTOCRAFTING_TERMINAL_MENU, id);
		this.be = be;
		this.inv = inv;
		MenuUtil.addPlayerSlots(this::addSlot, inv, 8, PLAYER_Y);
		var upgrades = be != null ? be.getUpgrades() : new UpgradeContainer(AutocraftingTerminalBlockEntity.UPGRADE_SLOTS, Upgrades.AUTOCRAFTING_TERMINAL, null);
		for (int i = 0; i < AutocraftingTerminalBlockEntity.UPGRADE_SLOTS; i++) addSlot(new UpgradeSlot(upgrades, i, WIDTH));
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if (be == null || syncTimer-- > 0) return;
		syncTimer = 10;
		List<Craftable> list = be.craftables().stream().map(st -> new Craftable(st, be.stockOf(st))).toList();
		List<JobView> views = be.jobViews();
		List<JobHistoryEntry> hist = List.copyOf(be.history());
		MenuUtil.sendToClient(inv.player, out -> {
			list.forEach(out.list("craft", Craftable.CODEC)::add);
			views.forEach(out.list("jobs", JobView.CODEC)::add);
			hist.forEach(out.list("hist", JobHistoryEntry.CODEC)::add);
		});
	}

	@Override
	public void receive(ValueInput in) {
		if (be == null) {
			craftables.clear();
			craftables.addAll(in.listOrEmpty("craft", Craftable.CODEC).stream().toList());
			jobs.clear();
			jobs.addAll(in.listOrEmpty("jobs", JobView.CODEC).stream().toList());
			history.clear();
			history.addAll(in.listOrEmpty("hist", JobHistoryEntry.CODEC).stream().toList());
			if (onUpdate != null) onUpdate.run();
			return;
		}
		if (inv.player.isSpectator()) return;
		if (CraftRequestProtocol.receive(in, inv.player, be)) syncTimer = 0;
		if (in.getBooleanOr("hclear", false)) {
			be.clearHistory();
			syncTimer = 0;
		}
		Map<String, IntConsumer> jobActions = Map.of("cancel", be::cancel, "pause", be::togglePause, "prio", be::cyclePriority);
		jobActions.forEach((key, action) -> in.getInt(key).ifPresent(id -> {
			action.accept(id);
			syncTimer = 0;
		}));
	}

	@Override
	public boolean stillValid(Player player) {
		return be == null || be.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		// Upgrades <-> player inventory, otherwise between main inventory and hotbar
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		boolean moved;
		if (index >= UPGRADES) moved = moveItemStackTo(stack, 0, UPGRADES, true);
		else if (Upgrades.allowed(Upgrades.AUTOCRAFTING_TERMINAL).contains(stack.getItem()) && moveItemStackTo(stack, UPGRADES, slots.size(), false)) moved = true;
		else moved = index < 27 ? moveItemStackTo(stack, 27, 36, false) : moveItemStackTo(stack, 0, 27, false);
		if (!moved) return ItemStack.EMPTY;
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}
}
