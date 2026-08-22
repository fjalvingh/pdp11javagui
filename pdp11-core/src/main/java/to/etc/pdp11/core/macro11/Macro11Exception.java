package to.etc.pdp11.core.macro11;

/**
 * Something went wrong running or reading MACRO-11.
 *
 * <p>The assembler is an external program that this application does not ship, so most of the
 * ways this fails are environmental - it is not installed, the directory holding the source is
 * read-only, it ran for longer than it is allowed to. All of those want the same treatment: a
 * sentence the user can act on, rather than a stack trace about a missing file.</p>
 */
public class Macro11Exception extends RuntimeException {
	public Macro11Exception(String message) {
		super(message);
	}

	public Macro11Exception(String message, Throwable cause) {
		super(message, cause);
	}
}
