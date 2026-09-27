package to.etc.pdp11.core.console;

/**
 * Not an error: the scanner ran off the end of what has arrived so far.
 *
 * <p>Ported from {@code EConsoleScannerInputIncomplete} ({@code ConsoleGenericU.pas:104}).
 * This is the control flow that lets the protocol layer tolerate a byte-at-a-time serial link:
 * a decode attempt that hits the end of the buffer rewinds to the last mark and waits for more
 * bytes, then tries again from the same place. See PLAN.md §2.</p>
 *
 * <p>Unchecked, because it is thrown from deep inside a decoder and caught one or two frames
 * up in the same class; making every scanner helper declare it would be noise. The Pascal's
 * is an ordinary {@code Exception} for the same reason.</p>
 */
public class ScannerInputIncompleteException extends RuntimeException {
	public ScannerInputIncompleteException(String message) {
		super(message);
	}

	/**
	 * Control flow, thrown often, and its stack trace is never looked at - so do not build
	 * one. The same applies to {@link ScannerUnknownExpressionException}.
	 */
	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}
}
