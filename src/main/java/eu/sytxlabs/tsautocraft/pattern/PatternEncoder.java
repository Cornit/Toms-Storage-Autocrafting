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
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.api.PatternMode;
import eu.sytxlabs.tsautocraft.api.PlannerHooks;

public final class PatternEncoder {
	private PatternEncoder() {}

	public record Result(PatternData data, String error) {
		public static Result error(String key) {
			return new Result(null, key);
		}

		public static Result ok(PatternData data) {
			return new Result(data, null);
		}

		public ItemStack toItem() {
			ItemStack pattern = new ItemStack(ModContent.PATTERN);
			pattern.set(ModContent.PATTERN_COMPONENT, data);
			return pattern;
		}
	}

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

	public static final int MAX_SUBSTITUTES = 32;

	/** Furnace result for one {@code input} (smelting, then blasting, then smoking recipes), empty if none. */
	public static ItemStack smeltingResult(Level level, ItemStack input) {
		if (level == null || level.getServer() == null || input.isEmpty()) return ItemStack.EMPTY;
		SingleRecipeInput in = new SingleRecipeInput(input.copyWithCount(1));
		var rm = level.getServer().getRecipeManager();
		var r = rm.getRecipeFor(RecipeType.SMELTING, in, level).map(h -> h.value().assemble(in));
		if (r.isEmpty()) r = rm.getRecipeFor(RecipeType.BLASTING, in, level).map(h -> h.value().assemble(in));
		if (r.isEmpty()) r = rm.getRecipeFor(RecipeType.SMOKING, in, level).map(h -> h.value().assemble(in));
		return r.orElse(ItemStack.EMPTY);
	}

	/** First non-empty stack of {@code grid}. */
	public static ItemStack firstItem(List<ItemStack> grid) {
		for (ItemStack st : grid) if (!st.isEmpty()) return st;
		return ItemStack.EMPTY;
	}

	/**
	 * Crafting recipes from REI come as a list of {@code width * height} inputs: try every grid width until the
	 * recipe matches, so the pattern gets real 3x3 positions. Lists that already match are returned as they are.
	 */
	public static List<ItemStack> layoutGrid(Level level, List<ItemStack> in) {
		if (in.size() <= 9 && findRecipe(level, in).isPresent()) return in;
		for (int w = 1; w <= 3; w++) {
			if (in.size() > w * 3) continue;
			List<ItemStack> grid = new ArrayList<>(9);
			for (int i = 0; i < 9; i++) grid.add(ItemStack.EMPTY);
			for (int i = 0; i < in.size(); i++) grid.set(i / w * 3 + i % w, in.get(i));
			if (findRecipe(level, grid).isPresent()) return grid;
		}
		return in;
	}

	/**
	 * Encodes with the given mode (see {@link PatternMode#encode}).
	 * @param grid        crafting: the 9 grid positions; processing: up to 9 inputs with amounts; smelting: first item
	 * @param outputs     processing only: up to 3 outputs with amounts (crafting/smelting outputs come from the recipe)
	 * @param substitutes crafting only: store every item the recipe also accepts at a position (e.g. all planks)
	 */
	public static Result encode(Level level, PatternMode mode, List<ItemStack> grid, List<ItemStack> outputs, boolean substitutes) {
		Result r = mode.encode(level, grid, outputs, substitutes);
		if (r.error() == null && (r.data() == null || !r.data().isValid())) return Result.error("msg.tsautocraft.encode.invalid");
		return r;
	}

	/** Smelting pattern: first item of the grid, result from the furnace recipe. */
	public static Result encodeSmelting(Level level, List<ItemStack> grid) {
		ItemStack input = firstItem(grid);
		if (input.isEmpty()) return Result.error("msg.tsautocraft.encode.invalid");
		ItemStack result = smeltingResult(level, input);
		if (result.isEmpty()) return Result.error("msg.tsautocraft.encode.no_smelting");
		return Result.ok(new PatternData(PatternData.SMELTING, List.of(new PatternData.Entry(input, 1)), List.of(PatternData.Entry.of(result))));
	}

	/** Crafting pattern: the 9 grid positions, result (and remainders) from the crafting recipe. */
	public static Result encodeCrafting(Level level, List<ItemStack> grid, boolean substitutes) {
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
		for (ItemStack r : recipe.get().value().getRemainingItems(input)) if (!r.isEmpty()) out.add(PatternData.Entry.of(r));
		return Result.ok(new PatternData(PatternData.CRAFTING, in, out));
	}

	/** Pattern with free inputs (up to 9) and outputs (up to 3) of the given mode (processing and similar modes). */
	public static Result encodeFree(net.minecraft.resources.Identifier mode, List<ItemStack> grid, List<ItemStack> outputs) {
		List<PatternData.Entry> in = new ArrayList<>();
		for (int i = 0; i < 9 && i < grid.size(); i++) if (!grid.get(i).isEmpty()) in.add(PatternData.Entry.of(grid.get(i)));
		List<PatternData.Entry> out = new ArrayList<>();
		for (int i = 0; i < 3 && i < outputs.size(); i++) if (!outputs.get(i).isEmpty()) out.add(PatternData.Entry.of(outputs.get(i)));
		return Result.ok(new PatternData(mode, in, out));
	}

	/**
	 * Every item that can replace the one at {@code pos} so that the same recipe still matches and gives the same result (e.g. any planks for a crafting table).
	 * Tested against the recipe itself, so tags, shapeless recipes and mod recipes all work.
	 */
	private static List<ItemStack> substitutesAt(Level level, CraftingRecipe recipe, List<ItemStack> grid, int pos, ItemStack result) {
		ItemStack original = grid.get(pos);
		List<ItemStack> test = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) test.add(i < grid.size() ? grid.get(i) : ItemStack.EMPTY);
		List<ItemStack> alt = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR || item == original.getItem()) continue;
			ItemStack candidate = item.getDefaultInstance();
			if (!PlannerHooks.isSubstitute(original, candidate)) continue;
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
