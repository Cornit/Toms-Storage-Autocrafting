package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** NeoForge: an item with components, without amount (wraps an {@link ItemResource}). */
public final class ItemVariant {
	private static final ItemVariant BLANK = new ItemVariant(ItemResource.EMPTY);
	private final ItemResource resource;

	private ItemVariant(ItemResource resource) {
		this.resource = resource;
	}

	public static ItemVariant of(ItemStack stack) {
		return stack.isEmpty() ? BLANK : new ItemVariant(ItemResource.of(stack));
	}

	public static ItemVariant of(ItemResource resource) {
		return resource.isEmpty() ? BLANK : new ItemVariant(resource);
	}

	public static ItemVariant blank() {
		return BLANK;
	}

	public boolean isBlank() {
		return resource.isEmpty();
	}

	public Item getItem() {
		return resource.getItem();
	}

	public ItemStack toStack() {
		return resource.toStack();
	}

	public ItemStack toStack(int count) {
		return resource.toStack(count);
	}

	public ItemResource resource() {
		return resource;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ItemVariant v && resource.equals(v.resource);
	}

	@Override
	public int hashCode() {
		return resource.hashCode();
	}

	@Override
	public String toString() {
		return resource.toString();
	}
}
