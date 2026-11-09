package eu.sytxlabs.tsautocraft.transfer;

/**
 * Loader independent item/fluid storage (Fabric Transfer API storage, NeoForge resource handler), with the same method
 * names as Fabric's {@code Storage}. Amounts are the loader's units (fluids: droplets on Fabric, mB on NeoForge), so
 * code only passes on amounts it got from the loader. Iterating gives one view per slot/tank.
 */
public interface Storage<T> extends Iterable<StorageView<T>> {
	/** @return how much was inserted (0..maxAmount) */
	long insert(T resource, long maxAmount, TransactionContext tx);

	/** @return how much was extracted (0..maxAmount) */
	long extract(T resource, long maxAmount, TransactionContext tx);

	/** The loader's own object (identity), to compare with handlers of Tom's network. */
	Object platformHandler();
}
