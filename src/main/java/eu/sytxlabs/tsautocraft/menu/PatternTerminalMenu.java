package eu.sytxlabs.tsautocraft.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.menu.StorageTerminalMenu;
import com.tom.storagemod.menu.slot.PhantomSlot;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.blockentity.PatternTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;
import eu.sytxlabs.tsautocraft.util.GhostContainer;

/**
 * Pattern Terminal: Tom's storage terminal (5 storage rows, search, sorting, tall mode) plus the encoding area
 * where the Crafting Terminal has its crafting grid.
 * Slot order: 0-8 grid, 9-11 outputs, 12 blank pattern, 13 result, then player inventory + offhand.
 * Buttons: 0 = toggle mode, 1 = encode, 2 = load pattern, 3 = clear grid, 4 = toggle substitutes.
 */
public class PatternTerminalMenu extends StorageTerminalMenu {
	public static final int GRID = 0, OUTPUTS = 9, BLANK = 12, RESULT = 13;
	public static final int MAX_PROCESSING_COUNT = 999;
	/** Top of the encoding area relative to the GUI (non-tall mode); the player inventory starts at 174. */
	public static final int AREA_Y = 94;
	public static final int GRID_X = 26, OUTPUT_X = 100, PATTERN_X = 130;

	private final PatternTerminalBlockEntity be;
	public boolean craftingMode = true;
	public boolean substitutes;

	/** Ghost slot with counts. Extends PhantomSlot so JEI/REI drag and drop works through Tom's handler. */
	public static class GhostSlot extends PhantomSlot {
		public final boolean output;

		public GhostSlot(Container inv, int index, int x, int y, boolean output) {
			super(inv, index, x, y);
			this.output = output;
		}

		@Override
		public int getMaxStackSize() {
			return MAX_PROCESSING_COUNT;
		}
	}

	public PatternTerminalMenu(int id, Inventory inv) {
		super(ModContent.PATTERN_TERMINAL_MENU, id, inv);
		this.be = null;
		init(new GhostContainer(9, null), new GhostContainer(3, null), new SimpleContainer(2));
		addPlayerSlots(inv, 8, 174);
	}

	public PatternTerminalMenu(int id, Inventory inv, PatternTerminalBlockEntity be) {
		super(ModContent.PATTERN_TERMINAL_MENU, id, inv, be);
		this.be = be;
		init(be.getGrid(), be.getOutputs(), be.getPatternSlots());
		addPlayerSlots(inv, 8, 174);
	}

	private void init(Container grid, Container outputs, Container slotsInv) {
		int y = AREA_Y + 17;
		for (int row = 0; row < 3; ++row) {
			for (int col = 0; col < 3; ++col) {
				addSlot(new GhostSlot(grid, col + row * 3, GRID_X + col * 18, y + row * 18, false));
			}
		}
		for (int i = 0; i < 3; i++) addSlot(new GhostSlot(outputs, i, OUTPUT_X, y + i * 18, true));

		addSlot(new Slot(slotsInv, PatternTerminalBlockEntity.SLOT_BLANK, PATTERN_X, y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.is(ModContent.BLANK_PATTERN) || stack.getItem() instanceof PatternItem;
			}
		});
		addSlot(new Slot(slotsInv, PatternTerminalBlockEntity.SLOT_RESULT, PATTERN_X, y + 36) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		});

		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be == null || be.isCraftingMode() ? 1 : 0;
			}

			@Override
			public void set(int value) {
				craftingMode = value != 0;
			}
		});
		addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return be != null && be.isSubstitutes() ? 1 : 0;
			}

			@Override
			public void set(int value) {
				substitutes = value != 0;
			}
		});
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		Slot slot = slotId >= 0 && slotId < slots.size() ? slots.get(slotId) : null;
		if (slot instanceof GhostSlot g) {
			boolean crafting = craftingModeServer();
			if (g.output && crafting) return; // result is computed from the recipe
			ItemStack carried = getCarried();
			ItemStack current = slot.getItem();
			if (!carried.isEmpty()) {
				if (button == 1 && ItemStack.isSameItemSameComponents(current, carried) && !crafting) {
					slot.set(current.copyWithCount(Math.min(current.getCount() + 1, MAX_PROCESSING_COUNT)));
				} else if (button == 1 || crafting) {
					slot.set(carried.copyWithCount(1));
				} else {
					slot.set(carried.copyWithCount(Math.min(carried.getCount(), MAX_PROCESSING_COUNT)));
				}
			} else if (button == 1 && current.getCount() > 1) {
				slot.set(current.copyWithCount(current.getCount() - 1));
			} else {
				slot.set(ItemStack.EMPTY);
			}
			return;
		}
		super.clicked(slotId, button, input, player);
	}

	private boolean craftingModeServer() {
		return be != null ? be.isCraftingMode() : craftingMode;
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (be == null) return false;
		switch (id) {
		case 0 -> be.setCraftingMode(!be.isCraftingMode());
		case 1 -> {
			String err = be.encode();
			MenuUtil.sendToClient(player, out -> out.putString("msg", err == null ? "msg.tsautocraft.encode.ok" : err));
		}
		case 2 -> {
			if (!be.loadFromPattern()) MenuUtil.sendToClient(player, out -> out.putString("msg", "msg.tsautocraft.encode.nothing_to_load"));
		}
		case 3 -> be.clearGrid();
		case 4 -> be.setSubstitutes(!be.isSubstitutes());
		default -> {
			return false;
		}
		}
		return true;
	}

	/** Server: JEI recipe transfer ({@code jei}) and JEI ghost drag ({@code ghost_slot} + {@code ghost_item}). */
	@Override
	public void receive(ValueInput in) {
		super.receive(in);
		if (be == null || pinv.player.isSpectator()) return;
		if (in.getBooleanOr("jei", false)) {
			be.applyRecipe(in.getBooleanOr("jei_craft", true),
					in.listOrEmpty("jei_in", ItemStack.OPTIONAL_CODEC).stream().toList(),
					in.listOrEmpty("jei_out", ItemStack.OPTIONAL_CODEC).stream().toList());
		}
		in.getInt("ghost_slot").ifPresent(idx -> in.read("ghost_item", ItemStack.CODEC).ifPresent(st -> {
			if (idx >= 0 && idx < slots.size() && slots.get(idx) instanceof GhostSlot g && !(g.output && be.isCraftingMode())) {
				g.set(st.copyWithCount(1));
			}
		}));
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return !(slot instanceof GhostSlot) && super.canTakeItemForPickAll(stack, slot);
	}

	/** Shift click on one of the menu's own slots (player slots are pushed into the storage by Tom's code). */
	@Override
	protected ItemStack shiftClickItems(Player player, int index) {
		if (index != BLANK && index != RESULT) return ItemStack.EMPTY; // ghost slots
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		// Player inventory without the offhand slot (last slot)
		if (!moveItemStackTo(stack, playerSlotsStart + 1, slots.size() - 1, true)) return ItemStack.EMPTY;
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return ItemStack.EMPTY;
	}
}
