package to.etc.pdp11.ui;

import javax.swing.JLabel;
import java.awt.Color;

/**
 * A window's status line, and the one place a typed-in value that cannot be used is reported.
 *
 * <h2>Why not a dialog</h2>
 *
 * <p>The argument is the Assembler's, which has always said it in its own source: a compile
 * error is shown in the status line rather than in a modal dialog, because a dialog is "one
 * keystroke of penance per typo". Every other window disagreed - a mistyped octal address in
 * Memory, Disassembler, Execution, Dumper, Loader, Memory Test or Bitfields put up a modal
 * {@code JOptionPane} titled "PDP11GUI" that had to be dismissed before anything could be
 * retyped. Typing an address wrong is not an exceptional condition; it is what typing is like,
 * and it happens most in exactly the window where you are typing fastest.</p>
 *
 * <p>So field-level validation says what is wrong where the window already says what it is
 * doing, and {@link AppContext#reportFailure} keeps the dialog for what it is for: something
 * that went wrong out in the world - a command the machine refused, a file that would not be
 * read - which the user did not cause and cannot see the result of.</p>
 *
 * <h2>How it clears</h2>
 *
 * <p>An error stays put until the window next says something about itself, which is the moment
 * something worked. Nothing has to remember to clear it: every panel already rewrites its status
 * line after any action that changes what is displayed, and that write goes through
 * {@link #setText}.</p>
 */
public final class FieldStatus {
	private final JLabel m_label;

	/** Whatever colour the panel gave the label, kept so the ordinary text goes back to it. */
	private final Color m_normalColour;

	private boolean m_showingError;

	/** For a label that keeps whatever colour the theme gives it. */
	public FieldStatus(JLabel label) {
		this(label, label.getForeground());
	}

	/**
	 * For a label with a colour of its own - usually {@link UiColors#SECONDARY_TEXT}, which is
	 * what a status line under a window's controls is.
	 *
	 * <p>The colour is given here rather than read off the label, because a field initialiser
	 * runs before the constructor body that would have set it: reading it would have captured
	 * the default and quietly restored the wrong colour after every cleared error.</p>
	 */
	public FieldStatus(JLabel label, Color normalColour) {
		m_label = label;
		m_normalColour = normalColour;
		label.setForeground(normalColour);
	}

	/** What the window has to say about itself. Replaces an error if one is showing. */
	public void setText(String text) {
		setText(text, m_normalColour);
	}

	/**
	 * The same, in a colour the window chose - a result that passed or failed.
	 *
	 * <p>Distinct from {@link #error}, which is always about a value the user typed. This one is
	 * an outcome: "8192 words written", "Test of the low 4K: failed". Both can be red and they
	 * mean different things, so they are different calls.</p>
	 */
	public void setText(String text, Color colour) {
		m_showingError = false;
		m_label.setForeground(colour);
		m_label.setText(text == null || text.isEmpty() ? " " : text);
	}

	/**
	 * Something the user typed cannot be used.
	 *
	 * <p>Phrased as what is wrong with the value, not as an apology and not as a command: the
	 * user is looking at the field they just typed into.</p>
	 */
	public void error(String message) {
		m_showingError = true;
		m_label.setForeground(UiColors.ERROR_TEXT);
		m_label.setText(message);
	}

	/** Whether an error is showing, for a test and for a panel that wants to know. */
	public boolean hasError() {
		return m_showingError;
	}

	/** What the line says, for a test. */
	public String getText() {
		return m_label.getText();
	}

	/** The label itself, for the panel that owns it to lay out. */
	public JLabel getLabel() {
		return m_label;
	}
}
