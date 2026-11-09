package eu.sytxlabs.tsautocraft.transfer;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;

/** NeoForge: our {@link Storage} over a resource handler (amounts are ints there, larger requests are capped). */
final class NeoStorage<T, R extends Resource> implements Storage<T> {
	private final ResourceHandler<R> handler;
	private final Function<T, R> toNeo;
	private final Function<R, T> fromNeo;

	NeoStorage(ResourceHandler<R> handler, Function<T, R> toNeo, Function<R, T> fromNeo) {
		this.handler = handler;
		this.toNeo = toNeo;
		this.fromNeo = fromNeo;
	}

	private static int cap(long amount) {
		return (int) Math.min(Integer.MAX_VALUE, amount);
	}

	@Override
	public long insert(T resource, long maxAmount, TransactionContext tx) {
		R r = toNeo.apply(resource);
		if (r.isEmpty() || maxAmount <= 0) return 0;
		return handler.insert(r, cap(maxAmount), Transaction.neo(tx));
	}

	@Override
	public long extract(T resource, long maxAmount, TransactionContext tx) {
		R r = toNeo.apply(resource);
		if (r.isEmpty() || maxAmount <= 0) return 0;
		return handler.extract(r, cap(maxAmount), Transaction.neo(tx));
	}

	@Override
	public Object platformHandler() {
		return handler;
	}

	@Override
	public Iterator<StorageView<T>> iterator() {
		int size = handler.size();
		return new Iterator<>() {
			private int index;

			@Override
			public boolean hasNext() {
				return index < size;
			}

			@Override
			public StorageView<T> next() {
				if (index >= size) throw new NoSuchElementException();
				int i = index++;
				return new StorageView<>() {
					@Override
					public T getResource() {
						return fromNeo.apply(handler.getResource(i));
					}

					@Override
					public long getAmount() {
						return handler.getAmountAsLong(i);
					}

					@Override
					public boolean isResourceBlank() {
						return handler.getResource(i).isEmpty();
					}

					@Override
					public long extract(T resource, long maxAmount, TransactionContext tx) {
						R r = toNeo.apply(resource);
						if (r.isEmpty() || maxAmount <= 0) return 0;
						return handler.extract(i, r, cap(maxAmount), Transaction.neo(tx));
					}
				};
			}
		};
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof NeoStorage<?, ?> s && handler == s.handler;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(handler);
	}
}
