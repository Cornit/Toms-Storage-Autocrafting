package eu.sytxlabs.tsautocraft.menu;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.api.PatternMode;
import eu.sytxlabs.tsautocraft.api.PatternModes;
import eu.sytxlabs.tsautocraft.api.Upgrades;
import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;
import eu.sytxlabs.tsautocraft.pattern.PatternEncoder;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;
import eu.sytxlabs.tsautocraft.util.UpgradeContainer;

/**
 * Up to 4 rows of 9 pattern slots (rows 2-4 unlocked by Capacity Upgrades), 4 upgrade slots on the right, toggle for blocking mode (button id 0). Substitutes come only from the pattern (Pattern Terminal).
 * Slot order: 0-35 patterns, 36-71 player inventory, 72-75 upgrades.
 */
public class PatternProviderMenu extends AbstractContainerMenu implements IDataReceiver {
	public static final int WIDTH = 176, HEIGHT = 225, GRID_Y = 18, PLAYER_Y = 143;
	public static final int PLAYER = PatternProviderBlockEntity.SLOTS, UPGRADES = PLAYER + 36;

	private final PatternProviderBlockEntity be;
	private final Inventory inv;
	private final Container patterns;
	private final Container upgrades;
	public boolean blocking = true;
	public PatternProviderBlockEntity.LockMode lockMode = PatternProviderBlockEntity.LockMode.OFF;
	public int priority;

	public PatternProviderMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public PatternProviderMenu(int id, Inventory inv, PatternProviderBlockEntity be) {
		super(ModContent.PATTERN_PROVIDER_MENU, id);
		this.be = be;
		this.inv = inv;
		this.patterns = be != null ? be.getPatternInventory() : new SimpleContainer(PatternProviderBlockEntity.SLOTS);
		this.upgrades = be != null ? be.getUpgrades() : new UpgradeContainer(PatternProviderBlockEntity.UPGRADE_SLOTS, Upgrades.PATTERN_PROVIDER, null);

		for (int i = 0; i < PatternProviderBlockEntity.SLOTS; i++) {
			final int slot = i;
			addSlot(new Slot(patterns, i, 8 + i % 9 * 18, GRID_Y + i / 9 * 18) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return stack.getItem() instanceof PatternItem;
				}

				@Override
				public int getMaxStackSize() {
					return 1;
				}

				// Rows without a Capacity Upgrade are hidden
				@Override
				public boolean isActive() {
					return slot < activeSlots();
				}
			});
		}
		MenuUtil.addPlayerSlots(this::addSlot, inv, 8, PLAYER_Y);
		for (int i = 0; i < PatternProviderBlockEntity.UPGRADE_SLOTS; i++) {
			addSlot(new UpgradeSlot(upgrades, i, WIDTH, this::mayRemoveUpgrade));
		}

		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null && be.isBlocking() ? 1 : 0;
			}

			@Override
			public void set(int value) {
				blocking = value != 0;
			}
		});
		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null ? be.getLockMode().ordinal() : 0;
			}

			@Override
			public void set(int value) {
				lockMode = PatternProviderBlockEntity.LockMode.byId(value);
			}
		});
		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null ? be.getPriority() : 0;
			}

			@Override
			public void set(int value) {
				priority = (short) value; // data slots are synced as shorts
			}
		});
	}

	/** Pattern slots usable with the installed Capacity Upgrades. */
	public int activeSlots() {
		return PatternProviderBlockEntity.activeSlots(upgrades);
	}

	/** Capacity Upgrades can only be removed while the rows they unlock (the last ones, one per upgrade in the slot) are empty. */
	private boolean mayRemoveUpgrade(ItemStack upgrade) {
		if (!upgrade.is(ModContent.CAPACITY_UPGRADE)) return true;
		int active = activeSlots();
		for (int i = Math.max(PatternProviderBlockEntity.ROW, active - PatternProviderBlockEntity.ROW * upgrade.getCount()); i < active; i++) if (!patterns.getItem(i).isEmpty()) return false;
		return true;
	}

	public static final int BTN_BLOCKING = 0, BTN_LOCK_NEXT = 2, BTN_LOCK_PREV = 3, BTN_PRIO_DOWN = 4, BTN_PRIO_UP = 5, BTN_PRIO_DOWN_10 = 6, BTN_PRIO_UP_10 = 7;

	/** 0 = blocking mode, (1 = former substitutes toggle, unused), 2/3 = lock mode next/previous, 4-7 = priority -1/+1/-10/+10. */
	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (be == null) return false;
		switch (id) {
			case BTN_BLOCKING -> be.setBlocking(!be.isBlocking());
			case BTN_LOCK_NEXT, BTN_LOCK_PREV -> be.cycleLockMode(id == BTN_LOCK_PREV);
			case BTN_PRIO_DOWN -> be.setPriority(be.getPriority() - 1);
			case BTN_PRIO_UP -> be.setPriority(be.getPriority() + 1);
			case BTN_PRIO_DOWN_10 -> be.setPriority(be.getPriority() - 10);
			case BTN_PRIO_UP_10 -> be.setPriority(be.getPriority() + 10);
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Server: JEI/REI "+" ({@code jei}, same data as the Pattern Terminal transfer) encodes the recipe straight into a free slot. */
	@Override
	public void receive(ValueInput in) {
		if (be == null || !in.getBooleanOr("jei", false) || inv.player.isSpectator()) return;
		Player player = inv.player;
		String error = encodeFromJei(player, in);
		player.sendOverlayMessage(Component.translatable(error != null ? error : "msg.tsautocraft.encode.ok"));
	}

	private String encodeFromJei(Player player, ValueInput in) {
		int slot = freePatternSlot();
		if (slot < 0) return "msg.tsautocraft.jei.provider_full";
		int blank = player.hasInfiniteMaterials() ? -2 : blankPatternSlot(player.getInventory());
		if (blank == -1) return "msg.tsautocraft.encode.no_blank";
		PatternMode mode = transferMode(in);
		var r = PatternEncoder.encode(player.level(), mode, mode.transferInputs(player.level(), in.listOrEmpty("jei_in", ItemStack.OPTIONAL_CODEC).stream().toList()), in.listOrEmpty("jei_out", ItemStack.OPTIONAL_CODEC).stream().toList(), false); // exact: substitutes are set in the Pattern Terminal
		if (r.error() != null) return r.error();
		if (blank >= 0) player.getInventory().removeItem(blank, 1);
		patterns.setItem(slot, r.toItem());
		broadcastChanges();
		return null;
	}

	public static PatternMode transferMode(ValueInput in) {
		return in.getString("jei_mode").map(Identifier::tryParse).map(PatternModes::get).orElse(PatternModes.CRAFTING);
	}

	public int freePatternSlot() {
		for (int i = 0; i < activeSlots(); i++) if (patterns.getItem(i).isEmpty()) return i;
		return -1;
	}

	public static int blankPatternSlot(Inventory inv) {
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(ModContent.BLANK_PATTERN)) return i;
		return -1;
	}

	@Override
	public boolean stillValid(Player player) {
		return be == null || be.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index < PLAYER || index >= UPGRADES) {
			if (!moveItemStackTo(stack, PLAYER, UPGRADES, true)) return ItemStack.EMPTY;
		} else if (stack.getItem() instanceof PatternItem) {
			if (!moveItemStackTo(stack, 0, activeSlots(), false)) return ItemStack.EMPTY;
		} else if (upgrades.canPlaceItem(0, stack)) {
			if (!moveItemStackTo(stack, UPGRADES, slots.size(), false)) return ItemStack.EMPTY;
		} else {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}
}
