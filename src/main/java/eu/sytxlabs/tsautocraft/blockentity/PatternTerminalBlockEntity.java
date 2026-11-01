package eu.sytxlabs.tsautocraft.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.menu.PatternTerminalMenu;
import eu.sytxlabs.tsautocraft.pattern.PatternData;
import eu.sytxlabs.tsautocraft.pattern.PatternEncoder;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;
import eu.sytxlabs.tsautocraft.util.GhostContainer;

/**
 * Pattern Terminal: a Tom's Storage terminal (storage view, search, sorting) that also encodes patterns.
 * <ul>
 *   <li>3x3 ghost grid = inputs. In crafting mode every position maps exactly to the crafter slot
 *       the provider will later fill. The result is computed from the recipe automatically.</li>
 *   <li>In processing mode amounts are free (right click +1, click with stack = stack size) and the
 *       3 output slots are set by hand.</li>
 *   <li>"Blank" slot: blank pattern or old pattern to overwrite; "Result" slot: encoded pattern.</li>
 * </ul>
 */
public class PatternTerminalBlockEntity extends StorageTerminalBlockEntity {
	public static final int SLOT_BLANK = 0, SLOT_RESULT = 1;

	private final GhostContainer grid = new GhostContainer(9, this::onGridChanged);
	private final GhostContainer outputs = new GhostContainer(3, this::setChanged);
	private final SimpleContainer patternSlots = new SimpleContainer(2) {
		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return slot == SLOT_BLANK && (stack.is(ModContent.BLANK_PATTERN) || stack.getItem() instanceof PatternItem);
		}

		@Override
		public void setChanged() {
			super.setChanged();
			PatternTerminalBlockEntity.this.setChanged();
		}
	};
	private boolean craftingMode = true;
	/** Crafting patterns: also accept substitutes (e.g. any planks) - stored per input in the pattern. */
	private boolean substitutes;
	private boolean loading;

	public PatternTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.PATTERN_TERMINAL_BE, pos, state);
	}

	public GhostContainer getGrid() {
		return grid;
	}

	public GhostContainer getOutputs() {
		return outputs;
	}

	public SimpleContainer getPatternSlots() {
		return patternSlots;
	}

	public boolean isCraftingMode() {
		return craftingMode;
	}

	public boolean isSubstitutes() {
		return substitutes;
	}

	public void setSubstitutes(boolean substitutes) {
		this.substitutes = substitutes;
		setChanged();
	}

	public void setCraftingMode(boolean crafting) {
		this.craftingMode = crafting;
		if (crafting) {
			// Crafting: exactly 1 per slot
			for (int i = 0; i < 9; i++) {
				ItemStack st = grid.getItem(i);
				if (st.getCount() > 1) grid.setItem(i, st.copyWithCount(1));
			}
		}
		onGridChanged();
	}

	private void onGridChanged() {
		if (!loading && craftingMode && level != null && !level.isClientSide()) {
			ItemStack result = findRecipe().map(r -> r.value().assemble(craftInput())).orElse(ItemStack.EMPTY);
			outputs.clearContent();
			outputs.setItem(0, result);
		}
		setChanged();
	}

	private List<ItemStack> gridItems() {
		List<ItemStack> l = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) l.add(grid.getItem(i));
		return l;
	}

	private CraftingInput craftInput() {
		return PatternEncoder.craftInput(gridItems());
	}

	private Optional<RecipeHolder<CraftingRecipe>> findRecipe() {
		return PatternEncoder.findRecipe(level, gridItems());
	}

	/** Encodes the current grid. Returns a translation key for an error, or null on success. */
	public String encode() {
		ItemStack blank = patternSlots.getItem(SLOT_BLANK);
		if (blank.isEmpty()) return "msg.tsautocraft.encode.no_blank";
		if (!patternSlots.getItem(SLOT_RESULT).isEmpty()) return "msg.tsautocraft.encode.result_full";

		List<ItemStack> outs = new ArrayList<>(3);
		for (int i = 0; i < 3; i++) outs.add(outputs.getItem(i));
		PatternEncoder.Result r = PatternEncoder.encode(level, craftingMode, gridItems(), outs, substitutes);
		if (r.error() != null) return r.error();

		ItemStack pattern = r.toItem();
		blank.shrink(1);
		patternSlots.setItem(SLOT_BLANK, blank.isEmpty() ? ItemStack.EMPTY : blank);
		patternSlots.setItem(SLOT_RESULT, pattern);
		return null;
	}

	/** Loads a pattern from the "blank" slot into the grid (for editing/copying). */
	public boolean loadFromPattern() {
		PatternData d = PatternItem.get(patternSlots.getItem(SLOT_BLANK));
		if (d == null) d = PatternItem.get(patternSlots.getItem(SLOT_RESULT));
		if (d == null) return false;
		loading = true;
		try {
			craftingMode = d.crafting();
			substitutes = d.hasSubstitutes();
			grid.clearContent();
			outputs.clearContent();
			if (d.crafting()) {
				for (int i = 0; i < 9 && i < d.inputs().size(); i++) grid.setItem(i, d.inputs().get(i).stack());
			} else {
				int i = 0;
				for (PatternData.Entry e : d.inputs()) if (i < 9) grid.setItem(i++, e.stack());
			}
			int o = 0;
			for (PatternData.Entry e : d.outputs()) if (o < 3) outputs.setItem(o++, e.stack());
		} finally {
			loading = false;
		}
		onGridChanged();
		return true;
	}

	/** Recipe transfer from JEI: crafting = 9 grid positions, processing = up to 9 inputs and 3 outputs with amounts. */
	public void applyRecipe(boolean crafting, List<ItemStack> in, List<ItemStack> out) {
		loading = true;
		try {
			craftingMode = crafting;
			grid.clearContent();
			outputs.clearContent();
			for (int i = 0; i < 9 && i < in.size(); i++) {
				ItemStack s = in.get(i);
				if (s.isEmpty()) continue;
				grid.setItem(i, s.copyWithCount(crafting ? 1 : Math.clamp(s.getCount(), 1, PatternTerminalMenu.MAX_PROCESSING_COUNT)));
			}
			if (!crafting) {
				for (int i = 0; i < 3 && i < out.size(); i++) {
					ItemStack s = out.get(i);
					if (!s.isEmpty()) outputs.setItem(i, s.copyWithCount(Math.clamp(s.getCount(), 1, PatternTerminalMenu.MAX_PROCESSING_COUNT)));
				}
			}
		} finally {
			loading = false;
		}
		onGridChanged();
	}

	public void clearGrid() {
		grid.clearContent();
		outputs.clearContent();
		onGridChanged();
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		grid.save(out, "Grid");
		outputs.save(out, "Outputs");
		var list = out.list("Slots", ItemStack.OPTIONAL_CODEC);
		list.add(patternSlots.getItem(0));
		list.add(patternSlots.getItem(1));
		out.putBoolean("Crafting", craftingMode);
		out.putBoolean("Substitutes", substitutes);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		loading = true;
		try {
			grid.load(in, "Grid");
			outputs.load(in, "Outputs");
			List<ItemStack> l = in.listOrEmpty("Slots", ItemStack.OPTIONAL_CODEC).stream().toList();
			patternSlots.setItem(0, l.size() > 0 ? l.get(0) : ItemStack.EMPTY);
			patternSlots.setItem(1, l.size() > 1 ? l.get(1) : ItemStack.EMPTY);
			craftingMode = in.getBooleanOr("Crafting", true);
			substitutes = in.getBooleanOr("Substitutes", false);
		} finally {
			loading = false;
		}
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		Containers.dropContents(level, pos, patternSlots);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new PatternTerminalMenu(id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("menu.tsautocraft.pattern_terminal");
	}
}
