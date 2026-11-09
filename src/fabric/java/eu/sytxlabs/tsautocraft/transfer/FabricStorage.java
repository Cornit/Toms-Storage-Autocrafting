package eu.sytxlabs.tsautocraft.transfer;

import java.util.Iterator;
import java.util.function.Function;

/** Fabric: our {@link Storage} over a Transfer API storage. */
final class FabricStorage<T, F extends net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant<?>> implements Storage<T> {
	private final net.fabricmc.fabric.api.transfer.v1.storage.Storage<F> fabric;
	private final Function<T, F> toFabric;
	private final Function<F, T> fromFabric;

	FabricStorage(net.fabricmc.fabric.api.transfer.v1.storage.Storage<F> fabric, Function<T, F> toFabric, Function<F, T> fromFabric) {
		this.fabric = fabric;
		this.toFabric = toFabric;
		this.fromFabric = fromFabric;
	}

	@Override
	public long insert(T resource, long maxAmount, TransactionContext tx) {
		return fabric.insert(toFabric.apply(resource), maxAmount, Transaction.fabric(tx));
	}

	@Override
	public long extract(T resource, long maxAmount, TransactionContext tx) {
		return fabric.extract(toFabric.apply(resource), maxAmount, Transaction.fabric(tx));
	}

	@Override
	public Object platformHandler() {
		return fabric;
	}

	@Override
	public Iterator<StorageView<T>> iterator() {
		Iterator<net.fabricmc.fabric.api.transfer.v1.storage.StorageView<F>> it = fabric.iterator();
		return new Iterator<>() {
			@Override
			public boolean hasNext() {
				return it.hasNext();
			}

			@Override
			public StorageView<T> next() {
				net.fabricmc.fabric.api.transfer.v1.storage.StorageView<F> v = it.next();
				return new StorageView<>() {
					@Override
					public T getResource() {
						return fromFabric.apply(v.getResource());
					}

					@Override
					public long getAmount() {
						return v.getAmount();
					}

					@Override
					public boolean isResourceBlank() {
						return v.isResourceBlank();
					}

					@Override
					public long extract(T resource, long maxAmount, TransactionContext tx) {
						return v.extract(toFabric.apply(resource), maxAmount, Transaction.fabric(tx));
					}
				};
			}
		};
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof FabricStorage<?, ?> s && fabric == s.fabric;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(fabric);
	}
}
