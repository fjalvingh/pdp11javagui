package to.etc.pdp11.common.macro11.asm;

import java.util.Objects;

/**
 * One error or warning from an assembly.
 *
 * @param severity how bad it is
 * @param warning  which warning this is, or null for an error
 * @param location where it is; {@link Location#root()} is the line in the user's file
 * @param message  what to tell the user
 */
public record Diagnostic(Severity severity, WarningKind warning, Location location, String message) {
	public Diagnostic {
		Objects.requireNonNull(severity, "severity");
		Objects.requireNonNull(location, "location");
		Objects.requireNonNull(message, "message");
		if((severity == Severity.WARNING) != (warning != null))
			throw new IllegalArgumentException("A warning needs a WarningKind and an error must not have one");
	}

	public boolean isError() {
		return severity == Severity.ERROR;
	}

	/**
	 * The message, with where the expansion came from when the line is not in a file.
	 *
	 * <p>An error inside a macro is reported against the line that called it, because that is the
	 * line an editor can show, but the user needs to know it happened inside the expansion.</p>
	 */
	public String describe() {
		if(!location.isExpansion())
			return message;
		return message + " (in " + location.source() + ", line " + location.line() + ")";
	}

	@Override
	public String toString() {
		return severity + " " + location + ": " + message;
	}
}
