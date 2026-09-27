package to.etc.pdp11.core.conn;

/**
 * How to reach the console. The other half of PLAN.md §3's decomposition.
 */
public enum TransportKind {
	/** Launch SimH ourselves and drive its remote console. */
	SIMH_PROCESS("SimH, launched by us"),

	/** A telnet port somebody else is listening on. */
	TELNET("Telnet"),

	/** A real serial line to a real machine. */
	SERIAL("Serial port"),

	/**
	 * A simulated machine inside this JVM.
	 *
	 * <p>Replaces the Pascal's {@code consoleSelftest*} half of {@code TConsoleType}: there,
	 * every console type appears twice, once for hardware and once for its fake. Here it is a
	 * transport, so it costs one entry rather than doubling the list - and it means the whole
	 * application can be run with no hardware, no SimH and no serial port.</p>
	 */
	SIMULATED("Simulated machine (no hardware)");

	private final String m_label;

	TransportKind(String label) {
		m_label = label;
	}

	public String getLabel() {
		return m_label;
	}

	@Override
	public String toString() {
		return m_label;
	}
}
