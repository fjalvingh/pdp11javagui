package to.etc.pdp11.ui.microcode;

import to.etc.pdp11.core.microcode.MicroInstruction;
import to.etc.pdp11.core.microcode.MicrocodeField;
import to.etc.pdp11.core.util.Octal;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One microword as a table: its name and address, then every field with its bits and what its
 * value means, then where it came from in the listing.
 *
 * <p>The rows of {@code TFormMicroCode.UpdateDisplay} ({@code FormMicroCodeU.pas:160-274}), which
 * builds them by assigning into {@code StringGrid.Cells[]} - and works out which ones to
 * highlight in the paint handler afterwards, by subtracting 3 from the row number to get back to
 * a field index ({@code :341}). Here a row knows whether it is interesting, because the thing
 * that made the row knew.</p>
 */
public final class MicrocodeTableModel extends AbstractTableModel {
	/**
	 * One line of the display: a label, the bits it comes from if any, and what it says.
	 *
	 * @param highlight the field is doing something this cycle
	 * @param differs   the other revision of the same board has something else here, which is the
	 *                  only way a wrongly chosen revision ever shows itself
	 */
	public record Row(String label, String bits, String info, boolean highlight, boolean differs) {
		public Row(String label, String bits, String info, boolean highlight) {
			this(label, bits, info, highlight, false);
		}
	}

	private static final String[] COLUMNS = {"Field", "Bits", "Info"};

	/** Wide enough for the widest field name and for {@code 102:93}. */
	static final int[] WIDTHS = {200, 70, 520};

	private List<Row> m_rows = List.of();

	/**
	 * Show this microword, or nothing.
	 *
	 * @param mi           the microword, or {@code null} for "nothing loaded"
	 * @param predecessors what falls through to it, which the Pascal cannot work out
	 */
	public void setInstruction(MicroInstruction mi, List<MicroInstruction> predecessors) {
		setInstruction(mi, predecessors, Set.of());
	}

	/**
	 * Show this microword, marking the fields another revision of the same board disagrees on.
	 *
	 * @param differing which fields the other revision has something else in, which is empty for
	 *                  a machine that has only one
	 */
	public void setInstruction(MicroInstruction mi, List<MicroInstruction> predecessors,
		Set<MicrocodeField> differing) {
		m_rows = mi == null ? List.of() : build(mi, predecessors, differing);
		fireTableDataChanged();
	}

	private static List<Row> build(MicroInstruction mi, List<MicroInstruction> predecessors,
		Set<MicrocodeField> differing) {
		//-- The microword's own field table, not a static one: which fields there are is a
		//-- property of the machine the microword came off, and there is more than one machine.
		List<Row> rows = new ArrayList<>();
		rows.add(new Row("Symbolic tag", "", mi.getSymbolicTag(), false));
		rows.add(new Row("Address", "", mi.getAddressOctal(), false));
		//-- The decoded successor, above the fields rather than inside them. On the KD11-B the
		//-- next-address bits are burned complemented, so the field below reads 215 where the
		//-- microword goes to 162, and a reader who sees only the field is being misled. And
		//-- where a microtest is selected the hardware ORs its result into those bits, so what is
		//-- printed is a branch base and not the successor: saying "next 162" flat would be
		//-- stating as fact something that depends on the state of the machine.
		rows.add(new Row("Next microword", "", nextAddress(mi), false));
		for(MicrocodeField f : mi.getFields()) {
			int value = mi.getValue(f);
			String text = mi.getText(f);
			String dontCare = mi.getDontCareReason(f);
			//-- The value in octal, and what it means where the print set says: "2 = DATO". A
			//-- field whose value is named as nothing, and one the print set does not name at
			//-- all, both show as the number by itself.
			String info = octal(value, f.length());
			if(text != null && !text.isEmpty())
				info = info + " = " + text;
			//-- A field whose value is not what the machine does says so instead of saying what
			//-- it would have meant. The KD11-B's ALU field is this in nine microwords.
			if(dontCare != null)
				info = info + "  -  don't care: " + dontCare;
			//-- Highlighted when the field is doing something, which needs it to have a resting
			//-- value to differ from. The next-address field has none - it is different in every
			//-- microword - and the Pascal highlights it in every microword as a result
			//-- ({@code FormMicroCodeU.pas:341}, where the default is -1 and the comparison can
			//-- never be equal). A row that is always yellow says nothing; this one is not.
			rows.add(new Row(f.name(), f.bitRange(), info,
				f.hasDefault() && !mi.isDefault(f) && dontCare == null, differing.contains(f)));
		}
		//-- Highlighted, like the Pascal highlights it: of everything here it is the one row that
		//-- says what the microword is for. A document that does not carry the microassembler
		//-- source - the KD11-B's is a bit table - gets no such row rather than an empty one.
		if(!mi.getOperations().isEmpty())
			rows.add(new Row("Source code", "", String.join("  |  ", mi.getOperations()), true));
		rows.add(new Row("Jumped to from", "", describe(predecessors), false));
		rows.add(new Row("Listing file", "", mi.getSourceName(), false));
		rows.add(new Row("Listing line#", "", String.valueOf(mi.getLineNumber()), false));
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

	/** Octal, padded to the digits the field's width needs. */
	private static String octal(int value, int bits) {
		return Octal.format(value, Octal.digitsForBits(bits));
	}

	public Row getRow(int row) {
		return m_rows.get(row);
	}

	@Override
	public int getRowCount() {
		return m_rows.size();
	}

	@Override
	public int getColumnCount() {
		return COLUMNS.length;
	}

	@Override
	public String getColumnName(int column) {
		return COLUMNS[column];
	}

	@Override
	public Object getValueAt(int row, int column) {
		Row r = m_rows.get(row);
		return switch(column) {
			case 0 -> r.label();
			case 1 -> r.bits();
			case 2 -> r.info();
			default -> "";
		};
	}
}
