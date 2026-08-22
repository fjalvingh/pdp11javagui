package to.etc.pdp11.ui.numbers;

import to.etc.pdp11.core.util.NumberConverter;
import to.etc.pdp11.core.util.NumberConverter.Base;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.util.function.IntSupplier;

/**
 * Keeps a field holding only a number in its own base that fits the chosen width.
 *
 * <p>A filter rather than a key listener, because a field takes text from more places than the
 * keyboard: a paste, a drag, or a programmatic {@code setText} all arrive here and none of them
 * arrive at {@code keyTyped}. The Pascal filters key presses ({@code FormKeyPress},
 * {@code :275-291}) and has a {@code stripInvalidDigits} for the paste case which it never
 * calls - both call sites are commented out - so pasting {@code "1,234"} into its decimal field
 * raises an exception out of {@code StrToInt64}.</p>
 *
 * <p>What does not fit is refused, leaving the field as it was. The original instead deletes the
 * number's leading digit to make room ({@code :312-313}), which turns one digit too many into a
 * different number rather than into nothing happening.</p>
 */
final class NumberDocumentFilter extends DocumentFilter {
	private final Base m_base;

	/** The width to check against, read when needed - the window's selector can change it. */
	private final IntSupplier m_bits;

	NumberDocumentFilter(Base base, IntSupplier bits) {
		m_base = base;
		m_bits = bits;
	}

	@Override
	public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
		throws BadLocationException {
		replace(fb, offset, 0, text, attr);
	}

	@Override
	public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attr)
		throws BadLocationException {
		String cleaned = NumberConverter.stripInvalidDigits(m_base, text);
		if(!cleaned.isEmpty() || (text != null && text.isEmpty())) {
			String current = fb.getDocument().getText(0, fb.getDocument().getLength());
			String proposed = current.substring(0, offset) + cleaned + current.substring(offset + length);
			if(!fits(proposed))
				return;
			fb.replace(offset, length, cleaned, attr);
		}
	}

	@Override
	public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
		//-- Deleting can only make a number smaller, so it never needs checking.
		fb.remove(offset, length);
	}

	private boolean fits(String proposed) {
		try {
			NumberConverter.parse(m_base, proposed, m_bits.getAsInt());
			return true;
		} catch(NumberFormatException x) {
			return false;
		}
	}
}
