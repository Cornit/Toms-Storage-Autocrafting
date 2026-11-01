package eu.sytxlabs.tsautocraft.menu;

import java.util.Arrays;

import com.mojang.serialization.Codec;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.menu.AbstractFilteredMenu;
import com.tom.storagemod.menu.slot.PhantomSlot;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.blockentity.RequesterBlockEntity;
import eu.sytxlabs.tsautocraft.util.GhostContainer;

/**
 * Requester: 6 ghost slots + target amounts. Amounts and status are synced through Tom's data packet.
 * <p>Client -> server: {@code set} (entry) + {@code amt} (amount).
 */
public class RequesterMenu extends AbstractFilteredMenu {
	public static final int ROW_Y = 18, ROW_H = 18, PLAYER_Y = 142;

	private final RequesterBlockEntity be;
	private int syncTimer;

	// Client state
	public final int[] amounts = new int[RequesterBlockEntity.ENTRIES];
	public final int[] status = new int[RequesterBlockEntity.ENTRIES];
	public final ItemStack[] statusItems = new ItemStack[RequesterBlockEntity.ENTRIES];
	public Runnable onUpdate;

	public RequesterMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public RequesterMenu(int id, Inventory inv, RequesterBlockEntity be) {
		super(ModContent.REQUESTER_MENU, id, inv);
		this.be = be;
		Arrays.fill(statusItems, ItemStack.EMPTY);
		Container items = be != null ? be.getItems() : new GhostContainer(RequesterBlockEntity.ENTRIES, null);
		for (int i = 0; i < RequesterBlockEntity.ENTRIES; i++) {
			addSlot(new PhantomSlot(items, i, 8, ROW_Y + i * ROW_H));
		}
		MenuUtil.addPlayerSlots(this::addSlot, inv, 8, PLAYER_Y);
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if (be == null) return;
		if (syncTimer-- > 0) return;
		syncTimer = 10;
		MenuUtil.sendToClient(pinv.player, out -> {
			var a = out.list("amt", Codec.INT);
			var s = out.list("st", Codec.INT);
			var si = out.list("si", ItemStack.OPTIONAL_CODEC);
			for (int i = 0; i < RequesterBlockEntity.ENTRIES; i++) {
				a.add(be.getAmount(i));
				s.add(be.getStatus(i));
				si.add(be.getStatusItem(i));
			}
		});
	}

	@Override
	public void receive(ValueInput in) {
		if (be == null) {
			// Client: data from the server
			var a = in.listOrEmpty("amt", Codec.INT).stream().toList();
			var s = in.listOrEmpty("st", Codec.INT).stream().toList();
			var si = in.listOrEmpty("si", ItemStack.OPTIONAL_CODEC).stream().toList();
			for (int i = 0; i < RequesterBlockEntity.ENTRIES; i++) {
				if (i < a.size()) amounts[i] = a.get(i);
				if (i < s.size()) status[i] = s.get(i);
				if (i < si.size()) statusItems[i] = si.get(i);
			}
			if (onUpdate != null) onUpdate.run();
			return;
		}
		super.receive(in); // JEI/REI ghost items from Tom's handler
		if (pinv.player.isSpectator()) return;
		in.getInt("set").ifPresent(i -> {
			be.setAmount(i, in.getIntOr("amt", 0));
			syncTimer = 0;
		});
		in.read("jei_req", ItemStack.CODEC).ifPresent(item -> {
			int slot = entryFor(item);
			if (slot < 0) return;
			be.getItems().setItem(slot, item.copyWithCount(1));
			if (be.getAmount(slot) <= 0) be.setAmount(slot, item.getMaxStackSize()); // new entry: keep one stack
			syncTimer = 0;
		});
	}

	/** JEI recipe transfer: the entry that already holds {@code item}, else the first empty one, else -1. */
	public int entryFor(ItemStack item) {
		int free = -1;
		for (int i = 0; i < RequesterBlockEntity.ENTRIES; i++) {
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

	/** Shift click in inventory: ghost item into the first free entry (default amount 64). */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < RequesterBlockEntity.ENTRIES) return ItemStack.EMPTY;
		Slot from = slots.get(index);
		if (from == null || !from.hasItem()) return ItemStack.EMPTY;
		ItemStack proto = from.getItem().copyWithCount(1);
		for (int i = 0; i < RequesterBlockEntity.ENTRIES; i++) {
			if (ItemStack.isSameItemSameComponents(slots.get(i).getItem(), proto)) return ItemStack.EMPTY;
		}
		for (int i = 0; i < RequesterBlockEntity.ENTRIES; i++) {
			if (!slots.get(i).hasItem()) {
				slots.get(i).set(proto);
				if (be != null && be.getAmount(i) == 0) be.setAmount(i, 64);
				break;
			}
		}
		return ItemStack.EMPTY;
	}
}
