package to.etc.pdp11.core.console;

/**
 * The lexer for ODT's output.
 *
 * <p>Ported from {@code TConsolePDP11ODTScanner} ({@code ConsolePDP11ODTU.pas:57-63,
 * 128-220}). Unlike SimH's, this console really is scanned symbol by symbol, because its
 * replies have no line structure to lean on: {@code @1000/000000 } is a prompt, an address, a
 * slash, a value and a space, arriving in any number of pieces down a serial line.</p>
 *
 * <h2>Where it stops</h2>
 *
 * <p>A symbol that <i>could</i> still grow if more bytes arrive is not a symbol yet, and the
 * scanner throws {@link ScannerInputIncompleteException} rather than guess. That covers a run
 * of octal digits reaching the end of the buffer - {@code 123} may yet become {@code 1234} -
 * and an {@code R} or {@code $} with nothing after it, which may become {@code R0} or may be a
 * stray character. The decoder catches it, rewinds to its mark, and tries again when more has
 * arrived. All three are "not all of it has arrived", so all three obey
 * {@code nextSymbol(false)} and answer {@link Sym#EOF} instead, for the caller that wants to be
 * told rather than interrupted.</p>
 *
 * <p>Line feeds are dropped outright ({@code :151-154}). ODT sends CR LF and only the CR is
 * structural; filtering here means no rule downstream has to mention LF at all.</p>
 */
public final class OdtScanner extends ConsoleScanner<OdtScanner.Sym> {
	private static final char CR = '\r';

	private static final char LF = '\n';

	/**
	 * What the current symbol is.
	 *
	 * <p>An enum, where the Pascal has untyped {@code integer} constants that each console
	 * redefines from zero ({@code :121-126} against {@code CurSymType: integer} in the base
	 * class) - PLAN.md §2's first scanner cleanup.</p>
	 */
	public enum Sym {
		/** A complete run of octal digits. */
		OCTAL,
		/** {@code R0}..{@code R7}, {@code $0}..{@code $7}, {@code RS} or {@code $S}. */
		REGISTER,
		/** One character that is none of the above: {@code @}, {@code /}, {@code ?}, a space. */
		OTHER,
		/** A carriage return. */
		EOLN,
		/** Nothing left in the buffer. */
		EOF
	}

	public OdtScanner() {
		clear();
	}

	/**
	 * Also fetches the first symbol, which is what the Pascal constructor does
	 * ({@code :139-143}) - and, unlike the Pascal, does it after a re-clear too, so a resync
	 * cannot leave a symbol from the previous conversation as the current one.
	 */
	@Override
	public void clear() {
		super.clear();
		nextSymbol(false);
	}

	private static boolean isOctalDigit(char c) {
		return c >= '0' && c <= '7';
	}

	/**
	 * Nothing more can be read yet: either say so or ask the caller to come back.
	 *
	 * <p>All three ways of running out of input come through here, which is what makes
	 * {@code raiseIncompleteOnEof} mean the same thing for each of them. Two of them used to
	 * throw whatever the flag said (FABLE-ISSUES #56): a number whose digits reach the end of
	 * the buffer and a bare {@code R} or {@code $} are both "not all of it has arrived", exactly
	 * like an empty buffer, and a caller that asked to be told rather than interrupted was told
	 * for one of the three and interrupted for the other two.</p>
	 *
	 * <p>Nothing is consumed either way. The partial symbol stays where it is, so the next call
	 * - with the rest of the line in the buffer by then - scans the whole of it.</p>
	 */
	private String endOfInput(boolean raiseIncompleteOnEof, String why) {
		setCurSymText("EOF");
		setCurSymType(Sym.EOF);
		//-- Tell the decoder to stop and come back later. Not an error.
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
			int symlen;
			if(c == LF) {
				//-- Filtered out, and go round again for a real symbol.
				setNextCharIndex(start + 1);
				continue;
			} else if(isOctalDigit(c)) {
				int i = start;
				while(i < length() && isOctalDigit(charAt(i))) {
					i++;
				}
				if(i >= length())
					return endOfInput(raiseIncompleteOnEof, "Octal digits run to the end of the buffer");
				setCurSymType(Sym.OCTAL);
				symlen = i - start;
			} else if(c == 'r' || c == 'R' || c == '$') {
				if(start + 1 >= length())
					return endOfInput(raiseIncompleteOnEof, "A register name may still be coming");
				char n = charAt(start + 1);
				if(isOctalDigit(n) || n == 'S' || n == 's') {
					symlen = 2;
					setCurSymType(Sym.REGISTER);
				} else {
					symlen = 1;
					setCurSymType(Sym.OTHER);
				}
			} else if(c == CR) {
				symlen = 1;
				setCurSymType(Sym.EOLN);
			} else {
				symlen = 1;
				setCurSymType(Sym.OTHER);
			}
			String text = substring(start, start + symlen);
			setCurSymText(text);
			setNextCharIndex(start + symlen);
			return text;
		}
	}
}
