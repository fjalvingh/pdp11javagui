package to.etc.pdp11.ui.diag;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.diag.DiagnosticSource;
import to.etc.pdp11.common.diag.IndexPageScanner;
import to.etc.pdp11.common.util.AppVersion;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Where to look, what was found there, and the two buttons: find, then collect.
 *
 * <p>Holds no state of its own beyond what it shows; {@link DiagnosticsPanel} runs the jobs and
 * tells it what happened.</p>
 */
final class CollectTab extends JPanel {
	private final SourceModel m_sources;

	private final JTable m_sourceTable;

	private final JTextArea m_note = new JTextArea(2, 40);

	final JButton m_find = new JButton("Find diagnostics");

	final JButton m_collect = new JButton("Collect");

	final JButton m_cancel = new JButton("Cancel");

	private final JLabel m_status = new JLabel(" ");

	private final JProgressBar m_progress = new JProgressBar();

	private final FoundModel m_found = new FoundModel();

	private final JTable m_foundTable = new JTable(m_found);

	private final JTextArea m_problems = new JTextArea(4, 40);

	/**
	 * @param chosen   called with a source and whether it is now ticked
	 */
	CollectTab(List<DiagnosticSource> sources, Set<String> ticked, BiConsumer<DiagnosticSource, Boolean> chosen) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][][][][][grow 60][][grow 40]"));
		m_sources = new SourceModel(sources, ticked, chosen);
		m_sourceTable = new JTable(m_sources);

		add(new JLabel("Where to look. Nothing is downloaded until you press Collect; DEC's diagnostics are not shipped with " + AppVersion.NAME + "."),
			"wrap");
		m_sourceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		m_sourceTable.setFillsViewportHeight(true);
		widths(m_sourceTable, new int[]{30, 300, 380}, new int[]{30, 160, 120});
		//-- Tall enough for every source without scrolling: they are few, and a tick box below the
		//-- fold is one nobody unticks.
		int rows = Math.max(4, sources.size());
		int height = rows * m_sourceTable.getRowHeight() + m_sourceTable.getTableHeader().getPreferredSize().height + 6;
		add(LibraryTab.scrolled(m_sourceTable), "growx, h " + Math.min(height, 120) + ":" + height + ":, wrap");
		m_note.setEditable(false);
		m_note.setLineWrap(true);
		m_note.setWrapStyleWord(true);
		m_note.setOpaque(false);
		m_note.setForeground(UiColors.SECONDARY_TEXT);
		add(m_note, "growx, wmin 0, wrap");
		m_sourceTable.getSelectionModel().addListSelectionListener(e -> showNote());

		add(m_find, "split 4");
		add(m_collect);
		add(m_cancel);
		add(m_status, "gapleft 8, growx, wmin 0, wrap");
		m_progress.setStringPainted(false);
		add(m_progress, "growx, wrap");

		m_foundTable.setFillsViewportHeight(true);
		m_foundTable.setAutoCreateRowSorter(true);
		widths(m_foundTable, new int[]{240, 420, 110}, new int[]{120, 160, 90});
		add(LibraryTab.scrolled(m_foundTable), "grow, h 100:150:, wrap");

		add(new JLabel("Problems"), "wrap");
		m_problems.setEditable(false);
		m_problems.setLineWrap(true);
		add(new JScrollPane(m_problems), "grow, h 60:80:");

		m_collect.setEnabled(false);
		m_cancel.setEnabled(false);
		if(!sources.isEmpty())
			m_sourceTable.setRowSelectionInterval(0, 0);
	}

	private static void widths(JTable table, int[] preferred, int[] minimum) {
		for(int i = 0; i < preferred.length; i++) {
			TableColumn c = table.getColumnModel().getColumn(i);
			c.setPreferredWidth(preferred[i]);
			c.setMinWidth(minimum[i]);
		}
	}

	private void showNote() {
		int i = m_sourceTable.getSelectedRow();
		m_note.setText(i < 0 ? "" : m_sources.source(m_sourceTable.convertRowIndexToModel(i)).note());
	}

	/** The sources that are ticked, in list order. */
	List<DiagnosticSource> chosenSources() {
		return m_sources.chosen();
	}

	/** Whether a job is running: the buttons follow. */
	void setBusy(boolean busy, int toCollect) {
		m_find.setEnabled(!busy);
		m_collect.setEnabled(!busy && toCollect > 0);
		m_collect.setText(toCollect > 0 ? "Collect " + toCollect + " new" : "Collect");
		m_cancel.setEnabled(busy);
		m_sourceTable.setEnabled(!busy);
		if(!busy)
			m_progress.setValue(0);
	}

	void setStatus(String text, boolean error) {
		m_status.setText(text);
		m_status.setForeground(error ? UiColors.ERROR_TEXT : UiColors.SECONDARY_TEXT);
	}

	void progress(int value, int maximum) {
		m_progress.setMaximum(Math.max(1, maximum));
		m_progress.setValue(value);
	}

	void showFound(List<IndexPageScanner.Found> found, Set<String> inLibrary) {
		m_found.set(found, inLibrary);
	}

	void showProblems(List<String> problems) {
		m_problems.setText(String.join("\n", problems));
		m_problems.setCaretPosition(0);
	}

	JTable getSourceTable() {
		return m_sourceTable;
	}

	JTable getFoundTable() {
		return m_foundTable;
	}

	JLabel getStatus() {
		return m_status;
	}

	JTextArea getProblems() {
		return m_problems;
	}

	/** The sources, with a tick box each. */
	private static final class SourceModel extends AbstractTableModel {
		private final List<DiagnosticSource> m_list;

		private final boolean[] m_ticked;

		private final BiConsumer<DiagnosticSource, Boolean> m_onChange;

		SourceModel(List<DiagnosticSource> sources, Set<String> ticked, BiConsumer<DiagnosticSource, Boolean> onChange) {
			m_list = List.copyOf(sources);
			m_ticked = new boolean[sources.size()];
			for(int i = 0; i < m_list.size(); i++)
				m_ticked[i] = ticked.contains(m_list.get(i).id());
			m_onChange = onChange;
		}

		DiagnosticSource source(int row) {
			return m_list.get(row);
		}

		List<DiagnosticSource> chosen() {
			List<DiagnosticSource> out = new ArrayList<>();
			for(int i = 0; i < m_list.size(); i++) {
				if(m_ticked[i])
					out.add(m_list.get(i));
			}
			return out;
		}

		@Override
		public int getRowCount() {
			return m_list.size();
		}

		@Override
		public int getColumnCount() {
			return 3;
		}

		@Override
		public String getColumnName(int column) {
			return switch(column) {
				case 0 -> "";
				case 1 -> "Source";
				default -> "Address";
			};
		}

		@Override
		public Class<?> getColumnClass(int column) {
			return column == 0 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == 0;
		}

		@Override
		public Object getValueAt(int row, int column) {
			DiagnosticSource s = m_list.get(row);
			return switch(column) {
				case 0 -> m_ticked[row];
				case 1 -> s.title();
				default -> s.url().getHost() + s.url().getPath();
			};
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			if(column != 0 || !(value instanceof Boolean b))
				return;
			m_ticked[row] = b;
			fireTableCellUpdated(row, column);
			m_onChange.accept(m_list.get(row), b);
		}
	}

	/** What the last search found, and whether each is in the library already. */
	private static final class FoundModel extends AbstractTableModel {
		private List<IndexPageScanner.Found> m_rows = List.of();

		private Set<String> m_inLibrary = Set.of();

		void set(List<IndexPageScanner.Found> rows, Set<String> inLibrary) {
			m_rows = List.copyOf(rows);
			m_inLibrary = Set.copyOf(inLibrary);
			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return m_rows.size();
		}

		@Override
		public int getColumnCount() {
			return 3;
		}

		@Override
		public String getColumnName(int column) {
			return switch(column) {
				case 0 -> "Source";
				case 1 -> "File";
				default -> "State";
			};
		}

		@Override
		public Object getValueAt(int row, int column) {
			IndexPageScanner.Found f = m_rows.get(row);
			return switch(column) {
				case 0 -> f.source().title();
				case 1 -> f.path();
				default -> m_inLibrary.contains(f.uri().toString()) ? "in the library" : "new";
			};
		}
	}
}
