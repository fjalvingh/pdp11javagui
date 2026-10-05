package to.etc.pdp11.core.conn;

/**
 * A named way of reaching one machine: which console dialect, over which transport.
 *
 * <p>PLAN.md §3: "Model this properly as {@code ConnectionProfile { ConsoleProtocol protocol;
 * TransportConfig transport; }} with two independent selectors, plus saved named profiles."
 * That is this. The two axes are genuinely independent - every console dialect can arrive over
 * every transport that can carry it - and modelling them separately is what stops the settings
 * dialog from being a list of two dozen sentences.</p>
 *
 * <p>The CPU is a third axis, and not an independent one: which machines a console can be in front
 * of depends on the console ({@link CpuModel#forProtocol}). It feeds {@link Capabilities}.</p>
 *
 * @param name      what the user calls it. Saved profiles are picked from a list by this.
 * @param protocol  which console is on the other end
 * @param transport how to reach it
 * @param cpu       which PDP-11 is behind the console; never null
 */
public record ConnectionProfile(String name, ConsoleProtocol protocol, TransportConfig transport, CpuModel cpu) {
	/**
	 * A profile saved before the CPU was asked has none, and Gson hands this a null for it; a
	 * hand-edited one may name a CPU its console is never on. Both are {@link CpuModel#UNKNOWN},
	 * which offers only what is true on every CPU - nothing in settings may stop a connection
	 * that worked yesterday.
	 */
	public ConnectionProfile {
		if(cpu == null || protocol == null || !CpuModel.forProtocol(protocol).contains(cpu))
			cpu = CpuModel.UNKNOWN;
	}

	/** A profile that does not say which CPU. */
	public ConnectionProfile(String name, ConsoleProtocol protocol, TransportConfig transport) {
		this(name, protocol, transport, CpuModel.UNKNOWN);
	}

	/** What a fresh installation offers: SimH, launched by us, which needs nothing installed but SimH. */
	public static ConnectionProfile defaultProfile() {
		return new ConnectionProfile("SimH", ConsoleProtocol.SIMH,
			TransportConfig.simhProcess(TransportConfig.DEFAULT_SIMH_EXECUTABLE, null));
	}

	/** A machine simulated in this JVM, which needs nothing at all. */
	public static ConnectionProfile simulated(ConsoleProtocol protocol) {
		return new ConnectionProfile("Simulated " + protocol.getLabel(), protocol, TransportConfig.simulated());
	}

	/**
	 * Why this profile cannot be connected, or {@code null} if it can.
	 *
	 * <p>Beyond the transport's own rules there is one cross-axis constraint, and it is the only
	 * one: {@link ConsoleProtocol#SIMH} is a program, so it cannot be at the end of a serial
	 * line, and every other protocol is a machine, so none of them can be launched as a
	 * process.</p>
	 */
	public String validate() {
		String transportProblem = transport.validate();
		if(transportProblem != null)
			return transportProblem;
		if(protocol == ConsoleProtocol.SIMH && transport.kind() == TransportKind.SERIAL)
			return "SimH is a program on this machine, not something at the end of a serial line";
		if(protocol != ConsoleProtocol.SIMH && transport.kind() == TransportKind.SIMH_PROCESS)
			return protocol.getLabel() + " is a machine, so it cannot be launched as a process";
		return null;
	}

	public boolean isValid() {
		return validate() == null;
	}

	/** One line for the status bar: what we are talking to, and over what. */
	public String describe() {
		return protocol.getLabel() + (cpu == CpuModel.UNKNOWN ? "" : " on a " + cpu.getLabel())
			+ " over " + transport.describe();
	}
}
