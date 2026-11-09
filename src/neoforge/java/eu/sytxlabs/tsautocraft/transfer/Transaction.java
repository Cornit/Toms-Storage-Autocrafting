package eu.sytxlabs.tsautocraft.transfer;

/** NeoForge: wraps a transfer transaction. Same use as Fabric's: open, change, {@link #commit()} or just close. */
public final class Transaction implements TransactionContext, AutoCloseable {
	private final net.neoforged.neoforge.transfer.transaction.Transaction neo;

	private Transaction(net.neoforged.neoforge.transfer.transaction.Transaction neo) {
		this.neo = neo;
	}

	public static Transaction openOuter() {
		return new Transaction(net.neoforged.neoforge.transfer.transaction.Transaction.openRoot());
	}

	@Override
	public Transaction openNested() {
		return new Transaction(net.neoforged.neoforge.transfer.transaction.Transaction.open(neo));
	}

	public void commit() {
		neo.commit();
	}

	@Override
	public void close() {
		neo.close();
	}

	/** The NeoForge transaction behind {@code tx}. */
	public static net.neoforged.neoforge.transfer.transaction.TransactionContext neo(TransactionContext tx) {
		return ((Transaction) tx).neo;
	}
}
