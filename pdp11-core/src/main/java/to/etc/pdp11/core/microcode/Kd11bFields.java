package to.etc.pdp11.core.microcode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PDP-11/05's microword: 18 named fields over 40 bits, and what their values mean.
 *
 * <p>The KD11-B holds 256 microwords of 40 bits in ten 256x4 PROMs, ten four-bit slices per
 * microword. Every bit is a control line and every one of them is named on the M7261 schematics,
 * so unlike the 11/44 - whose table came out of the Pascal - this one is built from the signal
 * names themselves. That is worth more than it sounds: <b>the polarity comes with the name</b>.
 * A {@code -L} line is asserted low, so its resting value is 1, and that is where
 * {@link MicrocodeField#defaultValue()} comes from for every single-bit field here rather than
 * from counting which value is commonest.</p>
 *
 * <h2>Bit numbers are the schematic's</h2>
 *
 * <p>Bit 39 is the leftmost column of the printed listing and bit 0 the rightmost, so
 * {@code SCHBIT = 39 - LISTCOL}. Those are the numbers on the drawings and on the KM11's
 * connector, which is the point: a microword read here can be compared against the lights.</p>
 *
 * <h2>Three fields are not what the listing's headings suggest</h2>
 *
 * <ul>
 *   <li><b>{@code SPA}</b>, the scratchpad register address, is printed as four non-adjacent and
 *       out-of-order single-bit columns - {@code SP0} at bit 18, {@code SP1} at 22, {@code SP2}
 *       at 12, {@code SP3} at 21. Assembled it reads as register numbers, R7 in 28 microwords
 *       and R6 in 22; treated as four flags it reads as nothing at all.</li>
 *   <li><b>{@code BUT}</b>, the branch microtest, is bit-scrambled: schematic bits 3..0 are
 *       {@code BUT-1, BUT-0, BUT-2, BUT-3}. All sixteen microtests are defined, so decoding the
 *       printed nibble as a plain number gives a <i>plausible</i> wrong microtest for every
 *       microword and there is no error to notice - the whole branch structure is simply
 *       mislabelled.</li>
 *   <li><b>{@code BRG}</b> is reversed the same way, {@code BMODE-0} above {@code BMODE-1}. The
 *       order was settled by the data rather than assumed: the printed pattern {@code 01} has to
 *       be {@code SRIGHT}, because that is where all fourteen {@code ASR} microwords are and an
 *       arithmetic shift right cannot be paired with a shift left.</li>
 * </ul>
 *
 * <p>The listing's {@code SM0}/{@code SM1} and {@code BTP}/{@code BBT} headings are likewise
 * pairs rather than flags, and are here as {@code SPAMUX} and {@code BLEG}. That leaves the
 * fields as they mean things rather than as they were printed, which is the whole job of a field
 * table.</p>
 *
 * <h2>Where AUX makes the ALU field a don't-care</h2>
 *
 * <p>{@code AUX-CONTROL-L} selects whether {@code ALU S0..S3}, {@code ALU MODE} and {@code CIN}
 * come from this microword or are decoded from instruction register bits 15..12 or 10..6 - the
 * opcode fields of the double- and single-operand formats. That is how one microword executes
 * {@code MOV}, {@code CMP}, {@code BIT}, {@code BIC}, {@code BIS}, {@code ADD} and {@code SUB}
 * alike, and it means that <b>in a microword that asserts {@code AUX} the printed {@code ALU}
 * field is not necessarily what the machine does</b>. See {@link #takesAluControlFromIr}.</p>
 *
 * <p>Everything here is from {@code microcode/kd11b/kd11b-fields.tsv} and
 * {@code kd11b-fieldvalues.tsv}, whose {@code kd11b-README.md} carries the transcription and how
 * far each claim is checked. The one thing that is <i>not</i> checked is any of it against a
 * running machine.</p>
 */
public final class Kd11bFields {
	/** How wide the microword is. */
	public static final int WORD_BITS = 40;

	/** How many of the 256 control store locations the listing prints. */
	public static final int LISTED_MICROWORDS = 214;

	/** How wide one control store PROM is, and so how many bits a slice of the word holds. */
	public static final int PROM_SLICE_BITS = 4;

	private Kd11bFields() {
	}

	// -----------------------------------------------------------------------------------------
	// The field names, so a lookup does not have to spell out the signal in brackets
	// -----------------------------------------------------------------------------------------

	public static final String NXT = "NXT (MPC-7..0-L, complemented)";

	public static final String ALU = "ALU (ALU-S3..S0-L, ALU-MODE-H)";

	public static final String CRI = "CRI (CIN-H)";

	public static final String FSH = "FSH (F-SHIFT-L)";

	public static final String AUX = "AUX (AUX-CONTROL-L)";

	public static final String PSW = "PSW (LOAD-PSW-L)";

	public static final String SPA = "SPA (ROM-SPA-3..0-H)";

	public static final String DIP = "DIP (ENAB-IN-PAUSE-L)";

	public static final String SPAMUX = "SPAMUX (SPA-MUX-1..0-H)";

	public static final String BLEG = "BLEG (BTOP-H, BBOT-H)";

	public static final String BAR = "BAR (BA-CLOCK-L)";

	public static final String SPF = "SPF (SP-WRITE-L)";

	public static final String CKO = "CKO (CKOFF-L)";

	public static final String ABT = "ABT (ALLOW-BYTE-L)";

	public static final String TNS = "TNS (DATO-L, DATI-L)";

	public static final String ALG = "ALG (RALEG-1..0-L)";

	public static final String BRG = "BRG (BMODE-1..0-H)";

	public static final String BUT = "BUT (BUT-3..0-L)";

	// -----------------------------------------------------------------------------------------
	// The table
	// -----------------------------------------------------------------------------------------

	/**
	 * Name a field's values, from 0 upwards, with {@code null} for a value the schematics do not
	 * name and {@code ""} for one whose name is nothing at all - "load PSW" against "do not load
	 * PSW". Both show as the number alone; the difference is whether anybody knows what it is.
	 */
	private static Map<Integer, String> values(String... byValue) {
		Map<Integer, String> map = new LinkedHashMap<>();
		for(int i = 0; i < byValue.length; i++) {
			if(byValue[i] != null)
				map.put(i, byValue[i]);
		}
		return Map.copyOf(map);
	}

	/**
	 * The KD11-B's microword, in the order the listing prints the columns.
	 *
	 * <p>All 40 bits are in exactly one field: this machine has no spare bit, which
	 * {@link MicrocodeArchitecture#unusedBits} is asserted on.</p>
	 */
	public static final MicrocodeArchitecture ARCHITECTURE;

	static {
		List<MicrocodeField> l = new ArrayList<>();

		//-- MPC-7..MPC-0, and stored active low: the next microaddress is NXT XOR 0377. Taken as
		//-- printed, 83.2% of the 214 next-addresses land on a listed location, which is chance
		//-- level because 214 of 256 are listed; complemented, 213 of 214 do. The loader does the
		//-- complementing, so the value shown here is the bits as burned.
		l.add(MicrocodeField.of(1, NXT, -1, 32, 8, Map.of()));

		//-- The operation table in EK-KD11B-MM-001, and the printed five bits are the code: all
		//-- twelve codes that occur are named operations, covering 214 of 214 microwords. ROL and
		//-- ROR are in the table but in no microword, because the rotates are not ALU functions -
		//-- the ALU passes a leg through and FSH does the shifting. A XOR B is absent for a
		//-- different reason: the 11/05 has no XOR instruction, which arrived with the 11/45.
		//-- AL - the A leg straight through - is the resting operation and 105 microwords use it.
		l.add(MicrocodeField.of(2, ALU, 001, 27, 5, values(
			"AA", "AL", null, "AB", null, "A · ~B", null, "ZERO",
			null, "A + B", null, "BL", "A PLUS B", "A XOR B", null, null,
			null, null, "A MINUS B MINUS 1", null, null, "~B", null, null,
			"ASL", "MINUS ONE", "ROL", null, "ASR", null, "A MINUS 1", "~A")));

		l.add(MicrocodeField.of(3, CRI, 0, 26, 1, values("", "CIN")));
		l.add(MicrocodeField.of(4, FSH, 1, 25, 1, values("SHIFT", "")));
		l.add(MicrocodeField.of(5, AUX, 1, 24, 1, values("ALU CONTROL FROM IR", "")));
		l.add(MicrocodeField.of(6, PSW, 1, 23, 1, values("LOAD PSW", "")));

		//-- Trap 1: four scattered, out-of-order single-bit columns, assembled SP3 SP2 SP1 SP0.
		l.add(new MicrocodeField(7, SPA, 0, List.of(21, 12, 22, 18), values(
			"R0", "R1", "R2", "R3", "R4", "R5", "R6(SP)", "R7(PC)",
			"R10", "R11", "R12", "R13", "R14", "R15", "R16", "R17")));

		l.add(MicrocodeField.of(8, DIP, 1, 20, 1, values("ENAB IN PAUSE", "")));

		//-- Where the scratchpad address comes from. This is the independent check on SPA: all 96
		//-- microwords with a non-zero ROM scratchpad address have this set to ROM, and none of
		//-- the other 118 do. IRS and IRD cannot be told apart by the data - they are the two
		//-- values that swap when the mux bits do - so that pair is inferred, not established.
		l.add(new MicrocodeField(9, SPAMUX, 3, List.of(17, 19), values("BA", "IRD", "IRS", "ROM")));

		//-- BTOP and BBOT together select the B leg source. BTP=1 BBT=0 occurs in 26 microwords
		//-- and the schematic notes do not name that combination.
		l.add(new MicrocodeField(10, BLEG, 3, List.of(14, 16), values("+1", "SEX", null, "BREG")));

		l.add(MicrocodeField.of(11, BAR, 1, 15, 1, values("BA CLOCK", "")));
		l.add(MicrocodeField.of(12, SPF, 1, 13, 1, values("WRITE", "READ")));

		//-- CKOFF-L, the processor clock stop. It sits among the MSYN/SSYN bus signals, but the
		//-- listing does not settle exactly when it is asserted: of the 36 microwords with CKO=0,
		//-- 22 start a bus cycle and 14 do not, and 17 microwords start a DATI without it. The
		//-- names below are the schematic's; note that they read backwards against the data, so
		//-- the line more likely enables a clock stop than commands one. One of the two fields
		//-- the E->F board revision changed.
		l.add(MicrocodeField.of(13, CKO, 1, 11, 1, values("ON", "OFF")));

		l.add(MicrocodeField.of(14, ABT, 1, 10, 1, values("ALLOW BYTE", "")));
		//-- Value 0 is both bus signals asserted at once, which occurs only in the all-but-zero
		//-- A145 filler microword and is named nowhere.
		l.add(MicrocodeField.of(15, TNS, 3, 8, 2, values(null, "DATO", "DATI", "NONE")));
		l.add(MicrocodeField.of(16, ALG, 3, 6, 2, values("PSW", "SPR", "NULL", "SP")));

		//-- Trap 2 and trap 3: both of these are printed in the opposite order to the value.
		l.add(new MicrocodeField(17, BRG, 0, List.of(4, 5), values("HOLD", "SLEFT", "SRIGHT", "LOAD")));
		l.add(new MicrocodeField(18, BUT, 017, List.of(0, 1, 3, 2), values(
			"IR-CLK", "INTR", "NON-MOD", "BYTE", "ENOFLO", "MOV", "SWITCHES", "IR-DECODE",
			"SSYNC", "DEST", "UNARY", "JMP/JSR", "SERVICE", "CONST", "INIT", "NON")));

		ARCHITECTURE = new MicrocodeArchitecture("PDP-11/05", l, NXT, BUT, Kd11bMicrocode::verify,
			(mi, field) -> field.name().equals(ALU) && takesAluControlFromIr(mi)
				? "the ALU control comes from the instruction register, not from this field"
				: null);
	}

	/**
	 * Whether this microword takes its ALU control from the instruction register rather than from
	 * its own {@code ALU} field, so that what the field says is not what the machine does.
	 *
	 * <p>Eight microwords in rev F and six in rev E, and not one of them the same one: the E to F
	 * revision moved the set wholesale. Both counts exclude the {@code A145} filler, whose 40
	 * bits are almost entirely zero and which therefore reads as asserting this and every other
	 * active-low line at once.</p>
	 */
	public static boolean takesAluControlFromIr(MicroInstruction mi) {
		return mi.getValue(ARCHITECTURE.byName(AUX)) == 0;
	}
}
