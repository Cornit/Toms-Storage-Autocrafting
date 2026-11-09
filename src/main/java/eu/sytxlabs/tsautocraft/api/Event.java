package eu.sytxlabs.tsautocraft.api;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.function.Function;

/**
 * Loader independent event (same use as Fabric's {@code Event}): {@link #register} a listener, the mod calls
 * {@link #invoker()}. Listeners are called in registration order, on the server thread.
 */
public final class Event<T> {
	private final Class<T> type;
	private final Function<T[], T> combiner;
	private T[] listeners;
	private volatile T invoker;

	private Event(Class<T> type, Function<T[], T> combiner) {
		this.type = type;
		this.combiner = combiner;
		this.listeners = newArray(0);
		this.invoker = combiner.apply(listeners);
	}

	/** An event whose invoker calls {@code combiner}'s result with all listeners registered so far. */
	public static <T> Event<T> createArrayBacked(Class<T> type, Function<T[], T> combiner) {
		return new Event<>(type, combiner);
	}

	public synchronized void register(T listener) {
		T[] l = Arrays.copyOf(listeners, listeners.length + 1);
		l[l.length - 1] = listener;
		listeners = l;
		invoker = combiner.apply(l);
	}

	public T invoker() {
		return invoker;
	}

	@SuppressWarnings("unchecked")
	private T[] newArray(int n) {
		return (T[]) Array.newInstance(type, n);
	}
}
