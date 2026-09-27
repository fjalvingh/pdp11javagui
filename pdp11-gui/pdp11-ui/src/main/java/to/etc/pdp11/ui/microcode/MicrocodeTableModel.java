package to.etc.pdp11.ui.microcode;

import to.etc.pdp11.common.microcode.MicrowordRow;

import javax.swing.table.AbstractTableModel;
import java.util.List;

/**
 * One microword as a table: the rows {@link MicrowordRow#of} makes, one per line, in three
 * columns. What the rows say is decided in pdp11-common, where the web page reads the same ones.
 */
public final class MicrocodeTableModel extends AbstractTableModel {
	private static final String[] COLUMNS = {"Field", "Bits", "Info"};

	/** Wide enough for the widest field name and for {@code 102:93}. */
	static final int[] WIDTHS = {200, 70, 520};

	private List<MicrowordRow> m_rows = List.of();

	/** Show these rows; none for "nothing loaded". */
	public void setRows(List<MicrowordRow> rows) {
		m_rows = rows;
		fireTableDataChanged();
	}

	public MicrowordRow getRow(int row) {
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
		MicrowordRow r = m_rows.get(row);
		return switch(column) {
			case 0 -> r.label();
			case 1 -> r.bits();
			case 2 -> r.info();
			default -> "";
		};
	}
}
