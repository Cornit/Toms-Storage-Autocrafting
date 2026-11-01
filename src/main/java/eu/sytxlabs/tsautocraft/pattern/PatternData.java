package eu.sytxlabs.tsautocraft.pattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;

import eu.sytxlabs.tsautocraft.util.ItemKey;

/**
 * Contents of an encoded pattern.
 * <ul>
 *   <li>{@code crafting = true}: exactly 9 inputs in crafter order (slot 0 = top left), empty entries allowed.
 *       The Pattern Provider places them into the {@code minecraft:crafter} exactly like this.</li>
 *   <li>{@code crafting = false}: processing pattern for any machine, inputs are simply inserted.</li>
 * </ul>
 * Counts are stored in {@link Entry#count()} (int), not in the ItemStack, because {@code ItemStack.CODEC} is limited to 99.
 */
public record PatternData(boolean crafting, List<Entry> inputs, List<Entry> outputs) {

	public static final Codec<PatternData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.fieldOf("crafting").forGetter(PatternData::crafting),
			Entry.CODEC.listOf().fieldOf("inputs").forGetter(PatternData::inputs),
			Entry.CODEC.listOf().fieldOf("outputs").forGetter(PatternData::outputs)
			).apply(i, PatternData::new));

	/**
	 * @param alternatives substitutes that may be used instead of {@code item} (crafting patterns encoded with
	 *                     "substitutes" on, e.g. birch planks for oak planks); empty = only {@code item}
	 */
	public record Entry(ItemStack item, int count, List<ItemStack> alternatives) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.OPTIONAL_CODEC.fieldOf("item").forGetter(Entry::item),
				Codec.INT.optionalFieldOf("count", 1).forGetter(Entry::count),
				ItemStack.CODEC.listOf().optionalFieldOf("alt", List.of()).forGetter(Entry::alternatives)
				).apply(i, Entry::new));

		public static final Entry EMPTY = new Entry(ItemStack.EMPTY, 0);

		public Entry {
			item = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
			if (item.isEmpty()) count = 0;
			alternatives = item.isEmpty() ? List.of() : alternatives.stream().filter(s -> !s.isEmpty()).map(s -> s.copyWithCount(1)).toList();
		}

		public Entry(ItemStack item, int count) {
			this(item, count, List.of());
		}

		/** The item itself followed by its substitutes. */
		public List<ItemStack> options() {
			if (alternatives.isEmpty()) return List.of(item);
			List<ItemStack> l = new ArrayList<>(alternatives.size() + 1);
			l.add(item);
			l.addAll(alternatives);
			return l;
		}

		/** Is {@code stack} this entry's item or one of its substitutes? */
		public boolean accepts(ItemStack stack) {
			for (ItemStack o : options()) if (ItemStack.isSameItemSameComponents(o, stack)) return true;
			return false;
		}

		public static Entry of(ItemStack stack) {
			return stack.isEmpty() ? EMPTY : new Entry(stack, stack.getCount());
		}

		public boolean isEmpty() {
			return item.isEmpty() || count <= 0;
		}

		/** ItemStack with the real count (may exceed the max stack size, display/transport only). */
		public ItemStack stack() {
			return isEmpty() ? ItemStack.EMPTY : item.copyWithCount(count);
		}

		// ItemStack has no value-based equals -> implement it, otherwise records compare by identity
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Entry e) || count != e.count || !ItemStack.isSameItemSameComponents(item, e.item)) return false;
			if (alternatives.size() != e.alternatives.size()) return false;
			for (int i = 0; i < alternatives.size(); i++) {
				if (!ItemStack.isSameItemSameComponents(alternatives.get(i), e.alternatives.get(i))) return false;
			}
			return true;
		}

		@Override
		public int hashCode() {
			return (ItemStack.hashItemAndComponents(item) * 31 + count) * 31 + alternatives.size();
		}
	}

	/** At least one input may be replaced by a substitute. */
	public boolean hasSubstitutes() {
		return inputs.stream().anyMatch(e -> !e.alternatives().isEmpty());
	}

	public PatternData {
		inputs = List.copyOf(inputs);
		outputs = List.copyOf(outputs);
	}

	public boolean isValid() {
		if (outputs.stream().allMatch(Entry::isEmpty)) return false;
		if (inputs.stream().allMatch(Entry::isEmpty)) return false;
		return !crafting || inputs.size() == 9;
	}

	/** Inputs per operation, merged by item. */
	public Map<ItemKey, Long> inputTotals() {
		return totals(inputs);
	}

	/** Outputs per operation, merged by item. */
	public Map<ItemKey, Long> outputTotals() {
		return totals(outputs);
	}

	private static Map<ItemKey, Long> totals(List<Entry> list) {
		Map<ItemKey, Long> m = new LinkedHashMap<>();
		for (Entry e : list) {
			if (!e.isEmpty()) m.merge(new ItemKey(e.item()), (long) e.count(), Long::sum);
		}
		return m;
	}

	/** Inputs as stacks (split by max stack size for processing patterns). */
	public List<ItemStack> inputStacks() {
		List<ItemStack> l = new ArrayList<>();
		for (Entry e : inputs) {
			if (e.isEmpty()) continue;
			int left = e.count();
			int max = Math.max(1, e.item().getMaxStackSize());
			while (left > 0) {
				int n = Math.min(left, max);
				l.add(e.item().copyWithCount(n));
				left -= n;
			}
		}
		return l;
	}

	public ItemStack primaryOutput() {
		for (Entry e : outputs) {
			if (!e.isEmpty()) return e.stack();
		}
		return ItemStack.EMPTY;
	}
}
