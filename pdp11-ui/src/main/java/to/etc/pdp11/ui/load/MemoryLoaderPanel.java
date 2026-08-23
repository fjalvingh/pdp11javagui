package to.etc.pdp11.ui.load;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.memfile.MemoryFileFormat;
import to.etc.pdp11.core.memfile.MemoryFileLoader;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.FieldStatus;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.mem.MemoryCellGroupTable;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Font;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read a program out of a file and put it into the machine.
 *
 * <p>Ported from {@code TFormMemoryLoader} ({@code FormMemoryLoaderU.pas}). The formats are
 * {@link MemoryFileLoader} in the core; this is a format, its file names, and the three things
 * you then do with what was read - deposit it, verify it, or look at it.</p>
 *
 * <p><b>Loading fills the grid; it does not touch the machine.</b> That is the Pascal's split
 * too, and it is worth keeping: what came out of the file is visible, and every word shows as
 * changed, before anything is written to a PDP-11. Deposit is a separate button and a separate
 * decision.</p>
 */
public final class MemoryLoaderPanel extends JPanel {
	private static final int MAX_ROWS = 2;

	private final AppContext m_context;

	private final MemoryCellGroup m_group;

	/** A file is being read right now, so nothing may start a second one. */
	private boolean m_loading;

	private final MemoryCellGroupTable m_grid;

	private final JComboBox<MemoryFileFormat> m_format = new JComboBox<>(MemoryFileFormat.values());

	private final JLabel m_startLabel = new JLabel("Load at:");

	private final JTextField m_startAddr = new JTextField(9);

	private final JLabel m_entryLabel = new JLabel("Entry address:");

	private final JTextField m_entryAddr = new JTextField(9);

	private final List<JLabel> m_fileLabels = new ArrayList<>();

	private final List<JTextField> m_fileFields = new ArrayList<>();

	private final List<JButton> m_fileBrowse = new ArrayList<>();

	private final JButton m_load = new JButton("Load file");

	private final JButton m_depositChanged = new JButton("Deposit changed");

	private final JButton m_depositAll = new JButton("Deposit all");

	private final JButton m_verify = new JButton("Verify");

	private final JLabel m_statusLabel = new JLabel();

	/** The status line, and where a value that cannot be used is reported. See {@link FieldStatus}. */
	private final FieldStatus m_status = new FieldStatus(m_statusLabel, UiColors.SECONDARY_TEXT);

	public MemoryLoaderPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][][grow][]"));
		m_context = context;

		m_group = context.getMemoryCellGroups().addGroup(addressType(context), "Memory load");
		m_group.setUsageTag("memoryload");
		//-- What is in this grid came out of a file and is waiting to be deposited. Another
		//-- window examining the same addresses must not quietly replace it with what the
		//-- machine currently holds - which is the whole point of the flag, and the Pascal sets
		//-- it here permanently ({@code FormMemoryLoaderU.pas:182}).
		m_group.setPdpOverwritesEdit(false);
		m_grid = new MemoryCellGroupTable(context);
		m_grid.connectTo(m_group);
		m_grid.setOnUpdate(this::updateStatus);

		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, m_startAddr.getFont().getSize());
		m_startAddr.setFont(mono);
		m_entryAddr.setFont(mono);
		//-- Filled in from the file, not typed: the format says where the program starts.
		m_entryAddr.setEditable(false);
		m_entryAddr.setForeground(UiColors.SECONDARY_TEXT);

		add(buildTopBar(), "growx, wrap");
		add(buildFileBar(), "growx, wrap");
		add(m_grid, "grow, wrap");
		add(m_statusLabel, "growx");

		m_startAddr.setText(Address.of(m_group.getType(), 01000).toOctal());
		showFormat();
		updateButtons();
		updateStatus();
	}

	private static MemoryAddressType addressType(AppContext context) {
		Console console = context.getConnectionManager().getConsole();
		return console == null ? MemoryAddressType.PHYSICAL22 : console.physicalAddressType();
	}

	private JPanel buildTopBar() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]16[][]16[]16[][][]", "[]"));
		bar.add(m_startLabel);
		bar.add(m_startAddr);
		bar.add(m_entryLabel);
		bar.add(m_entryAddr);
		bar.add(m_load);
		bar.add(m_depositChanged);
		bar.add(m_depositAll);
		bar.add(m_verify);

		m_startAddr.setToolTipText("Where to put the file's contents, for a format that does not say."
			+ " Enter reads the file, same as Load file.");
		//-- Enter acts, as it does in the Memory, Disassembler, Dumper, Memory Test and Bitfields
		//-- windows. Same widget, same shape; this one used to swallow the key.
		m_startAddr.addActionListener(e -> loadFile());
		m_load.setToolTipText("Read the file into the grid below. Nothing is written to the machine yet.");
		m_verify.setToolTipText("Read the same addresses back off the machine; anything that disagrees shows as changed");
		m_load.addActionListener(e -> loadFile());
		m_depositChanged.addActionListener(e -> m_grid.depositAll(true, owner()));
		m_depositAll.addActionListener(e -> m_grid.depositAll(false, owner()));
		m_verify.addActionListener(e -> verify());
		return bar;
	}

	private JPanel buildFileBar() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][grow][]", "[][][]"));
		bar.add(new JLabel("Format:"));
		bar.add(m_format, "growx, wrap");
		m_format.addActionListener(e -> showFormat());

		for(int i = 0; i < MAX_ROWS; i++) {
			JLabel label = new JLabel();
			JTextField field = new JTextField(28);
			JButton browse = new JButton("Browse ...");
			int index = i;
			browse.addActionListener(e -> browse(index));
			m_fileLabels.add(label);
			m_fileFields.add(field);
			m_fileBrowse.add(browse);
			bar.add(label);
			bar.add(field, "growx");
			bar.add(browse, "wrap");
		}
		return bar;
	}

	// -------------------------------------------------------------------------------------
	// The format
	// -------------------------------------------------------------------------------------

	private MemoryFileFormat format() {
		MemoryFileFormat f = (MemoryFileFormat) m_format.getSelectedItem();
		return f == null ? MemoryFileFormat.BYTE_STREAM : f;
	}

	private void showFormat() {
		MemoryFileFormat f = format();
		for(int i = 0; i < MAX_ROWS; i++) {
			boolean used = i < f.getFileCount();
			m_fileLabels.get(i).setVisible(used);
			m_fileFields.get(i).setVisible(used);
			m_fileBrowse.get(i).setVisible(used);
			if(used)
				m_fileLabels.get(i).setText(f.getFilePrompts().get(i) + ":");
		}
		//-- A format that carries its own addresses would ignore anything typed here, and a field
		//-- that is ignored should not be offered.
		boolean needsStart = !f.definesOwnAddresses();
		m_startLabel.setVisible(needsStart);
		m_startAddr.setVisible(needsStart);
		m_entryLabel.setVisible(f.hasEntryAddress());
		m_entryAddr.setVisible(f.hasEntryAddress());
		revalidate();
		repaint();
	}

	private void browse(int index) {
		MemoryFileFormat f = format();
		if(index >= f.getFileCount())
			return;
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Select a " + f.getFilePrompts().get(index).toLowerCase());
		chooser.setFileFilter(new FileNameExtensionFilter(f.getLabel(), f.getDefaultExtension()));
		String current = m_fileFields.get(index).getText().trim();
		if(!current.isEmpty())
			chooser.setSelectedFile(new java.io.File(current));
		if(chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		m_fileFields.get(index).setText(chooser.getSelectedFile().getAbsolutePath());
	}

	// -------------------------------------------------------------------------------------
	// Loading
	// -------------------------------------------------------------------------------------

	/** Read the file into the grid. Nothing is written to the machine. */
	private void loadFile() {
		MemoryFileFormat f = format();
		List<Path> files = new ArrayList<>();
		for(int i = 0; i < f.getFileCount(); i++) {
			String name = m_fileFields.get(i).getText().trim();
			if(name.isEmpty()) {
				m_context.reportFailure("Choose a " + f.getFilePrompts().get(i).toLowerCase()
					+ " first (use Browse)", null);
				return;
			}
			files.add(Path.of(name));
		}
		Address start = Address.of(m_group.getType(), 0);
		if(!f.definesOwnAddresses()) {
			Address typed = parse(m_startAddr);
			if(typed == null)
				return;
			start = Address.of(m_group.getType(), typed.val() & ~1L);
		}
		//-- Off the event thread: reading a file is the one thing this window does that can
		//-- block for an unbounded time, and on a stale mount it blocks forever
		//-- (FABLE-ISSUES #62). The group it fills in is guarded by its own lock and is already
		//-- written from the command thread; the grid it feeds is not, so that is rebuilt below.
		Address at = start;
		m_loading = true;
		updateButtons();
		m_status.setText("Reading " + files.get(0).getFileName() + " ...", UiColors.SECONDARY_TEXT);
		m_context.onFile("Could not read " + files.get(0),
			() -> MemoryFileLoader.load(f, m_group, files, at),
			r -> {
				m_loading = false;
				m_grid.rebuild();
				for(String w : r.warnings()) {
					m_context.getLogger().log(LogChannel.OTHER, "%s: %s", files.get(0).getFileName(), w);
				}
				//-- A format that says where the program starts tells the execution window, which
				//-- is a different window and does not know this one exists.
				if(r.entryAddress() != null) {
					Address entry = Address.of(MemoryAddressType.VIRTUAL, r.entryAddress().val());
					m_entryAddr.setText(entry.toOctal());
					m_context.getMachineState().setStartPc(entry);
				} else {
					m_entryAddr.setText("");
				}
				m_context.getLogger().log(LogChannel.OTHER, "Loaded %d words from %s",
					r.wordsLoaded(), files.get(0));
				m_status.setText(r.wordsLoaded() + " words read from " + files.get(0).getFileName()
					+ (r.entryAddress() == null ? "" : ", starting at " + m_entryAddr.getText())
					+ (r.warnings().isEmpty() ? "" : "  -  " + r.warnings().get(0))
					+ ".  Nothing has been written to the machine yet.",
					r.warnings().isEmpty() ? UiColors.OK_TEXT : UiColors.ERROR_TEXT);
				updateButtons();
			},
			() -> {
				m_loading = false;
				m_grid.rebuild();                           // a partial load still shows what it got
				m_status.setText("Nothing loaded", UiColors.ERROR_TEXT);
				updateButtons();
			});
	}

	/**
	 * Read the same addresses back off the machine without touching what was loaded.
	 *
	 * <p>Which is what makes this window useful after a deposit: the grid holds the file, the
	 * examine fills in what the machine actually has, and anything that disagrees is coloured.
	 * The group's {@code pdpOverwritesEdit} being false is what stops the read replacing the
	 * file's values instead of being compared with them.</p>
	 */
	private void verify() {
		m_grid.verifyAll(owner(), wrong -> {
			m_status.setText(wrong == 0
				? "The machine holds exactly what was loaded"
				: wrong + " word" + (wrong == 1 ? "" : "s") + " differ from the file",
				wrong == 0 ? UiColors.OK_TEXT : UiColors.ERROR_TEXT);
		});
	}

	private Address parse(JTextField field) {
		try {
			return Address.parseOctal(field.getText().trim(), m_group.getType());
		} catch(RuntimeException x) {
			m_status.error("\"" + field.getText().trim() + "\" is not an octal address");
			return null;
		}
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	private void updateButtons() {
		boolean connected = m_context.getConnectionManager().isConnected();
		boolean loaded = !m_group.isEmpty();
		m_load.setEnabled(!m_loading);
		m_depositChanged.setEnabled(connected && loaded && !m_loading);
		m_depositAll.setEnabled(connected && loaded && !m_loading);
		m_verify.setEnabled(connected && loaded && !m_loading);
	}

	private void updateStatus() {
		if(m_group.isEmpty()) {
			m_status.setText("Nothing loaded yet", UiColors.SECONDARY_TEXT);
		}
		updateButtons();
	}

	private final ConnectionManager.Listener m_connectionListener = (manager, state) -> AppContext.onUi(() -> {
		MemoryAddressType type = addressType(m_context());
		if(type != getGroup().getType() && getGroup().isEmpty()) {
			//-- Only while there is nothing loaded: re-expressing a program somebody just read
			//-- out of a file is not something to do behind their back.
			getGroup().shiftRange(Address.of(type, 01000), 0, false);
			m_startAddr.setText(Address.of(type, 01000).toOctal());
		}
		updateButtons();
	});

	public void attach() {
		detach();
		m_context.getConnectionManager().addListener(m_connectionListener);
		updateButtons();
	}

	public void detach() {
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	public void dispose() {
		detach();
		m_context.getMemoryCellGroups().removeGroup(m_group);
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	private AppContext m_context() {
		return m_context;
	}

	public MemoryCellGroup getGroup() {
		return m_group;
	}

	public MemoryCellGroupTable getGrid() {
		return m_grid;
	}

	public JComboBox<MemoryFileFormat> getFormatCombo() {
		return m_format;
	}

	public JTextField getStartField() {
		return m_startAddr;
	}

	public JTextField getEntryField() {
		return m_entryAddr;
	}

	public JButton getLoadButton() {
		return m_load;
	}

	public JButton getDepositAllButton() {
		return m_depositAll;
	}

	public JButton getVerifyButton() {
		return m_verify;
	}

	public JTextField getFileField(int index) {
		return m_fileFields.get(index);
	}

	public JLabel getFileLabel(int index) {
		return m_fileLabels.get(index);
	}

	public String getStatusText() {
		return m_status.getText();
	}
}
