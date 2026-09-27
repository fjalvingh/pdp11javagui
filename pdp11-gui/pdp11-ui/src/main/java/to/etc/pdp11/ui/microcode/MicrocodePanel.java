package to.etc.pdp11.ui.microcode;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.microcode.MicroInstruction;
import to.etc.pdp11.common.microcode.Microcode;
import to.etc.pdp11.common.microcode.MicrocodeBrowser;
import to.etc.pdp11.common.microcode.MicrocodeBrowser.SearchBy;
import to.etc.pdp11.common.microcode.MicrocodeSource;
import to.etc.pdp11.common.microcode.MicrowordRow;
import to.etc.pdp11.common.util.LogChannel;
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
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A processor's microcode, one microword at a time: what its bits are set to, what that means,
 * and where in the document it was read from.
 *
 * <p>Ported from {@code TFormMicroCode} ({@code FormMicroCodeU.pas}). Reading the document and
 * cutting the microwords into fields is {@link Microcode} and the loaders beside it, where each
 * is tested against the whole of its source; the search, the walk and what the rows say are
 * {@link MicrocodeBrowser} and {@link MicrowordRow}, which the web application's microcode page
 * shows as well. What is here is the widgets, the colours, the file chooser and the settings.</p>
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

	/** Which document, which microword, and the way back: everything but the widgets. */
	private final MicrocodeBrowser m_browser;

	/** Told the window what to put in its title bar. A panel does not reach for its frame. */
	private Consumer<String> m_titleListener = t -> {
	};

	/** Set while the search box is being refilled, so its own events do not navigate. */
	private boolean m_updating;

	private final JPanel m_controls;

	public MicrocodePanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;
		m_browser = new MicrocodeBrowser(context.getLogger());

		m_controls = buildControls();
		add(m_controls, "growx, wrap");
		m_table.setAutoCreateRowSorter(false);
		m_table.getTableHeader().setReorderingAllowed(false);
		m_table.setDefaultRenderer(Object.class, new RowRenderer());
		//-- Double-clicking where the microword says it goes next goes there, as Next does.
		m_table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if(e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e))
					rowDoubleClicked(m_table.rowAtPoint(e.getPoint()));
			}
		});
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
		//
		//-- Both combo boxes are given a width range rather than a width. A combo asks for as much
		//-- room as its longest item needs in the current font, and a JComboBox reports that as
		//-- its minimum as well as its preferred size - so the row cannot shrink at all, and on a
		//-- machine whose fonts are a few percent wider than the one it was laid out on the row
		//-- simply overflows and pushes the table off the side of the window. That is not
		//-- hypothetical: at the default font this row wanted 886 pixels of the 888 it had, and it
		//-- was CI's fonts rather than anything about the code that first went over the edge.
		//-- min:preferred: lets the two widest items give way first while everything stays put on
		//-- a display that has the room.
		//
		//-- The width ranges are not on their own enough, and what was actually holding the row
		//-- wide is worth writing down: the second row's three buttons are a split group in
		//-- column 2, so that column was as wide as they are - 333 pixels where the combo above it
		//-- asked for 110 - and no width range on the combo could reach it. "span" puts the group
		//-- across the rest of the row instead, where it is measured against the whole width, and
		//-- the buttons come out in the same places. That is the difference between a minimum of
		//-- 783 and one of 568.
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]16[][]8[]", "[]4[]"));
		bar.add(new JLabel("Microcode:"));
		m_source.setSelectedItem(m_browser.getSource());
		m_source.setToolTipText("Which processor's microcode to show."
			+ " The two PDP-11/05 entries are the two M7261 board revisions: read the part numbers"
			+ " off the two control store PROMs to tell which board is in the machine.");
		m_source.addActionListener(e -> {
			if(!m_updating)
				chooseSource((MicrocodeSource) m_source.getSelectedItem());
		});
		bar.add(m_source, "w 110:pref:");

		bar.add(new JLabel("Search by:"));
		m_searchBy.addActionListener(e -> {
			m_browser.setSearchBy(searchBy());
			refillSearch();
		});
		bar.add(m_searchBy, "w 80:pref:");

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
		bar.add(m_search, "w 130:180:, wrap");

		m_back.setToolTipText("Back to the microword you came from");
		m_back.addActionListener(e -> back());
		bar.add(m_back, "skip 1, split 3, span");
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
		if(m_browser.getMicrocode() != null)
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
		m_updating = true;
		try {
			m_source.setSelectedItem(source);
			m_searchBy.setModel(new DefaultComboBoxModel<>(SearchBy.availableFor(source)));
		} finally {
			m_updating = false;
		}
		m_browser.setSearchBy(searchBy());
		m_context.getSettings().setMicrocodeSelection(source.getLabel());
		m_context.saveSettings();
		m_titleListener.accept("Microcode - " + source.getLabel());

		String remembered = m_context.getSettings().getMicrocodeListing(source.getLabel());
		if(remembered != null && Files.isReadable(Path.of(remembered)) && open(source, List.of(Path.of(remembered))))
			return;
		//-- Either nothing was remembered, or the file has been moved or damaged since it was
		//-- chosen. Fall back to the packaged document rather than opening an empty window.
		m_browser.choose(source);
		refillSearch();
		refresh();
	}

	public void detach() {
	}

	// -------------------------------------------------------------------------------------
	// Loading
	// -------------------------------------------------------------------------------------

	private void chooseListing() {
		MicrocodeSource source = m_browser.getSource();
		JFileChooser chooser = new JFileChooser();
		//-- The multi-selection is for a listing split into one file per page, and a chooser
		//-- that quietly accepts several files without saying why is a feature nobody finds
		//-- (FABLE-ISSUES #63). The title is where a file chooser can say it.
		chooser.setDialogTitle(source.getOpenPrompt());
		String remembered = m_context.getSettings().getMicrocodeListing(source.getLabel());
		if(remembered != null)
			chooser.setSelectedFile(new java.io.File(remembered));
		//-- One file, not a wildcard over its neighbours: the Pascal strips the digits off the
		//-- name it was given and loads whatever matches ({@code FormMicroCodeU.pas:126-136}),
		//-- which quietly picks up files nobody chose.
		chooser.setMultiSelectionEnabled(source.isSplitAcrossFiles());
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
		return open(m_browser.getSource(), files);
	}

	private boolean open(MicrocodeSource source, List<Path> files) {
		try {
			m_browser.open(source, files);
		} catch(IOException | RuntimeException x) {
			m_context.reportFailure("Cannot read the microcode for " + source.getLabel(), x);
			return false;
		}
		m_context.getSettings().setMicrocodeListing(source.getLabel(), files.get(0).toAbsolutePath().toString());
		m_context.saveSettings();
		refillSearch();
		refresh();
		return true;
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
			for(String s : m_browser.searchIndex())
				model.addElement(s);
			m_search.setModel(model);
			if(m_browser.getCurrent() != null)
				m_search.getEditor().setItem(m_browser.searchTextOf(m_browser.getCurrent()));
		} finally {
			m_updating = false;
		}
	}

	private SearchBy searchBy() {
		SearchBy by = (SearchBy) m_searchBy.getSelectedItem();
		return by == null ? SearchBy.ADDRESS : by;
	}

	/** Which microcode is on screen. */
	public MicrocodeSource getSource() {
		return m_browser.getSource();
	}

	/** How the window is to be titled, which is where the chosen revision is visible. */
	public void setTitleListener(Consumer<String> listener) {
		m_titleListener = listener;
		listener.accept("Microcode - " + m_browser.getSource().getLabel());
	}

	/** Whatever was typed or picked, in whichever of the three ways it is being read. */
	public void searchFor(String text) {
		showOutcome(m_browser.searchFor(text));
	}

	/** Follow the fall-through, which is what the microword's next-address field says. */
	public void next() {
		showOutcome(m_browser.next());
	}

	/** A row was double-clicked: follow it if it is one that says where to go next. */
	void rowDoubleClicked(int row) {
		if(row >= 0 && row < m_model.getRowCount() && m_model.getRow(row).next())
			next();
	}

	/** Back the way we came. */
	public void back() {
		m_browser.back();
		refresh();
	}

	/** Show where a move went, or - leaving what is on screen alone - why it could not go. */
	private void showOutcome(String whyNot) {
		if(whyNot == null) {
			refresh();
			return;
		}
		m_status.setText(whyNot);
		m_status.setForeground(UiColors.ERROR_TEXT);
	}

	/** Show the browser's current microword. */
	private void refresh() {
		MicroInstruction mi = m_browser.getCurrent();
		m_model.setRows(m_browser.rows());
		m_updating = true;
		try {
			if(mi != null)
				m_search.getEditor().setItem(m_browser.searchTextOf(mi));
		} finally {
			m_updating = false;
		}
		updateButtons();
		showStatus();
	}

	private void updateButtons() {
		boolean loaded = m_browser.isLoaded();
		m_searchBy.setEnabled(loaded);
		m_search.setEnabled(loaded);
		m_next.setEnabled(m_browser.canGoNext());
		m_back.setEnabled(m_browser.canGoBack());
	}

	/** What is on screen, where it came from, and whether the listing hangs together. */
	private void showStatus() {
		Microcode code = m_browser.getMicrocode();
		m_status.setText(m_browser.statusText());
		if(code != null)
			m_status.setToolTipText(code.isOk() ? null : firstProblems(code));
		m_status.setForeground(m_browser.isStatusTroubled() ? UiColors.ERROR_TEXT : UiColors.SECONDARY_TEXT);
	}

	private static String firstProblems(Microcode code) {
		StringBuilder sb = new StringBuilder("<html>");
		List<Microcode.Problem> problems = code.getProblems();
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
			MicrowordRow r = m_model.getRow(row);
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
				MicrocodeSource other = m_browser.getSource().getOther();
				String tip = r.differs() && other != null
					? "This field is different in " + other.getLabel()
					: r.next() ? "Double-click to go to the next microword"
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

	/**
	 * The two rows of controls above the table.
	 *
	 * <p>Here so a test can ask what width they insist on. What they insist on is the whole
	 * question: it is a font-dependent number, and the machine the layout was written on is not
	 * the machine it has to fit on.</p>
	 */
	public JComponent getControls() {
		return m_controls;
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
		return m_browser.getCurrent();
	}

	public Microcode getMicrocode() {
		return m_browser.getMicrocode();
	}

	public String getStatusText() {
		return m_status.getText();
	}
}
