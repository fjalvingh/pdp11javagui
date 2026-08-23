package to.etc.pdp11.ui.mem;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellListener;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory as an editable grid: one row per {@code columns} words, addresses down the left and
 * {@code +0 +2 +4 ...} across the top.
 *
 * <p>Ported from {@code TFrameMemoryCellGroupGrid} ({@code FrameMemoryCellGroupGridU.pas}), one
 * of the two reusable frames PLAN.md §3 says to build early because seven forms use them. A
 * {@code JPanel}, so it can be laid out and rendered with no display.</p>
 *
 * <h2>pdpOverwritesEdit, and why a value arriving must not always be shown</h2>
 *
 * <p>The German comment at the top of the Pascal frame ({@code :33-48}) is a specification, and
 * it describes a real bug it is there to prevent. A grid the user has been typing into is full
 * of edited values that have not been deposited yet. Another window - the disassembler, say -
 * examines the same addresses, and the propagation bus offers every one of those values to this
 * grid. Take them and the user's typing is silently replaced by what is already in the machine,
 * which is precisely what they were about to change. The per-group opt-out is
 * {@link MemoryCellGroup#setPdpOverwritesEdit}, and a grid built with
 * {@link OverwritePolicy#FOLLOW_EDITS} turns it off while it holds uncommitted edits.</p>

 * <p>That is a policy about the <i>group</i>, not about the grid, so a grid whose group has
 * already made the decision must not take it over: the loader, the dumper and the assembler
 * code window each turn the flag off permanently in their constructors, and a grid that reset
 * it from "are there edits right now" undid that on the first refresh after a successful
 * deposit. Those windows use {@link OverwritePolicy#GROUP_DECIDES}, which is the default.</p>
 *
 * <h2>Two cells at one address is normal</h2>
 *
 * <p>The grid is laid out by <i>address</i>, not by cell index - the address span of the group
 * divided across the columns - so gaps in the addresses show as empty grid squares, which is
 * what a group built from a MACRO-11 listing looks like. A cell whose address is outside the
 * span simply has nowhere to go and is not shown.</p>
 */
public final class MemoryCellGroupTable extends JPanel {
	/** Who decides whether values arriving from elsewhere may overwrite this grid's cells. */
	public enum OverwritePolicy {
		/**
		 * The group's own {@code pdpOverwritesEdit} is left exactly as its owner set it. For
		 * every window whose group is a document rather than a view of the machine - a loaded
		 * file, a dump to be written, an assembled program - because those turn it off once and
		 * mean it.
		 */
		GROUP_DECIDES,

		/**
		 * Follow whether the grid currently holds uncommitted edits: protected while there is
		 * something to protect, tracking the machine when there is not. For the plain memory
		 * window, which is a view of the machine that happens to be typeable.
		 */
		FOLLOW_EDITS
	}

	/** What the Pascal's constructor sets ({@code :131}). Eight words is 16 bytes a row. */
	public static final int DEFAULT_COLUMNS = 8;

	private final AppContext m_context;

	private final int m_columns;

	private final OverwritePolicy m_overwritePolicy;

	private final GridModel m_model = new GridModel();

	private final JTable m_table = new JTable(m_model);

	private final JScrollPane m_scroll = new JScrollPane(m_table);

	private MemoryCellGroup m_group;

	/** Where each cell sits in the grid: {@code row * columns + column - 1} of the address span. */
	private MemoryCell[] m_slots = new MemoryCell[0];

	private Address m_firstAddress;

	private Runnable m_onUpdate = () -> {
	};

	/** Subscribed to the group, and unsubscribed when this table is pointed at another one. */
	private final MemoryCellListener m_listener = (group, cell) -> AppContext.onUi(() -> {
		//-- What the machine says becomes what is shown, which is the Pascal's memoryCellChange
		//-- ({@code :508-513}). It is only reached at all when the group allows it.
		cell.setEditValue(cell.getPdpValue());
		repaintCell(cell);
	});

	public MemoryCellGroupTable(AppContext context) {
		this(context, DEFAULT_COLUMNS, OverwritePolicy.GROUP_DECIDES);
	}

	public MemoryCellGroupTable(AppContext context, OverwritePolicy overwritePolicy) {
		this(context, DEFAULT_COLUMNS, overwritePolicy);
	}

	public MemoryCellGroupTable(AppContext context, int columns, OverwritePolicy overwritePolicy) {
		super(new MigLayout("fill, insets 0", "[grow]", "[grow]"));
		if(columns < 1)
			throw new IllegalArgumentException("A memory grid needs at least one column");
		m_context = context;
		m_columns = columns;
		m_overwritePolicy = overwritePolicy;

		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, m_table.getFont().getSize());
		m_table.setFont(mono);
		m_table.getTableHeader().setReorderingAllowed(false);
		m_table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		m_table.setCellSelectionEnabled(true);
		m_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		m_table.setRowSelectionAllowed(false);
		m_table.setDefaultRenderer(Object.class, new ValueRenderer());
		m_table.setDefaultEditor(Object.class, new OctalCellEditor());
		//-- One click into a cell starts editing; the Pascal grid behaves this way and a memory
		//-- editor where you have to double-click every word is tiring within about a minute.
		m_table.setSurrendersFocusOnKeystroke(true);
		//-- Announce the selection, so the Bitfields window can follow it. The Pascal grid calls
		//-- FormMain.syncBitfieldForm from here instead ({@code :159-166}).
		m_table.getSelectionModel().addListSelectionListener(e -> announceSelection(e.getValueIsAdjusting()));
		m_table.getColumnModel().getSelectionModel()
			.addListSelectionListener(e -> announceSelection(e.getValueIsAdjusting()));
		//-- Explicitly, rather than leaving it to JScrollPane: a JTable only hands its header to
		//-- the scroll pane from addNotify(), which never runs on a component that is laid out
		//-- and painted with no display. Without this the offscreen renders - the ones a person
		//-- actually looks at - come out with no column headings at all.
		m_scroll.setColumnHeaderView(m_table.getTableHeader());
		add(m_scroll, "grow");
		sizeColumns();
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

	/** Told when the contents changed enough that the window around this wants to know. */
	public void setOnUpdate(Runnable onUpdate) {
		m_onUpdate = onUpdate == null ? () -> {
		} : onUpdate;
	}

	// -------------------------------------------------------------------------------------
	// Connecting to a group
	// -------------------------------------------------------------------------------------

	/**
	 * Show this group, and follow it from now on.
	 *
	 * <p>Ported from {@code ConnectToMemoryCellGroup} ({@code :417-501}). Safe to call again
	 * with the same group, which is what happens after every range change.</p>
	 */
	public void connectTo(MemoryCellGroup group) {
		if(m_group != null && m_group != group)
			m_group.removeListener(m_listener);
		boolean isNew = m_group != group;
		m_group = group;
		if(isNew && group != null)
			group.addListener(m_listener);
		rebuild();
	}

	/** Lay the group's cells out over the grid again, after the range or the contents moved. */
	public void rebuild() {
		//-- The cells are about to change, and with them whether there is anything to protect.
		updateOverwritePolicy();
		m_slots = new MemoryCell[0];
		m_firstAddress = null;
		if(m_group != null && !m_group.isEmpty()) {
			//-- By address span, not by cell count: a group with gaps in it must show the gaps.
			long lo = m_group.getRange().lo();
			long hi = m_group.getRange().hi();
			int span = (int) ((hi - lo) / 2) + 1;
			m_firstAddress = Address.of(m_group.getType(), lo);
			m_slots = new MemoryCell[span];
			for(MemoryCell mc : m_group.getCells()) {
				int index = (int) ((mc.getAddr().val() - lo) / 2);
				if(index >= 0 && index < span && m_slots[index] == null)
					m_slots[index] = mc;
			}
		}
		m_model.fireTableStructureChanged();
		sizeColumns();
		m_onUpdate.run();
	}

	private void sizeColumns() {
		if(m_table.getColumnCount() == 0)
			return;
		m_table.getColumnModel().getColumn(0).setPreferredWidth(110);
		for(int i = 1; i < m_table.getColumnCount(); i++) {
			m_table.getColumnModel().getColumn(i).setPreferredWidth(72);
		}
	}

	/** The size this grid would like to be, so the window can offer it exactly that. */
	public java.awt.Dimension getGridPreferredSize() {
		int width = 0;
		for(int i = 0; i < m_table.getColumnCount(); i++) {
			width += m_table.getColumnModel().getColumn(i).getPreferredWidth();
		}
		int height = (m_model.getRowCount() + 1) * m_table.getRowHeight();
		return new java.awt.Dimension(width + 24, height + 8);
	}

	// -------------------------------------------------------------------------------------
	// What is where
	// -------------------------------------------------------------------------------------

	/** The cell shown at this grid position, or null - both for the address column and a gap. */
	public MemoryCell cellAt(int row, int column) {
		if(column < 1 || m_firstAddress == null)
			return null;
		int index = row * m_columns + (column - 1);
		return index >= 0 && index < m_slots.length ? m_slots[index] : null;
	}

	/** The cell under the selection, or null. */
	public MemoryCell getSelectedCell() {
		return cellAt(m_table.getSelectedRow(), m_table.getSelectedColumn());
	}

	private void announceSelection(boolean adjusting) {
		if(adjusting)
			return;
		MemoryCell mc = getSelectedCell();
		if(mc != null)
			m_context.getCellSelection().select(mc);
	}

	private void repaintCell(MemoryCell cell) {
		if(m_firstAddress == null)
			return;
		int index = (int) ((cell.getAddr().val() - m_firstAddress.val()) / 2);
		if(index < 0 || index >= m_slots.length)
			return;
		updateOverwritePolicy();
		m_model.fireTableCellUpdated(index / m_columns, index % m_columns + 1);
		m_onUpdate.run();
	}

	/** Everything is different; repaint the lot. */
	public void refresh() {
		updateOverwritePolicy();
		m_model.fireTableDataChanged();
		m_onUpdate.run();
	}

	/**
	 * Stop values arriving from elsewhere while this grid holds edits, and start again once it
	 * does not.
	 *
	 * <p><b>A deliberate divergence.</b> In the Pascal {@code PdpOverwritesEdit} is a static
	 * decision taken at construction: the memory loader, the memory dumper and the assembler
	 * code window set it false forever ({@code FormMemoryLoaderU.pas:182},
	 * {@code FormMemoryDumperU.pas:182}, {@code FormMacro11CodeU.pas:101}) and everything else
	 * leaves it true. Which means the plain memory window - the one the frame's own comment uses
	 * as its example of the problem, "Mem1 ist komplett gelb ... der User verliert Werte"
	 * ({@code FrameMemoryCellGroupGridU.pas:38-48}) - still has the bug the flag exists to
	 * prevent.</p>
	 *
	 * <p>Making it follow whether there is actually anything to protect gets both halves right:
	 * a grid with uncommitted edits in it is never overwritten, and a grid with none tracks the
	 * machine, which is what a memory view is for.</p>
	 *
	 * <p>Only for a grid that asked for it. A group whose owner turned the flag off permanently
	 * has already decided - see {@link OverwritePolicy} - and this is not the place to overrule
	 * it.</p>
	 */
	private void updateOverwritePolicy() {
		if(m_overwritePolicy == OverwritePolicy.FOLLOW_EDITS && m_group != null)
			m_group.setPdpOverwritesEdit(getEditedCells().isEmpty());
	}

	// -------------------------------------------------------------------------------------
	// Operations on the whole group
	// -------------------------------------------------------------------------------------

	/**
	 * Read the group back from the machine and show what it said.
	 *
	 * <p>Ported from {@code ExamineCells} ({@code :198-211}), including the part that is easy to
	 * miss: after examining, every cell's edit value is set to what the machine said, so the
	 * grid shows the machine rather than showing yesterday's edits over the top of it.</p>
	 *
	 * <p>Which is right for <i>Examine all</i> and wrong for <i>Verify</i>: it is the step that
	 * throws away the thing a verify would have compared against. Use {@link #verifyAll} for
	 * that.</p>
	 *
	 * @param unknownOnly skip the cells that already have a value
	 */
	public void examineAll(boolean unknownOnly, java.awt.Window owner) {
		MemoryCellGroup group = m_group;
		if(group == null)
			return;
		to.etc.pdp11.ui.ProgressDialog progress = new to.etc.pdp11.ui.ProgressDialog(owner);
		m_context.onConsole("Examining memory", console -> {
			console.examine(group, unknownOnly, progress);
			for(MemoryCell mc : group.getCells()) {
				mc.setEditValue(mc.getPdpValue());
			}
			AppContext.onUi(this::refresh);
		});
	}

	/**
	 * Read the group back from the machine <b>without touching the edit values</b>, so the two
	 * can be compared.
	 *
	 * <p>This is what every window that offers a "Verify" button means by it: the grid holds
	 * what should be there - a file, an assembled program, something typed - the examine fills
	 * in what the machine actually has, and {@link MemoryCell#isEdited()} is then "the machine
	 * disagrees about this word", which is what colours it.</p>
	 *
	 * <p>It only says anything when the group's {@code pdpOverwritesEdit} is off. With it on,
	 * the arriving values replace the edits as they land and every word agrees by construction -
	 * which is the whole reason the loader, the dumper and the assembler turn it off for good.
	 * The plain memory window leaves it to {@link OverwritePolicy#FOLLOW_EDITS}, so a verify
	 * there compares when there is something to compare and is a plain read when there is
	 * not.</p>
	 *
	 * @param whenDone told, on the event thread, how many words the machine disagreed about.
	 *                 May be null.
	 */
	public void verifyAll(java.awt.Window owner, java.util.function.LongConsumer whenDone) {
		MemoryCellGroup group = m_group;
		if(group == null)
			return;
		to.etc.pdp11.ui.ProgressDialog progress = new to.etc.pdp11.ui.ProgressDialog(owner);
		m_context.onConsole("Verifying against the machine", console -> {
			console.examine(group, false, progress);
			AppContext.onUi(() -> {
				refresh();
				if(whenDone != null)
					whenDone.accept(group.getCells().stream().filter(MemoryCell::isEdited).count());
			});
		});
	}

	/** Read back one cell. Ported from {@code ExamineCurrentButtonClick} ({@code :213-224}). */
	public void examineCell(MemoryCell cell) {
		if(cell == null)
			return;
		m_context.onConsole("Examining " + cell.getAddr().toOctal(), console -> {
			CellValue v = console.examine(cell.getAddr());
			cell.setPdpValue(v);
			cell.setEditValue(v);
			MemoryCellGroup group = cell.getGroup();
			if(group.getOwner() != null)
				group.getOwner().syncMemoryCells(cell);
			AppContext.onUi(() -> repaintCell(cell));
		});
	}

	/**
	 * Write the group to the machine.
	 *
	 * @param optimize skip cells whose edited value is already what the machine holds
	 */
	public void depositAll(boolean optimize, java.awt.Window owner) {
		MemoryCellGroup group = m_group;
		if(group == null)
			return;
		to.etc.pdp11.ui.ProgressDialog progress = new to.etc.pdp11.ui.ProgressDialog(owner);
		m_context.onConsole("Depositing memory", console -> {
			console.deposit(group, optimize, progress);
			AppContext.onUi(this::refresh);
		});
	}

	/** Set every cell's edit value to zero. {@code Cleardata1Click} ({@code :515-527}). */
	public void clearData() {
		if(m_group == null)
			return;
		for(MemoryCell mc : m_group.getCells()) {
			mc.setEditValue(CellValue.of(0));
		}
		refresh();
	}

	/**
	 * Set every cell's edit value to its own word address. {@code Filldatawithaddr1Click}
	 * ({@code :529-541}) - a memory test pattern where a wrong value says where it came from.
	 */
	public void fillWithAddress() {
		if(m_group == null)
			return;
		for(MemoryCell mc : m_group.getCells()) {
			mc.setEditValue(CellValue.of((int) ((mc.getAddr().val() >> 1) & 0xFFFF)));
		}
		refresh();
	}

	/** The deposit commands for this group, as a SimH {@code DO} script. */
	public String toSimhScript() {
		StringBuilder sb = new StringBuilder();
		if(m_group == null)
			return "";
		for(MemoryCell mc : m_group.getCells()) {
			if(!mc.getEditValue().isKnown())
				continue;
			sb.append("d ").append(mc.getAddr().toOctal()).append(' ')
				.append(mc.getEditValue().toOctal()).append('\n');
		}
		return sb.toString();
	}

	/** Every cell holding a value the user has typed but not deposited. */
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
	// The model
	// -------------------------------------------------------------------------------------

	private final class GridModel extends AbstractTableModel {
		@Override
		public int getRowCount() {
			return m_slots.length == 0 ? 0 : (m_slots.length - 1) / m_columns + 1;
		}

		@Override
		public int getColumnCount() {
			return 1 + m_columns;
		}

		@Override
		public String getColumnName(int column) {
			//-- "start \ offset" in the corner, and the byte offset over each value column.
			return column == 0 ? "start \\ offset" : "+" + Octal.format((column - 1) * 2L, 1);
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return cellAt(row, column) != null;
		}

		@Override
		public Object getValueAt(int row, int column) {
			if(column == 0) {
				if(m_firstAddress == null)
					return "";
				return m_firstAddress.plus(2L * row * m_columns).toOctal();
			}
			MemoryCell mc = cellAt(row, column);
			if(mc == null)
				return "";
			//-- The edit value, which is what the user is working on. Unknown prints as "?",
			//-- the same spelling {@code Dword2OctalStr} uses and the one parseOctal reads back.
			return mc.getEditValue().toOctal();
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			MemoryCell mc = cellAt(row, column);
			if(mc == null)
				return;
			String text = value == null ? "" : value.toString().trim();
			try {
				//-- Empty means "not decided yet", which happens mid-edit; anything that is not
				//-- a value is left alone rather than silently turned into one.
				mc.setEditValue(text.isEmpty() ? CellValue.UNKNOWN : CellValue.parseOctal(text));
			} catch(NumberFormatException x) {
				return;
			}
			updateOverwritePolicy();
			m_onUpdate.run();
		}
	}

	/** Grey for the address column, the changed colour for a cell that has been typed into. */
	private final class ValueRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
			boolean focused, int row, int column) {
			Component c = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
			MemoryCell mc = cellAt(row, column);
			if(column == 0) {
				c.setBackground(table.getTableHeader().getBackground());
				c.setForeground(table.getTableHeader().getForeground());
			} else if(mc != null && mc.isEdited()) {
				c.setBackground(UiColors.EDITED_BACKGROUND);
				c.setForeground(UiColors.EDITED_TEXT);
			} else if(mc != null && !mc.getPdpValue().isKnown()) {
				c.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
				c.setForeground(UiColors.UNKNOWN_TEXT);
			} else {
				c.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
				c.setForeground(selected ? table.getSelectionForeground() : table.getForeground());
			}
			if(c instanceof JComponent jc)
				jc.setToolTipText(mc == null ? null : tooltipFor(mc));
			return c;
		}
	}

	private String tooltipFor(MemoryCell mc) {
		StringBuilder sb = new StringBuilder(mc.getAddr().toOctal());
		if(!mc.getName().isEmpty())
			sb.append(" - ").append(mc.getName());
		if(!mc.getInfo().isEmpty())
			sb.append(": ").append(mc.getInfo());
		sb.append("; machine holds ").append(mc.getPdpValue().isKnown() ? mc.getPdpValue().toOctal() : "nothing read");
		return sb.toString();
	}

	/** The renderer, for a test that wants to know what colour a cell came out. */
	public TableCellRenderer getValueRenderer() {
		return m_table.getDefaultRenderer(Object.class);
	}

	/** The background this cell would be painted with. For tests, and for nothing else. */
	public Color backgroundOf(int row, int column) {
		Component c = m_table.prepareRenderer(m_table.getCellRenderer(row, column), row, column);
		return c.getBackground();
	}
}
