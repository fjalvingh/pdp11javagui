package to.etc.pdp11.core.console;

/**
 * A console operation failed: the machine did not answer, answered something impossible, or
 * refused the command.
 *
 * <p>Checked, deliberately. Every {@link Console} method can fail this way - a console is a
 * wire to a forty-year-old machine that may simply not be listening - and a caller that
 * forgets to think about that is a caller that will hang a window on it.</p>
 *
 * <p>Where the Pascal popped a modal dialog from inside the protocol layer and then called
 * {@code Abort} to unwind ({@code ConsoleGenericU.pas:480}), this is thrown and the UI decides
 * what to ask. See PLAN.md §1.</p>
 */
public class ConsoleException extends Exception {
	public ConsoleException(String message) {
		super(message);
	}

	public ConsoleException(String message, Throwable cause) {
		super(message, cause);
	}
}
