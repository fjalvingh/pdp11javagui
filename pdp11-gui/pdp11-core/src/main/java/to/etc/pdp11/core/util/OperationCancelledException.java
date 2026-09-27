package to.etc.pdp11.core.util;

/**
 * Thrown to unwind a long console operation the user cancelled.
 *
 * <p>Replaces Pascal's {@code Abort}/{@code EAbort}, whose whole purpose was to unwind
 * silently without an error dialog. Being an exception rather than a return code matters
 * because cancellation has to escape from deep inside a memory transfer loop, and being a
 * <i>checked</i> exception would put a {@code throws} clause on every method between here and
 * there for a condition none of them can handle.</p>
 *
 * <p>Cancellation is not an error: catch it at the operation boundary and say nothing.</p>
 */
public class OperationCancelledException extends RuntimeException {
	public OperationCancelledException() {
		super("The operation was cancelled");
	}

	public OperationCancelledException(String message) {
		super(message);
	}
}
