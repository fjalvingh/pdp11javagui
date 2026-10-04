package to.etc.pdp11.common.macro11.asm;

/**
 * One token of a MACRO-11 statement.
 *
 * @param kind        what it is
 * @param text        the text, upper-cased for symbols and numbers; for an error, the reason
 * @param value       the character value of a {@link TokenKind#CHARACTER}, otherwise 0
 * @param location    where it starts
 * @param spaceBefore whether blanks came before it - MACRO-11 separates operands and
 *                    distinguishes a label from an operation by them
 * @param offset      where it starts in its line, for the reader's own use
 */
record Token(TokenKind kind, String text, int value, Location location, boolean spaceBefore, int offset) {
	boolean is(TokenKind k) {
		return kind == k;
	}

	boolean isSymbol(String name) {
		return kind == TokenKind.SYMBOL && text.equals(name);
	}

	boolean isEndOfLine() {
		return kind == TokenKind.END_OF_LINE;
	}

	/** How this token reads in an error message. */
	String describe() {
		return switch(kind) {
			case END_OF_LINE -> "the end of the line";
			case CHARACTER -> "a character constant";
			case CIRCUMFLEX -> "'^" + text + "'";
			default -> "'" + text + "'";
		};
	}
}
