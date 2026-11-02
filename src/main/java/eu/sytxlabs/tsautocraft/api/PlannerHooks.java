package eu.sytxlabs.tsautocraft.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.pattern.PatternData;

/**
 * Hooks into planning and encoding. Datapacks get the same through two item tags:
 * <ul>
 *   <li>{@code #tsautocraft:not_autocraftable}: patterns with such a primary output are ignored (not craftable).</li>
 *   <li>{@code #tsautocraft:no_substitute}: never stored as a substitute when encoding with substitutes on.</li>
 * </ul>
 */
public final class PlannerHooks {
	public static final TagKey<Item> NOT_AUTOCRAFTABLE = TagKey.create(Registries.ITEM, TsAutocraft.id("not_autocraftable"));
	public static final TagKey<Item> NO_SUBSTITUTE = TagKey.create(Registries.ITEM, TsAutocraft.id("no_substitute"));

	private static final List<Comparator<PatternData>> ORDER = new ArrayList<>();
	private static final List<Predicate<ItemStack>> CRAFTABLE = new ArrayList<>();
	private static final List<BiPredicate<ItemStack, ItemStack>> SUBSTITUTES = new ArrayList<>();

	private PlannerHooks() {}

	/**
	 * Order of several patterns for the same item: the planner takes the first one that can be crafted completely.
	 * Comparators are applied in registration order (a stable sort keeps the provider order on ties).
	 */
	public static synchronized void registerPatternOrder(Comparator<PatternData> order) {
		ORDER.add(order);
	}

	/** {@code filter} returns false for items that must not be autocrafted (in addition to the tag). */
	public static synchronized void registerCraftableFilter(Predicate<ItemStack> filter) {
		CRAFTABLE.add(filter);
	}

	/** {@code filter(original, candidate)} returns false to keep {@code candidate} out of the substitutes of {@code original}. */
	public static synchronized void registerSubstituteFilter(BiPredicate<ItemStack, ItemStack> filter) {
		SUBSTITUTES.add(filter);
	}

	/** Combined pattern order, null if no hook is registered. */
	public static Comparator<PatternData> patternOrder() {
		Comparator<PatternData> c = null;
		for (Comparator<PatternData> o : ORDER) c = c == null ? o : c.thenComparing(o);
		return c;
	}

	public static boolean isCraftable(ItemStack output) {
		if (output.isEmpty() || output.is(NOT_AUTOCRAFTABLE)) return false;
		for (Predicate<ItemStack> f : CRAFTABLE) if (!f.test(output)) return false;
		return true;
	}

	public static boolean isSubstitute(ItemStack original, ItemStack candidate) {
		if (candidate.is(NO_SUBSTITUTE)) return false;
		for (BiPredicate<ItemStack, ItemStack> f : SUBSTITUTES) if (!f.test(original, candidate)) return false;
		return true;
	}
}
