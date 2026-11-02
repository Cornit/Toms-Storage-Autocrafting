package eu.sytxlabs.tsautocraft.util;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

/**
 * Fluids without fluid storage in Tom's network: they travel as filled containers (buckets, bottles, mod tanks...).
 * A Pattern Provider empties containers into a machine, an Importer fills empty containers from a machine.
 */
public final class FluidUtil {
	private FluidUtil() {}

	/** Fluid and amount (droplets) in one item, or null if it holds no fluid. */
	public record Content(FluidVariant fluid, long amount, ItemStack empty) {}

	/** What one {@code stack} item holds and what is left after emptying it, null if it is no filled fluid container. */
	public static Content content(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Slot slot = new Slot(ItemVariant.of(stack), 1);
		ContainerItemContext ctx = ContainerItemContext.ofSingleSlot(slot);
		Storage<FluidVariant> st = ctx.find(FluidStorage.ITEM);
		if (st == null) return null;
		FluidVariant f = null;
		long amount = 0;
		for (StorageView<FluidVariant> view : st) {
			if (!view.isResourceBlank() && view.getAmount() > 0) {
				f = view.getResource();
				amount = view.getAmount();
				break;
			}
		}
		if (f == null) return null;
		// Simulated: only to learn what the empty container is
		try (Transaction t = Transaction.openOuter()) {
			long n = st.extract(f, amount, t);
			if (n <= 0) return null;
			return new Content(f, n, slot.getAmount() > 0 ? slot.getResource().toStack((int) slot.getAmount()) : ItemStack.EMPTY);
		}
	}

	/**
	 * Empties {@code stack} (one item) into {@code target} within {@code tx}: all of its fluid or nothing.
	 * @return the empty container (maybe {@link ItemStack#EMPTY}), or null if the fluid did not fit
	 */
	public static ItemStack emptyInto(ItemStack stack, Storage<FluidVariant> target, TransactionContext tx) {
		Slot slot = new Slot(ItemVariant.of(stack), 1);
		Storage<FluidVariant> st = ContainerItemContext.ofSingleSlot(slot).find(FluidStorage.ITEM);
		if (st == null) return null;
		// Collect first: the container's views change while it is emptied
		List<Content> parts = new ArrayList<>();
		for (StorageView<FluidVariant> view : st) {
			if (!view.isResourceBlank() && view.getAmount() > 0) parts.add(new Content(view.getResource(), view.getAmount(), ItemStack.EMPTY));
		}
		if (parts.isEmpty()) return null;
		try (Transaction t = tx.openNested()) {
			for (Content c : parts) {
				if (target.insert(c.fluid(), c.amount(), t) != c.amount() || st.extract(c.fluid(), c.amount(), t) != c.amount()) return null;
			}
			t.commit();
		}
		return slot.getAmount() > 0 ? slot.getResource().toStack((int) slot.getAmount()) : ItemStack.EMPTY;
	}

	/**
	 * Fills {@code empty} (one item) with {@code fluid} from {@code source} so that it becomes {@code wanted}.
	 * @return the filled container, or null (nothing changed) if the source does not have enough
	 */
	public static ItemStack fill(ItemStack empty, FluidVariant fluid, ItemStack wanted, Storage<FluidVariant> source) {
		Slot slot = new Slot(ItemVariant.of(empty), 1);
		Storage<FluidVariant> st = ContainerItemContext.ofSingleSlot(slot).find(FluidStorage.ITEM);
		if (st == null) return null;
		try (Transaction t = Transaction.openOuter()) {
			long room;
			try (Transaction sim = t.openNested()) {
				room = st.insert(fluid, Long.MAX_VALUE, sim);
			}
			if (room <= 0 || source.extract(fluid, room, t) != room || st.insert(fluid, room, t) != room) return null;
			ItemStack result = slot.getResource().toStack((int) Math.max(1, slot.getAmount()));
			if (!ItemStack.isSameItemSameComponents(result, wanted)) return null;
			t.commit();
			return result;
		}
	}

	/** Does {@code target} hold any of {@code fluids}? */
	public static boolean containsAny(Storage<FluidVariant> target, List<FluidVariant> fluids) {
		for (StorageView<FluidVariant> view : target) {
			if (!view.isResourceBlank() && view.getAmount() > 0 && fluids.contains(view.getResource())) return true;
		}
		return false;
	}

	public static List<FluidVariant> fluidsOf(List<ItemStack> stacks) {
		List<FluidVariant> l = new ArrayList<>();
		for (ItemStack st : stacks) {
			Content c = content(st);
			if (c != null && !l.contains(c.fluid())) l.add(c.fluid());
		}
		return l;
	}

	/** A one-slot item storage that lets the container item change (bucket -> empty bucket). */
	private static final class Slot extends SingleVariantStorage<ItemVariant> {
		Slot(ItemVariant v, long amount) {
			this.variant = v;
			this.amount = amount;
		}

		@Override
		protected ItemVariant getBlankVariant() {
			return ItemVariant.blank();
		}

		@Override
		protected long getCapacity(ItemVariant variant) {
			return 64;
		}
	}
}
