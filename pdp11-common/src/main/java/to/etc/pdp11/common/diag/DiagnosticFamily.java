package to.etc.pdp11.common.diag;

/**
 * What a diagnostic is for, as its name says.
 *
 * <p>DEC named every diagnostic with four letters, and the first says which machine or kind of
 * program it belongs to: {@code FKAA} is an 11/34 test, {@code KKTA} an 11/44 one, {@code ZRLG} a
 * device test that runs on any processor. The meanings below were read off DEC's own diagnostic
 * index ({@code AH-FG66P-MC}, 1990), by what the titles under each letter turned out to have in
 * common; the letters the index barely uses are lumped into {@link #OTHER}.</p>
 *
 * <p>The XXDP file name is the four letters, the revision letter and the patch digit:
 * {@code ZRLGE0.BIC} is revision E0 of {@code ZRLG}. DEC's part number puts a {@code C} in front -
 * {@code CZRLGE0}.</p>
 */
public enum DiagnosticFamily {
	XXDP("XXDP monitors, drivers and utilities"),
	PDP11_04("PDP-11/04"),
	PDP11_34("PDP-11/34"),
	PDP11_35_40("PDP-11/35, 11/40"),
	PDP11_44("PDP-11/44"),
	PDP11_45("PDP-11/45, 11/50, 11/55"),
	PDP11_60("PDP-11/60"),
	PDP11_70("PDP-11/70"),
	LSI11("LSI-11 and Q-bus"),
	PDP11_23_24("PDP-11/23, 11/24 (KDF11)"),
	KDJ11("PDP-11/73, 11/83, 11/84 (KDJ11)"),
	QBUS_OPTIONS("Q-bus options"),
	PERIPHERALS("Peripherals and options"),
	DECX11("DEC/X11 system exerciser"),
	ROMS("Boot ROMs and firmware"),
	SYSTEM_SCRIPTS("System configurations and chain files"),
	GT40("GT40 and general"),
	LABORATORY("Laboratory and real-time"),
	MPG("Maintenance program generator"),
	MANUFACTURING("Manufacturing and internal"),
	OTHER("Other");

	private final String m_label;

	DiagnosticFamily(String label) {
		m_label = label;
	}

	public String getLabel() {
		return m_label;
	}

	/** The family a four-letter diagnostic name belongs to. */
	public static DiagnosticFamily ofId(String id) {
		if(id == null || id.isEmpty())
			return OTHER;
		return switch(id.charAt(0)) {
			case 'B' -> PDP11_35_40;
			case 'C' -> PDP11_45;
			case 'D' -> GT40;
			case 'E' -> PDP11_70;
			case 'F' -> PDP11_34;
			case 'G' -> PDP11_04;
			case 'H', 'M' -> XXDP;
			case 'I' -> MANUFACTURING;
			case 'J' -> PDP11_23_24;
			case 'K' -> PDP11_44;
			case 'N' -> QBUS_OPTIONS;
			case 'O' -> KDJ11;
			case 'P' -> SYSTEM_SCRIPTS;
			case 'Q' -> PDP11_60;
			case 'R' -> LABORATORY;
			case 'T' -> MPG;
			case 'U' -> ROMS;
			case 'V' -> LSI11;
			case 'X' -> DECX11;
			case 'Z' -> PERIPHERALS;
			default -> OTHER;
		};
	}

	@Override
	public String toString() {
		return m_label;
	}
}
