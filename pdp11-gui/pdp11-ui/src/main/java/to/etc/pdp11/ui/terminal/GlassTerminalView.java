package to.etc.pdp11.ui.terminal;

import to.etc.pdp11.core.console.TerminalProfile;
import to.etc.pdp11.ui.UiColors;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * A glass teletype: the terminal, without an emulator behind it.
 *
 * <p>The fallback PLAN.md §3 describes, and the one this ships with. Every console here is a dumb
 * TTY - full ANSI matters only for programs <i>running on</i> the PDP-11, and none of the console
 * protocols emit escape sequences at all. What they do emit is line endings they disagree about,
 * and that is handled in front of this by {@link TerminalFilter}.</p>
 *
 * <p>The scrollback is bounded and the pane is not editable: characters go in through
 * {@link #append} and come out through the key listener, which is the shape of a terminal rather
 * than of a text editor. Typing is not echoed locally - the machine echoes, and echoing here as
 * well would double every character.</p>
 */
public final class GlassTerminalView implements TerminalView {
	/** Beyond this the oldest is dropped. Enough for a long session, bounded enough not to grow forever. */
	public static final int MAX_CHARACTERS = 400_000;

	/** How much to drop when the limit is reached, so trimming is rare rather than per character. */
	private static final int TRIM_CHUNK = 50_000;

	private final JTextPane m_pane = new JTextPane();

	private final JScrollPane m_scroll = new JScrollPane(m_pane);

	private final TerminalFilter m_filter = new TerminalFilter(TerminalProfile.of(true, true));

	private final AttributeSet m_pdpStyle;

	private final AttributeSet m_userStyle;

	private final AttributeSet m_systemStyle;

	private Consumer<String> m_inputListener = s -> {
	};

	/** How long the background stays flashed after a bell. Long enough to catch the eye, no more. */
	private static final int BELL_FLASH_MS = 150;

	/** Puts the background back after a bell. Restarted by each one, so a burst is one flash. */
	private final Timer m_bellFlash = new Timer(BELL_FLASH_MS, e -> showBackground(UiColors.TERMINAL_BACKGROUND));

	private Runnable m_bellListener = () -> {
	};

	private int m_bellCount;

	private boolean m_inputEnabled;

	public GlassTerminalView() {
		m_pane.setEditable(false);
		m_pane.setBackground(UiColors.TERMINAL_BACKGROUND);
		m_pane.setCaretColor(UiColors.TERMINAL_CARET);
		m_pane.setFont(monospaced());
		//-- A terminal is not a text editor: it has to take keystrokes while being uneditable.
		m_pane.setFocusable(true);
		m_pane.addKeyListener(new KeyAdapter() {
			@Override
			public void keyTyped(KeyEvent e) {
				onKeyTyped(e);
			}

			@Override
			public void keyPressed(KeyEvent e) {
				onKeyPressed(e);
			}
		});
		//-- No border. The terminal is the main window's content rather than a widget on a
		//-- form, and a themed scroll pane border here is both wrong-looking and, because
		//-- FlatLaf reports visual padding for it, laid out two pixels outside the panel on
		//-- every side - so the border it draws is clipped away anyway.
		m_scroll.setBorder(BorderFactory.createEmptyBorder());
		m_scroll.setViewportBorder(BorderFactory.createEmptyBorder());
		m_scroll.getViewport().setBackground(m_pane.getBackground());
		m_bellFlash.setRepeats(false);

		m_pdpStyle = style(UiColors.TERMINAL_PDP_TEXT);
		m_userStyle = style(UiColors.TERMINAL_USER_TEXT);
		m_systemStyle = style(UiColors.TERMINAL_SYSTEM_TEXT);
	}

	private static Font monospaced() {
		//-- Font.MONOSPACED is a logical name the platform maps to whatever it has, which is the
		//-- right thing to ask for: any real monospaced font will do and none of them are
		//-- guaranteed to be installed.
		return new Font(Font.MONOSPACED, Font.PLAIN, 13);
	}

	private static AttributeSet style(Color colour) {
		SimpleAttributeSet a = new SimpleAttributeSet();
		StyleConstants.setForeground(a, colour);
		return a;
	}

	@Override
	public JComponent getComponent() {
		return m_scroll;
	}

	@Override
	public void setProfile(TerminalProfile profile) {
		m_filter.setProfile(profile);
	}

	@Override
	public void setInputListener(Consumer<String> listener) {
		m_inputListener = listener == null ? s -> {
		} : listener;
	}

	@Override
	public void setInputEnabled(boolean enabled) {
		m_inputEnabled = enabled;
	}

	@Override
	public void append(String text, TerminalStyle style) {
		if(text == null || text.isEmpty())
			return;
		//-- Called from the reader thread for everything the machine says, so it marshals rather
		//-- than asking every caller to remember to.
		if(SwingUtilities.isEventDispatchThread())
			appendOnEdt(text, style);
		else
			SwingUtilities.invokeLater(() -> appendOnEdt(text, style));
	}

	private void appendOnEdt(String text, TerminalStyle style) {
		//-- Only the machine's own output goes through the filter. What the application says is
		//-- already text, and what the user typed is echoed by the machine, not by us.
		String filtered = style == TerminalStyle.PDP ? m_filter.filter(text) : text;
		if(filtered.isEmpty())
			return;
		StyledDocument doc = m_pane.getStyledDocument();
		AttributeSet attributes = switch(style) {
			case PDP -> m_pdpStyle;
			case USER -> m_userStyle;
			case SYSTEM -> m_systemStyle;
		};
		try {
			//-- Split on the markers rather than scanning per character: an erase or a bell is rare
			//-- and everything between two of them is one insert.
			int from = 0;
			while(from < filtered.length()) {
				int mark = nextMarker(filtered, from);
				String chunk = mark < 0 ? filtered.substring(from) : filtered.substring(from, mark);
				if(!chunk.isEmpty())
					doc.insertString(doc.getLength(), chunk, attributes);
				if(mark < 0)
					break;
				if(filtered.charAt(mark) == TerminalFilter.BELL)
					ring();
				else if(doc.getLength() > 0)
					doc.remove(doc.getLength() - 1, 1);
				from = mark + 1;
			}
			trim(doc);
		} catch(BadLocationException x) {
			//-- The only length used is the document's own, so this cannot happen; if it somehow
			//-- does, losing a line of terminal output is not worth stopping anything for.
		}
		m_pane.setCaretPosition(doc.getLength());
	}

	private static int nextMarker(String s, int from) {
		for(int i = from; i < s.length(); i++) {
			char c = s.charAt(i);
			if(c == TerminalFilter.ERASE || c == TerminalFilter.BELL)
				return i;
		}
		return -1;
	}

	/**
	 * The machine rang its bell: flash, count it, and say so.
	 *
	 * <p>A visual bell rather than a beep. XXDP rings once per completed pass, and what somebody
	 * running a diagnostic wants to know is that passes are still completing - which a glance at a
	 * flash, or at the count the listener shows, tells them and a beep from a room away does
	 * not.</p>
	 */
	private void ring() {
		m_bellCount++;
		showBackground(UiColors.TERMINAL_BELL_FLASH);
		m_bellFlash.restart();
		m_bellListener.run();
	}

	private void showBackground(Color colour) {
		m_pane.setBackground(colour);
		m_scroll.getViewport().setBackground(colour);
	}

	/** Told on the event thread each time the machine rings its bell. */
	public void setBellListener(Runnable listener) {
		m_bellListener = listener == null ? () -> {
		} : listener;
	}

	/** How many times the bell has rung since this was made. */
	public int getBellCount() {
		return m_bellCount;
	}

	/** Whether the background is flashed right now, for a test. */
	public boolean isBellFlashing() {
		return !UiColors.TERMINAL_BACKGROUND.equals(m_pane.getBackground());
	}

	private static void trim(StyledDocument doc) throws BadLocationException {
		if(doc.getLength() <= MAX_CHARACTERS)
			return;
		doc.remove(0, TRIM_CHUNK);
	}

	@Override
	public void clear() {
		if(SwingUtilities.isEventDispatchThread())
			clearOnEdt();
		else
			SwingUtilities.invokeLater(this::clearOnEdt);
	}

	private void clearOnEdt() {
		m_pane.setText("");
		m_filter.reset();
	}

	/**
	 * Everything on screen, for a test that wants to know what was shown.
	 *
	 * <p>Read from the document, not with {@code JTextPane.getText()}: that goes through the
	 * editor kit, which writes the platform's line separator, so on Windows every line came back
	 * ending in CR LF.</p>
	 */
	public String getText() {
		Document doc = m_pane.getDocument();
		try {
			return doc.getText(0, doc.getLength());
		} catch(BadLocationException x) {
			throw new IllegalStateException(x);
		}
	}

	/** Ask for the keyboard. The main window does this when it opens. */
	public void focusTerminal() {
		m_pane.requestFocusInWindow();
	}

	// -------------------------------------------------------------------------------------
	// Typing
	// -------------------------------------------------------------------------------------

	private void onKeyTyped(KeyEvent e) {
		if(!m_inputEnabled)
			return;
		char c = e.getKeyChar();
		if(c == KeyEvent.CHAR_UNDEFINED)
			return;
		//-- Enter arrives as \n from keyTyped, and every one of these consoles wants a CR.
		if(c == '\n')
			c = '\r';
		//-- Above 7 bits is not something any of these machines can receive; the line is 7-bit
		//-- and the console masks accordingly, so sending it would only confuse the transcript.
		if(c > 0x7F)
			return;
		m_inputListener.accept(String.valueOf(c));
		e.consume();
	}

	/**
	 * The keys that never reach {@code keyTyped}.
	 *
	 * <p>Control characters are how half of these protocols are driven by hand - {@code ^C} wakes
	 * an 11/44, {@code ^P} gets its attention, {@code ^E} halts SimH - so a terminal that cannot
	 * send them is a terminal that cannot do what the Pascal's could.</p>
	 */
	private void onKeyPressed(KeyEvent e) {
		if(!m_inputEnabled)
			return;
		if(!e.isControlDown() || e.isAltDown() || e.isMetaDown())
			return;
		int code = e.getKeyCode();
		if(code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
			m_inputListener.accept(String.valueOf((char) (code - KeyEvent.VK_A + 1)));
			e.consume();
		}
	}
}
