package to.etc.pdp11.common.macro11.asm;

/**
 * What a {@link Token} is.
 */
enum TokenKind {
	/** A name: letters, digits, {@code .} and {@code $}, not starting with a digit. Upper case. */
	SYMBOL,

	/**
	 * A run of symbol characters that starts with a digit: {@code 177777}, {@code 10.},
	 * {@code 1F}. Not converted here, because what it means depends on the radix in force where
	 * it is used; a word that is not a valid number in that radix is an error there.
	 */
	NUMBER,

	/** A local label such as {@code 10$}; the text includes the dollar sign. */
	LOCAL_LABEL,

	/** {@code 'x} or {@code "xy}: the value of one or two characters. */
	CHARACTER,

	/** {@code ^} and the letter after it, which is the text: {@code ^C}, {@code ^O}... */
	CIRCUMFLEX,

	PLUS,
	MINUS,
	STAR,
	SLASH,
	AMPERSAND,
	EXCLAMATION,
	LEFT_ANGLE,
	RIGHT_ANGLE,
	COMMA,
	LEFT_PAREN,
	RIGHT_PAREN,
	AT,
	HASH,
	PERCENT,
	EQUALS,
	COLON,

	/** Any other single character. */
	OTHER,

	/** Something the tokenizer could not read; the text is the reason. */
	ERROR,

	/** The end of the statement: the end of the line, or a comment. */
	END_OF_LINE
}
