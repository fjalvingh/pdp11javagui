package to.etc.pdp11.ui.terminal;

import to.etc.pdp11.core.console.TerminalProfile;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
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

	private boolean m_inputEnabled;

	public GlassTerminalView() {
		m_pane.setEditable(false);
		m_pane.setBackground(new Color(0x12, 0x12, 0x14));
		m_pane.setCaretColor(new Color(0xE0, 0xE0, 0xE0));
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

		m_pdpStyle = style(new Color(0xD8, 0xD8, 0xD8));
		m_userStyle = style(new Color(0x7F, 0xC7, 0xFF));
		m_systemStyle = style(new Color(0xB0, 0x90, 0x50));
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
			//-- Split on the erase marker rather than scanning per character: an erase is rare
			//-- and everything between two of them is one insert.
			int from = 0;
			while(from < filtered.length()) {
				int erase = filtered.indexOf(TerminalFilter.ERASE, from);
				String chunk = erase < 0 ? filtered.substring(from) : filtered.substring(from, erase);
				if(!chunk.isEmpty())
					doc.insertString(doc.getLength(), chunk, attributes);
				if(erase < 0)
					break;
				if(doc.getLength() > 0)
					doc.remove(doc.getLength() - 1, 1);
				from = erase + 1;
			}
			trim(doc);
		} catch(BadLocationException x) {
			//-- The only length used is the document's own, so this cannot happen; if it somehow
			//-- does, losing a line of terminal output is not worth stopping anything for.
		}
		m_pane.setCaretPosition(doc.getLength());
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
