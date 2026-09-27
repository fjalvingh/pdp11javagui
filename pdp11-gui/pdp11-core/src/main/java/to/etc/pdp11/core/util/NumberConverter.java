package to.etc.pdp11.core.util;

import java.util.Locale;

/**
 * One number in every base a PDP-11 person needs it in, at a chosen width.
 *
 * <p>The arithmetic behind the Number Converter window, taken out of it. In the Pascal all of
 * this - digit validity, parsing, binary grouping, the overflow rule - is loose functions and
 * event handlers inside {@code FormNumberconverterU.pas}, where the only way to check the
 * 32-bit binary grouping is to type into it and count.</p>
 *
 * <h2>Width</h2>
 *
 * <p>The Pascal is fixed at 32 bits, because its value is a {@code Dword}. Nothing on a PDP-11
 * is 32 bits: a word is 16, an address is 16, 18 or 22, and 32 bits of leading zeros in front
 * of a register value is noise you have to read past. So the width is a parameter here, and
 * every operation that could overflow takes it.</p>
 */
public final class NumberConverter {
	/** The bases the window offers, in the order it shows them. */
	public enum Base {
		OCTAL(8, "Octal", 3),
		DECIMAL(10, "Decimal", 0),
		HEX(16, "Hex", 4);

		private final int m_radix;

		private final String m_label;

		/** How many bits one digit of this base is worth, or 0 for a base that is not a power of two. */
		private final int m_bitsPerDigit;

		Base(int radix, String label, int bitsPerDigit) {
			m_radix = radix;
			m_label = label;
			m_bitsPerDigit = bitsPerDigit;
		}

		public int getRadix() {
			return m_radix;
		}

		public String getLabel() {
			return m_label;
		}

		/** Whether a binary display grouped this way lines up digit for digit with this base. */
		public boolean groupsBinary() {
			return m_bitsPerDigit > 0;
		}

		public int getBitsPerDigit() {
			return m_bitsPerDigit;
		}
	}

	/** The widths worth offering: a byte, a word, the three address widths, and the Pascal's dword. */
	public static final int[] WIDTHS = {8, 16, 18, 22, 32};

	public static final int MAX_BITS = 63;

	private NumberConverter() {
	}

	/** All ones, at this width. */
	public static long mask(int bits) {
		checkBits(bits);
		return bits == 64 ? -1L : (1L << bits) - 1;
	}

	public static boolean fits(long value, int bits) {
		return value >= 0 && value <= mask(bits);
	}

	private static void checkBits(int bits) {
		if(bits < 1 || bits > MAX_BITS)
			throw new IllegalArgumentException("bits must be 1.." + MAX_BITS + ", got " + bits);
	}

	// -------------------------------------------------------------------------------------
	// Digits
	// -------------------------------------------------------------------------------------

	/** Whether this character is a digit in this base. Hex takes either case. */
	public static boolean isValidDigit(Base base, char c) {
		return Character.digit(c, base.getRadix()) >= 0;
	}

	/**
	 * Everything in the text that is a digit in this base, in order.
	 *
	 * <p>The Pascal has this and does not use it: {@code stripInvalidDigits} is commented out at
	 * both call sites ({@code :263-269}), which is why pasting {@code "1,234"} into its decimal
	 * field raises inside {@code StrToInt64}.</p>
	 */
	public static String stripInvalidDigits(Base base, String text) {
		if(text == null)
			return "";
		StringBuilder sb = new StringBuilder(text.length());
		for(int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if(isValidDigit(base, c))
				sb.append(c);
		}
		return sb.toString();
	}

	// -------------------------------------------------------------------------------------
	// Parsing and formatting
	// -------------------------------------------------------------------------------------

	/**
	 * Read a number, as typed.
	 *
	 * <p>Empty is zero, not an error: a field the user has just emptied is on its way to being
	 * something else, and it would be rude to complain in the meantime. Anything else that is
	 * not a run of digits in this base is an error, and so is a value too wide for {@code bits}
	 * - which is the whole reason this takes a width.</p>
	 *
	 * @throws NumberFormatException if the text is not a number in this base, or does not fit
	 */
	public static long parse(Base base, String text, int bits) {
		checkBits(bits);
		String s = text == null ? "" : text.strip();
		if(s.isEmpty())
			return 0;
		for(int i = 0; i < s.length(); i++) {
			if(!isValidDigit(base, s.charAt(i)))
				throw new NumberFormatException("'" + text + "' is not " + base.getLabel().toLowerCase(Locale.ROOT)
					+ ": bad character '" + s.charAt(i) + "'");
		}
		long value;
		try {
			//-- Long, not Integer: 32 bits of ones is a perfectly ordinary value here and it does
			//-- not fit a signed int. Above 63 bits this throws, which is also an overflow.
			value = Long.parseLong(s, base.getRadix());
		} catch(NumberFormatException x) {
			throw new NumberFormatException("'" + text + "' does not fit in " + bits + " bits");
		}
		if(!fits(value, bits))
			throw new NumberFormatException("'" + text + "' does not fit in " + bits + " bits");
		return value;
	}

	/** Write a number, with no padding - what somebody would type. */
	public static String format(Base base, long value) {
		return Long.toString(value, base.getRadix()).toUpperCase(Locale.ROOT);
	}

	/**
	 * Write a number padded to the full width, which is how a PDP-11 prints one.
	 *
	 * <p>{@code 0000000123} rather than {@code 123}: leading zeros are how an address is read at
	 * a glance, and every console prints them.</p>
	 */
	public static String formatPadded(Base base, long value, int bits) {
		String s = format(base, value);
		int digits = digitsFor(base, bits);
		return s.length() >= digits ? s : "0".repeat(digits - s.length()) + s;
	}

	/** How many digits of this base a value of this width can need. */
	public static int digitsFor(Base base, int bits) {
		checkBits(bits);
		return switch(base) {
			case OCTAL -> (bits + 2) / 3;
			case HEX -> (bits + 3) / 4;
			//-- Decimal has no clean rule; the length of the largest value there is.
			case DECIMAL -> Long.toString(mask(bits)).length();
		};
	}

	// -------------------------------------------------------------------------------------
	// Binary
	// -------------------------------------------------------------------------------------

	/**
	 * The value in binary, zero-padded to the width and split into groups from the right.
	 *
	 * <p>Grouped from the <b>right</b>, so the groups line up with the digits of the base that
	 * has that many bits: fours under a hex number, threes under an octal one. A width that is
	 * not a multiple of the group leaves the leftmost group short, which is correct - 16 bits in
	 * threes is {@code "1 111 111 111 111 111"}, and its first group really is one bit, matching
	 * the leading {@code 1} of {@code 177777}.</p>
	 *
	 * @param group how many bits per group, or 0 for no grouping
	 */
	public static String binary(long value, int bits, int group) {
		checkBits(bits);
		if(group < 0)
			throw new IllegalArgumentException("group must be >= 0, got " + group);
		String raw = Long.toBinaryString(value & mask(bits));
		if(raw.length() < bits)
			raw = "0".repeat(bits - raw.length()) + raw;
		if(group == 0)
			return raw;
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < raw.length(); i++) {
			//-- A space wherever the distance to the right-hand end is a multiple of the group.
			if(i > 0 && (raw.length() - i) % group == 0)
				sb.append(' ');
			sb.append(raw.charAt(i));
		}
		return sb.toString();
	}

	/** The binary display that lines up with this base, or the ungrouped one for decimal. */
	public static String binary(Base base, long value, int bits) {
		return binary(value, bits, base.groupsBinary() ? base.getBitsPerDigit() : 0);
	}

	// -------------------------------------------------------------------------------------
	// Signed
	// -------------------------------------------------------------------------------------

	/**
	 * The same bits read as a two's complement signed number of this width.
	 *
	 * <p>Not in the Pascal, and the omission shows every time somebody looks at {@code 177777}
	 * and has to work out that it is {@code -1}. A word holding a small negative number is the
	 * commonest thing there is on this machine.</p>
	 */
	public static long signed(long value, int bits) {
		checkBits(bits);
		long masked = value & mask(bits);
		long signBit = 1L << (bits - 1);
		return (masked & signBit) != 0 ? masked - (signBit << 1) : masked;
	}
}
