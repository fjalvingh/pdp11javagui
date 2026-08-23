package to.etc.pdp11.ui.microcode;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.microcode.MicroInstruction;
import to.etc.pdp11.core.microcode.Pdp1144Microcode;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The PDP-11/44's microcode, one microword at a time: what its 104 bits are set to, what that
 * means, and the line of DEC's listing it was read from.
 *
 * <p>Ported from {@code TFormMicroCode} ({@code FormMicroCodeU.pas}). Reading the listing and
 * cutting the microwords into fields is {@link Pdp1144Microcode} in the core, where it is
 * tested against the whole of EY-C3012-RB-001; what is here is the search, the walk and the
 * table.</p>
 *
 * <h2>It is a reference, not a debugger</h2>
 *
 * <p>Nothing here touches a machine, and there is no {@code µPC} to read from one: an 11/44's
 * console cannot say which microword it is executing. This is the microcode as printed, beside
 * the processor it belongs to - what a {@code BUT} field selects, what a scratch pad address
 * means, which microword a state falls through to. The Pascal is the same and the naming here
 * says so.</p>
 *
 * <h2>What this does that the Pascal does not</h2>
 *
 * <p><b>The listing is packaged.</b> The Pascal remembers a file name in the registry, defaults
 * it into the data directory, and opens saying "code not loaded" when nobody has put a copy of
 * a 1981 DEC document there - which is the usual case. Here the listing is shipped and the
 * window works on first open; Load is for another scan or another revision.</p>
 *
 * <p><b>You can go back.</b> Next follows the fall-through, as it does there. But a microword
 * also lists what falls through to <i>it</i>, and Back returns along the way you came, because
 * microcode is mostly read backwards from the state you ended up in.</p>
 *
 * <p><b>A damaged listing still opens.</b> The Pascal raises on the first line it does not like
 * and shows nothing. Here what could not be read is a count in the status line and a note in the
 * log, and the rest of the microcode is there to look at.</p>
 */
public final class MicrocodePanel extends JPanel {
	/** What the box at the top holds, and so what typing in it means. */
	public enum SearchBy {
		ADDRESS("µPC"),
		TAG("Symbolic tag"),
		LINE("Listing line");

		private final String m_label;

		SearchBy(String label) {
			m_label = label;
		}

		@Override
		public String toString() {
			return m_label;
		}
	}

	private final AppContext m_context;

	private final JComboBox<SearchBy> m_searchBy = new JComboBox<>(SearchBy.values());

	private final JComboBox<String> m_search = new JComboBox<>();

	private final JButton m_back = new JButton("Back");

	private final JButton m_next = new JButton("Next instruction");

	/** Named after the same act in the Assembler window, and spelled the way every other one is. */
	private final JButton m_load = new JButton("Open listing ...");

	private final JLabel m_status = new JLabel();

	private final MicrocodeTableModel m_model = new MicrocodeTableModel();

	private final JTable m_table = new JTable(m_model);

	/** Where the user has been, most recent first, so Back walks it. */
	private final Deque<Integer> m_history = new ArrayDeque<>();

	private Pdp1144Microcode m_code;

	private MicroInstruction m_current;

	/** Set while the search box is being refilled, so its own events do not navigate. */
	private boolean m_updating;

	public MicrocodePanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;

		add(buildControls(), "growx, wrap");
		m_table.setAutoCreateRowSorter(false);
		m_table.getTableHeader().setReorderingAllowed(false);
		m_table.setDefaultRenderer(Object.class, new RowRenderer());
		for(int i = 0; i < MicrocodeTableModel.WIDTHS.length; i++) {
			TableColumn c = m_table.getColumnModel().getColumn(i);
			//-- Both, not just the preferred width: any auto-resize mode redistributes preferred
			//-- widths on the first layout pass and keeps the result.
			c.setPreferredWidth(MicrocodeTableModel.WIDTHS[i]);
			c.setMinWidth(MicrocodeTableModel.WIDTHS[i] / 2);
		}
		add(new JScrollPane(m_table), "grow, wrap");
		m_status.setForeground(UiColors.SECONDARY_TEXT);
		add(m_status, "growx");
		updateButtons();
		showStatus();
	}

	private JPanel buildControls() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]12[][]8[]8[]12[]", "[]"));
		bar.add(new JLabel("Search by:"));
		m_searchBy.addActionListener(e -> refillSearch());
		bar.add(m_searchBy);

		bar.add(new JLabel("µInstruction:"));
		//-- Editable, because 1018 microwords is too many to find by scrolling and everybody
		//-- arrives here already knowing an address or a tag.
		m_search.setEditable(true);
		m_search.addActionListener(e -> {
			if(!m_updating)
				searchFor(String.valueOf(m_search.getEditor().getItem()));
		});
		Component editor = m_search.getEditor().getEditorComponent();
		if(editor instanceof JTextField tf)
			tf.setFont(new Font(Font.MONOSPACED, Font.PLAIN, tf.getFont().getSize()));
		bar.add(m_search, "w 200!");

		m_back.setToolTipText("Back to the microword you came from");
		m_back.addActionListener(e -> back());
		bar.add(m_back);
		m_next.setToolTipText("Follow this microword's next-address field, which is where it goes"
			+ " when nothing branches");
		m_next.addActionListener(e -> next());
		bar.add(m_next);
		m_load.setToolTipText("Read another copy of the microcode listing."
			+ " A listing split into one file per page can be chosen all at once.");
		m_load.addActionListener(e -> chooseListing());
		bar.add(m_load);
		return bar;
	}

	// -------------------------------------------------------------------------------------
	// Showing and hiding
	// -------------------------------------------------------------------------------------

	/**
	 * Make sure there is microcode to look at.
	 *
	 * <p>On the event thread, and deliberately: reading and decoding the whole listing takes
	 * some tens of milliseconds once, on the first open of this window, and doing it on a worker
	 * would buy a flash of an empty table in exchange for a thread and its marshalling. The same
	 * choice as {@code MemoryLoaderPanel}, which reads its files on the event thread too.</p>
	 */
	public void attach() {
		if(m_code != null)
			return;
		String remembered = m_context.getSettings().getLastMicrocodeFile();
		if(remembered != null && Files.isReadable(Path.of(remembered))) {
			if(loadFrom(Path.of(remembered)))
				return;
			//-- The file has been moved or damaged since it was chosen. Say so, and fall back to
			//-- the packaged listing rather than opening an empty window.
		}
		show(Pdp1144Microcode.builtin());
	}

	public void detach() {
	}

	// -------------------------------------------------------------------------------------
	// Loading
	// -------------------------------------------------------------------------------------

	private void chooseListing() {
		JFileChooser chooser = new JFileChooser();
		//-- The multi-selection is for a listing split into one file per page, and a chooser
		//-- that quietly accepts several files without saying why is a feature nobody finds
		//-- (FABLE-ISSUES #63). The title is where a file chooser can say it.
		chooser.setDialogTitle("Open a PDP-11/44 microcode listing (or every page of one)");
		String remembered = m_context.getSettings().getLastMicrocodeFile();
		if(remembered != null)
			chooser.setSelectedFile(new java.io.File(remembered));
		//-- One file, not a wildcard over its neighbours: the Pascal strips the digits off the
		//-- name it was given and loads whatever matches ({@code FormMicroCodeU.pas:126-136}),
		//-- which quietly picks up files nobody chose.
		chooser.setMultiSelectionEnabled(true);
		if(chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		java.io.File[] chosen = chooser.getSelectedFiles();
		if(chosen.length <= 1) {
			java.io.File one = chosen.length == 1 ? chosen[0] : chooser.getSelectedFile();
			if(one == null)
				return;
			loadFrom(one.toPath());
			return;
		}
		List<Path> paths = new ArrayList<>();
		for(java.io.File f : chosen)
			paths.add(f.toPath());
		loadPages(paths);
	}

	/** Read one listing file, keeping the microword being looked at if the new listing has it. */
	public boolean loadFrom(Path file) {
		try {
			replace(Pdp1144Microcode.load(file), file);
			return true;
		} catch(IOException | RuntimeException x) {
			m_context.reportFailure("Cannot read the microcode listing " + file.getFileName(), x);
			return false;
		}
	}

	/** Read a listing that is split across per-page files. */
	public boolean loadPages(List<Path> files) {
		try {
			replace(Pdp1144Microcode.load(files), files.get(0));
			return true;
		} catch(IOException | RuntimeException x) {
			m_context.reportFailure("Cannot read the microcode listing", x);
			return false;
		}
	}

	private void replace(Pdp1144Microcode code, Path remember) {
		int keep = m_current == null ? -1 : m_current.getAddress();
		m_context.getSettings().setLastMicrocodeFile(remember.toAbsolutePath().toString());
		m_context.saveSettings();
		show(code);
		if(keep >= 0 && code.atAddress(keep) != null)
			select(code.atAddress(keep), false);
	}

	/** Take a freshly read listing, complain about it in the log if it has anything wrong. */
	private void show(Pdp1144Microcode code) {
		m_code = code;
		m_history.clear();
		m_context.getLogger().log(LogChannel.OTHER, "Microcode: %s", code.describe());
		for(Pdp1144Microcode.Problem p : code.getProblems())
			m_context.getLogger().log(LogChannel.OTHER, "Microcode: %s", p.describe());
		refillSearch();
		select(code.isEmpty() ? null : code.byAddress().get(0), false);
	}

	// -------------------------------------------------------------------------------------
	// Getting about
	// -------------------------------------------------------------------------------------

	/**
	 * Fill the search box with every value in whichever order was chosen.
	 *
	 * <p>The list is the index: with all 1018 in it, dropping the box open at "Symbolic tag" is
	 * the listing's table of contents, and typing into it finds one directly.</p>
	 */
	private void refillSearch() {
		m_updating = true;
		try {
			DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
			if(m_code != null) {
				for(MicroInstruction mi : instructionsInSearchOrder())
					model.addElement(searchTextOf(mi));
			}
			m_search.setModel(model);
			if(m_current != null)
				m_search.getEditor().setItem(searchTextOf(m_current));
		} finally {
			m_updating = false;
		}
	}

	private List<MicroInstruction> instructionsInSearchOrder() {
		return switch(searchBy()) {
			case ADDRESS -> m_code.byAddress();
			case TAG -> m_code.byTag();
			case LINE -> m_code.byLineNumber();
		};
	}

	private String searchTextOf(MicroInstruction mi) {
		return switch(searchBy()) {
			case ADDRESS -> mi.getAddressOctal();
			case TAG -> mi.getSymbolicTag();
			case LINE -> String.valueOf(mi.getLineNumber());
		};
	}

	private SearchBy searchBy() {
		SearchBy by = (SearchBy) m_searchBy.getSelectedItem();
		return by == null ? SearchBy.ADDRESS : by;
	}

	/** Whatever was typed or picked, in whichever of the three ways it is being read. */
	public void searchFor(String text) {
		if(m_code == null || text == null)
			return;
		String s = text.strip();
		if(s.isEmpty())
			return;
		MicroInstruction found = switch(searchBy()) {
			case ADDRESS -> m_code.atAddress((int) Octal.parseOr(s, -1));
			case TAG -> m_code.withTag(s);
			case LINE -> {
				try {
					yield m_code.atLineNumber(Integer.parseInt(s));
				} catch(NumberFormatException x) {
					yield null;
				}
			}
		};
		if(found == null) {
			//-- Nothing is worse here than jumping somewhere else: say it was not found and leave
			//-- what is on screen alone. The Pascal's address mode reads an unparseable address
			//-- as 0 and silently shows the first microword instead.
			m_status.setText("No microword " + (searchBy() == SearchBy.TAG ? "tagged " : "at ") + s);
			m_status.setForeground(UiColors.ERROR_TEXT);
			return;
		}
		select(found, true);
	}

	/** Follow the fall-through, which is what the microword's next-address field says. */
	public void next() {
		if(m_current == null || m_code == null)
			return;
		MicroInstruction to = m_code.atAddress(m_current.getNextAddress());
		if(to == null) {
			m_status.setText("This microword goes to " + m_current.getNextAddressOctal()
				+ ", which is not in this listing");
			m_status.setForeground(UiColors.ERROR_TEXT);
			return;
		}
		select(to, true);
	}

	/** Back the way we came. */
	public void back() {
		if(m_history.isEmpty() || m_code == null)
			return;
		MicroInstruction to = m_code.atAddress(m_history.pop());
		if(to != null)
			select(to, false);
		updateButtons();
	}

	private void select(MicroInstruction mi, boolean remember) {
		if(remember && m_current != null && m_current != mi)
			m_history.push(m_current.getAddress());
		m_current = mi;
		m_model.setInstruction(mi, mi == null || m_code == null ? List.of() : m_code.predecessorsOf(mi));
		m_updating = true;
		try {
			if(mi != null)
				m_search.getEditor().setItem(searchTextOf(mi));
		} finally {
			m_updating = false;
		}
		updateButtons();
		showStatus();
	}

	private void updateButtons() {
		boolean loaded = m_code != null && !m_code.isEmpty();
		m_searchBy.setEnabled(loaded);
		m_search.setEnabled(loaded);
		m_next.setEnabled(loaded && m_current != null);
		m_back.setEnabled(!m_history.isEmpty());
	}

	/** What is on screen, where it came from, and whether the listing hangs together. */
	private void showStatus() {
		if(m_code == null) {
			m_status.setText("No microcode loaded");
			m_status.setForeground(UiColors.ERROR_TEXT);
			return;
		}
		StringBuilder sb = new StringBuilder();
		if(m_current != null) {
			sb.append("µPC = ").append(m_current.getAddressOctal())
				.append("  ·  ").append(m_current.getSymbolicTag())
				.append("  ·  next ").append(m_current.getNextAddressOctal())
				.append("  ·  ");
		}
		sb.append(m_code.describe());
		m_status.setText(sb.toString());
		m_status.setToolTipText(m_code.isOk() ? null : firstProblems());
		m_status.setForeground(m_code.isOk() ? UiColors.SECONDARY_TEXT : UiColors.ERROR_TEXT);
	}

	private String firstProblems() {
		StringBuilder sb = new StringBuilder("<html>");
		List<Pdp1144Microcode.Problem> problems = m_code.getProblems();
		for(int i = 0; i < Math.min(5, problems.size()); i++)
			sb.append(problems.get(i).describe()).append("<br>");
		if(problems.size() > 5)
			sb.append("... and ").append(problems.size() - 5).append(" more, in the log");
		return sb.append("</html>").toString();
	}

	/** Yellow for the fields this microword actually sets, as in the Pascal. */
	private final class RowRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
			boolean focused, int row, int column) {
			Component c = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
			MicrocodeTableModel.Row r = m_model.getRow(row);
			if(r.highlight() && !selected) {
				c.setBackground(UiColors.EDITED_BACKGROUND);
				c.setForeground(UiColors.EDITED_TEXT);
			} else {
				c.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
				c.setForeground(selected ? table.getSelectionForeground() : table.getForeground());
			}
			if(c instanceof JComponent jc)
				jc.setToolTipText(column == 2 && !r.info().isEmpty() ? r.info() : null);
			return c;
		}
	}

	// -------------------------------------------------------------------------------------
	// For tests
	// -------------------------------------------------------------------------------------

	public JTable getTable() {
		return m_table;
	}

	public MicrocodeTableModel getModel() {
		return m_model;
	}

	public JComboBox<SearchBy> getSearchBySelector() {
		return m_searchBy;
	}

	public JComboBox<String> getSearchBox() {
		return m_search;
	}

	public JButton getNextButton() {
		return m_next;
	}

	public JButton getBackButton() {
		return m_back;
	}

	public JButton getLoadButton() {
		return m_load;
	}

	public MicroInstruction getCurrent() {
		return m_current;
	}

	public Pdp1144Microcode getMicrocode() {
		return m_code;
	}

	public String getStatusText() {
		return m_status.getText();
	}
}
