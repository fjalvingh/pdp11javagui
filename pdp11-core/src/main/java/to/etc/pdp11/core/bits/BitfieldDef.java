package to.etc.pdp11.core.bits;

/**
 * One named run of bits inside a 16-bit register value.
 *
 * <p>Ported from {@code TBitfieldDef} ({@code BitFieldU.pas:40-53}). Machine descriptions
 * spell these as {@code name = bit_hi:bit_lo;info}, which is what drives the Bitfields window
 * and the I/O page scanner.</p>
 *
 * @param name  the field's name, as it appears in the register documentation
 * @param info  a human-readable description, possibly empty
 * @param bitHi the highest bit of the run, 0..15
 * @param bitLo the lowest bit of the run, 0..15, never above {@code bitHi}
 */
public record BitfieldDef(String name, String info, int bitHi, int bitLo) {
	public BitfieldDef {
		if(name == null || name.isBlank())
			throw new IllegalArgumentException("A bitfield needs a name");
		if(info == null)
			info = "";
		if(bitLo < 0 || bitHi > 15)
			throw new IllegalArgumentException("Bitfield '" + name + "' has bits " + bitHi + ".." + bitLo
				+ ", outside the 0..15 of a PDP-11 word");
		if(bitHi < bitLo)
			throw new IllegalArgumentException("Bitfield '" + name + "' has bitHi " + bitHi
				+ " below bitLo " + bitLo + "; swap them at the parser, not here");
	}

	/** A single-bit field. */
	public static BitfieldDef of(String name, String info, int bit) {
		return new BitfieldDef(name, info, bit, bit);
	}

	/** How many bits wide this field is. */
	public int width() {
		return bitHi - bitLo + 1;
	}

	/**
	 * A mask with bits {@code bitHi..bitLo} set, in place.
	 *
	 * <p>{@code TBitfieldDef.getMask} ({@code BitFieldU.pas:104-121}) uses a 17-entry lookup
	 * table for this, which exists because Pascal's {@code shl} on a {@code dword} by 16 is
	 * not the identity people expect. Java's {@code <<} on an {@code int} is well-defined for
	 * shift counts 0..31, so the table is unnecessary.</p>
	 */
	public int mask() {
		return ((1 << width()) - 1) << bitLo;
	}

	/** A mask for this field's width, shifted down to bit 0. */
	public int unshiftedMask() {
		return (1 << width()) - 1;
	}

	/**
	 * Extract this field from a register value, shifted down to bit 0. Ported from
	 * {@code getFieldInValue} ({@code BitFieldU.pas:137-142}).
	 */
	public int get(int value) {
		return (value & mask()) >>> bitLo;
	}

	/**
	 * Return {@code value} with this field replaced. Ported from {@code setFieldInValue}
	 * ({@code BitFieldU.pas:123-135}), including its refusal to truncate: a field value too
	 * wide for the field is a caller bug, and silently dropping the high bits would write a
	 * different value to a device register than the user asked for.
	 *
	 * @throws IllegalArgumentException if {@code fieldValue} does not fit in {@link #width()}
	 *                                  bits.
	 */
	public int set(int value, int fieldValue) {
		if((fieldValue & ~unshiftedMask()) != 0)
			throw new IllegalArgumentException("Value 0" + Integer.toOctalString(fieldValue)
				+ " does not fit in bits " + bitHi + ".." + bitLo + " of field '" + name + "'");
		return (value & ~mask()) | (fieldValue << bitLo);
	}

	/** {@code "NAME"} for a single bit, {@code "NAME<hi:lo>"} for a run. */
	public String toBitRangeString() {
		return bitHi == bitLo
			? name + "<" + bitLo + ">"
			: name + "<" + bitHi + ":" + bitLo + ">";
	}
}
