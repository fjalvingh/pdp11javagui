package to.etc.pdp11.common.macro11.asm;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

/**
 * Reads MACRO-11 source from a {@link Reader}, a statement at a time, as tokens.
 *
 * <p>MACRO-11 is line oriented: a statement is a line, and a line is never continued. So the
 * reader moves from line to line explicitly with {@link #nextLine()}, and within a line hands out
 * tokens, ending with an {@link TokenKind#END_OF_LINE} that stays there however often it is
 * asked for. A comment is the end of the line.</p>
 *
 * <h2>Lookahead</h2>
 *
 * <p>Tokens are read lazily into a lookahead queue: {@link #peek(int)} looks any distance ahead
 * and {@link #pushBack} returns a token that turned out not to be wanted. That is all the
 * backtracking the grammar needs - telling a label from an operation needs two tokens, and an
 * addressing mode at most three.</p>
 *
 * <h2>Text that is not tokens</h2>
 *
 * <p>Some operands are not made of tokens at all: the string of an {@code .ASCII}, whose
 * delimiter can be any character; a macro argument, which is text to be substituted; the
 * remark of {@code .TITLE}. Those are read with the {@code read...} methods, which start at the
 * next token that has not been consumed - anything looked at but not taken is given back first,
 * so a caller that peeked to decide what comes next loses nothing by having done so.</p>
 */
final class TokenReader {
	private final Reader m_reader;

	private final String m_source;

	private final Location m_expandedFrom;

	private final Deque<Token> m_lookahead = new ArrayDeque<>();

	/** The number the current line has. */
	private int m_lineNumber;

	/** Whether the next line read starts a new line in the file, rather than following a form feed. */
	private boolean m_startsNewLine = true;

	/** The current line, without its terminator; null before the first and after the last. */
	private String m_line;

	/** Where in {@link #m_line} the next token starts to be looked for. */
	private int m_position;

	/**
	 * @param reader       the text; read to the end, never closed here
	 * @param source       the file or expansion name, for locations
	 * @param expandedFrom where an expansion was called from, or null for a file
	 */
	TokenReader(Reader reader, String source, Location expandedFrom) {
		m_reader = reader;
		m_source = source;
		m_expandedFrom = expandedFrom;
	}

	/**
	 * Close the underlying reader. A reader that will not close has nothing left to give, so
	 * that is not worth failing an assembly over.
	 */
	void close() {
		try {
			m_reader.close();
		} catch(IOException ignored) {
			//-- Nothing to be done about it, and nothing lost.
		}
	}

		String getSource() {
		return m_source;
	}

	Location getExpandedFrom() {
		return m_expandedFrom;
	}

	// -------------------------------------------------------------------------------------
	// Lines
	// -------------------------------------------------------------------------------------

	/**
	 * Move to the next line.
	 *
	 * <p>A line ends at a newline or a form feed; carriage returns and NULs are dropped, as they
	 * were from paper tape. A form feed starts a new page but not a new line in the numbering,
	 * so the text after it keeps the number of the text before it.</p>
	 *
	 * @return false at the end of the input
	 */
	boolean nextLine() {
		m_lookahead.clear();
		StringBuilder sb = new StringBuilder();
		int terminator = -1;
		boolean any = false;
		try {
			int c;
			while((c = m_reader.read()) != -1) {
				any = true;
				if(c == '\n' || c == '\f') {
					terminator = c;
					break;
				}
				if(c == '\r' || c == 0)
					continue;
				sb.append((char) c);
			}
		} catch(IOException x) {
			throw new UncheckedIOException(x);
		}
		if(!any) {
			m_line = null;
			return false;
		}
		if(m_startsNewLine)
			m_lineNumber++;
		m_startsNewLine = terminator != '\f';
		m_line = sb.toString();
		m_position = 0;
		return true;
	}

	/** The current line's text. */
	String lineText() {
		return m_line == null ? "" : m_line;
	}

	int lineNumber() {
		return m_lineNumber;
	}

	/** The current line, at its first column. */
	Location lineLocation() {
		return new Location(m_source, m_lineNumber, 0, m_expandedFrom);
	}

	/** Where the next token or text would be read, for an error about what is there. */
	Location here() {
		int at = m_lookahead.isEmpty() ? m_position : m_lookahead.peekFirst().offset();
		return locationAt(at);
	}

	private Location locationAt(int offset) {
		return new Location(m_source, m_lineNumber, offset + 1, m_expandedFrom);
	}

	// -------------------------------------------------------------------------------------
	// Tokens
	// -------------------------------------------------------------------------------------

	/** The next token, without taking it. */
	Token peek() {
		return peek(0);
	}

	/** The token {@code n} places ahead, without taking anything. */
	Token peek(int n) {
		while(m_lookahead.size() <= n)
			m_lookahead.addLast(scan());
		Iterator<Token> it = m_lookahead.iterator();
		for(int i = 0; i < n; i++)
			it.next();
		return it.next();
	}

	/** Take the next token. */
	Token next() {
		Token t = peek(0);
		if(!t.isEndOfLine())
			m_lookahead.removeFirst();
		return t;
	}

	/** Take the next token if it is of this kind. */
	boolean accept(TokenKind kind) {
		if(!peek().is(kind))
			return false;
		next();
		return true;
	}

	/** Give back a token that was taken, so that it is the next one again. */
	void pushBack(Token t) {
		if(t.isEndOfLine())
			return;
		m_lookahead.addFirst(t);
	}

	/** Whether nothing but a comment is left on the line. */
	boolean atEndOfStatement() {
		return peek().isEndOfLine();
	}

	/** Forget the rest of the line, after an error in it. */
	void skipRestOfLine() {
		m_lookahead.clear();
		m_position = lineText().length();
	}

	private Token scan() {
		String line = lineText();
		int start = m_position;
		while(m_position < line.length() && isBlank(line.charAt(m_position)))
			m_position++;
		boolean space = m_position > start;
		if(m_position >= line.length() || line.charAt(m_position) == ';') {
			m_position = line.length();
			return token(TokenKind.END_OF_LINE, "", 0, line.length(), space);
		}

		int at = m_position;
		char c = line.charAt(at);
		if(isSymbolChar(c)) {
			while(m_position < line.length() && isSymbolChar(line.charAt(m_position)))
				m_position++;
			String text = line.substring(at, m_position).toUpperCase(Locale.ROOT);
			if(!isDigit(c))
				return token(TokenKind.SYMBOL, text, 0, at, space);
			if(text.length() > 1 && text.endsWith("$") && allDigits(text, text.length() - 1))
				return token(TokenKind.LOCAL_LABEL, text, 0, at, space);
			return token(TokenKind.NUMBER, text, 0, at, space);
		}

		switch(c) {
			case '\'':
				if(at + 1 >= line.length()) {
					m_position = line.length();
					return token(TokenKind.ERROR, "Missing character after '", 0, at, space);
				}
				m_position = at + 2;
				return token(TokenKind.CHARACTER, line.substring(at, at + 2), line.charAt(at + 1) & 0xFF, at, space);
			case '"':
				if(at + 2 >= line.length()) {
					m_position = line.length();
					return token(TokenKind.ERROR, "Missing characters after \"", 0, at, space);
				}
				m_position = at + 3;
				int pair = (line.charAt(at + 1) & 0xFF) | ((line.charAt(at + 2) & 0xFF) << 8);
				return token(TokenKind.CHARACTER, line.substring(at, at + 3), pair, at, space);
			case '^':
				if(at + 1 < line.length() && Character.isLetter(line.charAt(at + 1))) {
					m_position = at + 2;
					return token(TokenKind.CIRCUMFLEX, String.valueOf(line.charAt(at + 1)).toUpperCase(Locale.ROOT), 0, at, space);
				}
				m_position = at + 1;
				return token(TokenKind.CIRCUMFLEX, "", 0, at, space);
			default:
				m_position = at + 1;
				return token(punctuation(c), String.valueOf(c), 0, at, space);
		}
	}

	private static TokenKind punctuation(char c) {
		return switch(c) {
			case '+' -> TokenKind.PLUS;
			case '-' -> TokenKind.MINUS;
			case '*' -> TokenKind.STAR;
			case '/' -> TokenKind.SLASH;
			case '&' -> TokenKind.AMPERSAND;
			case '!' -> TokenKind.EXCLAMATION;
			case '<' -> TokenKind.LEFT_ANGLE;
			case '>' -> TokenKind.RIGHT_ANGLE;
			case ',' -> TokenKind.COMMA;
			case '(' -> TokenKind.LEFT_PAREN;
			case ')' -> TokenKind.RIGHT_PAREN;
			case '@' -> TokenKind.AT;
			case '#' -> TokenKind.HASH;
			case '%' -> TokenKind.PERCENT;
			case '=' -> TokenKind.EQUALS;
			case ':' -> TokenKind.COLON;
			default -> TokenKind.OTHER;
		};
	}

	private Token token(TokenKind kind, String text, int value, int offset, boolean space) {
		return new Token(kind, text, value, locationAt(offset), space, offset);
	}

	// -------------------------------------------------------------------------------------
	// Text that is not tokens
	// -------------------------------------------------------------------------------------

	/** A string between delimiters, as {@code .ASCII} and {@code .RAD50} take. */
	record Delimited(String text, char delimiter, boolean terminated, Location location) {
	}

	/** A macro or conditional argument. */
	record Argument(String text, boolean bracketed, boolean terminated, Location location) {
	}

	/**
	 * Continue at the next token that has not been taken: anything in the lookahead is put back
	 * into the line, so text is read from where the caller thinks it is.
	 */
	private void returnLookahead() {
		if(!m_lookahead.isEmpty()) {
			m_position = m_lookahead.peekFirst().offset();
			m_lookahead.clear();
		}
	}

	private void skipBlanks() {
		String line = lineText();
		while(m_position < line.length() && isBlank(line.charAt(m_position)))
			m_position++;
	}

	/**
	 * The next character, after blanks, without taking it; -1 at the end of the line.
	 *
	 * <p>A semicolon is a character like any other here: inside a string it is text.</p>
	 */
	int peekCharacter() {
		returnLookahead();
		skipBlanks();
		String line = lineText();
		return m_position < line.length() ? line.charAt(m_position) : -1;
	}

	/**
	 * Text between a delimiter and the next occurrence of it, as in {@code .ASCII /text/}.
	 *
	 * @return null when the line has nothing more
	 */
	Delimited readDelimited() {
		returnLookahead();
		skipBlanks();
		String line = lineText();
		if(m_position >= line.length())
			return null;
		int start = m_position;
		char delimiter = line.charAt(start);
		int end = line.indexOf(delimiter, start + 1);
		boolean terminated = end >= 0;
		if(!terminated)
			end = line.length();
		m_position = terminated ? end + 1 : end;
		return new Delimited(line.substring(start + 1, end), delimiter, terminated, locationAt(start));
	}

	/**
	 * One macro argument, or the argument of a {@code .IF B}-style condition.
	 *
	 * <p>Three forms. {@code <text>} brackets text that may hold commas and blanks, and nests;
	 * {@code ^xtextx} does the same with any delimiter; anything else runs to the next comma,
	 * blank, tab or semicolon. The brackets are not part of the result.</p>
	 *
	 * @return null when the statement has no more arguments
	 */
	Argument readArgument() {
		returnLookahead();
		skipBlanks();
		String line = lineText();
		if(m_position >= line.length() || line.charAt(m_position) == ';')
			return null;
		int start = m_position;
		char c = line.charAt(start);
		if(c == '<') {
			int depth = 1;
			int i = start + 1;
			for(; i < line.length(); i++) {
				char x = line.charAt(i);
				if(x == '<')
					depth++;
				else if(x == '>' && --depth == 0)
					break;
			}
			boolean terminated = i < line.length();
			m_position = terminated ? i + 1 : i;
			return new Argument(line.substring(start + 1, i), true, terminated, locationAt(start));
		}
		if(c == '^' && start + 1 < line.length() && !isBlank(line.charAt(start + 1))) {
			char delimiter = line.charAt(start + 1);
			int end = line.indexOf(delimiter, start + 2);
			boolean terminated = end >= 0;
			if(!terminated)
				end = line.length();
			m_position = terminated ? end + 1 : end;
			return new Argument(line.substring(start + 2, end), true, terminated, locationAt(start));
		}
		int i = start;
		while(i < line.length() && ",;".indexOf(line.charAt(i)) < 0 && !isBlank(line.charAt(i)))
			i++;
		m_position = i;
		return new Argument(line.substring(start, i), false, true, locationAt(start));
	}

	/**
	 * Take a comma between arguments, if there is one.
	 *
	 * <p>MACRO-11 separates arguments with a comma or with blanks, so a missing comma is not an
	 * error; this only takes it out of the way.</p>
	 */
	void skipArgumentSeparator() {
		returnLookahead();
		skipBlanks();
		String line = lineText();
		if(m_position < line.length() && line.charAt(m_position) == ',')
			m_position++;
	}

	/** A run of non-blank characters up to a comma or a comment: a floating-point number. */
	String readWord() {
		returnLookahead();
		skipBlanks();
		String line = lineText();
		int start = m_position;
		while(m_position < line.length() && ",;".indexOf(line.charAt(m_position)) < 0
			&& !isBlank(line.charAt(m_position)))
			m_position++;
		return line.substring(start, m_position);
	}

	/** What {@link #readWord()} would return, without taking it. */
	String peekWord() {
		returnLookahead();
		skipBlanks();
		String line = lineText();
		int end = m_position;
		while(end < line.length() && ",;".indexOf(line.charAt(end)) < 0 && !isBlank(line.charAt(end)))
			end++;
		return line.substring(m_position, end);
	}

	/**
	 * Up to {@code count} characters that RAD50 can encode, for {@code ^R}: {@code ^RDU,R4}
	 * is {@code DU}, not {@code DU,}.
	 */
	String readRad50Characters(int count) {
		returnLookahead();
		String line = lineText();
		int end = m_position;
		while(end < line.length() && end - m_position < count && line.charAt(end) != ' ' && Rad50.code(line.charAt(end)) >= 0)
			end++;
		String s = line.substring(m_position, end);
		m_position = end;
		return s;
	}

	/** Up to {@code count} characters as they are. */
	String readCharacters(int count) {
		returnLookahead();
		String line = lineText();
		int end = Math.min(line.length(), m_position + count);
		String s = line.substring(m_position, end);
		m_position = end;
		return s;
	}

	/** Everything left on the line, comment included, without leading blanks. */
	String readRestOfLine() {
		returnLookahead();
		skipBlanks();
		String line = lineText();
		String s = line.substring(m_position);
		m_position = line.length();
		return s;
	}

	/**
	 * Skip past the next occurrence of {@code c} on this line.
	 *
	 * @return false, leaving the reader at the end of the line, if there is none
	 */
	boolean skipPast(char c) {
		returnLookahead();
		String line = lineText();
		int i = line.indexOf(c, m_position);
		if(i < 0) {
			m_position = line.length();
			return false;
		}
		m_position = i + 1;
		return true;
	}

	// -------------------------------------------------------------------------------------
	// Characters
	// -------------------------------------------------------------------------------------

	static boolean isBlank(char c) {
		return c == ' ' || c == '\t';
	}

	static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	/** Letters, digits, {@code .} and {@code $}: what a symbol is made of. */
	static boolean isSymbolChar(char c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || isDigit(c) || c == '.' || c == '$';
	}

	private static boolean allDigits(String s, int length) {
		for(int i = 0; i < length; i++) {
			if(!isDigit(s.charAt(i)))
				return false;
		}
		return true;
	}
}
