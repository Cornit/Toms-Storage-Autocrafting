package eu.sytxlabs.tsautocraft.menu;

import java.util.Arrays;

import com.mojang.serialization.Codec;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.menu.AbstractFilteredMenu;
import com.tom.storagemod.menu.slot.PhantomSlot;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.api.Upgrades;
import eu.sytxlabs.tsautocraft.blockentity.LevelMaintainerBlockEntity;
import eu.sytxlabs.tsautocraft.util.GhostContainer;
import eu.sytxlabs.tsautocraft.util.UpgradeContainer;

/**
 * Level Maintainer: 12 ghost slots in 2 columns of 6, each with threshold and batch; upgrade slots on the right
 * (slot order: 0-11 entries, 12-47 player inventory, 48-49 upgrades). Values, stock and status are synced through Tom's data packet.
 * <p>Client -> server: {@code set} (entry) + {@code thr} and/or {@code bat}; button 0 cycles the redstone mode.
 */
public class LevelMaintainerMenu extends AbstractFilteredMenu {
	public static final int ENTRIES = LevelMaintainerBlockEntity.ENTRIES, ROWS = 6;
	public static final int WIDTH = 244, HEIGHT = 236, COL_W = 118, ROW_X = 8, ROW_Y = 30, ROW_H = 18, PLAYER_X = 42, PLAYER_Y = 154;
	public static final int PLAYER = ENTRIES, UPGRADES = ENTRIES + 36;

	private final LevelMaintainerBlockEntity be;
	private final Container upgrades;
	private int syncTimer;
	/** Server: entry items at the last sync, to notice new ghosts. */
	private final ItemStack[] lastItems = new ItemStack[ENTRIES];

	// Client state
	public final int[] thresholds = new int[ENTRIES];
	public final int[] batches = new int[ENTRIES];
	public final int[] status = new int[ENTRIES];
	public final long[] stock = new long[ENTRIES];
	public final ItemStack[] statusItems = new ItemStack[ENTRIES];
	/** Client: redstone mode ({@code AbstractIOBlockEntity.RS_*}), used with a Redstone Upgrade. */
	public int redstoneMode;
	public Runnable onUpdate;

	public LevelMaintainerMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public LevelMaintainerMenu(int id, Inventory inv, LevelMaintainerBlockEntity be) {
		super(ModContent.LEVEL_MAINTAINER_MENU, id, inv);
		this.be = be;
		Arrays.fill(statusItems, ItemStack.EMPTY);
		for (int i = 0; i < ENTRIES; i++) lastItems[i] = be != null ? be.getItems().getItem(i).copy() : ItemStack.EMPTY;
		Container items = be != null ? be.getItems() : new GhostContainer(ENTRIES, null);
		for (int i = 0; i < ENTRIES; i++) addSlot(new PhantomSlot(items, i, slotX(i), slotY(i)));
		MenuUtil.addPlayerSlots(this::addSlot, inv, PLAYER_X, PLAYER_Y);
		this.upgrades = be != null ? be.getUpgrades() : new UpgradeContainer(LevelMaintainerBlockEntity.UPGRADE_SLOTS, Upgrades.LEVEL_MAINTAINER, null);
		for (int i = 0; i < LevelMaintainerBlockEntity.UPGRADE_SLOTS; i++) addSlot(new UpgradeSlot(upgrades, i, WIDTH));
		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null ? be.getRedstoneMode() : 0;
			}

			@Override
			public void set(int value) {
				redstoneMode = value;
			}
		});
	}

	/** Item position of entry {@code i} (column first: 0-5 left, 6-11 right). */
	public static int slotX(int i) {
		return ROW_X + i / ROWS * COL_W;
	}

	public static int slotY(int i) {
		return ROW_Y + i % ROWS * ROW_H;
	}

	/** Client and server: is a Redstone Upgrade installed (the upgrade slots are synced)? */
	public boolean hasRedstoneUpgrade() {
		return UpgradeContainer.count(upgrades, ModContent.REDSTONE_UPGRADE) > 0;
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (be == null || id != 0 || !be.hasRedstoneUpgrade()) return false;
		be.cycleRedstoneMode();
		return true;
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if (be == null) return;
		// A ghost placed by click or drag (Tom's phantom handling) starts with one stack as threshold and batch
		for (int i = 0; i < ENTRIES; i++) {
			ItemStack cur = be.getItems().getItem(i);
			if (!ItemStack.isSameItemSameComponents(cur, lastItems[i])) {
				lastItems[i] = cur.copy();
				if (!cur.isEmpty() && be.getThreshold(i) <= 0 && be.getBatch(i) <= 0) {
					initEntry(i, cur);
					syncTimer = 0;
				}
			}
		}
		if (syncTimer-- > 0) return;
		syncTimer = 10;
		MenuUtil.sendToClient(pinv.player, out -> {
			var t = out.list("thr", Codec.INT);
			var b = out.list("bat", Codec.INT);
			var s = out.list("st", Codec.INT);
			var c = out.list("stock", Codec.LONG);
			var si = out.list("si", ItemStack.OPTIONAL_CODEC);
			for (int i = 0; i < ENTRIES; i++) {
				t.add(be.getThreshold(i));
				b.add(be.getBatch(i));
				s.add(be.getStatus(i));
				c.add(be.getStock(i));
				si.add(be.getStatusItem(i));
			}
		});
	}

	@Override
	public void receive(ValueInput in) {
		if (be == null) {
			// Client: data from the server
			var t = in.listOrEmpty("thr", Codec.INT).stream().toList();
			var b = in.listOrEmpty("bat", Codec.INT).stream().toList();
			var s = in.listOrEmpty("st", Codec.INT).stream().toList();
			var c = in.listOrEmpty("stock", Codec.LONG).stream().toList();
			var si = in.listOrEmpty("si", ItemStack.OPTIONAL_CODEC).stream().toList();
			for (int i = 0; i < ENTRIES; i++) {
				if (i < t.size()) thresholds[i] = t.get(i);
				if (i < b.size()) batches[i] = b.get(i);
				if (i < s.size()) status[i] = s.get(i);
				if (i < c.size()) stock[i] = c.get(i);
				if (i < si.size()) statusItems[i] = si.get(i);
			}
			if (onUpdate != null) onUpdate.run();
			return;
		}
		super.receive(in); // JEI/REI ghost items from Tom's handler
		if (pinv.player.isSpectator()) return;
		in.getInt("set").ifPresent(i -> {
			in.getInt("thr").ifPresent(v -> be.setThreshold(i, v));
			in.getInt("bat").ifPresent(v -> be.setBatch(i, v));
			syncTimer = 0;
		});
		in.read("jei_req", ItemStack.CODEC).ifPresent(item -> {
			int slot = entryFor(item);
			if (slot < 0) return;
			be.getItems().setItem(slot, item.copyWithCount(1));
			initEntry(slot, item);
			syncTimer = 0;
		});
	}

	/** New entry: keep at least one stack, craft one stack at a time. */
	private void initEntry(int slot, ItemStack item) {
		if (be == null) return;
		if (be.getThreshold(slot) <= 0) be.setThreshold(slot, item.getMaxStackSize());
		if (be.getBatch(slot) <= 0) be.setBatch(slot, item.getMaxStackSize());
	}

	/** JEI/REI recipe transfer: the entry that already holds {@code item}, else the first empty one, else -1. */
	public int entryFor(ItemStack item) {
		int free = -1;
		for (int i = 0; i < ENTRIES; i++) {
			ItemStack cur = slots.get(i).getItem();
			if (ItemStack.isSameItemSameComponents(cur, item)) return i;
			if (cur.isEmpty() && free < 0) free = i;
		}
		return free;
	}

	@Override
	public boolean stillValid(Player player) {
		return be == null || be.stillValid(player);
	}

	/**
	 * Shift click: upgrades move between inventory and panel; any other item from the inventory becomes a ghost in the first
	 * free entry (threshold and batch one stack).
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < PLAYER) return ItemStack.EMPTY;
		Slot from = slots.get(index);
		if (!from.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = from.getItem();
		if (index >= UPGRADES || upgrades.canPlaceItem(0, stack)) {
			ItemStack copy = stack.copy();
			boolean moved = index >= UPGRADES ? moveItemStackTo(stack, PLAYER, UPGRADES, true) : moveItemStackTo(stack, UPGRADES, slots.size(), false);
			if (!moved) return ItemStack.EMPTY;
			if (stack.isEmpty()) from.setByPlayer(ItemStack.EMPTY);
			else from.setChanged();
			return copy;
		}
		ItemStack proto = stack.copyWithCount(1);
		for (int i = 0; i < ENTRIES; i++) {
			if (ItemStack.isSameItemSameComponents(slots.get(i).getItem(), proto)) return ItemStack.EMPTY;
		}
		for (int i = 0; i < ENTRIES; i++) {
			if (!slots.get(i).hasItem()) {
				slots.get(i).set(proto);
				initEntry(i, proto);
				break;
			}
		}
		return ItemStack.EMPTY;
	}
}
