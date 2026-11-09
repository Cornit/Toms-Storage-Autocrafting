package eu.sytxlabs.tsautocraft.transfer;

/** An open {@link Transaction} that nested transactions can be opened in. */
public interface TransactionContext {
	Transaction openNested();
}
