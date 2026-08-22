package to.etc.pdp11.core.console;

/**
 * Which flavour of ODT is on the other end of the wire.
 *
 * <p>PLAN.md §2: "Console variants differ by boolean flags threaded through the scanner -
 * {@code isK1630} ({@code ConsolePDP11ODTU.pas:76}), {@code GobbleExtraSpaceAfterPrompt}
 * ({@code :75, 601, 639, 654}). Replace with a {@code ConsoleDialect} value object." This is
 * that object: the flags stop being two independent booleans that happen to have to agree - the
 * Pascal's own comment says the K1630 "needs also GobbleExtraSpaceAfterPrompt" ({@code :232}),
 * which is a constraint nothing enforces there.</p>
 *
 * <p>{@code FakePdp11Odt} has an enum of the same name and the same two members. They are
 * deliberately not the same type: the fake's says what a machine <i>prints</i>, this says how a
 * driver <i>reads</i> it, and sharing one would put a cycle between the packages, since the
 * transports depend on the fakes and the console layer depends on the transports.</p>
 */
public enum OdtDialect {
	/**
	 * DEC's own ODT, as on an 11/23, 11/73 or 11/93. Prompt is {@code "@"}.
	 *
	 * <p>Space-gobbling is on even here. DEC's ODT never prints one, so it costs nothing, and
	 * the Pascal turns it on unconditionally in the constructor for that reason
	 * ({@code :231}).</p>
	 */
	DEC(true, false),

	/**
	 * Robotron A6402, CPU K1630 - an East German PDP-11/23 equivalent, reported by Ruediger
	 * Kurt in 2016.
	 *
	 * <p>Two differences that reach the parser: the prompt is {@code "@ "} rather than
	 * {@code "@"}, and physical addresses are printed and accepted with an {@code A} suffix.</p>
	 */
	K1630(true, true);

	private final boolean m_gobbleSpaceAfterPrompt;

	private final boolean m_physicalAddressSuffixA;

	OdtDialect(boolean gobbleSpaceAfterPrompt, boolean physicalAddressSuffixA) {
		m_gobbleSpaceAfterPrompt = gobbleSpaceAfterPrompt;
		m_physicalAddressSuffixA = physicalAddressSuffixA;
	}

	/** Whether a space may follow the prompt, and the {@code /}, and be ignored. */
	public boolean isGobbleSpaceAfterPrompt() {
		return m_gobbleSpaceAfterPrompt;
	}

	/** Whether a physical address is written and read as {@code nnnnnnA}. */
	public boolean isPhysicalAddressSuffixA() {
		return m_physicalAddressSuffixA;
	}
}
