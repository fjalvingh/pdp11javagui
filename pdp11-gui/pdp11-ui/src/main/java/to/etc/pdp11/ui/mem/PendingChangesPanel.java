package to.etc.pdp11.ui.mem;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.mem.SharedMemory;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every word in shared memory that is waiting to be deposited: where, what the machine holds,
 * what should be there, and which window put it there.
 *
 * <p>The reason this exists is that an edit outlives the window it was made in. Something typed
 * an hour ago in a Memory window that has since been closed is still pending, and Deposit
 * changed will write it in the middle of a program - so what is about to be written has to be
 * visible in one place, and removable from it. PLAN.md §1, "Shared memory".</p>
 */
public final class PendingChangesPanel extends JPanel {
	private static final String[] COLUMNS = {"Address", "Machine", "To deposit", "From"};

	private final AppContext m_context;

	private final PendingModel m_model = new PendingModel();

	private final JTable m_table = new JTable(m_model);

	private final JLabel m_status = new JLabel();

	private final JButton m_deposit = new JButton("Deposit changed");

	private final JButton m_discardSelected = new JButton("Discard selected");

	private final JButton m_discardAll = new JButton("Discard all");

	private final AtomicBoolean m_reloadQueued = new AtomicBoolean();

	/** Told of any change in shared memory, possibly per word; reloads the list once per burst. */
	private final Runnable m_memoryListener = () -> {
		if(m_reloadQueued.compareAndSet(false, true)) {
			AppContext.onUi(() -> {
				m_reloadQueued.set(false);
				reload();
			});
		}
	};

	private final ConnectionManager.Listener m_connectionListener = (manager, state) -> AppContext.onUi(this::updateButtons);

	public PendingChangesPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;

		m_table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_table.getFont().getSize()));
		m_table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		m_table.getTableHeader().setReorderingAllowed(false);
		m_table.getSelectionModel().addListSelectionListener(e -> updateButtons());
		sizeColumn(0, 110);
		sizeColumn(1, 80);
		sizeColumn(2, 80);
		sizeColumn(3, 160);
		JScrollPane scroll = new JScrollPane(m_table);
		//-- See MemoryCellGroupTable: offscreen renders never run addNotify, which is what hands a
		//-- table's header to its scroll pane.
		scroll.setColumnHeaderView(m_table.getTableHeader());

		m_deposit.setToolTipText("Write every word listed here to the machine");
		m_deposit.addActionListener(e -> SharedMemoryActions.depositChanged(m_context, owner()));
		m_discardSelected.setToolTipText("Give up the selected changes; those words show what the machine holds again");
		m_discardSelected.addActionListener(e -> discardSelected());
		m_discardAll.setToolTipText("Give up every change listed here");
		m_discardAll.addActionListener(e -> {
			m_context.getMemoryCellGroups().getSharedMemory().discardAllEdits();
			reload();
		});

		add(m_status, "growx, wrap");
		add(scroll, "grow, wrap");
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][][]push", "[]"));
		bar.add(m_deposit);
		bar.add(m_discardSelected);
		bar.add(m_discardAll);
		add(bar, "growx");
		reload();
	}

	/** Both widths; see CLAUDE.md on why a preferred width alone comes back elided. */
	private void sizeColumn(int index, int width) {
		TableColumn c = m_table.getColumnModel().getColumn(index);
		c.setMinWidth(width);
		c.setPreferredWidth(width);
	}

	public void attach() {
		detach();
		m_context.getMemoryCellGroups().getSharedMemory().addChangeListener(m_memoryListener);
		m_context.getConnectionManager().addListener(m_connectionListener);
		reload();
	}

	public void detach() {
		m_context.getMemoryCellGroups().getSharedMemory().removeChangeListener(m_memoryListener);
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	/** Read the list again from shared memory. On the event thread. */
	public void reload() {
		m_model.set(m_context.getMemoryCellGroups().getSharedMemory().getPending());
		int n = m_model.getRowCount();
		if(n == 0)
			m_status.setText("Nothing is waiting to be deposited");
		else
			m_status.setText(n + (n == 1 ? " word is" : " words are") + " waiting to be deposited");
		m_status.setForeground(n == 0 ? UiColors.SECONDARY_TEXT : UiColors.EDITED_TEXT);
		updateButtons();
	}

	private void discardSelected() {
		List<Address> addresses = new ArrayList<>();
		for(int row : m_table.getSelectedRows()) {
			addresses.add(m_model.at(row).address());
		}
		m_context.getMemoryCellGroups().getSharedMemory().discardEditsAt(addresses);
		reload();
	}

	private void updateButtons() {
		boolean any = m_model.getRowCount() > 0;
		m_deposit.setEnabled(any && m_context.getConnectionManager().isConnected());
		m_discardSelected.setEnabled(m_table.getSelectedRowCount() > 0);
		m_discardAll.setEnabled(any);
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	public JTable getTable() {
		return m_table;
	}

	public String getStatusText() {
		return m_status.getText();
	}

	public JButton getDepositButton() {
		return m_deposit;
	}

	public JButton getDiscardSelectedButton() {
		return m_discardSelected;
	}

	public JButton getDiscardAllButton() {
		return m_discardAll;
	}

	private static final class PendingModel extends AbstractTableModel {
		private List<SharedMemory.PendingWord> m_rows = List.of();

		void set(List<SharedMemory.PendingWord> rows) {
			m_rows = rows;
			fireTableDataChanged();
		}

		SharedMemory.PendingWord at(int row) {
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
			SharedMemory.PendingWord w = m_rows.get(row);
			return switch(column) {
				case 0 -> w.address().toOctal();
				case 1 -> w.machine().toOctal() + (w.stale() ? " (old)" : "");
				case 2 -> w.edit().toOctal();
				default -> w.owner().isEmpty() ? "(window closed)" : w.owner();
			};
		}
	}
}
