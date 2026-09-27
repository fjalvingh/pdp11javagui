package to.etc.pdp11.core.console;

/**
 * What a terminal has to know about one console's idea of a line.
 *
 * <p>Ported from {@code TTerminalSettings} ({@code FormTerminalU.pas:42-53}), which every console
 * supplies through {@code getTerminalSettings}.</p>
 *
 * <h2>Why this is not something a VT100 emulator replaces</h2>
 *
 * <p>These consoles are not ANSI devices and they do not agree with each other about line
 * endings. ODT sends CR and LF and means the LF ({@code ConsolePDP11ODTU.pas:312-321}); the
 * 11/44's console sends a <b>lone CR</b> and means it ({@code ConsolePDP1144U.pas:158-165},
 * "LF ignorieren"). Feed a lone-CR stream to a conforming VT100 emulator and every line
 * overwrites the one before it, which is why PLAN.md §3 says to apply this as a <i>pre-filter in
 * front of</i> the emulator rather than as emulator configuration.</p>
 *
 * <p>Exactly one of {@link #crIsNewline} and {@link #lfIsNewline} should normally be true; the
 * Pascal says so in a comment and enforces nothing. Neither does this - a console that sets both
 * is describing a machine that double-spaces, which is a thing some of them do.</p>
 *
 * @param crIsNewline true: a received CR ends the line. False: ignore it.
 * @param lfIsNewline true: a received LF ends the line. False: ignore it.
 * @param backspace   the character that rubs out the one before it, or {@code 0} for a console
 *                    that never erases - which is most of them, since they are printing
 *                    terminals by ancestry.
 * @param tabStop     expand tabs to this column, or {@code 0} to pass them through.
 */
public record TerminalProfile(boolean crIsNewline, boolean lfIsNewline, char backspace, int tabStop) {
	/** No erase, no tab expansion. */
	public static TerminalProfile of(boolean crIsNewline, boolean lfIsNewline) {
		return new TerminalProfile(crIsNewline, lfIsNewline, (char) 0, 0);
	}

	public boolean hasBackspace() {
		return backspace != 0;
	}

	public boolean expandsTabs() {
		return tabStop > 0;
	}
}
