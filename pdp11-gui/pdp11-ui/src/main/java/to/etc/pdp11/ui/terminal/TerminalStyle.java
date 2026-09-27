package to.etc.pdp11.ui.terminal;

/**
 * Who said it.
 *
 * <p>Ported from {@code TTerminalOutputStyle} ({@code tosPDP}/{@code tosUser}/{@code tosSystem}).
 * PLAN.md §3 says to keep this, and the reason is worth restating: the terminal shows the
 * <b>entire</b> byte stream, the console's own automated commands and their replies included
 * ({@code SerialIoHubU.pas:901-902}). That is how a flaky console gets debugged, and merging the
 * terminal into the main window must not mean showing less. Colouring is what keeps it
 * readable.</p>
 */
public enum TerminalStyle {
	/** What the machine sent. */
	PDP,
	/** What the user typed. */
	USER,
	/** What the application itself has to say - connecting, disconnected, why. */
	SYSTEM
}
