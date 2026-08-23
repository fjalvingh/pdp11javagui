package to.etc.pdp11.core.disas;

import to.etc.pdp11.core.util.Octal;

import java.util.Locale;

/**
 * One disassembled instruction.
 *
 * <p>The Pascal returns the word count and hands back the text through an {@code out}
 * parameter ({@code Pdp11DisasU.pas:53-54}); a record carries both plus the pieces the UI
 * wants to lay out in separate columns.</p>
 *
 * @param address    where the instruction starts
 * @param words      how many 16-bit words it occupies, at least 1
 * @param mnemonic   the operation, upper case ({@code "MOV"}, {@code ".WORD"})
 * @param operands   the operand text, possibly empty ({@code "R0,R1"})
 * @param recognized {@code false} when the word is not a known opcode, or a needed extension
 *                   word was not valid memory, and this is the {@code .WORD} fallback
 */
public record DecodedInstruction(int address, int words, String mnemonic, String operands, boolean recognized) {
	/**
	 * The instruction as the Pascal formats it: {@code LowerCase(Format('%-8s%s', [mnemonic,
	 * operands]))} ({@code Pdp11DisasU.pas:583}). Kept byte-identical, trailing spaces
	 * included - a no-operand instruction really does come out as {@code "halt    "} - so the
	 * two implementations can be diffed directly. Use {@link #textTrimmed()} for display.
	 */
	public String text() {
		return String.format(Locale.ROOT, "%-8s%s", mnemonic, operands).toLowerCase(Locale.ROOT);
	}

	/** {@link #text()} without the padding a bare mnemonic leaves behind. */
	public String textTrimmed() {
		return text().stripTrailing();
	}

	/** The address as six octal digits, the way a listing line starts. */
	public String addressOctal() {
		return Octal.format(address & 0xFFFF, 6);
	}

	@Override
	public String toString() {
		return addressOctal() + ": " + text();
	}
}
