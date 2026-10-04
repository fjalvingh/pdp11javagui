package to.etc.pdp11.common.macro11.asm;

/**
 * How bad a {@link Diagnostic} is.
 */
public enum Severity {
	/** The program is wrong: it cannot be assembled as written, or would not do what it says. */
	ERROR,

	/** The program assembles, but something in it is probably not what was meant. */
	WARNING
}
