package to.etc.pdp11.ui.mem;

import javax.swing.DefaultCellEditor;
import javax.swing.JTextField;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * A table cell that takes octal digits and nothing else.
 *
 * <p>Ported from the two identical {@code MemoryCellsStringGridKeyPress} handlers
 * ({@code FrameMemoryCellGroupGridU.pas:331-355} and
 * {@code FrameMemoryCellGroupListU.pas:168-189}) - minus their hand-rolled {@code ^C} and
 * {@code ^V}, because a {@code JTextField} already has a working clipboard, and that is most of
 * what those handlers were.</p>
 */
final class OctalCellEditor extends DefaultCellEditor {
	OctalCellEditor() {
		super(new JTextField());
		JTextField field = (JTextField) getComponent();
		field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, field.getFont().getSize()));
		field.addKeyListener(new KeyAdapter() {
			@Override
			public void keyTyped(KeyEvent e) {
				char c = e.getKeyChar();
				if(c >= '0' && c <= '7')
					return;
				if(c == KeyEvent.VK_BACK_SPACE || c == KeyEvent.VK_DELETE || e.isControlDown())
					return;
				e.consume();
			}
		});
		//-- One click starts editing: a memory editor that wants a double-click per word is
		//-- tiring within about a minute.
		setClickCountToStart(1);
	}
}
