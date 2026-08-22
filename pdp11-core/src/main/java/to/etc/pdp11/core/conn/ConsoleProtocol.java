package to.etc.pdp11.core.conn;

import to.etc.pdp11.core.addr.MemoryAddressType;

/**
 * Which console dialect is on the other end.
 *
 * <p>Half of the decomposition PLAN.md §3 asks for. {@code FormSettingsU.pas} offers
 * <b>24 flat combo entries</b> that are really this list crossed with a transport - "Physical
 * PDP-11 ODT 18 bit (11/23) over serial port" and "…over telnet" as two separate choices, and
 * so on for every pair. A list that grows by multiplication every time either axis gains a
 * member is a list that will be wrong; two independent selectors are not.</p>
 *
 * <p>The Pascal also doubles every entry again for its self-test variants
 * ({@code consoleSelftest11ODT16} beside {@code consolePDP11ODT16}, {@code TConsoleType},
 * {@code ConsoleGenericU.pas:55-74}), which is the same cross product a third time. Here the
 * simulated machine is a {@link TransportKind}, so it multiplies nothing.</p>
 */
public enum ConsoleProtocol {
	/** SimH's remote console, over a SimH we launched or a telnet port somebody else opened. */
	SIMH("SimH", MemoryAddressType.PHYSICAL22),

	/** Microcode ODT on a 16-bit machine. */
	ODT_16("PDP-11 ODT, 16 bit", MemoryAddressType.PHYSICAL16),

	/** Microcode ODT on an 18-bit machine - an 11/23, for instance. */
	ODT_18("PDP-11 ODT, 18 bit (11/23)", MemoryAddressType.PHYSICAL18),

	/** Microcode ODT on a 22-bit machine - an 11/73 or 11/93. */
	ODT_22("PDP-11 ODT, 22 bit (11/73, 11/93)", MemoryAddressType.PHYSICAL22),

	/** Robotron A6402's K1630, an 18-bit ODT with its own spelling. */
	ODT_K1630("Robotron K1630 ODT, 18 bit", MemoryAddressType.PHYSICAL18),

	/** The PDP-11/44's console processor. */
	PDP1144("PDP-11/44 console", MemoryAddressType.PHYSICAL22),

	/** The same, running the undocumented V3.40C firmware. */
	PDP1144_V340C("PDP-11/44 console, V3.40C firmware", MemoryAddressType.PHYSICAL22);

	private final String m_label;

	private final MemoryAddressType m_addressType;

	ConsoleProtocol(String label, MemoryAddressType addressType) {
		m_label = label;
		m_addressType = addressType;
	}

	/** How to name this in a menu. */
	public String getLabel() {
		return m_label;
	}

	/** How wide this machine's physical addresses are. */
	public MemoryAddressType getAddressType() {
		return m_addressType;
	}

	@Override
	public String toString() {
		return m_label;
	}
}
