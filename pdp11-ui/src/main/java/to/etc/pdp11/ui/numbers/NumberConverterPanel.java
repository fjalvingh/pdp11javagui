package to.etc.pdp11.ui.numbers;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.util.NumberConverter;
import to.etc.pdp11.core.util.NumberConverter.Base;
import to.etc.pdp11.ui.UiColors;

import javax.swing.AbstractAction;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * One number in octal, decimal, hex and binary at once: type into any of them and the rest
 * follow.
 *
 * <p>Ported from {@code TFormNumberConverter} ({@code FormNumberconverterU.pas}). The
 * conversions are {@link NumberConverter}, in the core; what is here is three fields that update
 * each other without doing so forever, and the two binary lines that make an octal number and a
 * hex one legible at the same time.</p>
 *
 * <h2>What is different</h2>
 *
 * <p><b>A width, rather than always 32 bits.</b> The Pascal's value is a {@code Dword}, so every
 * word you look at comes with sixteen leading zeros and a binary line twice as long as the thing
 * you are reading. The width defaults to 16 - a PDP-11 word - and the selector has the address
 * widths as well.</p>
 *
 * <p><b>Overflow refuses the keystroke</b> instead of deleting the leading digit. The original
 * responds to a value that no longer fits by dropping the number's most significant digit
 * ({@code :312-313}), so typing one digit too many silently changes the number to something else
 * entirely rather than declining to change it.</p>
 *
 * <p><b>A signed reading</b>, which the original does not show. {@code 177777} is {@code -1} and
 * having to work that out by hand is exactly the sort of thing this window exists to stop.</p>
 */
public final class NumberConverterPanel extends JPanel {
	/** What the window starts on: the width of a PDP-11 word. */
	public static final int DEFAULT_BITS = 16;

	private final JComboBox<Integer> m_width = new JComboBox<>();

	private final Map<Base, JTextField> m_fields = new EnumMap<>(Base.class);

	private final Map<Base, JLabel> m_binary = new EnumMap<>(Base.class);

	private final JLabel m_signed = new JLabel();

	private final JLabel m_note = new JLabel();

	/** The value every field is showing. */
	private long m_value;

	/** True while the fields are being rewritten, so their listeners do not rewrite each other. */
	private boolean m_updating;

	public NumberConverterPanel() {
		super(new MigLayout("insets 8, fillx", "[70!][grow,fill][]", "[]12[]4[]8[]4[]8[]8[]"));
		buildWidthRow();
		//-- Octal first: it is the base everything else in this application is written in.
		buildBase(Base.OCTAL, KeyEvent.VK_O);
		buildBase(Base.HEX, KeyEvent.VK_H);
		buildBase(Base.DECIMAL, KeyEvent.VK_D);
		buildSignedRow();
		installShortcuts();
		setValue(0);
	}

	private void buildWidthRow() {
		for(int bits : NumberConverter.WIDTHS) {
			m_width.addItem(bits);
		}
		m_width.setSelectedItem(DEFAULT_BITS);
		m_width.setRenderer(new WidthRenderer());
		m_width.addActionListener(e -> widthChanged());
		add(new JLabel("Width"));
		add(m_width, "growx 0, wrap");
		m_note.setForeground(UiColors.SECONDARY_TEXT);
	}

	private void buildBase(Base base, int mnemonic) {
		JTextField field = new JTextField();
		field.setFont(monospaced(15));
		//-- The filter is what keeps a field holding only numbers in its own base and only
		//-- values that fit: it sees pasted text as well as typing, which the original's
		//-- key-press filter does not.
		((javax.swing.text.AbstractDocument) field.getDocument())
			.setDocumentFilter(new NumberDocumentFilter(base, this::bits));
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				typed(base);
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				typed(base);
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				typed(base);
			}
		});
		//-- Which field the caret is in is worth seeing at a glance, as it is in the original.
		field.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				highlight(base);
			}
		});
		JLabel label = new JLabel(base.getLabel());
		label.setDisplayedMnemonic(mnemonic);
		label.setLabelFor(field);

		JLabel binary = new JLabel();
		binary.setFont(monospaced(13));
		binary.setForeground(UiColors.SECONDARY_TEXT);

		m_fields.put(base, field);
		m_binary.put(base, binary);
		add(label);
		add(field, "growx, wrap");
		//-- The binary line sits under the field it belongs to, grouped so that its columns line
		//-- up with that base's digits: fours under hex, threes under octal.
		add(new JLabel(""));
		add(binary, "growx, wrap");
	}

	private void buildSignedRow() {
		JLabel label = new JLabel("Signed");
		label.setToolTipText("The same bits read as a two's complement number of the chosen width");
		m_signed.setFont(monospaced(13));
		add(label);
		add(m_signed, "growx, wrap");
		add(m_note, "span 2, growx");
	}

	private static Font monospaced(int size) {
		return new Font(Font.MONOSPACED, Font.PLAIN, size);
	}

	/**
	 * Alt-O, Alt-H, Alt-D, and Escape to clear.
	 *
	 * <p>The same keys the original binds ({@code FormKeyDown}, {@code :246-271}), on the panel's
	 * WHEN_IN_FOCUSED_WINDOW map so they work whichever field has the caret. The label mnemonics
	 * would do it on their own on most platforms; these make it certain.</p>
	 */
	private void installShortcuts() {
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.ALT_DOWN_MASK), "focus-octal",
			() -> m_fields.get(Base.OCTAL).requestFocusInWindow());
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_H, KeyEvent.ALT_DOWN_MASK), "focus-hex",
			() -> m_fields.get(Base.HEX).requestFocusInWindow());
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.ALT_DOWN_MASK), "focus-decimal",
			() -> m_fields.get(Base.DECIMAL).requestFocusInWindow());
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear", () -> setValue(0));
	}

	private void bind(KeyStroke stroke, String name, Runnable action) {
		getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
		getActionMap().put(name, new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}

	// -------------------------------------------------------------------------------------
	// The value
	// -------------------------------------------------------------------------------------

	public long getValue() {
		return m_value;
	}

	/** The width being shown, in bits. */
	public int bits() {
		Integer b = (Integer) m_width.getSelectedItem();
		return b == null ? DEFAULT_BITS : b;
	}

	/** Put a value in, and write it into every field. */
	public void setValue(long value) {
		m_value = value & NumberConverter.mask(bits());
		writeFields(null);
	}

	/** One field changed: read it, and rewrite the others. */
	private void typed(Base source) {
		if(m_updating)
			return;
		String text = m_fields.get(source).getText();
		try {
			m_value = NumberConverter.parse(source, text, bits());
			m_note.setText("");
		} catch(NumberFormatException x) {
			//-- The filter refuses anything that does not fit, so this is only reachable if one
			//-- ever gets past it. Saying so beats showing a number that is not what was typed.
			m_note.setText(x.getMessage());
			m_note.setForeground(UiColors.ERROR_TEXT);
			return;
		}
		writeFields(source);
	}

	/**
	 * Write the value into every field except the one being typed in.
	 *
	 * <p>Skipping the source is what the original's {@code updateEdits(value, sourceEdit)} does,
	 * and it is not only about avoiding a loop: rewriting the field under the caret moves the
	 * caret, so typing {@code 1} then {@code 2} would leave {@code 21}.</p>
	 */
	private void writeFields(Base source) {
		m_updating = true;
		try {
			for(Base base : Base.values()) {
				if(base != source)
					m_fields.get(base).setText(NumberConverter.format(base, m_value));
				m_binary.get(base).setText(NumberConverter.binary(base, m_value, bits()));
			}
			//-- The number alone: the octal it came from is in the field two rows above, and
			//-- repeating it here only makes the row harder to read.
			long signed = NumberConverter.signed(m_value, bits());
			m_signed.setText(String.valueOf(signed));
			m_signed.setForeground(signed < 0 ? UiColors.EDITED_TEXT : UiColors.SECONDARY_TEXT);
		} finally {
			m_updating = false;
		}
	}

	/**
	 * A narrower width was chosen and the value does not fit it.
	 *
	 * <p>Truncated to the new width rather than refused: the width is a way of looking at a
	 * number, and the bits that fall off are the ones the machine would drop too. It says so,
	 * because a number quietly changing while you watch is worse than either.</p>
	 */
	private void widthChanged() {
		long before = m_value;
		long after = before & NumberConverter.mask(bits());
		m_value = after;
		writeFields(null);
		if(after != before) {
			m_note.setText("Truncated to " + bits() + " bits: "
				+ NumberConverter.formatPadded(Base.OCTAL, before, 32) + " octal no longer fits");
			m_note.setForeground(UiColors.SECONDARY_TEXT);
		} else {
			m_note.setText("");
		}
	}

	private void highlight(Base focused) {
		for(Map.Entry<Base, JLabel> e : m_binary.entrySet()) {
			e.getValue().setForeground(e.getKey() == focused ? UiColors.EDITED_TEXT : UiColors.SECONDARY_TEXT);
		}
	}

	// -------------------------------------------------------------------------------------
	// For tests
	// -------------------------------------------------------------------------------------

	public JTextField getField(Base base) {
		return m_fields.get(base);
	}

	public String getBinaryText(Base base) {
		return m_binary.get(base).getText();
	}

	public String getSignedText() {
		return m_signed.getText();
	}

	public String getNoteText() {
		return m_note.getText();
	}

	public JComboBox<Integer> getWidthSelector() {
		return m_width;
	}

	/** "16 bits", not "16". */
	private static final class WidthRenderer extends javax.swing.DefaultListCellRenderer {
		@Override
		public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
			int index, boolean selected, boolean focused) {
			super.getListCellRendererComponent(list, value, index, selected, focused);
			setText(value + " bits");
			return this;
		}
	}
}
