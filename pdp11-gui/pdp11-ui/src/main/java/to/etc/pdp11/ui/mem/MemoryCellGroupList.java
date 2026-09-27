package to.etc.pdp11.ui.mem;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellListener;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.ProgressDialog;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Named memory cells as a list: register, address, value, description.
 *
 * <p>Ported from {@code TFrameMemoryCellGroupList} ({@code FrameMemoryCellGroupListU.pas}), the
 * second of the two reusable frames PLAN.md §3 says to build - the one for a group whose cells
 * have <b>names</b> rather than a contiguous range. That is the register groups the machine
 * description creates, the I/O page scanner's findings, and the memory loader's and dumper's
 * lists.</p>
 *
 * <p>Where {@link MemoryCellGroupTable} lays cells out by address across a grid, this shows one
 * cell per row in the group's own order and has room for what the machine description knows
 * about each: {@code PSW}, {@code 0177776}, and "Processor Status Word".</p>
 *
 * <h2>Two cells at one address is normal here</h2>
 *
 * <p>The RX211's data buffer is declared six times under six names because the controller
 * reinterprets it at each stage of a transfer. A list shows all six rows; they hold the same
 * word, and the propagation bus keeps them agreeing. That is a feature of this view and the
 * reason the register windows use it rather than a grid.</p>
 */
public final class MemoryCellGroupList extends JPanel {
	private static final int COL_NAME = 0;

	private static final int COL_ADDRESS = 1;

	private static final int COL_VALUE = 2;

	private static final int COL_INFO = 3;

	private final AppContext m_context;

	private final ListModel m_model = new ListModel();

	private final JTable m_table = new JTable(m_model);

	private final JScrollPane m_scroll = new JScrollPane(m_table);

	private MemoryCellGroup m_group;

	private List<MemoryCell> m_rows = List.of();

	private Runnable m_onUpdate = () -> {
	};

	/** Told which cell the user is looking at, for the Bitfields window to follow. */
	private Consumer<MemoryCell> m_onSelect = mc -> {
	};

	private final MemoryCellListener m_listener = (group, cell) -> AppContext.onUi(() -> {
		//-- What the machine says becomes what is shown. Only reached when the group allows it.
		cell.setEditValue(cell.getPdpValue());
		repaintCell(cell);
	});

	public MemoryCellGroupList(AppContext context) {
		super(new MigLayout("fill, insets 0", "[grow]", "[grow]"));
		m_context = context;

		m_table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_table.getFont().getSize()));
		m_table.getTableHeader().setReorderingAllowed(false);
		m_table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		m_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		m_table.setDefaultRenderer(Object.class, new RowRenderer());
		m_table.setDefaultEditor(Object.class, new OctalCellEditor());
		m_table.setSurrendersFocusOnKeystroke(true);
		//-- See MemoryCellGroupTable: a JTable only hands its header to the scroll pane from
		//-- addNotify(), which never runs on a component laid out with no display.
		m_scroll.setColumnHeaderView(m_table.getTableHeader());
		m_table.getSelectionModel().addListSelectionListener(e -> {
			if(!e.getValueIsAdjusting())
				m_onSelect.accept(getSelectedCell());
		});
		sizeColumns();
		add(m_scroll, "grow");
	}

	/**
	 * The Pascal measures these from the font ({@code :270-273}); the widest thing each column
	 * has to hold is the same here.
	 *
	 * <p>A minimum as well as a preferred width, because a table with an auto-resize mode
	 * redistributes preferred widths on its first layout pass and keeps the result - so
	 * preferred alone lasts exactly until the window is shown.</p>
	 */
	private void sizeColumns() {
		fix(COL_NAME, 130);
		fix(COL_ADDRESS, 100);
		fix(COL_VALUE, 80);
		//-- And Info takes whatever is left, which is FrameResize's whole job ({@code :117-127}).
		m_table.getColumnModel().getColumn(COL_INFO).setPreferredWidth(320);
		m_table.getColumnModel().getColumn(COL_INFO).setMinWidth(80);
	}

	private void fix(int column, int width) {
		m_table.getColumnModel().getColumn(column).setMinWidth(width);
		m_table.getColumnModel().getColumn(column).setPreferredWidth(width);
	}

	public JTable getTable() {
		return m_table;
	}

	public JScrollPane getScroll() {
		return m_scroll;
	}

	public MemoryCellGroup getGroup() {
		return m_group;
	}

	public void setOnUpdate(Runnable onUpdate) {
		m_onUpdate = onUpdate == null ? () -> {
		} : onUpdate;
	}

	/**
	 * Told when the selected cell changes.
	 *
	 * <p>This is {@code SyncBitfieldForm} ({@code :108-114}) without the reach-in: there the
	 * frame calls {@code FormMain.SyncBitfieldForm} by name, so the list knows the main form
	 * exists and the main form knows the Bitfields window exists. Here it says which cell is
	 * selected and whoever cares subscribes.</p>
	 */
	public void setOnSelect(Consumer<MemoryCell> onSelect) {
		m_onSelect = onSelect == null ? mc -> {
		} : onSelect;
	}

	// -------------------------------------------------------------------------------------
	// Connecting to a group
	// -------------------------------------------------------------------------------------

	/** Show this group, and follow it. Ported from {@code ConnectToMemoryCellGroup}. */
	public void connectTo(MemoryCellGroup group) {
		if(m_group != null && m_group != group)
			m_group.removeListener(m_listener);
		boolean isNew = m_group != group;
		m_group = group;
		if(isNew && group != null)
			group.addListener(m_listener);
		rebuild();
	}

	/**
	 * Stop following, and show nothing.
	 *
	 * <p>{@code Disconnect} ({@code :350-356}) exists for a reason worth keeping in the comment
	 * rather than in the code: there, the grid holds raw pointers to {@code TMemoryCell} objects
	 * in {@code Objects[]}, so a repaint during a rescan - and the scan is full of
	 * {@code ProcessMessages} - would paint from freed memory. None of that can happen here; what
	 * survives is the useful half, which is dropping the subscription before the cells go.</p>
	 */
	public void disconnect() {
		if(m_group != null)
			m_group.removeListener(m_listener);
		m_group = null;
		rebuild();
	}

	/** Take the group's cells again, in its order, after they have been rebuilt. */
	public void rebuild() {
		m_rows = m_group == null ? List.of() : List.copyOf(m_group.getCells());
		m_model.fireTableDataChanged();
		m_onUpdate.run();
	}

	public MemoryCell cellAt(int row) {
		return row >= 0 && row < m_rows.size() ? m_rows.get(row) : null;
	}

	public MemoryCell getSelectedCell() {
		return cellAt(m_table.getSelectedRow());
	}

	public int getRowCount() {
		return m_rows.size();
	}

	private void repaintCell(MemoryCell cell) {
		int row = m_rows.indexOf(cell);
		if(row < 0)
			return;
		updateOverwritePolicy();
		m_model.fireTableRowsUpdated(row, row);
		m_onUpdate.run();
	}

	public void refresh() {
		updateOverwritePolicy();
		m_model.fireTableDataChanged();
		m_onUpdate.run();
	}

	/** See {@link MemoryCellGroupTable} - incoming values wait while there are edits to protect. */
	private void updateOverwritePolicy() {
		if(m_group != null)
			m_group.setPdpOverwritesEdit(getEditedCells().isEmpty());
	}

	public List<MemoryCell> getEditedCells() {
		List<MemoryCell> l = new ArrayList<>();
		if(m_group != null) {
			for(MemoryCell mc : m_group.getCells()) {
				if(mc.isEdited())
					l.add(mc);
			}
		}
		return l;
	}

	// -------------------------------------------------------------------------------------
	// Operations
	// -------------------------------------------------------------------------------------

	/** Read the whole group back, and show what the machine said. */
	public void examineAll(Window owner) {
		MemoryCellGroup group = m_group;
		if(group == null)
			return;
		ProgressDialog progress = new ProgressDialog(owner);
		m_context.onConsole("Examining registers", console -> {
			//-- Snapshot before the long job; see MemoryCellGroupTable.examineAll.
			List<MemoryCell> cells = List.copyOf(group.getCells());
			console.examine(group, false, progress);
			if(progress.isCancelled() || !group.holdsExactly(cells)) {
				AppContext.onUi(this::refresh);
				return;
			}
			for(MemoryCell mc : cells) {
				if(mc.getPdpValue().isKnown())
					mc.setEditValue(mc.getPdpValue());
			}
			AppContext.onUi(this::refresh);
		});
	}

	/** Read one cell back. */
	public void examineCell(MemoryCell cell) {
		if(cell == null)
			return;
		m_context.onConsole("Examining " + cell.getAddr().toOctal(), console -> {
			CellValue v = console.examine(cell.getAddr());
			cell.setPdpValue(v);
			cell.setEditValue(v);
			if(cell.getGroup().getOwner() != null)
				cell.getGroup().getOwner().syncMemoryCells(cell);
			AppContext.onUi(() -> repaintCell(cell));
		});
	}

	/** Write the group to the machine. {@code optimize} skips values it already holds. */
	public void depositAll(boolean optimize, Window owner) {
		MemoryCellGroup group = m_group;
		if(group == null)
			return;
		ProgressDialog progress = new ProgressDialog(owner);
		m_context.onConsole("Depositing registers", console -> {
			console.deposit(group, optimize, progress);
			AppContext.onUi(this::refresh);
		});
	}

	// -------------------------------------------------------------------------------------
	// The model
	// -------------------------------------------------------------------------------------

	private final class ListModel extends AbstractTableModel {
		@Override
		public int getRowCount() {
			return m_rows.size();
		}

		@Override
		public int getColumnCount() {
			return 4;
		}

		@Override
		public String getColumnName(int column) {
			return switch(column) {
				case COL_NAME -> "Register";
				case COL_ADDRESS -> "Address";
				case COL_VALUE -> "Value";
				default -> "Info";
			};
		}

		/** Only the value column, exactly as {@code MemoryCellsStringGridSelectCell} decides. */
		@Override
		public boolean isCellEditable(int row, int column) {
			return column == COL_VALUE && cellAt(row) != null;
		}

		@Override
		public Object getValueAt(int row, int column) {
			MemoryCell mc = cellAt(row);
			if(mc == null)
				return "";
			return switch(column) {
				case COL_NAME -> mc.getName();
				case COL_ADDRESS -> mc.getAddr().toOctal();
				case COL_VALUE -> mc.getEditValue().toOctal();
				default -> mc.getInfo();
			};
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			MemoryCell mc = cellAt(row);
			if(mc == null || column != COL_VALUE)
				return;
			String text = value == null ? "" : value.toString().trim();
			try {
				mc.setEditValue(text.isEmpty() ? CellValue.UNKNOWN : CellValue.parseOctal(text));
			} catch(NumberFormatException x) {
				return;
			}
			updateOverwritePolicy();
			m_onUpdate.run();
		}
	}

	/** The read-only columns recede; a value typed and not deposited stands out. */
	private final class RowRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
			boolean focused, int row, int column) {
			Component c = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
			MemoryCell mc = cellAt(row);
			if(column == COL_VALUE && mc != null && mc.isEdited()) {
				c.setBackground(UiColors.EDITED_BACKGROUND);
				c.setForeground(UiColors.EDITED_TEXT);
			} else {
				c.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
				if(column == COL_VALUE && mc != null && !mc.getPdpValue().isKnown())
					c.setForeground(UiColors.UNKNOWN_TEXT);
				else if(column == COL_INFO)
					c.setForeground(UiColors.SECONDARY_TEXT);
				else
					c.setForeground(selected ? table.getSelectionForeground() : table.getForeground());
			}
			if(c instanceof JComponent jc)
				jc.setToolTipText(mc == null || mc.getInfo().isEmpty() ? null : mc.getInfo());
			return c;
		}
	}

	/** The background this cell would be painted with. For tests. */
	public Color backgroundOf(int row, int column) {
		return m_table.prepareRenderer(m_table.getCellRenderer(row, column), row, column).getBackground();
	}
}
