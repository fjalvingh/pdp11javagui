package to.etc.pdp11.ui.microcode;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.microcode.MicroInstruction;
import to.etc.pdp11.core.microcode.Microcode;
import to.etc.pdp11.core.microcode.MicrocodeField;
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
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A processor's microcode, one microword at a time: what its bits are set to, what that means,
 * and where in the document it was read from.
 *
 * <p>Ported from {@code TFormMicroCode} ({@code FormMicroCodeU.pas}). Reading the document and
 * cutting the microwords into fields is {@link Microcode} and the loaders beside it in the core,
 * where each is tested against the whole of its source; what is here is the search, the walk and
 * the table.</p>
 *
 * <h2>On the 11/05 it is a debugger</h2>
 *
 * <p>The Pascal's window is a reference and says so, because an 11/44's console cannot tell you
 * which microword it is executing. <b>That stops being true on the PDP-11/05.</b> With a KM11 in
 * slot 8 the KD11-B's microprogram counter is on the lights - {@code MPC0} through {@code MPC7} -
 * so eight bits read off the panel and typed into the µPC box say exactly which microword the
 * machine is sitting in, and this window says what that microword asserts. That is the reason it
 * shows the 11/05 at all.</p>
 *
 * <p>For the 11/44 it remains what it was: the microcode as printed, beside the processor it
 * belongs to.</p>
 *
 * <h2>Which microcode, and why the title says so</h2>
 *
 * <p>Three documents, in one combo: the 11/44's listing and the KD11-B's two board revisions. The
 * revisions matter more than they look. They have <i>identical</i> addresses and next-addresses
 * and differ in 20 bits across 14 microwords, so having the wrong one selected does not look
 * wrong - every address resolves, every chain walks, and the microword on screen is simply
 * incorrect in {@code AUX} or {@code CKO} with nothing at all to show for it. So the selection is
 * in the window title, and the fields the other revision disagrees on are coloured.</p>
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

		/** The ways the loaded document can actually be searched, which is not always all three. */
		public static SearchBy[] availableFor(MicrocodeSource source) {
			return source.hasListingLineNumbers() ? values() : new SearchBy[]{ADDRESS, TAG};
		}

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

	/** Which microcode is being looked at. Three entries, not a machine combo and a revision one. */
	private final JComboBox<MicrocodeSource> m_source = new JComboBox<>(MicrocodeSource.values());

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

	/** What has been read so far, so that switching back and forth does not re-read anything. */
	private final Map<MicrocodeSource, Microcode> m_loaded = new EnumMap<>(MicrocodeSource.class);

	/** Told the window what to put in its title bar. A panel does not reach for its frame. */
	private Consumer<String> m_titleListener = t -> {
	};

	private MicrocodeSource m_selected = MicrocodeSource.DEFAULT;

	private Microcode m_code;

	/** The same microwords off the other revision of the same board, or null when there is none. */
	private Microcode m_otherRevision;

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
		//-- Two rows. One would need about 1100 pixels now that there are three microcodes to
		//-- choose between, and a window that has to be that wide before its toolbar fits is a
		//-- window that opens wrong on a laptop.
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]16[][]8[]", "[]4[]"));
		bar.add(new JLabel("Microcode:"));
		m_source.setSelectedItem(m_selected);
		m_source.setToolTipText("Which processor's microcode to show."
			+ " The two PDP-11/05 entries are the two M7261 board revisions: read the part numbers"
			+ " off the two control store PROMs to tell which board is in the machine.");
		m_source.addActionListener(e -> {
			if(!m_updating)
				chooseSource((MicrocodeSource) m_source.getSelectedItem());
		});
		bar.add(m_source);

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
		bar.add(m_search, "w 200!, wrap");

		m_back.setToolTipText("Back to the microword you came from");
		m_back.addActionListener(e -> back());
		bar.add(m_back, "skip 1, split 3");
		m_next.setToolTipText("Follow this microword's next-address field, which is where it goes"
			+ " when nothing branches");
		m_next.addActionListener(e -> next());
		bar.add(m_next);
		m_load.setToolTipText("Read another copy of the selected microcode."
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
		//-- A selection this version does not know - written by a newer one, or edited by hand -
		//-- is not a reason to fail to open. Nothing in settings may stop the application.
		String remembered = m_context.getSettings().getMicrocodeSelection();
		MicrocodeSource source = remembered == null ? null : MicrocodeSource.byLabel(remembered);
		if(remembered != null && source == null)
			m_context.getLogger().log(LogChannel.OTHER,
				"Microcode: the settings ask for \"%s\", which this version does not have; showing %s",
				remembered, MicrocodeSource.DEFAULT.getLabel());
		chooseSource(source == null ? MicrocodeSource.DEFAULT : source);
	}

	/**
	 * Show one of the three, reading it if it has not been read yet.
	 *
	 * <p>On the event thread, and deliberately: reading and decoding a whole document takes some
	 * tens of milliseconds once, and doing it on a worker would buy a flash of an empty table in
	 * exchange for a thread and its marshalling.</p>
	 */
	public void chooseSource(MicrocodeSource source) {
		m_selected = source;
		m_updating = true;
		try {
			m_source.setSelectedItem(source);
			m_searchBy.setModel(new DefaultComboBoxModel<>(SearchBy.availableFor(source)));
		} finally {
			m_updating = false;
		}
		m_context.getSettings().setMicrocodeSelection(source.getLabel());
		m_context.saveSettings();
		m_titleListener.accept("Microcode - " + source.getLabel());

		String remembered = m_context.getSettings().getMicrocodeListing(source.getLabel());
		if(remembered != null && Files.isReadable(Path.of(remembered)) && loadFrom(Path.of(remembered)))
			return;
		//-- Either nothing was remembered, or the file has been moved or damaged since it was
		//-- chosen. Fall back to the packaged document rather than opening an empty window.
		Microcode code = m_loaded.get(source);
		show(code == null ? source.load() : code);
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
		chooser.setDialogTitle(m_selected.getOpenPrompt());
		String remembered = m_context.getSettings().getMicrocodeListing(m_selected.getLabel());
		if(remembered != null)
			chooser.setSelectedFile(new java.io.File(remembered));
		//-- One file, not a wildcard over its neighbours: the Pascal strips the digits off the
		//-- name it was given and loads whatever matches ({@code FormMicroCodeU.pas:126-136}),
		//-- which quietly picks up files nobody chose.
		chooser.setMultiSelectionEnabled(m_selected.isSplitAcrossFiles());
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

	/** Read one file, keeping the microword being looked at if the new document has it. */
	public boolean loadFrom(Path file) {
		return loadPages(List.of(file));
	}

	/** Read a document, which for the 11/44 may be split across per-page files. */
	public boolean loadPages(List<Path> files) {
		try {
			replace(m_selected.load(files), files.get(0));
			return true;
		} catch(IOException | RuntimeException x) {
			m_context.reportFailure("Cannot read the microcode for " + m_selected.getLabel(), x);
			return false;
		}
	}

	private void replace(Microcode code, Path remember) {
		int keep = m_current == null ? -1 : m_current.getAddress();
		m_context.getSettings().setMicrocodeListing(m_selected.getLabel(), remember.toAbsolutePath().toString());
		m_context.saveSettings();
		show(code);
		if(keep >= 0 && code.atAddress(keep) != null)
			select(code.atAddress(keep), false);
	}

	/** Take a freshly read document, complain about it in the log if it has anything wrong. */
	private void show(Microcode code) {
		m_code = code;
		m_loaded.put(m_selected, code);
		m_history.clear();
		m_context.getLogger().log(LogChannel.OTHER, "Microcode: %s", code.describe());
		for(Microcode.Problem p : code.getProblems())
			m_context.getLogger().log(LogChannel.OTHER, "Microcode: %s", p.describe());
		m_otherRevision = otherRevision();
		refillSearch();
		select(code.isEmpty() ? null : code.byAddress().get(0), false);
	}

	/**
	 * The same board's other revision, read if it has not been already.
	 *
	 * <p>Worth the second read: it is what turns "you may have the wrong revision selected" into
	 * fourteen microwords with two coloured rows in them. Failing to read it costs the colouring
	 * and nothing else, so it is not allowed to stop the window working.</p>
	 */
	private Microcode otherRevision() {
		MicrocodeSource other = m_selected.getOther();
		if(other == null)
			return null;
		try {
			return m_loaded.computeIfAbsent(other, MicrocodeSource::load);
		} catch(RuntimeException x) {
			m_context.getLogger().log(LogChannel.OTHER,
				"Microcode: cannot read %s to compare against: %s", other.getLabel(), x);
			return null;
		}
	}

	/**
	 * Which fields the other revision of this board has something else in, for the microword on
	 * screen. Empty for a machine with one revision, and for the 200 microwords that are the same
	 * in both.
	 */
	private Set<MicrocodeField> differingFields(MicroInstruction mi) {
		if(mi == null || m_otherRevision == null)
			return Set.of();
		MicroInstruction other = m_otherRevision.atAddress(mi.getAddress());
		if(other == null || other.getArchitecture() != mi.getArchitecture())
			return Set.of();
		Set<MicrocodeField> out = new LinkedHashSet<>();
		for(MicrocodeField f : mi.getFields()) {
			if(mi.getValue(f) != other.getValue(f))
				out.add(f);
		}
		return out;
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

	/** Which microcode is on screen. */
	public MicrocodeSource getSource() {
		return m_selected;
	}

	/** How the window is to be titled, which is where the chosen revision is visible. */
	public void setTitleListener(Consumer<String> listener) {
		m_titleListener = listener;
		listener.accept("Microcode - " + m_selected.getLabel());
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
			m_status.setText(notFound(s));
			m_status.setForeground(UiColors.ERROR_TEXT);
			return;
		}
		select(found, true);
	}

	/**
	 * Why what was asked for is not there.
	 *
	 * <p>The µPC case is the one that needs saying. A KD11-B address typed off the KM11's lights
	 * can be a perfectly good control store location that the listing does not print - 42 of the
	 * 256 are not - and "no microword at 377" on its own reads like a typo when it is not.</p>
	 */
	private String notFound(String s) {
		if(searchBy() == SearchBy.TAG)
			return "No microword tagged " + s;
		if(searchBy() == SearchBy.LINE)
			return "No microword on listing line " + s;
		long address = Octal.parseOr(s, -1);
		if(address < 0)
			return "No microword: \"" + s + "\" is not an octal address";
		int bits = m_code.getArchitecture().getAddressBits();
		if(address >= (1L << bits))
			return "No microword at " + s + ": there is no such address in a " + bits
				+ " bit control store";
		return "No microword at " + s + ": it is one of the "
			+ ((1 << bits) - m_code.size()) + " control store locations this document does not print";
	}

	/** Follow the fall-through, which is what the microword's next-address field says. */
	public void next() {
		if(m_current == null || m_code == null)
			return;
		MicroInstruction to = m_code.atAddress(m_current.getNextAddress());
		if(to == null) {
			m_status.setText("This microword goes to " + m_current.getNextAddressOctal()
				+ ", which this document does not print");
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
		m_model.setInstruction(mi, mi == null || m_code == null ? List.of() : m_code.predecessorsOf(mi),
			differingFields(mi));
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
				.append("  ·  next ").append(m_current.getNextAddressOctal());
			//-- Where a microtest is selected the hardware ORs its result into the next address,
			//-- so what is printed is a branch base and not the successor. Saying "next 147" flat
			//-- would be stating as fact something that depends on the state of the machine.
			if(m_current.isBranching())
				sb.append(" if ").append(m_current.getMicrotestName()).append(" is zero (a branch base)");
			sb.append("  ·  ");
		}
		sb.append(m_code.describe());
		m_status.setText(sb.toString());
		m_status.setToolTipText(m_code.isOk() ? null : firstProblems());
		m_status.setForeground(m_code.isOk() ? UiColors.SECONDARY_TEXT : UiColors.ERROR_TEXT);
	}

	private String firstProblems() {
		StringBuilder sb = new StringBuilder("<html>");
		List<Microcode.Problem> problems = m_code.getProblems();
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
			if(r.differs() && !selected) {
				//-- The only way a wrongly chosen board revision ever shows itself.
				c.setBackground(UiColors.REVISION_DIFFERENCE_BACKGROUND);
				c.setForeground(UiColors.REVISION_DIFFERENCE_TEXT);
			} else if(r.highlight() && !selected) {
				c.setBackground(UiColors.EDITED_BACKGROUND);
				c.setForeground(UiColors.EDITED_TEXT);
			} else {
				c.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
				c.setForeground(selected ? table.getSelectionForeground() : table.getForeground());
			}
			if(c instanceof JComponent jc) {
				String tip = r.differs() && m_selected.getOther() != null
					? "This field is different in " + m_selected.getOther().getLabel()
					: column == 2 && !r.info().isEmpty() ? r.info() : null;
				jc.setToolTipText(tip);
			}
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

	public JComboBox<MicrocodeSource> getSourceSelector() {
		return m_source;
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

	public Microcode getMicrocode() {
		return m_code;
	}

	public String getStatusText() {
		return m_status.getText();
	}
}
