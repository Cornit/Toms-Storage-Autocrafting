package eu.sytxlabs.tsautocraft.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import eu.sytxlabs.tsautocraft.ModContent;

/** Builds pattern items; shared by the Pattern Terminal and the JEI transfer into a Pattern Provider. */
public final class PatternEncoder {
	private PatternEncoder() {}

	/** Pattern data or a translation key for the error. */
	public record Result(PatternData data, String error) {
		static Result error(String key) {
			return new Result(null, key);
		}

		public ItemStack toItem() {
			ItemStack pattern = new ItemStack(ModContent.PATTERN);
			pattern.set(ModContent.PATTERN_COMPONENT, data);
			return pattern;
		}
	}

	/** 3x3 crafting input with one item per used position. */
	public static CraftingInput craftInput(List<ItemStack> grid) {
		List<ItemStack> l = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) {
			ItemStack st = i < grid.size() ? grid.get(i) : ItemStack.EMPTY;
			l.add(st.isEmpty() ? ItemStack.EMPTY : st.copyWithCount(1));
		}
		return CraftingInput.of(3, 3, l);
	}

	public static Optional<RecipeHolder<CraftingRecipe>> findRecipe(Level level, List<ItemStack> grid) {
		if (level == null || level.getServer() == null) return Optional.empty();
		return level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craftInput(grid), level);
	}

	/** Most substitutes stored per input position. */
	public static final int MAX_SUBSTITUTES = 32;

	/**
	 * @param grid        crafting: the 9 grid positions; processing: up to 9 inputs with amounts
	 * @param outputs     processing only: up to 3 outputs with amounts (crafting outputs come from the recipe)
	 * @param substitutes crafting only: store every item the recipe also accepts at a position (e.g. all planks)
	 */
	public static Result encode(Level level, boolean crafting, List<ItemStack> grid, List<ItemStack> outputs, boolean substitutes) {
		PatternData data;
		if (crafting) {
			var recipe = findRecipe(level, grid);
			if (recipe.isEmpty()) return Result.error("msg.tsautocraft.encode.no_recipe");
			CraftingInput input = craftInput(grid);
			ItemStack result = recipe.get().value().assemble(input);
			List<PatternData.Entry> in = new ArrayList<>(9);
			for (int i = 0; i < 9; i++) {
				ItemStack st = i < grid.size() ? grid.get(i) : ItemStack.EMPTY;
				if (st.isEmpty()) {
					in.add(PatternData.Entry.EMPTY);
					continue;
				}
				List<ItemStack> alt = substitutes ? substitutesAt(level, recipe.get().value(), grid, i, result) : List.of();
				in.add(new PatternData.Entry(st, 1, alt));
			}
			List<PatternData.Entry> out = new ArrayList<>();
			out.add(PatternData.Entry.of(result));
			// Remember remainders (empty bucket etc.) as by-products so the planner knows them
			NonNullList<ItemStack> rem = recipe.get().value().getRemainingItems(input);
			for (ItemStack r : rem) if (!r.isEmpty()) out.add(PatternData.Entry.of(r));
			data = new PatternData(true, in, out);
		} else {
			List<PatternData.Entry> in = new ArrayList<>();
			for (int i = 0; i < 9 && i < grid.size(); i++) if (!grid.get(i).isEmpty()) in.add(PatternData.Entry.of(grid.get(i)));
			List<PatternData.Entry> out = new ArrayList<>();
			for (int i = 0; i < 3 && i < outputs.size(); i++) if (!outputs.get(i).isEmpty()) out.add(PatternData.Entry.of(outputs.get(i)));
			data = new PatternData(false, in, out);
		}
		if (!data.isValid()) return Result.error("msg.tsautocraft.encode.invalid");
		return new Result(data, null);
	}

	/**
	 * Every item that can replace the one at {@code pos} so that the same recipe still matches and gives the same
	 * result (e.g. any planks for a crafting table). Tested against the recipe itself, so tags, shapeless recipes and
	 * mod recipes all work.
	 */
	private static List<ItemStack> substitutesAt(Level level, CraftingRecipe recipe, List<ItemStack> grid, int pos, ItemStack result) {
		ItemStack original = grid.get(pos);
		List<ItemStack> test = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) test.add(i < grid.size() ? grid.get(i) : ItemStack.EMPTY);
		List<ItemStack> alt = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR || item == original.getItem()) continue;
			ItemStack candidate = item.getDefaultInstance();
			test.set(pos, candidate);
			CraftingInput input = craftInput(test);
			if (!recipe.matches(input, level)) continue;
			ItemStack r = recipe.assemble(input);
			if (r.getCount() != result.getCount() || !ItemStack.isSameItemSameComponents(r, result)) continue;
			alt.add(candidate.copyWithCount(1));
			if (alt.size() >= MAX_SUBSTITUTES) break;
		}
		return alt;
	}
}
