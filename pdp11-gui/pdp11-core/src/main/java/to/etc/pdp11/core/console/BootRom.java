package to.etc.pdp11.core.console;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;

/**
 * Which boot ROM's console emulator a {@link M9312Console} is talking to.
 *
 * <p>The Pascal has a subclass, {@code TConsolePDP11M9301} ({@code ConsolePDP11M9301U.pas}),
 * whose comment is the whole specification: "identical to the M9312, but prompt {@code $}
 * instead of {@code @}". That and a name are the entire difference, so it is a constant here,
 * the same treatment {@link OdtDialect} and {@link Pdp1144Firmware} got.</p>
 */
public enum BootRom {
	/** The M9312 bootstrap/terminator, on an 11/04, 11/34 or 11/60. */
	M9312("PDP-11 M9312 console", "@", 0165020),

	/**
	 * The older M9301. Its prompt is {@code $}, and an M9301-YA follows it with a NUL fill
	 * character - which the scanner drops along with every other NUL.
	 */
	M9301("PDP-11 M9301 console", "$", -1);

	private final String m_name;

	private final String m_prompt;

	private final long m_monitorEntry;

	BootRom(String name, String prompt, long monitorEntry) {
		m_name = name;
		m_prompt = prompt;
		m_monitorEntry = monitorEntry;
	}

	public String getName() {
		return m_name;
	}

	public String getPrompt() {
		return m_prompt;
	}

	/**
	 * Where a program jumps to get back into the console emulator, or {@code null} for none.
	 *
	 * <p>The defaults the Pascal settings dialog offers ({@code FormSettingsU.pas}):
	 * {@code 165020} for an M9312, "see M9312 doc"; for an M9301 {@code 0}, with the comment
	 * "depends on switch settings", and 0 is what the disc image driver reads as "there is none,
	 * use HALT" ({@code FormDiscImageU.pas:1081-1083}).</p>
	 */
	public Address getDefaultMonitorEntry() {
		return m_monitorEntry < 0 ? null : Address.of(MemoryAddressType.VIRTUAL, m_monitorEntry);
	}
}
