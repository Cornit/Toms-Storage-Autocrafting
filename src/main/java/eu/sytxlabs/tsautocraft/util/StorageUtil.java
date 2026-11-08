package eu.sytxlabs.tsautocraft.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;

public final class StorageUtil {
	private StorageUtil() {}

	/** Item storage of the block {@code facing} points at, as seen from our side. */
	public static Storage<ItemVariant> facingStorage(Level level, BlockPos pos, Direction facing) {
		return ItemStorage.SIDED.find(level, pos.relative(facing), facing.getOpposite());
	}

	/** How much of {@code stack} fits (simulation only)? */
	public static long simulateInsert(Storage<ItemVariant> storage, ItemStack stack, long max) {
		if (storage == null || stack.isEmpty() || max <= 0) return 0;
		try (Transaction t = Transaction.openOuter()) {
			return storage.insert(ItemVariant.of(stack), max, t);
		}
	}

	/** Real insert. Returns the remainder. */
	public static ItemStack insert(Storage<ItemVariant> storage, ItemStack stack) {
		if (storage == null || stack.isEmpty()) return stack;
		try (Transaction t = Transaction.openOuter()) {
			long n = storage.insert(ItemVariant.of(stack), stack.getCount(), t);
			t.commit();
			ItemStack rem = stack.copy();
			rem.shrink((int) n);
			return rem;
		}
	}

	public static boolean canInsertAll(Storage<ItemVariant> storage, List<ItemStack> stacks) {
		if (storage == null) return false;
		try (Transaction t = Transaction.openOuter()) {
			for (ItemStack st : stacks) {
				if (st.isEmpty()) continue;
				if (storage.insert(ItemVariant.of(st), st.getCount(), t) < st.getCount()) return false;
			}
			return true;
		}
	}

	/**
	 * Inserts every stack, each one spread over {@code targets} in order, all or nothing.
	 * @param commit false = only test whether everything fits
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean insertAll(List<Storage<ItemVariant>> targets, List<ItemStack> stacks, boolean commit) {
		try (Transaction t = Transaction.openOuter()) {
			if (!insertAll(targets, stacks, t)) return false;
			if (commit) t.commit();
			return true;
		}
	}

	/** Like {@link #insertAll(List, List, boolean)}, inside the caller's transaction (the caller commits). */
	public static boolean insertAll(List<Storage<ItemVariant>> targets, List<ItemStack> stacks, TransactionContext t) {
		if (targets.isEmpty()) return false;
		for (ItemStack st : stacks) {
			if (st.isEmpty()) continue;
			long left = st.getCount();
			ItemVariant v = ItemVariant.of(st);
			for (Storage<ItemVariant> target : targets) {
				left -= target.insert(v, left, t);
				if (left <= 0) break;
			}
			if (left > 0) return false;
		}
		return true;
	}

	/** Item counts of one network storage, built in one pass; valid while Tom's change tracker stays the same. */
	private static final class Counts {
		long time;
		long tracker;
		final Map<ItemKey, Long> counts = new java.util.HashMap<>();
	}

	private static final Map<IInventoryAccess, Counts> COUNTS = new java.util.WeakHashMap<>();

	/**
	 * Amount of {@code key} in the network storage. Tom's {@code countItems} walks every slot of every inventory, so all
	 * counts come from one map per storage, rebuilt only when Tom's change tracker reports a change. Our own pulls and
	 * pushes ({@link #pull}, {@link #push}) adjust the map directly, so counts stay exact within a tick.
	 */
	public static long count(IInventoryAccess access, Level level, ItemKey key) {
		if (access == PlatformInventoryAccess.EMPTY) return 0;
		return counts(access, level).counts.getOrDefault(key, 0L);
	}

	private static Counts counts(IInventoryAccess access, Level level) {
		Counts c = COUNTS.get(access);
		long now = level.getGameTime();
		if (c != null && c.time == now) return c;
		var tr = access.tracker();
		long tracker = tr.getChangeTracker(level);
		if (c != null && c.tracker == tracker) {
			c.time = now;
			return c;
		}
		if (c == null) COUNTS.put(access, c = new Counts());
		c.time = now;
		c.tracker = tracker;
		c.counts.clear();
		Map<ItemKey, Long> m = c.counts;
		tr.streamWrappedStacks(false).forEach(s -> {
			if (s != null && s.getQuantity() > 0) m.merge(new ItemKey(s.getStack()), s.getQuantity(), Long::sum);
		});
		return c;
	}

	private static void adjust(IInventoryAccess access, ItemStack stack, long delta) {
		if (delta == 0 || stack.isEmpty()) return;
		Counts c = COUNTS.get(access);
		if (c != null) c.counts.merge(new ItemKey(stack), delta, (a, b) -> Math.max(0, a + b));
	}

	/** {@link IInventoryAccess#pullMatchingStack} that keeps {@link #count} exact. */
	public static ItemStack pull(IInventoryAccess access, ItemStack stack, long max) {
		ItemStack got = access.pullMatchingStack(stack, max);
		adjust(access, got, -got.getCount());
		return got;
	}

	/** {@link IInventoryAccess#pushStack} that keeps {@link #count} exact. @return what did not fit */
	public static ItemStack push(IInventoryAccess access, ItemStack stack) {
		if (stack.isEmpty()) return stack;
		ItemStack proto = stack.copyWithCount(1);
		int before = stack.getCount();
		ItemStack rest = access.pushStack(stack);
		adjust(access, proto, before - rest.getCount());
		return rest;
	}

	/**
	 * Pulls all stacks from the network, or nothing at all.
	 * @return the pulled stacks (same order) or {@code null} if something was missing (everything has been put back)
	 */
	public static List<ItemStack> pullAll(IInventoryAccess access, Level level, List<ItemStack> wanted) {
		Map<ItemKey, Long> totals = new java.util.HashMap<>();
		for (ItemStack w : wanted) {
			if (!w.isEmpty()) totals.merge(new ItemKey(w), (long) w.getCount(), Long::sum);
		}
		for (Map.Entry<ItemKey, Long> e : totals.entrySet()) {
			if (count(access, level, e.getKey()) < e.getValue()) return null;
		}
		List<ItemStack> pulled = new ArrayList<>(wanted.size());
		boolean ok = true;
		for (ItemStack w : wanted) {
			if (w.isEmpty()) {
				pulled.add(ItemStack.EMPTY);
				continue;
			}
			ItemStack got = StorageUtil.pull(access, w, w.getCount());
			pulled.add(got);
			if (got.getCount() < w.getCount()) {
				ok = false;
				break;
			}
		}
		if (ok) return pulled;

		// Put everything back first, then report all stacks that did not fit
		List<ItemStack> leftovers = new ArrayList<>();
		for (ItemStack p : pulled) {
			if (!p.isEmpty()) {
				ItemStack rem = StorageUtil.push(access, p);
				if (!rem.isEmpty()) leftovers.add(rem);
			}
		}
		if (!leftovers.isEmpty()) throw new LeftoverException(leftovers);
		return null;
	}

	/** Thrown when there was no room to put items back. The caller must buffer the stacks. */
	public static final class LeftoverException extends RuntimeException {
		private final transient List<ItemStack> leftovers;

		public LeftoverException(List<ItemStack> leftovers) {
			super("leftover", null, false, false);
			this.leftovers = leftovers;
		}

		public List<ItemStack> leftovers() {
			return leftovers;
		}
	}
}
