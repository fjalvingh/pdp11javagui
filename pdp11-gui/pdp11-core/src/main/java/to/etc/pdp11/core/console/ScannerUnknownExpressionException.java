package to.etc.pdp11.core.console;

/**
 * Not an error either: what the scanner is looking at is complete, but it is not the thing
 * being parsed.
 *
 * <p>Ported from {@code EConsoleScannerUnknownExpression} ({@code ConsoleGenericU.pas:106}).
 * A decoder tries the phrase shapes in turn and this is how one shape says "not me" - the
 * partial result is discarded and the next shape gets a go. Reaching the end of the list means
 * the line is something nobody recognises, which is a perfectly ordinary thing for a console
 * to print. See PLAN.md §2.</p>
 */
public class ScannerUnknownExpressionException extends RuntimeException {
	public ScannerUnknownExpressionException(String message) {
		super(message);
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}
}
