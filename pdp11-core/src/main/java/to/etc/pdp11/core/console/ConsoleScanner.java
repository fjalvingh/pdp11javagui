package to.etc.pdp11.core.console;

/**
 * The restartable lexer every console decoder reads through.
 *
 * <p>Ported from {@code TConsoleScanner} ({@code ConsoleGenericU.pas:108-126}). The design is
 * sound and is kept as-is: an incremental buffer that grows as bytes arrive, a one-deep
 * {@link #markParsePosition()}/{@link #restoreParsePosition()} backtrack, and the two
 * control-flow exceptions {@link ScannerInputIncompleteException} and
 * {@link ScannerUnknownExpressionException}. Together they are why a console protocol survives
 * arriving one byte at a time down a serial line: a decode that runs out of input rewinds and
 * is simply retried when more shows up. See PLAN.md §2.</p>
 *
 * <h2>Two cleanups over the Pascal</h2>
 *
 * <p>The current symbol's type is an untyped {@code integer} there, with each console defining
 * its own unrelated constants ({@code ConsolePDP11ODTU.pas:125-129} against
 * {@code CurSymType: integer} at {@code ConsoleGenericU.pas:112}). Here it is a type parameter,
 * so a console's symbol enum is checked against its own scanner.</p>
 *
 * <p>Indices are 0-based. The Pascal's are 1-based throughout, and this is exactly the kind of
 * conversion CLAUDE.md says to do deliberately, per scanner, with tests - not by reflex.</p>
 *
 * <h2>Text, but only just</h2>
 *
 * <p>The buffer holds one {@code char} per received byte, each already masked to 7 bits by the
 * connection ({@code SerialIoHubU.pas:843} - every PDP-11 console is a 7-bit device). No
 * charset decoding happens anywhere: byte {@code n} is char {@code n}. The protocol layer stays
 * byte-oriented in the only sense that matters, which is that no default-charset conversion can
 * ever get near it.</p>
 *
 * @param <S> this console's symbol types
 */
public abstract class ConsoleScanner<S extends Enum<S>> {
	/** Everything received and not yet consumed. Decoders shorten it as they recognise things. */
	private final StringBuilder m_input = new StringBuilder();

	/** Index of the next unprocessed character in {@link #m_input}. 0-based. */
	private int m_nextCharIndex;

	private String m_curSymText = "";

	private S m_curSymType;

	private int m_markedNextCharIndex;

	private String m_markedCurSymText = "";

	private S m_markedCurSymType;

	/**
	 * Forget everything: unparsed input, the current symbol and the mark.
	 *
	 * <p>The Pascal's {@code Clear} resets only the buffer and the parse position
	 * ({@code :302-307}), leaving {@code CurSymTxt}, {@code CurSymType} and the marked position
	 * pointing into a buffer that no longer exists. Nothing has been seen to depend on that,
	 * and a restore to an index past the end of an empty buffer is not something to leave
	 * lying around. Subclasses that want a first symbol fetch it by overriding this, which is
	 * what the Pascal constructors do.</p>
	 */
	public void clear() {
		m_input.setLength(0);
		m_nextCharIndex = 0;
		m_curSymText = "";
		m_curSymType = null;
		m_markedNextCharIndex = 0;
		m_markedCurSymText = "";
		m_markedCurSymType = null;
	}

	/**
	 * Add received input to the parser.
	 *
	 * <p>NUL characters are dropped ({@code :313-317}): some consoles pad with fill NULs and
	 * they can turn up anywhere, including in the middle of a number.</p>
	 */
	public void moreInput(String s) {
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if(c != 0)
				m_input.append(c);
		}
	}

	/**
	 * Everything still in the buffer, consumed part included.
	 *
	 * <p><b>Not thread safe, and read from two threads.</b> The buffer is appended by the reader
	 * thread under the console's decode lock, so a command thread wanting this for a diagnostic
	 * goes through {@link AbstractConsole#getUnconsumedInput()}, which takes that lock.
	 * {@code ConsoleThreadingTest} holds that down.</p>
	 */
	public String getInput() {
		return m_input.toString();
	}

	/** What is left from the parse position on. */
	public String getRemainingInput() {
		return m_input.substring(m_nextCharIndex);
	}

	public int length() {
		return m_input.length();
	}

	public int getNextCharIndex() {
		return m_nextCharIndex;
	}

	public void setNextCharIndex(int index) {
		if(index < 0 || index > m_input.length())
			throw new IllegalArgumentException("Parse position " + index + " is outside the buffer");
		m_nextCharIndex = index;
	}

	public boolean isAtEnd() {
		return m_nextCharIndex >= m_input.length();
	}

	/**
	 * The character at the parse position, without consuming it.
	 *
	 * @throws ScannerInputIncompleteException at the end of what has arrived so far
	 */
	public char peek() {
		if(isAtEnd())
			throw new ScannerInputIncompleteException("End of console input");
		return m_input.charAt(m_nextCharIndex);
	}

	/**
	 * The character at the parse position, consuming it.
	 *
	 * @throws ScannerInputIncompleteException at the end of what has arrived so far
	 */
	public char take() {
		char c = peek();
		m_nextCharIndex++;
		return c;
	}

	/** The character at an absolute index, for decoders that scan ahead. */
	public char charAt(int index) {
		return m_input.charAt(index);
	}

	/** A slice of the buffer by absolute index, for a lexer that has measured a symbol. */
	public String substring(int from, int to) {
		return m_input.substring(from, to);
	}

	/**
	 * Throw away the part already scanned.
	 *
	 * <p>{@code CleanupInput} ({@code :320-325}). Called once a phrase has been recognised, so
	 * the buffer does not grow without bound over a long session.</p>
	 */
	public void cleanupInput() {
		m_input.delete(0, m_nextCharIndex);
		m_nextCharIndex = 0;
	}

	/**
	 * Drop the first {@code count} characters of the buffer outright, parse position and all.
	 *
	 * <p>For decoders that work on the raw buffer instead of on symbols - SimH's does, and its
	 * scanner never tokenises anything ({@code ConsolePDP11SimHU.pas:179-183} raises "not
	 * implemented" from {@code NxtSym}).</p>
	 */
	public void dropLeading(int count) {
		if(count <= 0)
			return;
		int n = Math.min(count, m_input.length());
		m_input.delete(0, n);
		m_nextCharIndex = Math.max(0, m_nextCharIndex - n);
	}

	/** Remember where we are, so a failed decode attempt can be rewound. One deep. */
	public void markParsePosition() {
		m_markedNextCharIndex = m_nextCharIndex;
		m_markedCurSymText = m_curSymText;
		m_markedCurSymType = m_curSymType;
	}

	/** Go back to the mark and pretend nothing happened. */
	public void restoreParsePosition() {
		m_nextCharIndex = m_markedNextCharIndex;
		m_curSymText = m_markedCurSymText;
		m_curSymType = m_markedCurSymType;
	}

	public String getCurSymText() {
		return m_curSymText;
	}

	protected void setCurSymText(String curSymText) {
		m_curSymText = curSymText;
	}

	public S getCurSymType() {
		return m_curSymType;
	}

	protected void setCurSymType(S curSymType) {
		m_curSymType = curSymType;
	}

	/**
	 * Read the next symbol, leaving it in {@link #getCurSymText()}/{@link #getCurSymType()}.
	 *
	 * @param raiseIncompleteOnEof when false, running out of input yields an end-of-input
	 *                             symbol instead of {@link ScannerInputIncompleteException} -
	 *                             for the decoder that wants to know rather than to wait
	 * @return the symbol's text, the same as {@link #getCurSymText()}
	 */
	public abstract String nextSymbol(boolean raiseIncompleteOnEof);

	public String nextSymbol() {
		return nextSymbol(true);
	}
}
