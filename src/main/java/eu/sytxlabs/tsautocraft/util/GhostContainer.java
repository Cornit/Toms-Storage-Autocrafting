package eu.sytxlabs.tsautocraft.util;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import eu.sytxlabs.tsautocraft.pattern.PatternData;

/**
 * Container for "ghost" items (template only, not a real item). Counts are not limited to the max stack size,
 * so processing patterns can ask for e.g. 128 sand.
 */
public class GhostContainer implements Container {
	private final NonNullList<ItemStack> items;
	private final Runnable onChange;

	public GhostContainer(int size, Runnable onChange) {
		this.items = NonNullList.withSize(size, ItemStack.EMPTY);
		this.onChange = onChange;
	}

	@Override
	public int getContainerSize() {
		return items.size();
	}

	@Override
	public boolean isEmpty() {
		return items.stream().allMatch(ItemStack::isEmpty);
	}

	@Override
	public ItemStack getItem(int slot) {
		return items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		items.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
		setChanged();
	}

	@Override
	public void setChanged() {
		if (onChange != null) onChange.run();
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void clearContent() {
		for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
		setChanged();
	}

	public void save(ValueOutput out, String name) {
		var list = out.list(name, PatternData.Entry.CODEC);
		for (ItemStack st : items) list.add(PatternData.Entry.of(st));
	}

	public void load(ValueInput in, String name) {
		List<PatternData.Entry> list = new ArrayList<>(in.listOrEmpty(name, PatternData.Entry.CODEC).stream().toList());
		for (int i = 0; i < items.size(); i++) {
			items.set(i, i < list.size() ? list.get(i).stack() : ItemStack.EMPTY);
		}
	}

	/** For ItemStack lists without counts above 99 (filters). */
	public static final Codec<ItemStack> STACK = ItemStack.OPTIONAL_CODEC;
}
