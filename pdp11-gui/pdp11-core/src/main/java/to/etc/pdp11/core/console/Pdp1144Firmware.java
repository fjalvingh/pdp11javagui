package to.etc.pdp11.core.console;

/**
 * Which console firmware a PDP-11/44 is running.
 *
 * <p>Replaces the {@code isV340c} boolean threaded through {@code TConsolePDP1144}
 * ({@code ConsolePDP1144U.pas:70}) and the whole of {@code ConsolePDP1144v340cU.pas}, which is a
 * four-line subclass that exists only to set it. Same reasoning as {@link OdtDialect}: a flag
 * that changes how output is parsed is a dialect, and dialects are worth naming.</p>
 *
 * <p>The two say the same things in different words, and every difference is in what the machine
 * <i>prints</i> - the commands sent to it are identical.</p>
 */
public enum Pdp1144Firmware {
	/**
	 * The ordinary console.
	 *
	 * <p>Reports a stop by examining R7 at you - {@code 17777707 000114} - and a nonexistent
	 * address as {@code ?20 TRAN ERR}. An examine answer is bare: {@code <addr> <value>}.</p>
	 */
	CLASSIC("?20 TRAN ERR", true),

	/**
	 * The undocumented V3.40C firmware.
	 *
	 * <p>Says {@code Halted at 000114} and {@code ?Bus timeout error?}, and prefixes every
	 * examine answer with the space it read from: {@code P} for physical memory,
	 * {@code G} for a global register - and under {@code G} the address it prints is the
	 * register <i>number</i>, so the base has to be added back on.</p>
	 */
	V340C("?Bus timeout error?", false);

	private final String m_busTimeoutMarker;

	private final boolean m_promptsAfterStart;

	Pdp1144Firmware(String busTimeoutMarker, boolean promptsAfterStart) {
		m_busTimeoutMarker = busTimeoutMarker;
		m_promptsAfterStart = promptsAfterStart;
	}

	/** What this firmware prints instead of a value when the address does not answer. */
	public String getBusTimeoutMarker() {
		return m_busTimeoutMarker;
	}

	/**
	 * Whether a prompt follows {@code S} and {@code C}, with the machine now running.
	 *
	 * <p>The classic console draws its prompt after every command, this one included
	 * ({@code FakePDP1144U.pas:228} prompts unconditionally at the end of the command handler),
	 * so a start is answered even though nothing was asked. V3.40C has handed the terminal to the
	 * program by then and says nothing until {@code ^P} takes it back.</p>
	 *
	 * <p>It matters because the answer has to go somewhere. See
	 * {@code Pdp1144Console.takeTheStartPrompt}.</p>
	 */
	public boolean promptsAfterStart() {
		return m_promptsAfterStart;
	}
}
