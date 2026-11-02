package eu.sytxlabs.tsautocraft.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.StoredItemStack;

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
		if (targets.isEmpty()) return false;
		try (Transaction t = Transaction.openOuter()) {
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
			if (commit) t.commit();
			return true;
		}
	}

	public static long count(IInventoryAccess access, Level level, ItemKey key) {
		var tr = access.tracker();
		tr.getChangeTracker(level);
		return tr.countItems(new StoredItemStack(key.template()));
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
			ItemStack got = access.pullMatchingStack(w, w.getCount());
			pulled.add(got);
			if (got.getCount() < w.getCount()) {
				ok = false;
				break;
			}
		}
		if (ok) return pulled;

		for (ItemStack p : pulled) {
			if (!p.isEmpty()) {
				ItemStack rem = access.pushStack(p);
				if (!rem.isEmpty()) {
					throw new LeftoverException(rem);
				}
			}
		}
		return null;
	}

	/** Thrown when there was no room to put items back. The caller must buffer the stack. */
	public static final class LeftoverException extends RuntimeException {
		private final transient ItemStack leftover;

		public LeftoverException(ItemStack leftover) {
			super("leftover", null, false, false);
			this.leftover = leftover;
		}

		public ItemStack leftover() {
			return leftover;
		}
	}
}
