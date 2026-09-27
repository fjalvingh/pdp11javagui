package to.etc.pdp11.core.console;

/**
 * Where the machine's ENABLE/HALT switch is, for the consoles that have one.
 *
 * <p>Ported from {@code TConsoleRunMode} ({@code ConsoleGenericU.pas:160}), named after the
 * 11/70 front panel. Only meaningful for a console reporting
 * {@link ConsoleFeature#SWITCH_ENABLE_OR_HALT} - nothing on a SimH connection has a switch to
 * be in a position.</p>
 */
public enum ConsoleRunMode {
	UNKNOWN,
	RUN,
	HALT
}
