package to.etc.pdp11.ui.bits;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.bits.BitfieldDef;
import to.etc.pdp11.core.bits.BitfieldsDef;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellListener;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.ui.FieldStatus;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.CellSelection;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.List;

/**
 * One register, broken into its named bits.
 *
 * <p>Ported from {@code TFormBitfields} ({@code FormBitfieldsU.pas}). A word of a PDP-11 device
 * register is rarely a number - it is half a dozen flags and a two-bit mode field - and this is
 * where you set the mode field to 3 without working out that it means adding {@code 014000}.</p>
 *
 * <h2>The value and the fields are two views of one number</h2>
 *
 * <p>Typing in the value updates every field; typing in a field updates the value. The Pascal
 * guards that with an {@code editChangeEventsEnabled} flag ({@code :74}) because the two edits
 * call each other, and the flag is the whole of its recursion control. The same guard is here
 * and for the same reason - a Swing document listener firing a table update that fires a
 * document listener is exactly the same loop.</p>
 *
 * <h2>Divergence: the address field is typeable</h2>
 *
 * <p>The Pascal declares {@code AddrEdit} as {@code ReadOnly = True}
 * ({@code FormBitfieldsU.dfm:59}) and yet hangs an {@code OnKeyPress} handler off it that
 * filters keystrokes down to octal digits and backspace ({@code :151-156}) - a filter that can
 * never fire, on a field that can never be typed in. The window was reachable only from a
 * selection somewhere else, which makes looking at one register of a device you are not already
 * displaying a detour through a memory window. The field is editable here: Enter points the
 * window at what was typed, and Examine reads it.</p>
 *
 * <h2>It works on its own copy</h2>
 *
 * <p>{@code ShowNewAddr} does {@code memorycell.Assign(mc)} ({@code :414}): the window holds its
 * own cell at the same address rather than editing the one the other window is showing. Kept,
 * because it is right - an experiment with the bits of a register is not a change to what the
 * memory window is displaying until it is deposited, and once it is, the propagation bus tells
 * that window anyway.</p>
 */
public final class BitfieldsPanel extends JPanel {
	private static final int COL_NAME = 0;

	private static final int COL_BITS = 1;

	private static final int COL_MASK = 2;

	private static final int COL_VALUE = 3;

	private static final int COL_MAX = 4;

	private static final int COL_INFO = 5;

	private final AppContext m_context;

	/** This window's own group, holding the one cell it is editing. */
	private final MemoryCellGroup m_group;

	/**
	 * Not final: pointing this window at another address re-points the group, and
	 * {@link MemoryCellGroup#shiftRange} builds a new cell to do it - which is exactly what has
	 * to happen, because a cell's address is what the propagation index is keyed on.
	 */
	private MemoryCell m_cell;

	private final JTextField m_address = new JTextField(9);

	private final JTextField m_value = new JTextField(8);

	private final JButton m_examine = new JButton("Examine cell");

	private final JButton m_deposit = new JButton("Deposit cell");

	private final JLabel m_info = new JLabel(" ");

	/** The status line, and where a value that cannot be used is reported. See {@link FieldStatus}. */
	private final FieldStatus m_status = new FieldStatus(m_info, UiColors.SECONDARY_TEXT);

	private final JLabel m_noDefinitions = new JLabel();

	private final FieldsModel m_model = new FieldsModel();

	private final JTable m_table = new JTable(m_model);

	private final JScrollPane m_scroll = new JScrollPane(m_table);

	private BitfieldsDef m_def;

	/** The Pascal's {@code editChangeEventsEnabled}: the value and the fields edit each other. */
	private boolean m_updating;

	public BitfieldsPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][]4[grow][]"));
		m_context = context;

		//-- A group of one, so this window's cell is on the propagation bus like any other: a
		//-- deposit here reaches every window showing the same address.
		m_group = context.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "Bitfields");
		m_group.setUsageTag("bitfields");
		m_cell = m_group.add(0);
		m_group.addListener(m_listener);

		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, m_value.getFont().getSize());
		m_address.setFont(mono);
		m_value.setFont(mono);

		m_table.setFont(mono);
		m_table.getTableHeader().setReorderingAllowed(false);
		m_table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		m_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		m_table.setDefaultRenderer(Object.class, new FieldRenderer());
		m_table.setDefaultEditor(Object.class, new to.etc.pdp11.ui.mem.OctalCellEditor());
		m_table.setSurrendersFocusOnKeystroke(true);
		m_scroll.setColumnHeaderView(m_table.getTableHeader());
		sizeColumns();

		m_noDefinitions.setForeground(UiColors.SECONDARY_TEXT);

		updateButtons();
		add(buildTop(), "growx, wrap");
		add(m_info, "growx, wrap");
		add(m_scroll, "grow, wrap");
		add(m_noDefinitions, "growx");

		m_value.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onValueTyped();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onValueTyped();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onValueTyped();
			}
		});
		//-- Enter in the address field is the "go there" gesture; Examine reads it when there
		//-- is a machine to read it from. Not examining while disconnected keeps Enter from
		//-- being a "Not connected" dialog every time somebody types an address offline.
		m_address.addActionListener(e -> {
			if(applyTypedAddress() && m_context.getConnectionManager().isConnected())
				examine();
		});
		showCell(null);
	}

	private JPanel buildTop() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]12[][]16[][]", "[]"));
		bar.add(new JLabel("Address:"));
		bar.add(m_address);
		bar.add(new JLabel("Value:"));
		bar.add(m_value);
		bar.add(m_examine);
		bar.add(m_deposit);
		m_examine.addActionListener(e -> examine());
		m_deposit.addActionListener(e -> deposit());
		return bar;
	}

	/**
	 * Fixed widths for the columns that hold a known shape of text, and the rest to Info.
	 *
	 * <p>Both a minimum and a preferred width, and the minimum is the one that matters: laying
	 * out a table with an auto-resize mode redistributes the preferred widths and leaves them
	 * redistributed, so preferred alone is a suggestion that survives until the first layout
	 * pass. The minimum is a floor the redistribution respects. Without it "Previous Mode"
	 * renders as "Previ..." while the Info column has three hundred pixels of space.</p>
	 */
	private void sizeColumns() {
		fix(COL_NAME, 160);
		fix(COL_BITS, 60);
		fix(COL_MASK, 80);
		fix(COL_VALUE, 80);
		fix(COL_MAX, 70);
		//-- Info gets whatever is left, and a tooltip when that is not enough.
		m_table.getColumnModel().getColumn(COL_INFO).setPreferredWidth(200);
		m_table.getColumnModel().getColumn(COL_INFO).setMinWidth(80);
	}

	private void fix(int column, int width) {
		m_table.getColumnModel().getColumn(column).setMinWidth(width);
		m_table.getColumnModel().getColumn(column).setPreferredWidth(width);
	}

	// -------------------------------------------------------------------------------------
	// Showing a cell
	// -------------------------------------------------------------------------------------

	/**
	 * Show this cell's address, value and bit definitions. Ported from {@code ShowNewAddr}.
	 *
	 * @param source the cell to copy, or null to show nothing
	 */
	public void showCell(MemoryCell source) {
		m_updating = true;
		try {
			if(source == null) {
				m_def = null;
				m_address.setText("");
				m_value.setText("");
				m_status.setText(" ");
			} else {
				//-- Copy, not adopt: see the class comment. Moving to another address goes
				//-- through the group rather than by assigning the cell's address, because
				//-- MemoryCellGroups keys its propagation index on the address - a cell whose
				//-- address changed underneath it would still be indexed at the old one, and
				//-- would receive values meant for a register it is no longer showing.
				Address addr = source.getAddr();
				if(m_group.size() != 1 || !addr.equals(m_cell.getAddr())) {
					m_group.shiftRange(addr, 1, false);
					m_cell = m_group.cell(0);
				}
				m_cell.setPdpValue(source.getPdpValue());
				m_cell.setEditValue(source.getEditValue());
				m_cell.setName(source.getName());
				m_cell.setInfo(source.getInfo());
				m_def = m_context.getBitfieldDefs().findByAddress(m_cell.getAddr());
				m_address.setText(m_cell.getAddr().toOctal());
				m_value.setText(m_cell.getEditValue().toOctal());
				m_status.setText(describe(source));
			}
			showDefinitionOrReason(source != null);
			m_model.fireTableDataChanged();
			updateValueColour();
		} finally {
			m_updating = false;
		}
		revalidate();
		repaint();
	}

	/**
	 * Point the window at an address nobody selected, and forget what was in the old one.
	 *
	 * <p>The values are dropped rather than carried across, because they belonged to the
	 * register that was being shown: {@link MemoryCellGroup#shiftRange} is called without the
	 * optimise flag exactly so the new cell starts unknown. Examine fills it in.</p>
	 */
	public void showAddress(Address addr) {
		m_updating = true;
		try {
			if(m_group.size() != 1 || !addr.equals(m_cell.getAddr())) {
				m_group.shiftRange(addr, 1, false);
				m_cell = m_group.cell(0);
			}
			m_def = m_context.getBitfieldDefs().findByAddress(m_cell.getAddr());
			m_address.setText(m_cell.getAddr().toOctal());
			m_value.setText(m_cell.getEditValue().toOctal());
			//-- No group and no name to put in front of it: this address came from the keyboard,
			//-- so the only thing known about it is what the machine description calls its bits.
			String what = m_def == null ? "" : m_def.getName();
			m_status.setText(m_cell.getAddr().toOctal() + (what.isEmpty() ? "" : "  -  " + what));
			showDefinitionOrReason(true);
			m_model.fireTableDataChanged();
			updateValueColour();
		} finally {
			m_updating = false;
		}
		revalidate();
		repaint();
	}

	/**
	 * Move to whatever the address field says, if it says something and it has changed.
	 *
	 * <p>Read at the group's own width, which is the width of the last register shown here, so
	 * an address typed after selecting a 22-bit cell is a 22-bit address. Bad text is reported
	 * rather than swallowed - it is a deliberate action with a visible result, unlike the value
	 * field, where half-typed text is normal and the panel simply waits.</p>
	 *
	 * @return false when there is nothing usable in the field, and the caller should stop
	 */
	private boolean applyTypedAddress() {
		String text = m_address.getText().trim();
		if(text.isEmpty()) {
			m_status.error("Type the octal address of a register to look at");
			return false;
		}
		Address addr;
		try {
			addr = Address.parseOctal(text, m_group.getType());
		} catch(RuntimeException x) {
			m_status.error("\"" + text + "\" is not an octal address");
			return false;
		}
		if(!addr.equals(m_cell.getAddr()))
			showAddress(addr);
		return true;
	}

	/** Either the table of fields, or the line saying why there is not one. */
	private void showDefinitionOrReason(boolean haveAddress) {
		m_scroll.setVisible(m_def != null);
		m_noDefinitions.setVisible(m_def == null);
		m_noDefinitions.setText(m_def != null ? ""
			: !haveAddress
				? "Select a register in a memory or device window, or type an address above, to see its bits."
				: "No bit field definitions for " + m_cell.getAddr().toOctal()
					+ " in the loaded machine description.");
	}

	/** {@code "CPU . PSW"}, or the address when the cell has no name ({@code :404-412}). */
	private String describe(MemoryCell source) {
		StringBuilder sb = new StringBuilder();
		if(source.getGroup() != null && !source.getGroup().getGroupName().isEmpty())
			sb.append(source.getGroup().getGroupName()).append(" . ");
		sb.append(source.getName().isEmpty() ? source.getAddr().toOctal() : source.getName());
		String info = source.getInfo().isEmpty() && m_def != null ? m_def.getName() : source.getInfo();
		if(!info.isEmpty())
			sb.append("  -  ").append(info);
		return sb.toString();
	}

	/** The cell this window is editing. Its own, at the address of the one selected elsewhere. */
	public MemoryCell getCell() {
		return m_cell;
	}

	public BitfieldsDef getDefinition() {
		return m_def;
	}

	public JTable getTable() {
		return m_table;
	}

	public JTextField getValueField() {
		return m_value;
	}

	public JTextField getAddressField() {
		return m_address;
	}

	public String getInfoText() {
		return m_info.getText();
	}

	public String getNoDefinitionsText() {
		return m_noDefinitions.isVisible() ? m_noDefinitions.getText() : "";
	}

	/** The two controls that need a machine, for a test that wants to know whether they are live. */
	public List<JButton> getMachineControls() {
		return List.of(m_examine, m_deposit);
	}

	/**
	 * Examine and Deposit are dead while nothing is connected, as in every other data window.
	 *
	 * <p>Typing bits and reading what they mean is the rest of this window and stays available:
	 * only the two round trips go. Before this, clicking either one offline produced a modal
	 * "Not connected to a machine" dialog where the Loader's identical gesture is a dead
	 * button.</p>
	 */
	private void updateButtons() {
		boolean connected = m_context.getConnectionManager().isConnected();
		m_examine.setEnabled(connected);
		m_deposit.setEnabled(connected);
	}

	// -------------------------------------------------------------------------------------
	// Editing
	// -------------------------------------------------------------------------------------

	/** The whole word was typed; every field follows. */
	private void onValueTyped() {
		if(m_updating)
			return;
		String text = m_value.getText().trim();
		m_updating = true;
		try {
			//-- Empty happens mid-edit and means "not decided yet", exactly as in the Pascal.
			m_cell.setEditValue(text.isEmpty() ? CellValue.UNKNOWN : CellValue.parseOctal(text));
		} catch(NumberFormatException x) {
			return;                                         // not a value; leave the cell alone
		} finally {
			m_updating = false;
		}
		m_model.fireTableDataChanged();
		updateValueColour();
	}

	/** One field was typed; the whole word follows. */
	private void onFieldTyped(int row, String text) {
		BitfieldDef field = fieldAt(row);
		if(field == null)
			return;
		int fieldValue;
		try {
			fieldValue = text.isBlank() ? 0 : (int) Octal.parse(text.trim());
		} catch(NumberFormatException x) {
			return;
		}
		//-- A field that does not fit its own width would corrupt its neighbours; BitfieldDef.set
		//-- refuses it, and this is the check that keeps a keystroke from being an exception.
		//-- unshiftedMask() is the largest the field can hold; mask() is the same bits in place.
		if(fieldValue < 0 || fieldValue > field.unshiftedMask())
			return;
		int base = m_cell.getEditValue().isKnown() ? m_cell.getEditValue().word() : 0;
		m_cell.setEditValue(CellValue.of(field.set(base, fieldValue)));
		m_updating = true;
		try {
			m_value.setText(m_cell.getEditValue().toOctal());
		} finally {
			m_updating = false;
		}
		updateValueColour();
	}

	/**
	 * Yellow while the value differs from what the machine holds, as the Pascal does - and while
	 * it does, nothing else may write over it.
	 *
	 * <h2>Why the overwrite policy is set here</h2>
	 *
	 * <p>This window's whole purpose is composing a register value bit by bit, and its group is
	 * on the propagation bus like any other so that a deposit here reaches every window showing
	 * the same address. That bus runs the other way too: with {@code pdpOverwritesEdit} left at
	 * its default, any other window examining the same address propagated the machine's value in
	 * and the cell listener copied it over the composition, silently, halfway through. Every
	 * other edit-holding view protects itself; this one did not.</p>
	 *
	 * <p>Following {@link MemoryCell#isEdited()} rather than turning the flag off for good is
	 * what {@code MemoryCellGroupTable.updateOverwritePolicy} does and for the same reason: with
	 * nothing being composed there is nothing to protect, and a bitfields window showing the
	 * PSW should follow the PSW. Every path that can change the edit value ends here.</p>
	 */
	private void updateValueColour() {
		boolean changed = m_cell.isEdited();
		m_group.setPdpOverwritesEdit(!changed);
		m_value.setBackground(changed ? UiColors.EDITED_BACKGROUND : UIManagerBackground());
		m_value.setForeground(changed ? UiColors.EDITED_TEXT : UIManagerForeground());
	}

	private static Color UIManagerBackground() {
		Color c = javax.swing.UIManager.getColor("TextField.background");
		return c == null ? Color.WHITE : c;
	}

	private static Color UIManagerForeground() {
		Color c = javax.swing.UIManager.getColor("TextField.foreground");
		return c == null ? Color.BLACK : c;
	}

	private BitfieldDef fieldAt(int row) {
		if(m_def == null)
			return null;
		List<BitfieldDef> fields = m_def.getFields();
		return row >= 0 && row < fields.size() ? fields.get(row) : null;
	}

	// -------------------------------------------------------------------------------------
	// The machine
	// -------------------------------------------------------------------------------------

	private void examine() {
		//-- An address typed but not Entered is still what the user is pointing at.
		if(!applyTypedAddress())
			return;
		//-- The cell as it is now, not as the field will be when the answer comes back. Every
		//-- other panel captures the cell; this one used to capture the address and then write
		//-- the answer through m_cell, so re-pointing the window between pressing Examine and
		//-- the machine answering put one address's value into another address's cell
		//-- (FABLE-ISSUES #50).
		MemoryCell cell = m_cell;
		m_context.onConsole("Examining " + cell.getAddr().toOctal(), console -> examineInto(console, cell));
	}

	/** One examine, on the command thread, into the cell the job was queued for. */
	void examineInto(Console console, MemoryCell cell) throws ConsoleException {
		CellValue v = console.examine(cell.getAddr());
		cell.setPdpValue(v);
		cell.setEditValue(v);
		if(m_group.getOwner() != null)
			m_group.getOwner().syncMemoryCells(cell);
		AppContext.onUi(this::refreshValue);
	}

	private void deposit() {
		if(!applyTypedAddress())
			return;
		if(!m_cell.getEditValue().isKnown()) {
			m_status.error("There is no value to deposit");
			return;
		}
		MemoryCell cell = m_cell;                           // see examine(): the cell, not the field
		int value = cell.getEditValue().word();
		m_context.onConsole("Depositing " + cell.getAddr().toOctal(), console -> depositFrom(console, cell, value));
	}

	/** One deposit, on the command thread, of the value the job was queued with. */
	void depositFrom(Console console, MemoryCell cell, int value) throws ConsoleException {
		console.deposit(cell.getAddr(), value);
		cell.setDeposited();
		if(m_group.getOwner() != null)
			m_group.getOwner().syncMemoryCells(cell);
		AppContext.onUi(this::refreshValue);
	}

	/** Redraw from the cell, without disturbing what is being typed. */
	public void refreshValue() {
		m_updating = true;
		try {
			m_value.setText(m_cell.getEditValue().toOctal());
		} finally {
			m_updating = false;
		}
		m_model.fireTableDataChanged();
		updateValueColour();
	}

	// -------------------------------------------------------------------------------------
	// Following the selection
	// -------------------------------------------------------------------------------------

	private final CellSelection.Listener m_selectionListener = this::showCell;

	private final MemoryCellListener m_listener = (group, cell) -> AppContext.onUi(() -> {
		cell.setEditValue(cell.getPdpValue());
		refreshValue();
	});

	private final ConnectionManager.Listener m_connectionListener =
		(manager, state) -> AppContext.onUi(this::updateButtons);

	public void attach() {
		detach();
		m_context.getCellSelection().addListener(m_selectionListener);
		m_context.getConnectionManager().addListener(m_connectionListener);
		updateButtons();
		showCell(m_context.getCellSelection().getSelected());
	}

	public void detach() {
		m_context.getCellSelection().removeListener(m_selectionListener);
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	/** Give the group back when the window goes for good. */
	public void dispose() {
		detach();
		m_group.removeListener(m_listener);
		m_context.getMemoryCellGroups().removeGroup(m_group);
	}

	// -------------------------------------------------------------------------------------
	// The model
	// -------------------------------------------------------------------------------------

	private final class FieldsModel extends AbstractTableModel {
		@Override
		public int getRowCount() {
			return m_def == null ? 0 : m_def.getFields().size();
		}

		@Override
		public int getColumnCount() {
			return 6;
		}

		@Override
		public String getColumnName(int column) {
			return switch(column) {
				case COL_NAME -> "Name";
				case COL_BITS -> "Bits";
				case COL_MASK -> "Mask";
				case COL_VALUE -> "Value";
				case COL_MAX -> "Max";
				default -> "Info";
			};
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == COL_VALUE && fieldAt(row) != null;
		}

		@Override
		public Object getValueAt(int row, int column) {
			BitfieldDef f = fieldAt(row);
			if(f == null)
				return "";
			int word = m_cell.getEditValue().isKnown() ? m_cell.getEditValue().word() : 0;
			return switch(column) {
				case COL_NAME -> f.name();
				//-- "7:5", or "4" for a single bit. toBitRangeString() puts the name in front,
				//-- which is already the column to the left of this one.
				case COL_BITS -> f.bitHi() == f.bitLo()
					? String.valueOf(f.bitHi())
					: f.bitHi() + ":" + f.bitLo();
				//-- The mask in place, so it reads against the whole word.
				case COL_MASK -> Octal.format(f.mask(), 6);
				case COL_VALUE -> m_cell.getEditValue().isKnown()
					? Octal.format(f.get(word), Octal.digitsForBits(f.width()))
					: "?";
				//-- The largest this field can hold, which is what says how wide it is.
				case COL_MAX -> Octal.format(f.unshiftedMask(), Octal.digitsForBits(f.width()));
				default -> f.info();
			};
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			if(column == COL_VALUE)
				onFieldTyped(row, value == null ? "" : value.toString());
		}
	}

	/** A field whose value differs from the machine's stands out, as in the grid. */
	private final class FieldRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
			boolean focused, int row, int column) {
			Component c = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
			BitfieldDef f = fieldAt(row);
			boolean changed = false;
			if(f != null && column == COL_VALUE && m_cell.getPdpValue().isKnown()
				&& m_cell.getEditValue().isKnown()) {
				changed = f.get(m_cell.getPdpValue().word()) != f.get(m_cell.getEditValue().word());
			}
			if(changed) {
				c.setBackground(UiColors.EDITED_BACKGROUND);
				c.setForeground(UiColors.EDITED_TEXT);
			} else {
				c.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
				c.setForeground(column == COL_INFO || column == COL_MASK || column == COL_MAX
					? UiColors.SECONDARY_TEXT
					: selected ? table.getSelectionForeground() : table.getForeground());
			}
			if(c instanceof JComponent jc)
				jc.setToolTipText(f == null || f.info().isEmpty() ? null : f.info());
			return c;
		}
	}

	/** The background this cell would be painted with. For tests. */
	public Color backgroundOf(int row, int column) {
		return m_table.prepareRenderer(m_table.getCellRenderer(row, column), row, column).getBackground();
	}
}
