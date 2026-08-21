package to.etc.pdp11.core.mem;

import to.etc.pdp11.core.util.Octal;

/**
 * The contents of one PDP-11 word, or "not known".
 *
 * <p>This type exists to kill {@code MEMORYCELL_ILLEGALVAL} ({@code MemoryCellU.pas:46}), the
 * {@code $ffffffff} the Pascal uses to mean "unknown" for both addresses and values. Carried
 * over literally into a signed Java {@code int} that constant is {@code -1}, and every
 * relational comparison against it silently inverts - which PLAN.md §2 calls the highest-risk
 * mechanical hazard in the whole port.</p>
 *
 * <p>The defence is not a convention to be remembered across 26 windows but a type where the
 * unknown state <b>cannot be compared</b>: there is no ordering on {@code CellValue} and
 * {@link #word()} throws rather than handing back a number that looks usable. Code that wants
 * the number has to say what it means for the value to be missing.</p>
 */
public record CellValue(int raw) {
	/** The bit pattern the Pascal uses, kept so serialised data reads the same way. */
	private static final int UNKNOWN_RAW = 0xFFFF_FFFF;

	/** No value has been read from the machine, or it was invalidated. */
	public static final CellValue UNKNOWN = new CellValue(UNKNOWN_RAW);

	/** A known 16-bit word. Anything above 16 bits is masked off, as the hardware would. */
	public static CellValue of(int word) {
		return new CellValue(word & 0xFFFF);
	}

	public boolean isKnown() {
		return raw != UNKNOWN_RAW;
	}

	/**
	 * The 16-bit word.
	 *
	 * @throws IllegalStateException if the value is not known. Deliberately not a
	 *                               "return 0 if unknown" convenience: a zero that means
	 *                               "never read" is how you deposit zero into a device
	 *                               register you meant to leave alone.
	 */
	public int word() {
		if(!isKnown())
			throw new IllegalStateException("This memory cell's value is not known");
		return raw;
	}

	/** The word, or {@code defaultValue} when nothing has been read. */
	public int wordOr(int defaultValue) {
		return isKnown() ? raw : defaultValue;
	}

	/**
	 * Six octal digits, or {@code "?"} for unknown - the same two spellings
	 * {@code Dword2OctalStr}/{@code OctalStr2Dword} use ({@code AuxU.pas:168-171, 139-142}),
	 * so what the user sees and types is unchanged.
	 */
	public String toOctal() {
		return isKnown() ? Octal.format(raw, 6) : "?";
	}

	/**
	 * Parse what the user typed: six octal digits, or {@code "?"} for unknown.
	 *
	 * @throws NumberFormatException if the text is neither
	 */
	public static CellValue parseOctal(String text) {
		String s = text == null ? "" : text.strip();
		if("?".equals(s))
			return UNKNOWN;
		long v = Octal.parse(s);
		if(v > 0xFFFF)
			throw new NumberFormatException("0" + Long.toOctalString(v) + " does not fit in a 16 bit word");
		return of((int) v);
	}

	@Override
	public String toString() {
		return toOctal();
	}
}
