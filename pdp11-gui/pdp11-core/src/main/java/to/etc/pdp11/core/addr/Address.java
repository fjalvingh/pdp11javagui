package to.etc.pdp11.core.addr;

import to.etc.pdp11.core.util.Octal;

/**
 * A PDP-11 address: a value plus the width model it is expressed in.
 *
 * <p>Ported from {@code TMemoryAddress} ({@code AddressU.pas:66-70}), with two changes.</p>
 *
 * <p>It is <b>immutable</b>. The Pascal record is copied by value, so mutating it is
 * harmless there; a shared mutable object in Java is not, and several call sites relied on
 * copy semantics without saying so - {@code TBitfieldsDefs.BitFieldsDefByAddr} rewrites the
 * stored address in place as a caching trick ({@code BitFieldU.pas:284-288}).</p>
 *
 * <p>The value is a <b>{@code long}</b>, not an {@code int}. 22 bits fit in an {@code int}
 * with room to spare, but the surrounding Pascal is full of {@code dword} arithmetic and an
 * {@code $ffffffff} sentinel that becomes {@code -1} when it lands in a signed Java
 * {@code int}, inverting every comparison it touches. Buying unsigned safety outright costs
 * nothing here. The {@code tmpval} field ({@code AddressU.pas:58-62}, "another representation
 * of val") is not carried over; it was scratch space, not state.</p>
 *
 * @param type how wide this address is
 * @param val  the address itself, always non-negative
 */
public record Address(MemoryAddressType type, long val) implements Comparable<Address> {
	public Address {
		if(type == null)
			throw new IllegalArgumentException("An address needs a MemoryAddressType");
		if(val < 0)
			throw new IllegalArgumentException("Address value cannot be negative: " + val);
		if(type.isConcretePhysical() || type == MemoryAddressType.VIRTUAL) {
			long max = (1L << type.getBits()) - 1;
			if(val > max)
				throw new IllegalArgumentException("Address 0" + Long.toOctalString(val)
					+ " does not fit in a " + type.getBits() + " bit address (max 0" + Long.toOctalString(max) + ")");
		}
	}

	public static Address of(MemoryAddressType type, long val) {
		return new Address(type, val);
	}

	/** Whether this address lies in the 8 KB I/O page at the top of its address space. */
	public boolean isInIopage() {
		return val >= type.getIopageBase();
	}

	/**
	 * The same location, expressed at a different physical address width.
	 *
	 * <p><b>This is the one genuinely non-obvious rule in the address model</b>
	 * ({@code AddressU.pas:131-145}), and it affects every window whenever the target machine
	 * changes. Two cases:</p>
	 *
	 * <ul>
	 *   <li>Below the I/O page, an address means the same thing at every width, so the value
	 *       is unchanged. Location {@code 01000} is {@code 01000} on all three machines.</li>
	 *   <li>Inside the I/O page it is not, because the I/O page always sits at the <i>top</i>
	 *       of the address space and the top moves. The value is rebased by the difference
	 *       between the two I/O page bases, so 16-bit {@code 0177570} - the console switch
	 *       register - is 22-bit {@code 017777570}.</li>
	 * </ul>
	 *
	 * <p>Converting down can produce a value too wide for the target: 22-bit {@code 0400000}
	 * is ordinary memory on a big machine and simply does not exist on a 16-bit one. That
	 * throws rather than silently truncating - the Pascal, working in {@code dword}, would
	 * have produced a value outside the target's range and carried on.</p>
	 *
	 * @throws IllegalArgumentException if {@code newType} is not a concrete physical width, if
	 *                                  this address has no width to convert from, or if the
	 *                                  result does not fit.
	 */
	public Address withWidth(MemoryAddressType newType) {
		if(!newType.isConcretePhysical())
			throw new IllegalArgumentException("Can only convert to a concrete physical width, not to " + newType);
		if(!type.isConcretePhysical() && type != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("Cannot convert a " + type + " address to another width");
		if(newType == type)
			return this;

		long newVal = isInIopage()
			? val + newType.getIopageBase() - type.getIopageBase()
			: val;
		return new Address(newType, newVal);        // the constructor rejects an overflow
	}

	/** Whether {@link #withWidth} would succeed. */
	public boolean fitsWidth(MemoryAddressType newType) {
		try {
			withWidth(newType);
			return true;
		} catch(IllegalArgumentException x) {
			return false;
		}
	}

	/** This address plus a byte offset, at the same width. */
	public Address plus(long offset) {
		return new Address(type, val + offset);
	}

	/**
	 * Zero-padded octal, at the width of this address type: six digits for 16 and 18 bits,
	 * eight for 22. Ported from {@code Addr2OctalStr} ({@code AddressU.pas:117-120}).
	 */
	public String toOctal() {
		return Octal.format(val, type.getOctalDigits());
	}

	/**
	 * Parse zero-padded or unpadded octal into an address of the given type.
	 *
	 * <p>Ported from {@code OctalStr2Addr} ({@code AddressU.pas:122-126}). Where the Pascal
	 * turned an out-of-range value into the {@code MEMORYCELL_ILLEGALVAL} sentinel
	 * ({@code AuxU.pas:151-153}), this throws: an address that does not fit is a mistake, not
	 * an unknown value, and the sentinel is exactly what the port is getting rid of.</p>
	 *
	 * @throws NumberFormatException    if the text is not octal
	 * @throws IllegalArgumentException if the value does not fit the type
	 */
	public static Address parseOctal(String text, MemoryAddressType type) {
		return new Address(type, Octal.parse(text));
	}

	/**
	 * Ordering by value, and only meaningful between addresses of the same type - which is
	 * why this throws rather than comparing across widths, where {@code 0177570} sorts below
	 * {@code 0400000} at 22 bits but is the same device register.
	 */
	@Override
	public int compareTo(Address o) {
		if(type != o.type)
			throw new IllegalArgumentException("Cannot compare a " + type + " address with a " + o.type
				+ " one; convert with withWidth() first");
		return Long.compare(val, o.val);
	}

	@Override
	public String toString() {
		return toOctal() + "/" + type;
	}
}
