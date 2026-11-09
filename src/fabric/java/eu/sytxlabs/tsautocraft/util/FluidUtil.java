package eu.sytxlabs.tsautocraft.util;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.minecraft.world.item.ItemStack;

import eu.sytxlabs.tsautocraft.transfer.FluidVariant;
import eu.sytxlabs.tsautocraft.transfer.Storage;
import eu.sytxlabs.tsautocraft.transfer.StorageView;
import eu.sytxlabs.tsautocraft.transfer.Transaction;
import eu.sytxlabs.tsautocraft.transfer.TransactionContext;

/**
 * Fluids without fluid storage in Tom's network: they travel as filled containers (buckets, bottles, mod tanks...).
 * A Pattern Provider empties containers into a machine, an Importer fills empty containers from a machine.
 * Fabric version (container items through {@code FluidStorage.ITEM}); NeoForge has its own with the same methods.
 */
public final class FluidUtil {
	private FluidUtil() {}

	/** Fluid and amount (loader units) in one item, or null if it holds no fluid. */
	public record Content(FluidVariant fluid, long amount, ItemStack empty) {}

	private static net.fabricmc.fabric.api.transfer.v1.storage.Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> itemStorage(Slot slot) {
		return ContainerItemContext.ofSingleSlot(slot).find(FluidStorage.ITEM);
	}

	/** What one {@code stack} item holds and what is left after emptying it, null if it is no filled fluid container. */
	public static Content content(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Slot slot = new Slot(stack);
		var st = itemStorage(slot);
		if (st == null) return null;
		net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant f = null;
		long amount = 0;
		for (var view : st) {
			if (!view.isResourceBlank() && view.getAmount() > 0) {
				f = view.getResource();
				amount = view.getAmount();
				break;
			}
		}
		if (f == null) return null;
		// Simulated: only to learn what the empty container is
		try (Transaction t = Transaction.openOuter()) {
			long n = st.extract(f, amount, Transaction.fabric(t));
			if (n <= 0) return null;
			return new Content(FluidVariant.of(f), n, slot.result());
		}
	}

	/**
	 * Empties {@code stack} (one item) into {@code target} within {@code tx}: all of its fluid or nothing.
	 * @return the empty container (maybe {@link ItemStack#EMPTY}), or null if the fluid did not fit
	 */
	public static ItemStack emptyInto(ItemStack stack, Storage<FluidVariant> target, TransactionContext tx) {
		Slot slot = new Slot(stack);
		var st = itemStorage(slot);
		if (st == null) return null;
		// Collect first: the container's views change while it is emptied
		List<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> fluids = new ArrayList<>();
		List<Long> amounts = new ArrayList<>();
		for (var view : st) {
			if (!view.isResourceBlank() && view.getAmount() > 0) {
				fluids.add(view.getResource());
				amounts.add(view.getAmount());
			}
		}
		if (fluids.isEmpty()) return null;
		try (Transaction t = tx.openNested()) {
			for (int i = 0; i < fluids.size(); i++) {
				long a = amounts.get(i);
				if (target.insert(FluidVariant.of(fluids.get(i)), a, t) != a || st.extract(fluids.get(i), a, Transaction.fabric(t)) != a) return null;
			}
			t.commit();
		}
		return slot.result();
	}

	/**
	 * Fills {@code empty} (one item) with {@code fluid} from {@code source} so that it becomes {@code wanted}.
	 * @return the filled container, or null (nothing changed) if the source does not have enough
	 */
	public static ItemStack fill(ItemStack empty, FluidVariant fluid, ItemStack wanted, Storage<FluidVariant> source) {
		try (Transaction t = Transaction.openOuter()) {
			ItemStack result = fill(empty, fluid, wanted, source, t);
			if (result != null) t.commit();
			return result;
		}
	}

	/** Like {@link #fill(ItemStack, FluidVariant, ItemStack, Storage)}, within {@code tx}. */
	public static ItemStack fill(ItemStack empty, FluidVariant fluid, ItemStack wanted, Storage<FluidVariant> source, TransactionContext tx) {
		Slot slot = new Slot(empty);
		var st = itemStorage(slot);
		if (st == null) return null;
		try (Transaction t = tx.openNested()) {
			long room;
			try (Transaction sim = t.openNested()) {
				room = st.insert(fluid.fabric(), Long.MAX_VALUE, Transaction.fabric(sim));
			}
			if (room <= 0 || source.extract(fluid, room, t) != room || st.insert(fluid.fabric(), room, Transaction.fabric(t)) != room) return null;
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

	/** A one-slot item storage holding one item that lets the container item change (bucket -> empty bucket). */
	private static final class Slot extends SingleVariantStorage<net.fabricmc.fabric.api.transfer.v1.item.ItemVariant> {
		Slot(ItemStack stack) {
			this.variant = net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(stack);
			this.amount = 1;
		}

		ItemStack result() {
			return amount > 0 ? variant.toStack((int) amount) : ItemStack.EMPTY;
		}

		@Override
		protected net.fabricmc.fabric.api.transfer.v1.item.ItemVariant getBlankVariant() {
			return net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.blank();
		}

		@Override
		protected long getCapacity(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant variant) {
			return 64;
		}
	}
}
