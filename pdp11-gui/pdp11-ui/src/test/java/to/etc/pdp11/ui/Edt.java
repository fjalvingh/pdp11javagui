package to.etc.pdp11.ui;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Do it on the event thread, and wait for it.
 *
 * <p>Swing's rule applies to tests as much as to the application, and this one is not
 * theoretical: a listener firing on the EDT while the test thread updates the same text field
 * interleaves two {@code setText}s, each of which is a remove and an insert, and the field ends
 * up holding the value twice. That is what happened the first time these tests were written,
 * and it presents as an assertion failure with a doubled string rather than as anything that
 * looks like a threading problem.</p>
 */
public final class Edt {
	private Edt() {
	}

	public static void run(Runnable work) {
		call(() -> {
			work.run();
			return null;
		});
	}

	public static <T> T call(Callable<T> work) {
		if(SwingUtilities.isEventDispatchThread())
			return callNow(work);
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<RuntimeException> failure = new AtomicReference<>();
		try {
			SwingUtilities.invokeAndWait(() -> {
				try {
					result.set(callNow(work));
				} catch(RuntimeException x) {
					failure.set(x);
				}
			});
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted", x);
		} catch(InvocationTargetException x) {
			throw new IllegalStateException(x.getCause());
		}
		if(failure.get() != null)
			throw failure.get();
		return result.get();
	}

	private static <T> T callNow(Callable<T> work) {
		try {
			return work.call();
		} catch(RuntimeException x) {
			throw x;
		} catch(Exception x) {
			throw new IllegalStateException(x);
		}
	}
}
