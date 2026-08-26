package to.etc.pdp11.core.microcode;

import to.etc.pdp11.core.util.Octal;

import java.util.List;

/**
 * One microword: where it lives, what it does, and where in its source document it was read from.
 *
 * <p>Ported from {@code TPDP1144MicroInstruction} ({@code Pdp1144MicroCodeU.pas:288-320}).
 * Immutable here, where the Pascal parses into itself in two steps - {@code Parse} then
 * {@code BuildFields} - and leaves a half-built object behind when the first of them throws.</p>
 *
 * <p>A microword is only meaningful against the field table it was decoded through, so it
 * carries the {@link MicrocodeArchitecture} it was decoded against rather than trusting whoever
 * looks at it to have the right one. Asking for a field belonging to another machine's table
 * throws instead of quietly reading somebody else's bits.</p>
 *
 * <h2>Where the bits are, on the 11/44</h2>
 *
 * <p>Its listing prints nine octal numbers per microword, which are not the fields: they are the
 * 104-bit word chopped into the twelve-bit chunks a line printer could line up. The chunks are
 * printed most significant first and held here least significant first, index 0 to 8, which is
 * the Pascal's {@code raw_code} order and the order the reassembly comment in
 * {@code BuildFields} ({@code Pdp1144MicroCodeU.pas:487-499}) is written in:</p>
 *
 * <pre>
 * raw word   bits of the microword
 *   [8]        103:93     (only 11 of its bits are used)
 *   [7]         92:81
 *   [6]         80:69
 *   [5]         68:57
 *   [4]         56:45
 *   [3]         44:33
 *   [2]         32:21
 *   [1]         20:9
 *   [0]          8:0      (9 bits)
 * </pre>
 */
public final class MicroInstruction {
	private final MicrocodeArchitecture m_architecture;

	private final int m_address;

	private final int m_nextAddress;

	private final String m_symbolicTag;

	private final String m_sortableTag;

	private final int m_lineNumber;

	private final String m_sourceName;

	private final int m_fileLine;

	private final String m_text;

	private final int[] m_rawWords;

	private final int[] m_fieldValues;

	private final List<String> m_operations;

	MicroInstruction(MicrocodeArchitecture architecture, int address, int nextAddress, String symbolicTag,
		String sortableTag, int lineNumber, String sourceName, int fileLine, String text, int[] rawWords,
		int[] fieldValues, List<String> operations) {
		if(fieldValues.length != architecture.size())
			throw new IllegalArgumentException(architecture.getName() + " has " + architecture.size()
				+ " fields, decoded " + fieldValues.length);
		m_architecture = architecture;
		m_address = address;
		m_nextAddress = nextAddress;
		m_symbolicTag = symbolicTag;
		m_sortableTag = sortableTag;
		m_lineNumber = lineNumber;
		m_sourceName = sourceName;
		m_fileLine = fileLine;
		m_text = text;
		m_rawWords = rawWords;
		m_fieldValues = fieldValues;
		m_operations = List.copyOf(operations);
	}

	/** Which processor's field table this microword was decoded against. */
	public MicrocodeArchitecture getArchitecture() {
		return m_architecture;
	}

	/** That architecture's fields, in the order a window lists them. */
	public List<MicrocodeField> getFields() {
		return m_architecture.getFields();
	}

	/** Where this microword sits in the control store. */
	public int getAddress() {
		return m_address;
	}

	/**
	 * The microword after this one when nothing branches.
	 *
	 * <p>Straight out of the {@code NEXT MICROWORD ADDRESS} field, which every microword carries:
	 * this machine has no microprogram counter that counts, so "the next instruction" is
	 * something each microword says for itself. A microword that branches - one with a
	 * {@code BUT ENABLE} - reaches somewhere else instead, by having some of these ten bits
	 * replaced in hardware by what was tested. So this is the fall-through, and the only target
	 * that can be read off the listing.</p>
	 */
	public int getNextAddress() {
		return m_nextAddress;
	}

	/** The name the microcode gives this address, like {@code 2-J} or {@code FP15-C}. */
	public String getSymbolicTag() {
		return m_symbolicTag;
	}

	/**
	 * The same tag rewritten so that sorting it puts the microcode in listing order.
	 *
	 * <p>A tag is {@code [FP]<page>-<block>} and plain text sorting gets it wrong twice over:
	 * {@code 20-Z} sorts before {@code 2-I} because {@code 0} precedes {@code -}, and the FP
	 * pages interleave with the CPU ones. Two-digit page numbers and an {@code AA} prefix where
	 * there is no {@code FP} fixes both, which is what {@code Pdp1144MicroCodeU.pas:443-465}
	 * does.</p>
	 */
	public String getSortableTag() {
		return m_sortableTag;
	}

	/** The line number the listing itself prints, after the semicolon: the {@code ;1062}. */
	public int getLineNumber() {
		return m_lineNumber;
	}

	/** Which listing this came out of. */
	public String getSourceName() {
		return m_sourceName;
	}

	/** Which physical line of that file the microword starts on, 1-based. */
	public int getFileLine() {
		return m_fileLine;
	}

	/** The listing line, continuations and all, exactly as it was read. */
	public String getText() {
		return m_text;
	}

	/**
	 * What the microword does, as the microassembler source that produced it:
	 * {@code [DATO, UDATA, J/2-L]}.
	 *
	 * <p>The listing prints these comma-separated after the tag and they are the only readable
	 * account of the microword there is - the fields say which control lines are asserted, this
	 * says what for. {@code Cleanup} ({@code Pdp1144MicroCodeU.pas:767-784}) rewrites the
	 * microassembler's {@code _} assignment operator as {@code :=} and that is done here, at
	 * parse time: {@code R0_R0+1} reads as {@code R0:=R0+1}.</p>
	 */
	public List<String> getOperations() {
		return m_operations;
	}

	/** The nine listing words, least significant first. A copy: nothing here is writable. */
	public int[] getRawWords() {
		return m_rawWords.clone();
	}

	/** What this microword has in that field. */
	public int getValue(MicrocodeField field) {
		return m_fieldValues[m_architecture.indexOf(field)];
	}

	/** What that value means, or {@code null} when the print set does not name it. */
	public String getText(MicrocodeField field) {
		return field.text(getValue(field));
	}

	/**
	 * Whether the hardware replaces part of this microword's next address with a test result.
	 *
	 * <p>When it does, {@link #getNextAddress()} is where the microword goes only if the test
	 * comes out zero: it is a branch base rather than the successor. Nothing in a listing says
	 * where the other targets are, because they depend on the state of the machine.</p>
	 */
	public boolean isBranching() {
		MicrocodeField test = m_architecture.getMicrotestField();
		return test != null && !isDefault(test);
	}

	/** What is being tested, or {@code null} when nothing is. */
	public String getMicrotestName() {
		MicrocodeField test = m_architecture.getMicrotestField();
		return test == null || !isBranching() ? null : getText(test);
	}

	/**
	 * Why this field's printed value is not what the machine does, or {@code null} when it is.
	 *
	 * <p>The KD11-B has microwords where the ALU control comes from the instruction register
	 * rather than from the {@code ALU} field beside it. Showing that field as an operation would
	 * be showing something the machine is not doing.</p>
	 */
	public String getDontCareReason(MicrocodeField field) {
		return m_architecture.dontCareReason(this, field);
	}

	/**
	 * Whether this field is doing nothing this cycle.
	 *
	 * <p>The question the window is really asking when it highlights: of all the fields, the two
	 * or three that are <i>not</i> at their default are what this microword is about.</p>
	 */
	public boolean isDefault(MicrocodeField field) {
		return field.hasDefault() && getValue(field) == field.defaultValue();
	}

	/**
	 * The address as the source document writes it: as many octal digits as the control store
	 * is wide - four on the 11/44, three on a machine with a 256 word store.
	 */
	public String getAddressOctal() {
		return Octal.format(m_address, addressDigits());
	}

	/** The next address, in the same width. */
	public String getNextAddressOctal() {
		return Octal.format(m_nextAddress, addressDigits());
	}

	private int addressDigits() {
		return Octal.digitsForBits(m_architecture.getAddressBits());
	}

	@Override
	public String toString() {
		return getAddressOctal() + " " + m_symbolicTag + ": " + String.join(",", m_operations);
	}
}
