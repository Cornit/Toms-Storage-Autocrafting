package eu.sytxlabs.tsautocraft.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.tom.storagemod.menu.AbstractFilteredMenu;
import com.tom.storagemod.menu.slot.FilterSlot;

import eu.sytxlabs.tsautocraft.blockentity.AbstractIOBlockEntity;

/**
 * Menu for Exporter and Importer: 3x3 filter slots in dispenser layout.
 * Extends Tom's {@link AbstractFilteredMenu} so ghost items, real filter items and
 * JEI/REI drag and drop work exactly like Tom's own filters.
 */
public class IOMenu extends AbstractFilteredMenu {
	private final AbstractIOBlockEntity be;
	private final Container filters;

	public IOMenu(MenuType<IOMenu> type, int id, Inventory inv, AbstractIOBlockEntity be) {
		super(type, id, inv);
		this.be = be;
		this.filters = be != null ? be.getFilters() : new SimpleContainer(AbstractIOBlockEntity.SLOTS);

		for (int row = 0; row < 3; ++row) {
			for (int col = 0; col < 3; ++col) {
				addSlot(new FilterSlot(filters, col + row * 3, 62 + col * 18, 17 + row * 18));
			}
		}
		MenuUtil.addPlayerSlots(this::addSlot, inv, 8, 84);
	}

	@Override
	public boolean stillValid(Player player) {
		return be == null || be.stillValid(player);
	}

	/** Shift click in the player inventory: item becomes a ghost filter in the first free slot. */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < AbstractIOBlockEntity.SLOTS) return ItemStack.EMPTY;
		Slot from = slots.get(index);
		if (from == null || !from.hasItem()) return ItemStack.EMPTY;
		ItemStack proto = from.getItem().copyWithCount(1);
		for (int i = 0; i < AbstractIOBlockEntity.SLOTS; i++) {
			if (ItemStack.isSameItemSameComponents(filters.getItem(i), proto)) return ItemStack.EMPTY;
		}
		for (int i = 0; i < AbstractIOBlockEntity.SLOTS; i++) {
			if (filters.getItem(i).isEmpty()) {
				slots.get(i).set(proto);
				break;
			}
		}
		return ItemStack.EMPTY;
	}
}
