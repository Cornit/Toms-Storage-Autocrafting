package eu.sytxlabs.tsautocraft.transfer;

/** Fabric: wraps a Transfer API transaction. Same use as Fabric's: open, change, {@link #commit()} or just close. */
public final class Transaction implements TransactionContext, AutoCloseable {
	private final net.fabricmc.fabric.api.transfer.v1.transaction.Transaction fabric;

	private Transaction(net.fabricmc.fabric.api.transfer.v1.transaction.Transaction fabric) {
		this.fabric = fabric;
	}

	public static Transaction openOuter() {
		return new Transaction(net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter());
	}

	@Override
	public Transaction openNested() {
		return new Transaction(fabric.openNested());
	}

	public void commit() {
		fabric.commit();
	}

	@Override
	public void close() {
		fabric.close();
	}

	/** The Fabric transaction behind {@code tx}. */
	public static net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext fabric(TransactionContext tx) {
		return ((Transaction) tx).fabric;
	}
}
