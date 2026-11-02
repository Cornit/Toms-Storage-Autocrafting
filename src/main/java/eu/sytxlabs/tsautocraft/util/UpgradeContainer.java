package eu.sytxlabs.tsautocraft.util;

import java.util.List;

import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import eu.sytxlabs.tsautocraft.api.Upgrades;

/** Upgrade slots of a block: the upgrades {@link Upgrades} allows for its host. Several of the same kind may share a slot, up to {@link Upgrades#max} in total. */
public class UpgradeContainer extends SimpleContainer {
	private final Identifier host;
	private final Runnable onChange;

	public UpgradeContainer(int size, Identifier host, Runnable onChange) {
		super(size);
		this.host = host;
		this.onChange = onChange;
	}

	public Identifier host() {
		return host;
	}

	public List<Item> allowed() {
		return Upgrades.allowed(host);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return limit(slot, stack) > 0;
	}

	/** How many of {@code stack} slot {@code slot} may hold: the host's maximum minus what the other slots hold. */
	public int limit(int slot, ItemStack stack) {
		if (stack.isEmpty()) return 0;
		int max = Upgrades.max(host, stack.getItem());
		if (max <= 0) return 0;
		ItemStack cur = getItem(slot);
		if (!cur.isEmpty() && !cur.is(stack.getItem())) return 0;
		return Math.clamp(max - (count(stack.getItem()) - (cur.isEmpty() ? 0 : cur.getCount())), 0, stack.getMaxStackSize());
	}

	@Override
	public void setChanged() {
		super.setChanged();
		if (onChange != null) onChange.run();
	}

	/** Number of installed upgrades of {@code item}. */
	public int count(Item item) {
		return count(this, item);
	}

	public static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
		return n;
	}

	public void save(ValueOutput out, String name) {
		var list = out.list(name, ItemStack.OPTIONAL_CODEC);
		for (int i = 0; i < getContainerSize(); i++) list.add(getItem(i));
	}

	public void load(ValueInput in, String name) {
		List<ItemStack> l = in.listOrEmpty(name, ItemStack.OPTIONAL_CODEC).stream().toList();
		for (int i = 0; i < getContainerSize(); i++) setItem(i, i < l.size() ? l.get(i) : ItemStack.EMPTY);
	}
}
