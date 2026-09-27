package to.etc.pdp11.common.microcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One line of a microword's display: a label, the bits it comes from if any, and what it says.
 *
 * <p>The rows of {@code TFormMicroCode.UpdateDisplay} ({@code FormMicroCodeU.pas:160-274}), which
 * builds them by assigning into {@code StringGrid.Cells[]} - and works out which ones to
 * highlight in the paint handler afterwards, by subtracting 3 from the row number to get back to
 * a field index ({@code :341}). Here a row knows whether it is interesting, because the thing
 * that made the row knew. The desktop window shows them in a table and the web page in another;
 * what is in them, and in which order, is decided once, here.</p>
 *
 * @param highlight the field is doing something this cycle
 * @param differs   the other revision of the same board has something else here, which is the
 *                  only way a wrongly chosen revision ever shows itself
 * @param next      the row says where the microword goes next, so choosing it goes there
 */
public record MicrowordRow(String label, String bits, String info, boolean highlight, boolean differs, boolean next) {
	public MicrowordRow(String label, String bits, String info, boolean highlight, boolean differs) {
		this(label, bits, info, highlight, differs, false);
	}

	public MicrowordRow(String label, String bits, String info, boolean highlight) {
		this(label, bits, info, highlight, false);
	}

	/**
	 * The rows for one microword: its name and address, then every field with its bits and what
	 * its value means, then where it came from in the listing.
	 *
	 * @param predecessors what falls through to it, which the Pascal cannot work out
	 * @param differing    which fields the other revision has something else in, which is empty
	 *                     for a machine that has only one
	 * @param role         the routine it belongs to and what it does there, or {@code null} where
	 *                     nothing says
	 */
	public static List<MicrowordRow> of(MicroInstruction mi, List<MicroInstruction> predecessors,
		Set<MicrocodeField> differing, MicrowordRole role) {
		//-- The microword's own field table, not a static one: which fields there are is a
		//-- property of the machine the microword came off, and there is more than one machine.
		List<MicrowordRow> rows = new ArrayList<>();
		rows.add(new MicrowordRow("Symbolic tag", "", mi.getSymbolicTag(), false));
		rows.add(new MicrowordRow("Address", "", mi.getAddressOctal(), false));
		//-- The decoded successor, above the fields rather than inside them. On the KD11-B the
		//-- next-address bits are burned complemented, so the field below reads 215 where the
		//-- microword goes to 162, and a reader who sees only the field is being misled. And
		//-- where a microtest is selected the hardware ORs its result into those bits, so what is
		//-- printed is a branch base and not the successor: saying "next 162" flat would be
		//-- stating as fact something that depends on the state of the machine.
		rows.add(new MicrowordRow("Next microword", "", nextAddress(mi), false, false, true));
		//-- What it is for, above the bits: of everything here it is what a reader wants first,
		//-- and highlighted like the 11/44's source code row, which is the same thing said
		//-- another way. From DEC's microprogram flow, so a microword the flow leaves out has
		//-- no routine - and says so in its notes below - rather than a guessed one.
		if(role != null && role.routine() != null) {
			rows.add(new MicrowordRow("Part of", "", role.summary(), false));
			if(role.action() != null)
				rows.add(new MicrowordRow("Does", "", role.action(), true));
		}
		//-- The value in octal, and what it means where the print set says: "2 = DATO". Which
		//-- fields are doing something is decided in MicrowordFieldValue, for every view alike.
		for(MicrowordFieldValue v : MicrowordFieldValue.of(mi, differing))
			rows.add(new MicrowordRow(v.field().name(), v.field().bitRange(), v.info(), v.active(), v.differs(), v.nextAddress()));
		//-- Highlighted, like the Pascal highlights it: of everything here it is the one row that
		//-- says what the microword is for. A document that does not carry the microassembler
		//-- source - the KD11-B's is a bit table - gets no such row rather than an empty one.
		if(!mi.getOperations().isEmpty())
			rows.add(new MicrowordRow("Source code", "", String.join("  |  ", mi.getOperations()), true));
		//-- One row per comment, as printed: some microwords have a dozen - B2-2 is where every
		//-- instruction ends - and one cell holding them all is a cell nobody can read.
		if(role != null) {
			for(int i = 0; i < role.notes().size(); i++)
				rows.add(new MicrowordRow(i == 0 ? "Flow notes" : "", "", role.notes().get(i), false));
		}
		rows.add(new MicrowordRow("Jumped to from", "", describe(predecessors), false));
		rows.add(new MicrowordRow("Listing file", "", mi.getSourceName(), false));
		rows.add(new MicrowordRow("Listing line#", "", String.valueOf(mi.getLineNumber()), false));
		if(role != null)
			rows.add(new MicrowordRow("Flow", "", role.source(), false));
		return List.copyOf(rows);
	}

	/** Where this microword goes, and how much of that the document actually settles. */
	private static String nextAddress(MicroInstruction mi) {
		if(!mi.isBranching())
			return mi.getNextAddressOctal();
		return mi.getNextAddressOctal() + "  -  a branch base: the " + mi.getMicrotestName()
			+ " microtest replaces some of these bits with what it finds";
	}

	/**
	 * The microwords that fall through to this one, or why there are none listed.
	 *
	 * <p>Nothing falling through is normal and does not mean unreachable: the interesting
	 * microwords are the ones a branch lands on, and a branch target is chosen by hardware
	 * substituting bits into the next address, which no listing spells out.</p>
	 */
	private static String describe(List<MicroInstruction> predecessors) {
		if(predecessors.isEmpty())
			return "nothing falls through to it - it is reached by a branch, or it is a starting point";
		List<String> tags = new ArrayList<>();
		for(MicroInstruction mi : predecessors)
			tags.add(mi.getSymbolicTag() + " (" + mi.getAddressOctal() + ")");
		return String.join(", ", tags);
	}
}
