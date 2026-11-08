package eu.sytxlabs.tsautocraft.util;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Optional;

import net.minecraft.world.item.ItemStack;

/**
 * Content hash of sync data (records, lists, item stacks) so menus only send a packet when something changed. Item
 * stacks compare by item, components and count (their own {@code hashCode} is identity based).
 */
public final class SyncHash {
	private SyncHash() {}

	public static long of(Object... parts) {
		long h = 1;
		for (Object o : parts) h = h * 31 + hash(o);
		return h;
	}

	private static long hash(Object o) {
		if (o == null) return 0;
		if (o instanceof ItemStack st) return st.isEmpty() ? 7 : ItemStack.hashItemAndComponents(st) * 31L + st.getCount();
		if (o instanceof Collection<?> c) {
			long h = 3;
			for (Object e : c) h = h * 31 + hash(e);
			return h;
		}
		if (o instanceof Optional<?> opt) return opt.map(SyncHash::hash).orElse(5L);
		if (o instanceof Record r) {
			long h = r.getClass().hashCode();
			for (RecordComponent rc : r.getClass().getRecordComponents()) {
				try {
					h = h * 31 + hash(rc.getAccessor().invoke(r));
				} catch (ReflectiveOperationException e) {
					return System.nanoTime(); // cannot compare: always send
				}
			}
			return h;
		}
		return o.hashCode();
	}
}
