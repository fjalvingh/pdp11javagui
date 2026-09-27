package to.etc.pdp11.core.addr;

/**
 * How wide an address is, and therefore where its I/O page starts.
 *
 * <p>Ported from {@code TMemoryAddressType} ({@code AddressU.pas:56-64}). The Pascal code
 * tests {@code newMat > matAnyPhysical} to mean "is a concrete physical width"
 * ({@code AddressU.pas:134}, {@code BitFieldU.pas:279}), which makes the declaration order of
 * the enum load-bearing. Java enums would give that away for free via {@code ordinal()}, and
 * it would keep working right up until somebody inserted a constant. {@link #isConcretePhysical()}
 * says what those comparisons meant, and nothing here relies on ordering.</p>
 */
public enum MemoryAddressType {
	/** No address at all. */
	UNKNOWN(0, false),

	/**
	 * The value encodes a special register rather than a memory location - the Pascal
	 * {@code MEMORYCELL_SPECIALADDR_*} codes.
	 */
	SPECIAL_REGISTER(0, false),

	/**
	 * A 16-bit address as the program sees it, before the MMU. Same width as
	 * {@link #PHYSICAL16} but a different thing: this one is subject to relocation.
	 */
	VIRTUAL(16, false),

	/**
	 * Physical, at whatever width the currently connected machine uses. A placeholder that
	 * has to be resolved to a concrete width before arithmetic.
	 */
	ANY_PHYSICAL(0, false),

	PHYSICAL16(16, true),
	PHYSICAL18(18, true),
	PHYSICAL22(22, true);

	private final int m_bits;

	private final boolean m_concretePhysical;

	MemoryAddressType(int bits, boolean concretePhysical) {
		m_bits = bits;
		m_concretePhysical = concretePhysical;
	}

	/**
	 * Whether this names one definite physical address width, so that an address of this type
	 * can be converted, compared or looked up.
	 *
	 * <p>Note {@link #VIRTUAL} is 16 bits wide but is <b>not</b> concrete physical, which is
	 * exactly what the Pascal {@code > matAnyPhysical} test also excluded.</p>
	 */
	public boolean isConcretePhysical() {
		return m_concretePhysical;
	}

	/**
	 * Address width in bits.
	 *
	 * @throws IllegalStateException for the types that have no width ({@link #UNKNOWN},
	 *                               {@link #SPECIAL_REGISTER}, {@link #ANY_PHYSICAL}) - the
	 *                               Pascal raised here too ({@code AddressU.pas:110}).
	 */
	public int getBits() {
		if(m_bits == 0)
			throw new IllegalStateException("A " + this + " address has no defined bit width");
		return m_bits;
	}

	/**
	 * The number of octal digits needed to print an address of this width: 6 for 16 and 18
	 * bits, 8 for 22.
	 *
	 * <p>Note 18-bit addresses print in six digits, same as 16-bit ones - {@code 0777776} is
	 * six digits. Following {@code Dword2OctalStr}'s {@code (fixbitwidth+2) div 3}
	 * ({@code AuxU.pas:174}).</p>
	 */
	public int getOctalDigits() {
		return (getBits() + 2) / 3;
	}

	/**
	 * Highest addressable byte at this width: {@code 0177776}, {@code 0777776} or
	 * {@code 017777776}. Odd addresses do not exist as word addresses, hence the {@code -2}.
	 */
	public long getMaxAddress() {
		return (1L << getBits()) - 2;
	}

	/**
	 * Where the 8 KB I/O page starts at this width: {@code 0160000}, {@code 0760000} or
	 * {@code 017760000}.
	 *
	 * <p>Ported from {@code PhysicalIopageBaseAddr} ({@code AddressU.pas:93-105}), which
	 * accepts {@link #VIRTUAL} as well as the three physical widths and raises for anything
	 * else.</p>
	 */
	public long getIopageBase() {
		return (1L << getBits()) - 8192;
	}

	/**
	 * The concrete physical type of the given width.
	 *
	 * @throws IllegalArgumentException for anything other than 16, 18 or 22.
	 */
	public static MemoryAddressType forBits(int bits) {
		return switch(bits) {
			case 16 -> PHYSICAL16;
			case 18 -> PHYSICAL18;
			case 22 -> PHYSICAL22;
			default -> throw new IllegalArgumentException(
				"There is no PDP-11 physical address width of " + bits + " bits; expected 16, 18 or 22");
		};
	}
}
