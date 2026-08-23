package to.etc.pdp11.ui.macro11;

import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.macro11.Macro11;
import to.etc.pdp11.core.macro11.Macro11Listing;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.mem.MemoryCellGroupTable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Write a MACRO-11 program, assemble it, and load it into the machine.
 *
 * <p><b>One window with three tabs, where the Pascal has three windows.</b>
 * {@code FormMacro11Source} holds the editor, {@code FormMacro11Listing} the assembler's output
 * and {@code FormMacro11Code} the words that came out of it - and all three are always about the
 * same program, so all three are always opened, moved and closed together. PLAN.md §3 says to
 * merge them, and merging is what lets the error marker in the listing and the error marker in
 * the source be one piece of state rather than two windows telling each other.</p>
 *
 * <h2>What the tabs are for</h2>
 *
 * <ul>
 *   <li><b>Source</b> - the editor, with the assembler's error line marked in it.</li>
 *   <li><b>Listing</b> - what MACRO-11 printed, with the same error marked, and the line the
 *       PC is on marked while the machine is stopped inside this program.</li>
 *   <li><b>Code</b> - the assembled words as an editable memory grid, which is what is
 *       actually deposited.</li>
 * </ul>
 *
 * <h2>Two behaviours of the original that are gone on purpose</h2>
 *
 * <ul>
 *   <li><b>The editor is not emptied when the window is hidden.</b> The Pascal clears it in
 *       {@code OnBeforeHide} ({@code FormMacro11SourceU.pas:187-192}) and re-reads the file on
 *       every reopen - a workaround for the MDI {@code FormStyle} flip destroying the native
 *       handle, which does not exist here. Unsaved edits therefore survive closing the
 *       window.</li>
 *   <li><b>An assembler error does not raise a modal dialog</b> ({@code :391}). It marks the
 *       line, colours the status bar and stays on the tab you can fix it in; a syntax error is
 *       an ordinary event in writing a program, and a dialog to dismiss before reaching the
 *       editor is one keystroke of penance per typo.</li>
 * </ul>
 */
public final class AssemblerPanel extends JPanel {
	private static final int TAB_SOURCE = 0;

	private static final int TAB_LISTING = 1;

	private static final int TAB_CODE = 2;

	private final AppContext m_context;

	private final AssemblerModel m_model;

	private final JTabbedPane m_tabs = new JTabbedPane();

	//-- Source tab
	private final RSyntaxTextArea m_source = new Macro11TextArea();

	private final JButton m_new = new JButton("New");

	private final JButton m_open = new JButton("Open ...");

	private final JButton m_save = new JButton("Save");

	private final JButton m_saveAs = new JButton("Save as ...");

	private final JButton m_compile = new JButton("Compile");

	private final JLabel m_sourceStatus = new JLabel();

	//-- Listing tab
	private final RSyntaxTextArea m_listing = new Macro11TextArea();

	private final JButton m_loadListing = new JButton("Open listing ...");

	private final JButton m_showCode = new JButton("Show code");

	private final JLabel m_listingStatus = new JLabel();

	//-- Code tab
	private final MemoryCellGroupTable m_grid;

	private final JTextField m_startAddr = new JTextField(9);

	private final JButton m_depositAll = new JButton("Load into machine");

	private final JButton m_depositChanged = new JButton("Deposit changed");

	private final JButton m_examine = new JButton("Verify");

	private final JLabel m_codeStatus = new JLabel();

	/** Set while the model is writing into the editor, so the echo is not fed back to it. */
	private boolean m_updatingEditor;

	/**
	 * The lines each editor currently has a marker on.
	 *
	 * <p>Kept because RSyntaxTextArea will not say - {@code LineHighlightManager} is
	 * package-private and hands out opaque tags - and because "which line is marked" is exactly
	 * what a test of a marker has to ask.</p>
	 */
	private final List<Integer> m_sourceMarks = new ArrayList<>();

	private final List<Integer> m_listingMarks = new ArrayList<>();

	public AssemblerPanel(AppContext context) {
		super(new MigLayout("fill, insets 0", "[grow]", "[grow]"));
		m_context = context;
		m_model = context.getAssembler();
		m_grid = new MemoryCellGroupTable(context);

		Macro11TokenMaker.register();
		m_tabs.addTab("Source", buildSourceTab());
		m_tabs.addTab("Listing", buildListingTab());
		m_tabs.addTab("Code", buildCodeTab());
		add(m_tabs, "grow");

		m_grid.connectTo(m_model.getGroup());
		m_grid.setOnUpdate(this::showCode);
		showModel();
	}

	// -------------------------------------------------------------------------------------
	// The tabs
	// -------------------------------------------------------------------------------------

	private JComponent buildSourceTab() {
		JPanel p = new JPanel(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][][][]16[]", "[]"));
		bar.add(m_new);
		bar.add(m_open);
		bar.add(m_save);
		bar.add(m_saveAs);
		bar.add(m_compile);
		p.add(bar, "growx, wrap");

		m_source.setSyntaxEditingStyle(Macro11TokenMaker.SYNTAX_STYLE);
		//-- Eight, which is what MACRO-11 listings are laid out on and what the Pascal detabs to.
		m_source.setTabSize(8);
		m_source.setTabsEmulated(false);
		m_source.setCodeFoldingEnabled(false);
		m_source.setHighlightCurrentLine(false);
		applyDarkTheme(m_source);
		m_source.setFont(monospaced(m_source.getFont()));
		m_source.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				editorChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				editorChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				editorChanged();
			}
		});
		RTextScrollPane scroll = new RTextScrollPane(m_source);
		scroll.setLineNumbersEnabled(true);
		p.add(scroll, "grow, wrap");

		m_sourceStatus.setForeground(UiColors.SECONDARY_TEXT);
		p.add(m_sourceStatus, "growx");

		m_new.addActionListener(e -> m_model.newSource());
		m_open.addActionListener(e -> openSource());
		m_save.addActionListener(e -> saveSource(false));
		m_saveAs.addActionListener(e -> saveSource(true));
		m_compile.addActionListener(e -> compile());
		m_compile.setToolTipText("Save the source and run MACRO-11 over it");
		return p;
	}

	private JComponent buildListingTab() {
		JPanel p = new JPanel(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]", "[]"));
		bar.add(m_loadListing);
		bar.add(m_showCode);
		p.add(bar, "growx, wrap");

		//-- No syntax style: a listing is columns of octal, and colouring it as source would
		//-- highlight the addresses as though they were code.
		m_listing.setEditable(false);
		m_listing.setCodeFoldingEnabled(false);
		m_listing.setHighlightCurrentLine(false);
		applyDarkTheme(m_listing);
		m_listing.setFont(monospaced(m_listing.getFont()));
		RTextScrollPane scroll = new RTextScrollPane(m_listing);
		//-- The listing prints its own source line numbers, so a second set beside them is noise.
		scroll.setLineNumbersEnabled(false);
		p.add(scroll, "grow, wrap");

		m_listingStatus.setForeground(UiColors.SECONDARY_TEXT);
		p.add(m_listingStatus, "growx");

		m_loadListing.addActionListener(e -> openListing());
		m_loadListing.setToolTipText("Read a .lst somebody else produced. The source is not needed,"
			+ " and neither is MACRO-11.");
		m_showCode.addActionListener(e -> m_tabs.setSelectedIndex(TAB_CODE));
		return p;
	}

	private JComponent buildCodeTab() {
		JPanel p = new JPanel(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]16[][][]", "[]"));
		bar.add(new JLabel("Start address:"));
		m_startAddr.setEditable(false);
		m_startAddr.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_startAddr.getFont().getSize()));
		m_startAddr.setForeground(UiColors.SECONDARY_TEXT);
		bar.add(m_startAddr);
		bar.add(m_depositAll);
		bar.add(m_depositChanged);
		bar.add(m_examine);
		p.add(bar, "growx, wrap");
		p.add(m_grid, "grow, wrap");

		m_codeStatus.setForeground(UiColors.SECONDARY_TEXT);
		p.add(m_codeStatus, "growx");

		m_depositAll.addActionListener(e -> m_model.deposit(owner(), this::showCode));
		m_depositAll.setToolTipText("Write every word of the program into the machine");
		m_depositChanged.addActionListener(e -> m_grid.depositAll(true, owner()));
		m_examine.addActionListener(e -> verifyCode());
		m_examine.setToolTipText("Read the same addresses back off the machine and compare");
		return p;
	}

	// -------------------------------------------------------------------------------------
	// Doing things
	// -------------------------------------------------------------------------------------

	private void editorChanged() {
		if(m_updatingEditor)
			return;
		m_model.setSourceText(m_source.getText());
		updateButtons();
		showSourceCaption();
	}

	private void openSource() {
		Path file = choose("Open a MACRO-11 source", "MACRO-11 source", "mac", false);
		if(file == null)
			return;
		try {
			m_model.loadSource(file);
		} catch(IOException x) {
			m_context.reportFailure("Could not read " + file, x);
		}
	}

	/** Save, asking for a name when there is none or when Save as was pressed. */
	private void saveSource(boolean askForName) {
		Path file = m_model.getSourceFile();
		if(askForName || file == null) {
			file = choose("Save the MACRO-11 source", "MACRO-11 source", "mac", true);
			if(file == null)
				return;
		}
		try {
			m_model.saveSource(file);
		} catch(IOException x) {
			m_context.reportFailure("Could not write " + file, x);
		}
	}

	private void openListing() {
		Path file = choose("Open a MACRO-11 listing", "MACRO-11 listing", "lst", false);
		if(file == null)
			return;
		try {
			m_model.loadListing(file);
			m_tabs.setSelectedIndex(TAB_LISTING);
		} catch(IOException x) {
			m_context.reportFailure("Could not read " + file, x);
		}
	}

	/**
	 * Assemble, then go where the answer is.
	 *
	 * <p>On success that is the listing; on an error it is the source, with the offending line
	 * marked - which is where the next thing to do is.</p>
	 */
	private void compile() {
		m_compile.setEnabled(false);
		m_sourceStatus.setText("Assembling ...");
		m_sourceStatus.setForeground(UiColors.SECONDARY_TEXT);
		m_model.assemble(outcome -> {
			updateButtons();
			m_tabs.setSelectedIndex(outcome.ok() ? TAB_LISTING : TAB_SOURCE);
		});
	}

	private Path choose(String title, String what, String extension, boolean forSaving) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileFilter(new FileNameExtensionFilter(what + " (*." + extension + ")", extension));
		Path current = m_model.getSourceFile();
		if(current != null)
			chooser.setCurrentDirectory(current.toAbsolutePath().getParent().toFile());
		int answer = forSaving ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
		if(answer != JFileChooser.APPROVE_OPTION)
			return null;
		Path file = chooser.getSelectedFile().toPath();
		//-- A name typed without an extension gets the obvious one, which is what every editor
		//-- does and what stops a source file called "test" that macro11 will not open.
		if(forSaving && file.getFileName().toString().indexOf('.') < 0)
			file = file.resolveSibling(file.getFileName() + "." + extension);
		return file;
	}

	// -------------------------------------------------------------------------------------
	// Showing what the model holds
	// -------------------------------------------------------------------------------------

	/** Everything on this panel that depends on the model. On the EDT. */
	private void showModel() {
		showSource();
		showListing();
		//-- The group's own listener only carries value changes; a listing that was just parsed
		//-- replaced every cell in it, so the grid has to be laid out again. rebuild() calls
		//-- showCode through the update hook, which is why showCode does not rebuild.
		m_grid.rebuild();
		updateButtons();
	}

	private void showSource() {
		if(!m_source.getText().equals(m_model.getSourceText())) {
			m_updatingEditor = true;
			try {
				m_source.setText(m_model.getSourceText());
				m_source.setCaretPosition(0);
			} finally {
				m_updatingEditor = false;
			}
		}
		showSourceCaption();
		markSource();
	}

	private void showSourceCaption() {
		Path file = m_model.getSourceFile();
		String name = file == null ? "(unsaved)" : file.toString();
		Macro11Listing listing = m_model.getListing();
		if(listing != null && !listing.isOk()) {
			m_sourceStatus.setText(listing.getFirstProblem().describe());
			m_sourceStatus.setForeground(UiColors.ERROR_TEXT);
		} else if(m_model.isTranslated()) {
			m_sourceStatus.setText(name + (m_model.isChanged() ? " *  -  changed since it was assembled" : "")
				+ "  -  " + m_model.getGroup().size() + " words assembled");
			m_sourceStatus.setForeground(m_model.isChanged() ? UiColors.SECONDARY_TEXT : UiColors.OK_TEXT);
		} else {
			m_sourceStatus.setText(name + (m_model.isChanged() ? " *" : ""));
			m_sourceStatus.setForeground(UiColors.SECONDARY_TEXT);
		}
	}

	private void showListing() {
		Macro11Listing listing = m_model.getListing();
		String text = listing == null ? "" : String.join("\n", listing.getLines());
		if(!m_listing.getText().equals(text)) {
			m_listing.setText(text);
			m_listing.setCaretPosition(0);
		}
		if(listing == null) {
			m_listingStatus.setText("Nothing assembled yet");
			m_listingStatus.setForeground(UiColors.SECONDARY_TEXT);
		} else if(listing.isOk()) {
			m_listingStatus.setText(m_model.getListingFile() + "  -  " + listing.getWordCount() + " words");
			m_listingStatus.setForeground(UiColors.OK_TEXT);
		} else {
			m_listingStatus.setText(listing.getProblems().size() + " problem"
				+ (listing.getProblems().size() == 1 ? "" : "s") + ": "
				+ listing.getFirstProblem().describe());
			m_listingStatus.setForeground(UiColors.ERROR_TEXT);
		}
		markListing();
	}

	/**
	 * Read the program back off the machine and say where it disagrees.
	 *
	 * <p>What the button has always claimed to do. It used to call {@code examineAll}, which
	 * copies what the machine said over the edit value of every cell - so the assembled program
	 * was replaced by the machine's contents, nothing could ever show as differing, and a
	 * corrupt load reported success. The code group's {@code pdpOverwritesEdit} is off for
	 * exactly this, the same as the Memory Loader's.</p>
	 */
	private void verifyCode() {
		m_grid.verifyAll(owner(), wrong -> {
			int words = m_model.getGroup().size();
			m_codeStatus.setText(wrong == 0
				? "The machine holds exactly the " + words + " assembled word"
					+ (words == 1 ? "" : "s")
				: wrong + " word" + (wrong == 1 ? "" : "s") + " of " + words
					+ " differ from the assembled program");
			m_codeStatus.setForeground(wrong == 0 ? UiColors.OK_TEXT : UiColors.ERROR_TEXT);
		});
	}

	private void showCode() {
		Macro11Listing listing = m_model.getListing();
		Address start = listing == null ? null : listing.getStartAddress();
		m_startAddr.setText(start == null ? "" : start.toOctal());
		int words = m_model.getGroup().size();
		m_codeStatus.setText(words == 0
			? "Nothing assembled yet"
			: words + " words at " + (start == null ? "?" : start.toOctal())
				+ ".  Nothing has been written to the machine yet unless you have pressed a button below.");
		m_codeStatus.setForeground(UiColors.SECONDARY_TEXT);
		updateButtons();
	}

	private void updateButtons() {
		boolean connected = m_context.getConnectionManager().isConnected();
		boolean haveCode = m_model.hasCode();
		boolean haveAssembler = Macro11.isAvailable();

		m_save.setEnabled(m_model.isChanged() || m_model.getSourceFile() == null);
		m_compile.setEnabled(m_model.canAssemble() && haveAssembler && !m_model.isAssembling());
		if(!haveAssembler)
			m_compile.setToolTipText(Macro11.notInstalledMessage());

		m_depositAll.setEnabled(connected && haveCode);
		m_depositChanged.setEnabled(connected && haveCode);
		m_examine.setEnabled(connected && haveCode);
	}

	// -------------------------------------------------------------------------------------
	// The two markers
	// -------------------------------------------------------------------------------------

	/**
	 * Mark the source line MACRO-11 complained about, and the line the PC is on.
	 *
	 * <p>Replaces {@code setErrorLine}/{@code setExecutionLine} and the hand-rolled
	 * {@code HighlightLine}/{@code OnSpecialLineColors} pair the Lazarus port needed
	 * ({@code FormMacro11SourceU.pas:56-63, 168-178}) - which can only mark <b>one</b> line,
	 * because that is all a single field holds, and so cannot show an error and a PC at once.</p>
	 */
	private void markSource() {
		m_source.removeAllLineHighlights();
		m_sourceMarks.clear();
		Macro11Listing listing = m_model.getListing();
		if(listing == null)
			return;
		if(!listing.isOk()) {
			int line = listing.getFirstProblem().sourceLine();
			if(highlight(m_source, m_sourceMarks, line - 1, UiColors.ERROR_BACKGROUND))
				scrollTo(m_source, line - 1);
			return;
		}
		int pcLine = pcListingLine();
		if(pcLine >= 0) {
			int source = listing.sourceLineOfListingLine(pcLine);
			if(highlight(m_source, m_sourceMarks, source - 1, UiColors.PC_BACKGROUND))
				scrollTo(m_source, source - 1);
		}
	}

	/**
	 * Mark every listing line the error came from, or the one the PC is on.
	 *
	 * <p>One source line can produce several listing lines, so an error marks all of them -
	 * which is what {@code setErrorMark} ({@code FormMacro11ListingU.pas:559-577}) does too.</p>
	 */
	private void markListing() {
		m_listing.removeAllLineHighlights();
		m_listingMarks.clear();
		Macro11Listing listing = m_model.getListing();
		if(listing == null)
			return;
		if(!listing.isOk()) {
			List<Integer> lines = listing.listingLinesForSourceLine(listing.getFirstProblem().sourceLine());
			boolean scrolled = false;
			for(int line : lines) {
				if(highlight(m_listing, m_listingMarks, line, UiColors.ERROR_BACKGROUND) && !scrolled) {
					scrollTo(m_listing, line);
					scrolled = true;
				}
			}
			//-- The diagnostic itself is a line of the listing and is worth marking even when it
			//-- claims a source line that produced nothing.
			if(!scrolled && highlight(m_listing, m_listingMarks, listing.getFirstProblem().listingLine(),
				UiColors.ERROR_BACKGROUND))
				scrollTo(m_listing, listing.getFirstProblem().listingLine());
			return;
		}
		int pcLine = pcListingLine();
		if(pcLine >= 0 && highlight(m_listing, m_listingMarks, pcLine, UiColors.PC_BACKGROUND))
			scrollTo(m_listing, pcLine);
	}

	/**
	 * Which listing line the machine is stopped on, or -1.
	 *
	 * <p>Only while the last assembly succeeded: marking a PC inside a listing that does not
	 * describe what is in the machine points at the wrong line with complete confidence. The
	 * Pascal guards the same way, at the call site ({@code FormExecuteU.pas:225}).</p>
	 */
	private int pcListingLine() {
		if(!m_model.isTranslated())
			return -1;
		Macro11Listing listing = m_model.getListing();
		Address pc = m_context.getMachineState().getPc();
		if(listing == null || pc == null)
			return -1;
		return listing.listingLineOfAddress(pc);
	}

	private static boolean highlight(RSyntaxTextArea area, List<Integer> marks, int line, Color color) {
		if(line < 0 || line >= area.getLineCount())
			return false;
		try {
			area.addLineHighlight(line, color);
			marks.add(line);
			return true;
		} catch(BadLocationException x) {
			return false;
		}
	}

	private static void scrollTo(RSyntaxTextArea area, int line) {
		try {
			area.setCaretPosition(area.getLineStartOffset(line));
		} catch(BadLocationException x) {
			//-- The line went away between the highlight and this. Nothing to scroll to.
		}
	}

	// -------------------------------------------------------------------------------------
	// Following the model and the machine
	// -------------------------------------------------------------------------------------

	private final AssemblerModel.Listener m_modelListener = model -> showModel();

	private final MachineState.Listener m_machineListener = state -> {
		markSource();
		markListing();
		updateButtons();
	};

	private final ConnectionManager.Listener m_connectionListener =
		(manager, state) -> AppContext.onUi(this::updateButtons);

	/** Start following. Called every time the window is shown. */
	public void attach() {
		//-- Remove first: showing a window that is already visible runs this again, and a
		//-- listener list that grows one entry per raise is a leak that presents as a slow window.
		detach();
		m_model.addListener(m_modelListener);
		m_context.getMachineState().addListener(m_machineListener);
		m_context.getConnectionManager().addListener(m_connectionListener);
		showModel();
	}

	public void detach() {
		m_model.removeListener(m_modelListener);
		m_context.getMachineState().removeListener(m_machineListener);
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	/**
	 * RSyntaxTextArea's own dark theme, so the editor is not a white rectangle in a dark app.
	 *
	 * <p>It carries the syntax colours as well, which is why it is used rather than setting a
	 * background: FlatLaf knows nothing about this component's token styles.</p>
	 */
	private void applyDarkTheme(RSyntaxTextArea area) {
		try(InputStream in = RSyntaxTextArea.class
			.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
			if(in == null)
				return;
			Theme.load(in).apply(area);
		} catch(IOException | RuntimeException x) {
			//-- A missing theme is a cosmetic problem, not a reason to refuse to open the window.
			m_context.getLogger().log(LogChannel.OTHER, "Could not load the editor theme: %s", x);
		}
	}

	private static Font monospaced(Font current) {
		return new Font(Font.MONOSPACED, Font.PLAIN, current == null ? 12 : current.getSize());
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	/** Read the source file the last session had open. Called once, when the window first opens. */
	public void loadLastSource() {
		if(m_model.getSourceFile() == null && m_model.getSourceText().isEmpty())
			m_model.loadLastSource();
	}

	// -------------------------------------------------------------------------------------
	// For the tests
	// -------------------------------------------------------------------------------------

	public JTabbedPane getTabs() {
		return m_tabs;
	}

	public RSyntaxTextArea getSourceArea() {
		return m_source;
	}

	public RSyntaxTextArea getListingArea() {
		return m_listing;
	}

	public MemoryCellGroupTable getGrid() {
		return m_grid;
	}

	public JButton getCompileButton() {
		return m_compile;
	}

	public JButton getNewButton() {
		return m_new;
	}

	public JButton getSaveButton() {
		return m_save;
	}

	public JButton getDepositAllButton() {
		return m_depositAll;
	}

	public JButton getVerifyButton() {
		return m_examine;
	}

	public JTextField getStartAddressField() {
		return m_startAddr;
	}

	public String getSourceStatusText() {
		return m_sourceStatus.getText();
	}

	public String getListingStatusText() {
		return m_listingStatus.getText();
	}

	public String getCodeStatusText() {
		return m_codeStatus.getText();
	}

	/** The 0-based source lines currently marked, error or PC. */
	public List<Integer> getSourceMarkedLines() {
		return List.copyOf(m_sourceMarks);
	}

	/** The 0-based listing lines currently marked, error or PC. */
	public List<Integer> getListingMarkedLines() {
		return List.copyOf(m_listingMarks);
	}
}
