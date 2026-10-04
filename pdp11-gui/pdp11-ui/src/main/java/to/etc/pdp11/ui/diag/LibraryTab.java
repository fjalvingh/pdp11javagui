package to.etc.pdp11.ui.diag;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.diag.CatalogEntry;
import to.etc.pdp11.common.diag.DiagnosticFamily;
import to.etc.pdp11.common.diag.LibraryFile;
import to.etc.pdp11.common.diag.LibraryListing;
import to.etc.pdp11.common.diag.LibraryMedium;
import to.etc.pdp11.common.diag.ProgramCheck;
import to.etc.pdp11.ui.UiColors;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.awt.Font;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The library: every program collected, what DEC's index says it is, and where it came from.
 *
 * <p>What it shows is {@link LibraryListing}'s, which is where the filtering and the ordering are
 * and are tested; this only draws them.</p>
 */
final class LibraryTab extends JPanel {
	static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ROOT);

	private final ProgramModel m_model = new ProgramModel();

	private final JTable m_table = new JTable(m_model);

	private final JComboBox<FamilyChoice> m_family = new JComboBox<>();

	private final JTextField m_filter = new JTextField();

	private final JLabel m_count = new JLabel(" ");

	private final JLabel m_location = new JLabel(" ");

	private final JButton m_openFolder = new JButton("Open folder");

	private final JTextArea m_details = new JTextArea();

	private final JCheckBox m_runnableOnly = new JCheckBox("Standalone only");

	private final JTextField m_start = new JTextField(7);

	private final JTextField m_switches = new JTextField(7);

	private final JButton m_load = new JButton("Load");

	private final JButton m_loadAndStart = new JButton("Load and start");

	private final JLabel m_runStatus = new JLabel(" ");

	private final RunHandler m_runHandler;

	/** What the Load buttons are asked to do: put a program into the machine, and maybe start it. */
	@FunctionalInterface
	interface RunHandler {
		/**
		 * @param switches the value for location 176, or null to leave the program's own
		 */
		void run(LibraryListing.Row row, int start, Integer switches, boolean andStart);
	}

	private List<LibraryListing.Row> m_all = List.of();

	private Map<String, LibraryMedium> m_media = Map.of();

	/** Rebuilding the family list fires its listener; that must not refilter halfway. */
	private boolean m_updating;

	/** One entry of the family combo: a family with how many it has, or all of them. */
	record FamilyChoice(DiagnosticFamily family, int count) {
		@Override
		public String toString() {
			return (family == null ? "All families" : family.getLabel()) + " (" + count + ")";
		}
	}

	LibraryTab(Runnable openFolder, RunHandler runHandler) {
		super(new MigLayout("fill, insets 6", "[][grow][][]", "[][][grow][]"));
		m_runHandler = runHandler;
		m_location.setForeground(UiColors.SECONDARY_TEXT);
		add(m_location, "span 3, growx, wmin 0");
		add(m_openFolder, "wrap");
		m_openFolder.addActionListener(e -> openFolder.run());

		add(new JLabel("Family:"));
		add(m_family, "split 4, w 260::");
		add(new JLabel("Find:"), "gapleft 12");
		add(m_filter, "growx, w 160::");
		add(m_runnableOnly, "gapleft 12");
		m_runnableOnly.setToolTipText("Only programs that run on their own, deposited and started, without XXDP or its supervisor");
		m_count.setForeground(UiColors.SECONDARY_TEXT);
		add(m_count, "span 2, wrap");
		m_filter.setToolTipText("Words to look for in the name, diagnostic number, title and family; all must match");

		m_table.setAutoCreateRowSorter(true);
		m_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		m_table.setFillsViewportHeight(true);
		for(int i = 0; i < ProgramModel.WIDTHS.length; i++) {
			TableColumn c = m_table.getColumnModel().getColumn(i);
			c.setPreferredWidth(ProgramModel.WIDTHS[i]);
			//-- A minimum as well, or the first layout pass hands the title column's width to
			//-- whichever neighbour asked loudest; see CLAUDE.md.
			c.setMinWidth(ProgramModel.MIN_WIDTHS[i]);
		}
		m_table.getColumnModel().getColumn(ProgramModel.COL_STATUS).setCellRenderer(new StatusRenderer());
		m_table.getColumnModel().getColumn(ProgramModel.COL_RUNS).setCellRenderer(new RunsRenderer());
		m_details.setEditable(false);
		m_details.setLineWrap(true);
		m_details.setWrapStyleWord(true);
		m_details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_details.getFont().getSize()));
		m_details.setRows(5);

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrolled(m_table), new JScrollPane(m_details));
		split.setResizeWeight(0.8);
		split.setBorder(null);
		add(split, "span, grow, wrap");

		//-- The run bar: what to start at, the software switch register, and the two buttons.
		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, m_start.getFont().getSize());
		m_start.setFont(mono);
		m_switches.setFont(mono);
		m_start.setToolTipText("Octal. Filled in from the program: its transfer address, or 200 when it has none");
		m_switches.setToolTipText("Octal value for location 176, the software switch register of standalone diagnostics; "
			+ "empty leaves what the program sets");
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][][][][][][grow]", "[]"));
		bar.add(new JLabel("Start at:"));
		bar.add(m_start);
		bar.add(new JLabel("Switches (176):"), "gapleft 12");
		bar.add(m_switches);
		bar.add(m_load, "gapleft 12");
		bar.add(m_loadAndStart);
		m_runStatus.setForeground(UiColors.SECONDARY_TEXT);
		bar.add(m_runStatus, "gapleft 8, growx, wmin 0");
		add(bar, "span, growx");
		m_load.addActionListener(e -> runSelected(false));
		m_loadAndStart.addActionListener(e -> runSelected(true));
		m_runnableOnly.addActionListener(e -> refilter());

		m_family.addActionListener(e -> {
			if(!m_updating)
				refilter();
		});
		m_filter.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				refilter();
			}
		});
		m_table.getSelectionModel().addListSelectionListener(e -> {
			if(!e.getValueIsAdjusting())
				showDetails();
		});
	}

	/**
	 * A table in a scroll pane with its header put there now, not when the table is added to a
	 * window: the table only does that itself in {@code addNotify}, which a panel rendered with no
	 * display never gets, and the header is half of what a render is for.
	 */
	static JScrollPane scrolled(JTable table) {
		JScrollPane scroll = new JScrollPane(table);
		scroll.setColumnHeaderView(table.getTableHeader());
		table.getTableHeader().setReorderingAllowed(false);
		return scroll;
	}

	/** Where the library is, for the line at the top. */
	void setLocation(String text) {
		m_location.setText(text);
		m_location.setToolTipText(text);
	}

	/** A new library to show. Keeps the family and the filter that were chosen. */
	void show(List<LibraryListing.Row> rows, Map<String, LibraryMedium> media) {
		m_all = rows;
		m_media = media;
		DiagnosticFamily chosen = selectedFamily();
		m_updating = true;
		try {
			DefaultComboBoxModel<FamilyChoice> families = new DefaultComboBoxModel<>();
			families.addElement(new FamilyChoice(null, rows.size()));
			FamilyChoice select = null;
			for(Map.Entry<DiagnosticFamily, Integer> e : LibraryListing.counts(rows).entrySet()) {
				FamilyChoice c = new FamilyChoice(e.getKey(), e.getValue());
				families.addElement(c);
				if(e.getKey() == chosen)
					select = c;
			}
			m_family.setModel(families);
			m_family.setSelectedItem(select != null ? select : families.getElementAt(0));
		} finally {
			m_updating = false;
		}
		refilter();
	}

	private DiagnosticFamily selectedFamily() {
		Object o = m_family.getSelectedItem();
		return o instanceof FamilyChoice c ? c.family() : null;
	}

	private void refilter() {
		List<LibraryListing.Row> shown = LibraryListing.filter(m_all, selectedFamily(), m_filter.getText(), m_runnableOnly.isSelected());
		m_model.setRows(shown);
		m_count.setText(shown.size() == m_all.size() ? m_all.size() + " programs" : shown.size() + " of " + m_all.size() + " programs");
		showDetails();
	}

	/** The row selected, or null. */
	private LibraryListing.Row selected() {
		int view = m_table.getSelectedRow();
		return view < 0 ? null : m_model.row(m_table.convertRowIndexToModel(view));
	}

	/** The run bar follows the selection: armed for a standalone program, and saying why not otherwise. */
	private void updateRunBar(LibraryListing.Row r) {
		boolean runnable = r != null && r.run().isRunnable();
		m_load.setEnabled(runnable);
		m_loadAndStart.setEnabled(runnable);
		m_start.setEnabled(runnable);
		m_switches.setEnabled(runnable);
		if(runnable) {
			m_start.setText(String.format(Locale.ROOT, "%06o", r.run().startAddress()));
			setRunStatus(" ", false);
		} else {
			m_start.setText("");
			setRunStatus(r == null || r.run().reason().isEmpty() ? " " : "Cannot be run here: " + r.run().reason(), false);
		}
	}

	void setRunStatus(String text, boolean error) {
		m_runStatus.setText(text);
		m_runStatus.setToolTipText(text.isBlank() ? null : text);
		m_runStatus.setForeground(error ? UiColors.ERROR_TEXT : UiColors.SECONDARY_TEXT);
	}

	private void runSelected(boolean andStart) {
		LibraryListing.Row r = selected();
		if(r == null || !r.run().isRunnable())
			return;
		Integer start = octal(m_start.getText(), "start address");
		if(start == null)
			return;
		if((start & 1) != 0) {
			setRunStatus("The start address must be even", true);
			return;
		}
		Integer switches = null;
		if(!m_switches.getText().isBlank()) {
			switches = octal(m_switches.getText(), "switch register value");
			if(switches == null)
				return;
		}
		m_runHandler.run(r, start, switches, andStart);
	}

	/** An octal word from a field, or null having said what is wrong with it. */
	private Integer octal(String text, String what) {
		String t = text.strip();
		try {
			int v = Integer.parseInt(t, 8);
			if(v < 0 || v > 0177777)
				throw new NumberFormatException();
			return v;
		} catch(NumberFormatException x) {
			setRunStatus("\"" + t + "\" is not an octal " + what, true);
			return null;
		}
	}

	private void showDetails() {
		int view = m_table.getSelectedRow();
		updateRunBar(selected());
		if(view < 0) {
			m_details.setText(m_all.isEmpty()
				? "Nothing has been collected yet. The Collect tab finds diagnostics on the Internet and downloads them."
				: "Select a program to see where it is and where it came from.");
			return;
		}
		LibraryListing.Row r = m_model.row(m_table.convertRowIndexToModel(view));
		m_details.setText(details(r, m_media));
		m_details.setCaretPosition(0);
	}

	/** What the details pane says about a program. */
	static String details(LibraryListing.Row r, Map<String, LibraryMedium> media) {
		LibraryFile f = r.file();
		StringBuilder sb = new StringBuilder();
		sb.append(f.name());
		if(!r.title().isEmpty())
			sb.append(" - ").append(r.title());
		sb.append('\n');
		if(f.damaged())
			sb.append("DAMAGED: the medium it came from could not be read whole, so this copy is probably incomplete.\n");
		sb.append("File: ").append(f.path()).append('\n');
		if(!r.run().reason().isEmpty())
			sb.append("Running: ").append(r.run().reason()).append('\n');
		CatalogEntry e = r.identification().entry();
		if(e != null) {
			sb.append("DEC: C").append(e.id()).append(r.identification().revision().isEmpty() ? "" : r.identification().revision())
				.append(", revisions ").append(String.join(" ", e.revisions()));
			if(!e.packages().isEmpty())
				sb.append(", shipped on ").append(String.join("; ", e.packages()));
			sb.append('\n');
		}
		sb.append("Found on:\n");
		for(String key : f.foundOn()) {
			LibraryMedium m = media.get(key);
			if(m == null)
				sb.append("  ").append(key).append('\n');
			else
				sb.append("  ").append(m.volume()).append(" (").append(m.description()).append(")\n");
		}
		return sb.toString();
	}

	JTable getTable() {
		return m_table;
	}

	JTextField getFilter() {
		return m_filter;
	}

	JComboBox<FamilyChoice> getFamily() {
		return m_family;
	}

	JTextArea getDetails() {
		return m_details;
	}

	JLabel getCount() {
		return m_count;
	}

	JCheckBox getRunnableOnly() {
		return m_runnableOnly;
	}

	JTextField getStartField() {
		return m_start;
	}

	JTextField getSwitchesField() {
		return m_switches;
	}

	JButton getLoadButton() {
		return m_load;
	}

	JButton getLoadAndStartButton() {
		return m_loadAndStart;
	}

	JLabel getRunStatus() {
		return m_runStatus;
	}

	/** The library's programs, a row each. */
	static final class ProgramModel extends AbstractTableModel {
		static final int COL_NAME = 0;

		static final int COL_TITLE = 1;

		static final int COL_FAMILY = 2;

		static final int COL_DATE = 3;

		static final int COL_SIZE = 4;

		static final int COL_MEDIA = 5;

		static final int COL_STATUS = 6;

		static final int COL_RUNS = 7;

		private static final String[] NAMES = {"Program", "Title", "Family", "Date", "Size", "Media", "Status", "Runs"};

		static final int[] WIDTHS = {110, 240, 190, 80, 65, 50, 75, 85};

		static final int[] MIN_WIDTHS = {90, 130, 90, 70, 50, 40, 60, 75};

		private List<LibraryListing.Row> m_rows = new ArrayList<>();

		void setRows(List<LibraryListing.Row> rows) {
			m_rows = rows;
			fireTableDataChanged();
		}

		LibraryListing.Row row(int index) {
			return m_rows.get(index);
		}

		@Override
		public int getRowCount() {
			return m_rows.size();
		}

		@Override
		public int getColumnCount() {
			return NAMES.length;
		}

		@Override
		public String getColumnName(int column) {
			return NAMES[column];
		}

		@Override
		public Class<?> getColumnClass(int column) {
			return column == COL_SIZE || column == COL_MEDIA ? Integer.class : String.class;
		}

		@Override
		public Object getValueAt(int row, int column) {
			LibraryListing.Row r = m_rows.get(row);
			LibraryFile f = r.file();
			return switch(column) {
				case COL_NAME -> f.name();
				case COL_TITLE -> r.title();
				case COL_FAMILY -> r.family().getLabel();
				case COL_DATE -> date(f.date());
				case COL_SIZE -> (int) f.size();
				case COL_MEDIA -> f.foundOn().size();
				case COL_STATUS -> f.damaged() ? "damaged" : f.path().startsWith("files/variants/") ? "other copy" : "";
				case COL_RUNS -> r.run().kind().getLabel();
				default -> "";
			};
		}

		private static String date(LocalDate d) {
			//-- DEC's own form, as XXDP's DIR prints it: 01-MAR-89. Sorting by it is wrong across
			//-- years, but nobody sorts this table by date, and DIR's form is what people know.
			return d == null ? "" : DATE.format(d).toUpperCase(Locale.ROOT);
		}
	}

	/** "standalone" in the colour that means good to go, the rest quietly. */
	private static final class RunsRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row,
			int column) {
			Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if(!isSelected)
				c.setForeground(ProgramCheck.Kind.STANDALONE.getLabel().equals(value) ? UiColors.OK_TEXT : UiColors.SECONDARY_TEXT);
			return c;
		}
	}

	/** "damaged" in the colour that means failed. */
	private static final class StatusRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row,
			int column) {
			Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if(!isSelected)
				c.setForeground("damaged".equals(value) ? UiColors.ERROR_TEXT : UiColors.SECONDARY_TEXT);
			return c;
		}
	}
}
