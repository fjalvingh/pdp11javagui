package to.etc.pdp11.core.microcode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One named bit field inside a PDP-11/44 microword, and what its values mean.
 *
 * <p>A microword on this machine is 104 bits wide - 48 of them the KD11-Z processor's, the rest
 * the FP11 floating point option's - and every one of those bits is a control line into some
 * part of the data path. The listing prints the word as nine octal numbers, which say nothing at
 * all until they are cut back into the fields the engineers named. This is that table: 37 fields
 * and the 130 value names, ported from {@code PDP1144MicroInstructionFieldDefs} and
 * {@code PDP1144MicroInstructionFieldEnumDefs} ({@code Pdp1144MicroCodeU.pas:88-280}).</p>
 *
 * <h2>The default matters as much as the value</h2>
 *
 * <p>Most fields spend most of their life at one value, which is the encoding for "this part of
 * the machine is not involved this cycle". Knowing that value is what turns 37 numbers into the
 * two or three that are actually doing something, and it is why {@link #defaultValue()} is part
 * of the table rather than a display detail: the window shows a microword by highlighting the
 * fields that differ from it.</p>
 *
 * <h2>Notation in the value names</h2>
 *
 * <p>The ALU function names are DEC's, printed as they are in the print set: {@code ·} is AND,
 * {@code +} is OR, {@code XOR} is spelled out, and {@code ~} is complement - so {@code A · ~B}
 * is A AND NOT B, while {@code A PLUS B} is the adder. The Pascal source carries these as
 * cp1252 bullets and marks two of them with a puzzled {@code // and ?} comment
 * ({@code Pdp1144MicroCodeU.pas:158-160}); they are AND and OR respectively.</p>
 *
 * @param tag          the field's number in the original table, kept because the value names are
 *                     keyed by it there and it makes the two tables checkable against each other
 * @param name         the field's name, as the print set spells it
 * @param defaultValue the value meaning "nothing to do", or -1 for a field that has no such
 *                     value - only the next-address field, which is different every time
 * @param lsb          the position of the field's least significant bit in the 104-bit word
 * @param length       how many bits
 * @param values       what each value means, where the print set names them; a value not in here
 *                     is shown as a number, and an empty name means the value is worth no words
 */
public record MicrocodeField(int tag, String name, int defaultValue, int lsb, int length,
	Map<Integer, String> values) {

	/** Highest bit position, so a field reads as {@code msb:lsb} the way a print set writes it. */
	public int msb() {
		return lsb + length - 1;
	}

	/** How the print set names this field's bits: {@code "87"} for one bit, {@code "102:93"} for more. */
	public String bitRange() {
		return length == 1 ? String.valueOf(lsb) : msb() + ":" + lsb;
	}

	/** Whether there is a value that means "not this cycle". */
	public boolean hasDefault() {
		return defaultValue >= 0;
	}

	/**
	 * What this value means, or {@code null} when the print set does not name it.
	 *
	 * <p>An empty string is a third case and not the same as {@code null}: it means the value is
	 * named and the name is nothing - "load BA" versus "do not load BA". Those show as the
	 * number alone, which is also what an unnamed value shows as, but the distinction is real
	 * and the FP11 fields rely on it: none of their values are named at all.</p>
	 */
	public String text(int value) {
		return values.get(value);
	}

	/** The largest value that fits. */
	public int mask() {
		return (1 << length) - 1;
	}

	@Override
	public String toString() {
		return name + " <" + bitRange() + ">";
	}

	// -----------------------------------------------------------------------------------------
	// The table
	// -----------------------------------------------------------------------------------------

	/** Value names by field tag, filled in below and consumed by the field table under it. */
	private static final Map<Integer, Map<Integer, String>> TEXTS = new LinkedHashMap<>();

	/**
	 * Name a field's values, from 0 upwards.
	 *
	 * <p>The Pascal lists these as 130 {@code (tag, value, text)} triples with the value written
	 * decimal but meant octal - a trap it works around with an {@code _10}..{@code _32} constant
	 * table ({@code OctalConst}). Every one of its groups turns out to be dense from zero, so the
	 * position in this list <i>is</i> the value and there is no octal-versus-decimal question to
	 * get wrong.</p>
	 */
	private static void texts(int tag, String... byValue) {
		Map<Integer, String> map = new LinkedHashMap<>();
		for(int i = 0; i < byValue.length; i++)
			map.put(i, byValue[i]);
		TEXTS.put(tag, Map.copyOf(map));
	}

	static {
		texts(2, "PSW", "ALU", "VECT", "UBUS");
		texts(3, "NOP", "LOAD IR", "LOAD PSW", "LOAD CC", "BUT DEST", "ENAB STOV", "LOAD COUNT", "CLK COUNT");
		texts(4, "", "BA");
		texts(5, "LONG CYCLE", "SHORT CYCLE");
		texts(6, "ZERO", "~A", "A PLUS 1", "A MINUS 1", "A MINUS B", "A", "B", "MINUS ONE",
			"A PLUS B", "A · B", "~A · B", "A + B", "A XOR B", "A · ~B", "A · ~BX", "A · BX",
			"A PLUS B PLUS 1", "A PLUS BX", "A MINUS BX", "A PLUS BX PLUS 1", "A PLUS 2", "A MINUS 2",
			"A PLUS A", "BX", "~B", "~BX", "A PLUS A PLUS 1");
		texts(7, "", "AUX");
		texts(8, "HOLD", "LOAD B", "LOAD BX", "SHF LFT(BX-0), LOAD B", "SHF LFT(BX-COUT), LOAD B",
			"SHF LFT(BX-1), LOAD B", "SHF LFT(B-0)", "SHF LFT(B-0), LOAD BX", "SHF LFT(B-BX15)",
			"SHF LFT(BX-0)", "SHF LFT(BX-1)", "SHF LFT(BX-OVX)", "SHF LFT(BX-COUT)", "SHF LFT(B-BX-0)",
			"SHF RT(B15-B-BX)", "ENAB DBE");
		texts(9, "", "TRAN");
		texts(10, "", "MAINT");
		texts(11, "STRT", "SEX", "SWAB", "EXTRNL");
		texts(12, "DATI", "DATIP", "DATO", "DATOB");
		texts(13, "RBA", "RS", "RD", "ROM");
		//-- Value 15 is deliberately absent: the print set names fourteen of the sixteen.
		texts(14, "NOP", "N BIT", "Z BIT", "C05", "BOOT", "BX00", "BX01", "COUT", "NO SERV",
			"N BIT, Z BIT", "BX00, N BIT", "C05, BX01, BX00", "C05, BX01, BX00", "ALL", "BX00, C05");
		texts(15, "RS + 1", "");
		texts(16, "PREV MODE", "");
		texts(17, "", "SERV");
		texts(18, "", "FORCE KERNEL");
		texts(19, "NOP", "SRI LOW", "SRI HI", "ZERO SRI");
		texts(20, "I SPACE", "D SPACE");
		texts(21, "R0", "R1", "R2", "R3", "R4", "R5", "R6(SP)", "R7(PC)",
			"R10", "R11", "R12", "R13", "R14", "R15", "R16", "R17");
		texts(22, "RBA", "RD", "RS", "ROM");
		texts(23, "K0", "K16", "K26", "K366");
	}

	private static MicrocodeField field(int tag, String name, int def, int lsb, int len) {
		return new MicrocodeField(tag, name, def, lsb, len, TEXTS.getOrDefault(tag, Map.of()));
	}

	/**
	 * Every field, in the order the window lists them: from the top of the word downwards, KD11-Z
	 * first and then the FP11.
	 *
	 * <p>Bit 56 is in none of them - it is unused, and the Pascal says so with a comment where
	 * the entry would be.</p>
	 */
	public static final List<MicrocodeField> ALL;

	/**
	 * Where this microword goes next when nothing branches: the ten bits at the top of the word.
	 *
	 * <p>Named because it is the one field with behaviour rather than just a value - it is what
	 * {@link MicroInstruction#getNextAddress()} reads and what the microcode's own consistency
	 * check is built on.</p>
	 */
	public static final MicrocodeField NEXT_ADDRESS;

	private static final Map<MicrocodeField, Integer> INDEX = new IdentityHashMap<>();

	static {
		List<MicrocodeField> l = new ArrayList<>();
		//-- KD11-Z, the processor itself.
		l.add(field(1, "NEXT MICROWORD ADDRESS", -1, 93, 10));
		l.add(field(2, "AMUX CONTROL", 1, 91, 2));
		l.add(field(3, "MISC CONTROL", 0, 88, 3));
		l.add(field(4, "LOAD BA", 0, 87, 1));
		l.add(field(5, "CYCLE", 1, 86, 1));
		l.add(field(6, "ALU/BLEG CONTROL", 5, 81, 5));
		l.add(field(7, "AUX CONTROL", 0, 80, 1));
		l.add(field(8, "B,BX,OVX,DBE CONTROL", 0, 76, 4));
		l.add(field(9, "DATA TRAN", 0, 75, 1));
		l.add(field(10, "ENAB MAINT", 0, 74, 1));
		l.add(field(11, "SSMUX CONTROL", 0, 72, 2));
		l.add(field(12, "UNIBUS CONTROL", 0, 70, 2));
		l.add(field(13, "SCRATCH PAD DST SELECT", 0, 68, 2));
		l.add(field(14, "BUT ENABLE", 0, 64, 4));
		l.add(field(15, "SRC REG OR 1", 1, 63, 1));
		l.add(field(16, "PREVIOUS MODE", 1, 62, 1));
		l.add(field(17, "BUT SERV", 0, 61, 1));
		l.add(field(18, "FORCE KERNEL", 0, 60, 1));
		l.add(field(19, "SRI CONTROL", 0, 58, 2));
		l.add(field(20, "I/D SPACE", 0, 57, 1));
		//-- Bit 56 is not used.
		l.add(field(21, "ROM SCRATCH PAD ADDRESS", 15, 52, 4));
		l.add(field(22, "SCRATCH PAD SRC SELECT", 3, 50, 2));
		l.add(field(23, "S0/S1 CONSTANT CONTROL", 0, 48, 2));
		//-- FP11, the floating point processor. None of its values are named in the print set,
		//-- so these show as octal numbers against their defaults.
		l.add(field(51, "FCTL", 3, 42, 6));
		l.add(field(52, "ECTL", 3, 36, 6));
		l.add(field(53, "ECIN", 0, 35, 1));
		l.add(field(54, "BSEL", 3, 33, 2));
		l.add(field(55, "ASEL", 1, 32, 1));
		l.add(field(56, "TOUT", 0, 31, 1));
		l.add(field(57, "DCTL", 0, 27, 4));
		l.add(field(58, "XTRA", 0, 26, 1));
		l.add(field(59, "BUT", 0, 20, 6));
		l.add(field(60, "CONST", 8, 14, 6));
		l.add(field(61, "MISC", 9, 10, 4));
		l.add(field(62, "AROM", 0, 7, 3));
		l.add(field(63, "BROM", 0, 4, 3));
		l.add(field(64, "SECTOR", 15, 0, 4));
		ALL = List.copyOf(l);
		NEXT_ADDRESS = ALL.get(0);
		for(int i = 0; i < ALL.size(); i++)
			INDEX.put(ALL.get(i), i);
	}

	/** The field with this name, or {@code null}. For tests and for looking one up by hand. */
	public static MicrocodeField byName(String name) {
		for(MicrocodeField f : ALL) {
			if(f.name().equals(name))
				return f;
		}
		return null;
	}

	/**
	 * Where this field sits in {@link #ALL}, which is the index its value has in a microword.
	 *
	 * <p>Identity, not equality: the fields are the singletons in {@link #ALL} and a microword
	 * holds its values in an array parallel to it, so this has to be a lookup a record's
	 * component-by-component {@code equals} - which would compare the whole value-name map on
	 * every call - is not needed for.</p>
	 */
	public int index() {
		Integer i = INDEX.get(this);
		if(i == null)
			throw new IllegalArgumentException("Not one of the fields in MicrocodeField.ALL: " + this);
		return i;
	}
}
