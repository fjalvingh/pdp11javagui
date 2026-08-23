package to.etc.pdp11.ui.microcode;

import to.etc.pdp11.core.microcode.MicroInstruction;
import to.etc.pdp11.core.microcode.MicrocodeField;
import to.etc.pdp11.core.util.Octal;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

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
	/** One line of the display: a label, the bits it comes from if any, and what it says. */
	public record Row(String label, String bits, String info, boolean highlight) {
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
		m_rows = mi == null ? List.of() : build(mi, predecessors);
		fireTableDataChanged();
	}

	private static List<Row> build(MicroInstruction mi, List<MicroInstruction> predecessors) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Row("Symbolic tag", "", mi.getSymbolicTag(), false));
		rows.add(new Row("Address", "", mi.getAddressOctal(), false));
		for(MicrocodeField f : MicrocodeField.ALL) {
			int value = mi.getValue(f);
			String text = mi.getText(f);
			//-- The value in octal, and what it means where the print set says: "2 = DATO". A
			//-- field whose value is named as nothing, and one the print set does not name at
			//-- all, both show as the number by itself.
			String info = octal(value, f.length());
			if(text != null && !text.isEmpty())
				info = info + " = " + text;
			//-- Highlighted when the field is doing something, which needs it to have a resting
			//-- value to differ from. The next-address field has none - it is different in every
			//-- microword - and the Pascal highlights it in every microword as a result
			//-- ({@code FormMicroCodeU.pas:341}, where the default is -1 and the comparison can
			//-- never be equal). A row that is always yellow says nothing; this one is not.
			rows.add(new Row(f.name(), f.bitRange(), info, f.hasDefault() && !mi.isDefault(f)));
		}
		//-- Highlighted, like the Pascal highlights it: of everything here it is the one row that
		//-- says what the microword is for.
		rows.add(new Row("Source code", "", String.join("  |  ", mi.getOperations()), true));
		rows.add(new Row("Jumped to from", "", describe(predecessors), false));
		rows.add(new Row("Listing file", "", mi.getSourceName(), false));
		rows.add(new Row("Listing line#", "", String.valueOf(mi.getLineNumber()), false));
		return List.copyOf(rows);
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
