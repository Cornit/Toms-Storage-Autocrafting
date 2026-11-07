package eu.sytxlabs.tsautocraft.menu;

import java.util.ArrayList;
import java.util.List;

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
import eu.sytxlabs.tsautocraft.api.PatternMode;
import eu.sytxlabs.tsautocraft.api.PatternModes;
import eu.sytxlabs.tsautocraft.blockentity.PatternTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;
import eu.sytxlabs.tsautocraft.util.GhostContainer;

/**
 * Pattern Terminal: Tom's storage terminal (5 storage rows, search, sorting, tall mode) plus the encoding area where the Crafting Terminal has its crafting grid.
 * Slot order: 0-8 grid, 9-11 outputs, 12 blank pattern, 13 result, then player inventory + offhand.
 * Buttons: 0 = next mode (crafting, processing, smelting, modes of other mods), 1 = encode, 2 = load pattern, 3 = clear grid, 4 = toggle substitutes,
 * 10-13 = scale all amounts x2, /2, x10, /10 (free-amount modes only).
 */
public class PatternTerminalMenu extends StorageTerminalMenu {
	public static final int GRID = 0, OUTPUTS = 9, BLANK = 12, RESULT = 13;
	public static final int MAX_PROCESSING_COUNT = 999;
	public static final int AREA_Y = 94;
	public static final int GRID_X = 26, OUTPUT_X = 100, PATTERN_X = 130;
	/** Buttons BTN_SCALE + i scale all amounts by {@code SCALES[i][0] / SCALES[i][1]} (x2, /2, x10, /10). */
	public static final int BTN_SCALE = 10;
	public static final int[][] SCALES = {{2, 1}, {1, 2}, {10, 1}, {1, 10}};

	private final PatternTerminalBlockEntity be;
	/** Client: index of the active mode in {@link PatternModes#all()}. */
	public int mode;
	public boolean substitutes;

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
				return be != null ? PatternModes.indexOf(be.getMode()) : 0;
			}

			@Override
			public void set(int value) {
				mode = value;
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
			if (!gridEnabled(slotId)) return; // position not used by this mode (e.g. brewing)
			boolean crafting = fixedModeServer();
			if (g.output && crafting) return;
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

	/** Is menu slot {@code slotId} a grid position the current mode uses (always true for other slots)? */
	public boolean gridEnabled(int slotId) {
		if (slotId < GRID || slotId >= OUTPUTS) return true;
		PatternMode m = be != null ? be.getMode() : modeType();
		return m.gridSlotEnabled(slotId - GRID);
	}

	private boolean fixedModeServer() {
		return be != null ? be.isFixedMode() : modeType().fixedInputs();
	}

	public PatternMode modeType() {
		return PatternModes.byIndex(mode);
	}

	/** Can every non-empty stack be scaled by {@code mul / div} to a whole amount in 1..{@link #MAX_PROCESSING_COUNT}? false if all are empty. */
	public static boolean canScale(List<ItemStack> stacks, int mul, int div) {
		boolean any = false;
		for (ItemStack st : stacks) {
			if (st.isEmpty()) continue;
			any = true;
			long n = (long) st.getCount() * mul;
			if (n % div != 0 || n / div < 1 || n / div > MAX_PROCESSING_COUNT) return false;
		}
		return any;
	}

	/** Client: grid and output stacks as shown in the ghost slots. */
	public List<ItemStack> encodingStacks() {
		List<ItemStack> l = new ArrayList<>(12);
		for (int i = GRID; i < BLANK; i++) l.add(slots.get(i).getItem());
		return l;
	}

	public boolean outputsLocked() {
		return modeType().fixedInputs();
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (be == null) return false;
		switch (id) {
		case 0 -> be.cycleMode();
		case 1 -> {
			String err = be.encode();
			MenuUtil.sendToClient(player, out -> out.putString("msg", err == null ? "msg.tsautocraft.encode.ok" : err));
		}
		case 2 -> {
			if (!be.loadFromPattern()) MenuUtil.sendToClient(player, out -> out.putString("msg", "msg.tsautocraft.encode.nothing_to_load"));
		}
		case 3 -> be.clearGrid();
		case 4 -> be.setSubstitutes(!be.isSubstitutes());
		case BTN_SCALE, BTN_SCALE + 1, BTN_SCALE + 2, BTN_SCALE + 3 -> {
			int[] f = SCALES[id - BTN_SCALE];
			be.scaleAmounts(f[0], f[1]);
		}
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
			PatternMode mode = PatternProviderMenu.transferMode(in);
			be.applyRecipe(mode, mode.transferInputs(be.getLevel(), in.listOrEmpty("jei_in", ItemStack.OPTIONAL_CODEC).stream().toList()), in.listOrEmpty("jei_out", ItemStack.OPTIONAL_CODEC).stream().toList());
		}
		in.getInt("ghost_slot").ifPresent(idx -> in.read("ghost_item", ItemStack.CODEC).ifPresent(st -> {
			if (idx >= 0 && idx < slots.size() && slots.get(idx) instanceof GhostSlot g && !(g.output && be.isFixedMode()) && gridEnabled(idx)) {
				g.set(st.copyWithCount(1));
			}
		}));
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return !(slot instanceof GhostSlot) && super.canTakeItemForPickAll(stack, slot);
	}

	@Override
	protected ItemStack shiftClickItems(Player player, int index) {
		if (index != BLANK && index != RESULT) return ItemStack.EMPTY; // ghost slots
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		if (!moveItemStackTo(stack, playerSlotsStart + 1, slots.size() - 1, true)) return ItemStack.EMPTY;
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return ItemStack.EMPTY;
	}
}
