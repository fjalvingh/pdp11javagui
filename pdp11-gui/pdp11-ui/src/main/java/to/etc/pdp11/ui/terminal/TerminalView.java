package to.etc.pdp11.ui.terminal;

import to.etc.pdp11.core.console.TerminalProfile;

import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * The terminal, behind an interface.
 *
 * <p>PLAN.md §3: "Wrap JediTerm behind a small {@code TerminalView} interface from day one, and
 * spike it before committing in phase 5. It is the riskiest dependency in the stack" - maintained
 * primarily as an IntelliJ component, recent releases Kotlin, API churn in the Swing module
 * between versions, and a {@code TtyConnector} that assumes a PTY-shaped blocking stream, which
 * is not what any of these consoles are.</p>
 *
 * <p>So this interface exists first and {@link GlassTerminalView} implements it, which is the
 * fallback PLAN.md describes: the consoles are dumb TTYs, and full ANSI only matters for programs
 * <i>running on</i> the PDP-11. Should that day come, a JediTerm implementation goes behind this
 * same interface and nothing above it changes.</p>
 */
public interface TerminalView {
	/** The component to put in a window. */
	JComponent getComponent();

	/**
	 * How to read this console's line endings.
	 *
	 * <p>Set whenever the connection changes, because the consoles genuinely disagree - see
	 * {@link TerminalFilter}.</p>
	 */
	void setProfile(TerminalProfile profile);

	/** Something arrived, or the application has something to say. Safe from any thread. */
	void append(String text, TerminalStyle style);

	void clear();

	/**
	 * Where what the user types goes.
	 *
	 * <p>Called on the event thread. The listener is expected to hand it straight to the
	 * connection, which queues it on the command thread - the terminal itself must not block.</p>
	 */
	void setInputListener(Consumer<String> listener);

	/** Whether typing does anything. False when there is nothing connected. */
	void setInputEnabled(boolean enabled);
}
