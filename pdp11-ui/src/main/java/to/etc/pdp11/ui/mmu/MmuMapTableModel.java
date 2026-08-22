package to.etc.pdp11.ui.mmu;

import to.etc.pdp11.core.mmu.MmuMemoryMap;

import javax.swing.table.AbstractTableModel;
import java.util.List;

/**
 * One {@link MmuMemoryMap} as table rows: the block number, where it is virtually, where it
 * really is, and how big it is.
 *
 * <p>The four columns of {@code UpdateMemoryMapGrid}'s {@code TStringGrid}
 * ({@code FormMmuU.pas:99-105}), which builds them by assigning into {@code grid.Cells[]} while
 * walking address space. Here the walk produced a list and this only reads it.</p>
 */
public final class MmuMapTableModel extends AbstractTableModel {
	private static final String[] COLUMNS = {"#", "Virtual", "Physical", "Size"};

	/** Wide enough for the widest thing each column holds: an eight-digit octal pair. */
	static final int[] WIDTHS = {40, 180, 220, 140};

	private List<MmuMemoryMap.Block> m_blocks = List.of();

	public void setMap(MmuMemoryMap map) {
		m_blocks = map == null ? List.of() : map.blocks();
		fireTableDataChanged();
	}

	public MmuMemoryMap.Block getBlock(int row) {
		return m_blocks.get(row);
	}

	@Override
	public int getRowCount() {
		return m_blocks.size();
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
		MmuMemoryMap.Block b = m_blocks.get(row);
		return switch(column) {
			case 0 -> String.valueOf(b.number());
			case 1 -> b.virtualRange();
			case 2 -> b.physicalRange();
			//-- Bytes, and the kilobytes nobody wants to work out from them. The I/O page is
			//-- worth naming: it is the one region that is there whatever the MMU is set to.
			case 3 -> size(b);
			default -> "";
		};
	}

	private static String size(MmuMemoryMap.Block b) {
		long bytes = b.byteCount();
		String size = bytes >= 1024 && bytes % 1024 == 0
			? bytes + " bytes (" + bytes / 1024 + " KB)"
			: bytes + " bytes";
		return b.ioPage() ? size + ", I/O page" : size;
	}
}
