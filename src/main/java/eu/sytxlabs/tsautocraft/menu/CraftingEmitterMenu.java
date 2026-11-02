package eu.sytxlabs.tsautocraft.menu;

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
import eu.sytxlabs.tsautocraft.blockentity.CraftingEmitterBlockEntity;
import eu.sytxlabs.tsautocraft.util.GhostContainer;

/**
 * Crafting Emitter: one ghost slot, amount (synced through Tom's data packet) and mode (data slot, button 0 cycles).
 * <p>Client -> server: {@code amt}.
 */
public class CraftingEmitterMenu extends AbstractFilteredMenu {
	public static final int SLOT_X = 8, SLOT_Y = 20, PLAYER_Y = 84;

	private final CraftingEmitterBlockEntity be;
	private int syncTimer;

	// Client state
	public int amount;
	public int mode;
	public Runnable onUpdate;

	public CraftingEmitterMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public CraftingEmitterMenu(int id, Inventory inv, CraftingEmitterBlockEntity be) {
		super(ModContent.CRAFTING_EMITTER_MENU, id, inv);
		this.be = be;
		Container item = be != null ? be.getItem() : new GhostContainer(1, null);
		addSlot(new PhantomSlot(item, 0, SLOT_X, SLOT_Y));
		MenuUtil.addPlayerSlots(this::addSlot, inv, 8, PLAYER_Y);
		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null ? be.getMode() : 0;
			}

			@Override
			public void set(int value) {
				mode = value;
			}
		});
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if (be == null) return;
		if (syncTimer-- > 0) return;
		syncTimer = 10;
		MenuUtil.sendToClient(pinv.player, out -> out.putInt("amt", be.getAmount()));
	}

	@Override
	public void receive(ValueInput in) {
		if (be == null) {
			in.getInt("amt").ifPresent(a -> {
				amount = a;
				if (onUpdate != null) onUpdate.run();
			});
			return;
		}
		super.receive(in); // JEI/REI ghost items
		if (pinv.player.isSpectator()) return;
		in.getInt("amt").ifPresent(a -> {
			be.setAmount(a);
			syncTimer = 0;
		});
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (be == null || id != 0) return false;
		be.cycleMode();
		return true;
	}

	@Override
	public boolean stillValid(Player player) {
		return be == null || be.stillValid(player);
	}

	/** Shift click in the inventory: ghost item into the slot. */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index == 0) return ItemStack.EMPTY;
		Slot from = slots.get(index);
		if (from.hasItem()) slots.getFirst().set(from.getItem().copyWithCount(1));
		return ItemStack.EMPTY;
	}
}
