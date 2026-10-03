package to.etc.pdp11.core.console;

/**
 * The lexer for the M9301/M9312 boot ROM console emulator's output.
 *
 * <p>Ported from {@code TConsolePDP11M9312Scanner} ({@code ConsolePDP11M9312U.pas:73-78,
 * 172-241}). It only has to tell numbers from commands: the emulator's whole vocabulary is
 * {@code L}, {@code E}, {@code D}, {@code S}, two-letter boot codes such as {@code DL}, a boot
 * code with a unit number such as {@code DL1}, octal numbers, and the four-number register dump.</p>
 *
 * <h2>Line endings, the other way round from ODT</h2>
 *
 * <p>The ROM ends a line with one LF and then a run of carriage returns - thirteen of them, to
 * give a hard-copy terminal's carriage time to get back to the margin. So here the <b>LF</b> is
 * the line end and every CR is dropped ({@code :198-201}); {@link OdtScanner} does the opposite.</p>
 *
 * <h2>Where it stops</h2>
 *
 * <p>A run of octal digits, or a command, that reaches the end of the buffer may still grow, so it
 * is not a symbol yet - exactly as in {@link OdtScanner}, and with the same correction to the
 * Pascal: {@code nextSymbol(false)} answers {@link Sym#EOF} for those as well as for an empty
 * buffer, instead of throwing whatever it was asked. The decoder reads one symbol past the prompt
 * with {@code false} ({@code :475}), and a Pascal scanner that threw there had already published
 * the prompt - so the retry published it a second time.</p>
 */
public final class M9312Scanner extends ConsoleScanner<M9312Scanner.Sym> {
	private static final char CR = '\r';

	private static final char LF = '\n';

	/** What the current symbol is. */
	public enum Sym {
		/** A complete run of octal digits. */
		OCTAL,
		/**
		 * Upper-case letters, then optionally octal digits: {@code E}, {@code L}, {@code DL},
		 * {@code DL1}. Every command the emulator knows, and every boot code.
		 */
		OPCODE,
		/** A run of anything else - a space, a prompt, a bracket. */
		OTHER,
		/** A line feed. */
		EOLN,
		/** Nothing left in the buffer. */
		EOF
	}

	public M9312Scanner() {
		clear();
	}

	/** Also fetches the first symbol, as the Pascal constructor does ({@code :152-156}). */
	@Override
	public void clear() {
		super.clear();
		nextSymbol(false);
	}

	private static boolean isOctalDigit(char c) {
		return c >= '0' && c <= '7';
	}

	private static boolean isLetter(char c) {
		return c >= 'A' && c <= 'Z';
	}

	/** Nothing more can be read yet: either say so or ask the caller to come back. Consumes nothing. */
	private String endOfInput(boolean raiseIncompleteOnEof, String why) {
		setCurSymText("EOF");
		setCurSymType(Sym.EOF);
		if(raiseIncompleteOnEof)
			throw new ScannerInputIncompleteException(why);
		return "EOF";
	}

	@Override
	public String nextSymbol(boolean raiseIncompleteOnEof) {
		for(;;) {
			int start = getNextCharIndex();
			if(start >= length())
				return endOfInput(raiseIncompleteOnEof, "End of console input");
			char c = charAt(start);
			int i = start;
			if(c == CR) {
				//-- Filtered out, and go round again for a real symbol.
				setNextCharIndex(start + 1);
				continue;
			} else if(isOctalDigit(c)) {
				while(i < length() && isOctalDigit(charAt(i))) {
					i++;
				}
				if(i >= length())
					return endOfInput(raiseIncompleteOnEof, "Octal digits run to the end of the buffer");
				setCurSymType(Sym.OCTAL);
			} else if(isLetter(c)) {
				i++;
				while(i < length() && isLetter(charAt(i))) {
					i++;
				}
				while(i < length() && isOctalDigit(charAt(i))) {
					i++;
				}
				if(i >= length())
					return endOfInput(raiseIncompleteOnEof, "A command runs to the end of the buffer");
				setCurSymType(Sym.OPCODE);
			} else if(c == LF) {
				i++;
				setCurSymType(Sym.EOLN);
			} else {
				while(i < length()) {
					char n = charAt(i);
					if(isLetter(n) || isOctalDigit(n) || n == CR || n == LF)
						break;
					i++;
				}
				setCurSymType(Sym.OTHER);
			}
			String text = substring(start, i);
			setCurSymText(text);
			setNextCharIndex(i);
			return text;
		}
	}
}
