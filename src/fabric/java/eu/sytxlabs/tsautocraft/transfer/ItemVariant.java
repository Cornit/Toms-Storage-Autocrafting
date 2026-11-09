package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Fabric: an item with components, without amount (wraps Fabric's {@code ItemVariant}). */
public final class ItemVariant {
	private static final ItemVariant BLANK = new ItemVariant(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.blank());
	private final net.fabricmc.fabric.api.transfer.v1.item.ItemVariant fabric;

	private ItemVariant(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant fabric) {
		this.fabric = fabric;
	}

	public static ItemVariant of(ItemStack stack) {
		return stack.isEmpty() ? BLANK : new ItemVariant(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(stack));
	}

	public static ItemVariant of(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant fabric) {
		return fabric.isBlank() ? BLANK : new ItemVariant(fabric);
	}

	public static ItemVariant blank() {
		return BLANK;
	}

	public boolean isBlank() {
		return fabric.isBlank();
	}

	public Item getItem() {
		return fabric.getItem();
	}

	public ItemStack toStack() {
		return fabric.toStack();
	}

	public ItemStack toStack(int count) {
		return fabric.toStack(count);
	}

	public net.fabricmc.fabric.api.transfer.v1.item.ItemVariant fabric() {
		return fabric;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ItemVariant v && fabric.equals(v.fabric);
	}

	@Override
	public int hashCode() {
		return fabric.hashCode();
	}

	@Override
	public String toString() {
		return fabric.toString();
	}
}
