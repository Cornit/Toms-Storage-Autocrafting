package eu.sytxlabs.tsautocraft.transfer;

/** One slot or tank of a {@link Storage}. */
public interface StorageView<T> {
	T getResource();

	long getAmount();

	boolean isResourceBlank();

	long extract(T resource, long maxAmount, TransactionContext tx);
}
