package ghidrallm.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Cooperative cancellation shared between the UI, the agent loop, and the HTTP layer. */
public class CancellationToken {

	/** Thrown by {@link #throwIfCancelled()}. */
	public static class CancelledException extends RuntimeException {
		public CancelledException() {
			super("Operation cancelled");
		}
	}

	private volatile boolean cancelled;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	public void cancel() {
		cancelled = true;
		for (Runnable r : listeners) {
			try {
				r.run();
			}
			catch (RuntimeException e) {
				// listeners must not break cancellation
			}
		}
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void throwIfCancelled() {
		if (cancelled) {
			throw new CancelledException();
		}
	}

	/** Registers a callback; invoked immediately if already cancelled. */
	public void onCancel(Runnable r) {
		listeners.add(r);
		if (cancelled) {
			r.run();
		}
	}

	public void removeListener(Runnable r) {
		listeners.remove(r);
	}
}
