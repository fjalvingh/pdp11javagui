package to.etc.pdp11.core.addr;

/**
 * The two things a PDP-11 has that live on the front panel rather than in the address space.
 *
 * <p>Ported from the {@code MEMORYCELL_SPECIALADDR_*} constants ({@code MemoryCellU.pas:54-57}),
 * which are plain integers stored in an address whose type is {@code matSpecialRegister}. An
 * enum makes the two-value set explicit and gives each one the address that names it.</p>
 *
 * <p>They are not memory. The switch register is what the operator sets by hand and the display
 * register is what the program lights up; neither has a UNIBUS address on most machines, and a
 * console that can reach them does so with its own command - SimH answers {@code e -d dr} with
 * {@code DR: nnnnnn}.</p>
 */
public enum SpecialRegister {
	/** What the running program displays. {@code MEMORYCELL_SPECIALADDR_DISPLAYREG}. */
	DISPLAY_REGISTER(0),

	/** What the operator has set on the switches. {@code MEMORYCELL_SPECIALADDR_SWITCHREG}. */
	SWITCH_REGISTER(1);

	private final int m_code;

	SpecialRegister(int code) {
		m_code = code;
	}

	public int getCode() {
		return m_code;
	}

	public Address toAddress() {
		return Address.of(MemoryAddressType.SPECIAL_REGISTER, m_code);
	}

	/** Which one an address names, or {@code null} if it does not name one. */
	public static SpecialRegister of(Address addr) {
		if(addr.type() != MemoryAddressType.SPECIAL_REGISTER)
			return null;
		for(SpecialRegister r : values()) {
			if(r.m_code == addr.val())
				return r;
		}
		return null;
	}
}
