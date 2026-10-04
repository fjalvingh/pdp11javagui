package to.etc.pdp11.common.macro11.asm;

import java.util.OptionalInt;

/**
 * RAD50: three characters from a 40-character set in one 16-bit word.
 *
 * <p>The set is blank, {@code A}-{@code Z}, {@code $}, {@code .}, an unused code, and
 * {@code 0}-{@code 9}; a word is {@code c1*050*050 + c2*050 + c3}. A string shorter than a
 * multiple of three is padded with blanks.</p>
 */
final class Rad50 {
	private static final String CHARACTERS = " ABCDEFGHIJKLMNOPQRSTUVWXYZ$.?0123456789";

	/** The code that does not stand for a character. */
	private static final int UNUSED = 29;

	private Rad50() {
	}

	/** The code of a character, or -1 when it has none. */
	static int code(char c) {
		int i = CHARACTERS.indexOf(Character.toUpperCase(c));
		return i < 0 || i == UNUSED ? -1 : i;
	}

	/** One word from up to three characters; empty when one of them cannot be encoded. */
	static OptionalInt encodeWord(String s) {
		if(s.length() > 3)
			throw new IllegalArgumentException("At most three characters fit in a word: '" + s + "'");
		int word = 0;
		for(int i = 0; i < 3; i++) {
			int c = i < s.length() ? code(s.charAt(i)) : 0;
			if(c < 0)
				return OptionalInt.empty();
			word = word * 050 + c;
		}
		return OptionalInt.of(word);
	}

	/**
	 * The three characters in a word; a word too large to be RAD50, or the unused code, comes
	 * out as {@code ?}.
	 */
	static String decode(int word) {
		int w = word & 0xFFFF;
		if(w >= 050 * 050 * 050)
			return "???";
		return "" + CHARACTERS.charAt(w / (050 * 050)) + CHARACTERS.charAt(w / 050 % 050) + CHARACTERS.charAt(w % 050);
	}

	/** Whether every character of the text can be encoded. */
	static boolean canEncode(String s) {
		for(int i = 0; i < s.length(); i++) {
			if(code(s.charAt(i)) < 0)
				return false;
		}
		return true;
	}
}
