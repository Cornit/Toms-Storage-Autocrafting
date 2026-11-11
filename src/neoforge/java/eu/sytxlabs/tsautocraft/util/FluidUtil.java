package eu.sytxlabs.tsautocraft.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;

import eu.sytxlabs.tsautocraft.transfer.FluidVariant;
import eu.sytxlabs.tsautocraft.transfer.Storage;
import eu.sytxlabs.tsautocraft.transfer.StorageView;
import eu.sytxlabs.tsautocraft.transfer.Transaction;
import eu.sytxlabs.tsautocraft.transfer.TransactionContext;

/**
 * Fluids without fluid storage in Tom's network: they travel as filled containers (buckets, bottles, mod tanks...).
 * A Pattern Provider empties containers into a machine, an Importer fills empty containers from a machine.
 * NeoForge version (container items through {@code Capabilities.Fluid.ITEM}); Fabric has its own with the same methods.
 */
public final class FluidUtil {
	private FluidUtil() {}

	/** Fluid and amount (loader units) in one item, or null if it holds no fluid. */
	public record Content(FluidVariant fluid, long amount, ItemStack empty) {}

	/** One item that may turn into another one (bucket -> empty bucket) and its fluid handler. */
	private record Container(ItemAccess access, ResourceHandler<FluidResource> fluids) {
		static Container of(ItemStack stack) {
			if (stack.isEmpty()) return null;
			ItemAccess access = new SlotAccess(stack);
			ResourceHandler<FluidResource> h = access.getCapability(Capabilities.Fluid.ITEM);
			return h == null ? null : new Container(access, h);
		}

		ItemStack result() {
			return access.getAmount() > 0 ? access.getResource().toStack(access.getAmount()) : ItemStack.EMPTY;
		}
	}

	/**
	 * A one-slot item access whose item may change (NeoForge's {@code ItemAccess.forStack} only accepts the item it started
	 * with, so a bucket could not turn into a lava bucket). Transactional through snapshots.
	 */
	private static final class SlotAccess extends SnapshotJournal<ItemStack> implements ItemAccess {
		private ItemStack stack;

		SlotAccess(ItemStack stack) {
			this.stack = stack.copyWithCount(1);
		}

		@Override
		public ItemResource getResource() {
			return ItemResource.of(stack);
		}

		@Override
		public int getAmount() {
			return stack.getCount();
		}

		@Override
		public int insert(ItemResource resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
			if (resource.isEmpty() || amount <= 0) return 0;
			int n;
			if (stack.isEmpty()) {
				n = Math.min(amount, resource.getMaxStackSize());
				updateSnapshots(tx);
				stack = resource.toStack(n);
			} else {
				if (!resource.matches(stack)) return 0;
				n = Math.min(amount, stack.getMaxStackSize() - stack.getCount());
				if (n <= 0) return 0;
				updateSnapshots(tx);
				stack = stack.copyWithCount(stack.getCount() + n);
			}
			return n;
		}

		@Override
		public int extract(ItemResource resource, int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext tx) {
			if (stack.isEmpty() || amount <= 0 || !resource.matches(stack)) return 0;
			int n = Math.min(amount, stack.getCount());
			updateSnapshots(tx);
			stack = stack.copyWithCount(stack.getCount() - n);
			return n;
		}

		@Override
		protected ItemStack createSnapshot() {
			return stack; // never changed in place, every change makes a new stack
		}

		@Override
		protected void revertToSnapshot(ItemStack snapshot) {
			stack = snapshot;
		}
	}

	/** What one {@code stack} item holds and what is left after emptying it, null if it is no filled fluid container. */
	public static Content content(ItemStack stack) {
		// Fluid unit (API 4): 1 mB per item, nothing left after emptying
		net.minecraft.world.level.material.Fluid unit = eu.sytxlabs.tsautocraft.api.FluidUnits.fluid(stack);
		if (unit != null) return new Content(FluidVariant.of(FluidResource.of(unit)), 1, ItemStack.EMPTY);
		Container c = Container.of(stack);
		if (c == null) return null;
		FluidResource f = null;
		int amount = 0;
		for (int i = 0; i < c.fluids().size(); i++) {
			FluidResource r = c.fluids().getResource(i);
			int a = c.fluids().getAmountAsInt(i);
			if (!r.isEmpty() && a > 0) {
				f = r;
				amount = a;
				break;
			}
		}
		if (f == null) return null;
		// Simulated: only to learn what the empty container is
		try (Transaction t = Transaction.openOuter()) {
			int n = c.fluids().extract(f, amount, Transaction.neo(t));
			if (n <= 0) return null;
			return new Content(FluidVariant.of(f), n, c.result());
		}
	}

	/**
	 * Empties {@code stack} (one item) into {@code target} within {@code tx}: all of its fluid or nothing.
	 * @return the empty container (maybe {@link ItemStack#EMPTY}), or null if the fluid did not fit
	 */
	public static ItemStack emptyInto(ItemStack stack, Storage<FluidVariant> target, TransactionContext tx) {
		net.minecraft.world.level.material.Fluid unit = eu.sytxlabs.tsautocraft.api.FluidUnits.fluid(stack);
		if (unit != null) {
			// Fluid unit: the whole stack (count mB) at once
			long a = stack.getCount();
			try (Transaction t = tx.openNested()) {
				if (target.insert(FluidVariant.of(FluidResource.of(unit)), a, t) != a) return null;
				t.commit();
			}
			return ItemStack.EMPTY;
		}
		Container c = Container.of(stack);
		if (c == null) return null;
		// Collect first: the container's contents change while it is emptied
		List<FluidResource> fluids = new ArrayList<>();
		List<Integer> amounts = new ArrayList<>();
		for (int i = 0; i < c.fluids().size(); i++) {
			FluidResource r = c.fluids().getResource(i);
			int a = c.fluids().getAmountAsInt(i);
			if (!r.isEmpty() && a > 0) {
				fluids.add(r);
				amounts.add(a);
			}
		}
		if (fluids.isEmpty()) return null;
		try (Transaction t = tx.openNested()) {
			for (int i = 0; i < fluids.size(); i++) {
				int a = amounts.get(i);
				if (target.insert(FluidVariant.of(fluids.get(i)), a, t) != a || c.fluids().extract(fluids.get(i), a, Transaction.neo(t)) != a) return null;
			}
			t.commit();
		}
		return c.result();
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
		Container c = Container.of(empty);
		if (c == null) return null;
		try (Transaction t = tx.openNested()) {
			int room;
			try (Transaction sim = t.openNested()) {
				room = c.fluids().insert(fluid.resource(), Integer.MAX_VALUE, Transaction.neo(sim));
			}
			if (room <= 0 || source.extract(fluid, room, t) != room || c.fluids().insert(fluid.resource(), room, Transaction.neo(t)) != room) return null;
			ItemStack result = c.result();
			if (result.isEmpty() || !ItemStack.isSameItemSameComponents(result, wanted)) return null;
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
}
