package to.etc.pdp11.core.util;

/**
 * Octal formatting and parsing.
 *
 * <p>The PDP-11 world is octal throughout: addresses, register contents, memory words and
 * everything the consoles print or accept. Every user-facing number in this application is
 * octal unless something says otherwise, so this class is deliberately the only place that
 * knows how the conversion is spelled.</p>
 *
 * <p>Values are treated as unsigned. Java has no unsigned integer type, and every relational
 * comparison on a PDP-11 word or address held in a signed {@code int} is a latent bug, so the
 * parse methods return {@code long} and the format methods take {@code long}. See PLAN.md
 * section 2 for the wider story: the Pascal original uses {@code $ffffffff} as an
 * "unknown" sentinel, which is {@code -1} as a signed Java {@code int}.</p>
 */
public final class Octal {
	private Octal() {
	}

	/**
	 * Format as octal, zero-padded to the given number of digits.
	 *
	 * @param value  the value, treated as unsigned
	 * @param digits the minimum width; longer values are not truncated
	 */
	public static String format(long value, int digits) {
		if(digits < 1)
			throw new IllegalArgumentException("digits must be >= 1, got " + digits);
		String s = Long.toOctalString(value);
		if(s.length() >= digits)
			return s;
		return "0".repeat(digits - s.length()) + s;
	}

	/**
	 * Format a 16-bit word as six octal digits, the way a PDP-11 console prints one.
	 */
	public static String word(int value) {
		return format(value & 0xFFFF, 6);
	}

	/**
	 * Parse an octal number. Leading and trailing whitespace is ignored; an empty or
	 * non-octal string is rejected.
	 *
	 * <p>Note this accepts a leading {@code 0} without treating it specially - there is no
	 * "0 means octal" convention here, because everything is octal already.</p>
	 *
	 * @throws NumberFormatException if the text is not a non-empty run of octal digits, or
	 *                               does not fit in 64 bits
	 */
	public static long parse(String text) {
		if(text == null)
			throw new NumberFormatException("null is not an octal number");
		String s = text.strip();
		if(s.isEmpty())
			throw new NumberFormatException("empty string is not an octal number");
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if(c < '0' || c > '7')
				throw new NumberFormatException("'" + text + "' is not octal: bad character '" + c + "'");
		}
		return Long.parseLong(s, 8);
	}

	/**
	 * Parse an octal number, returning {@code defaultValue} when the text is not one. For
	 * places that must tolerate whatever the user typed, like a live-updating input field.
	 */
	public static long parseOr(String text, long defaultValue) {
		try {
			return parse(text);
		} catch(NumberFormatException x) {
			return defaultValue;
		}
	}
}
