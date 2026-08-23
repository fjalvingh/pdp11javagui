package to.etc.pdp11.ui.dump;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.memfile.MemoryDumper;
import to.etc.pdp11.core.memfile.MemoryFileFormat;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read a range of memory off the machine and write it to a file.
 *
 * <p>Ported from {@code TFormMemoryDumper} ({@code FormMemoryDumperU.pas}). The file formats are
 * {@link MemoryDumper} in the core; what is here is a range, a format, the file names that format
 * needs, and a grid showing what is about to be written.</p>
 *
 * <h2>The file controls change with the format</h2>
 *
 * <p>Which is the awkward part of the original: it creates one loader object per format, hands
 * each of them <i>the same three widgets</i>, and then shows and hides those widgets according to
 * which loader is selected ({@code :167-233}). Here the format says how many files it needs and
 * what to call them ({@link MemoryFileFormat#getFilePrompts()}), and the rows are built from
 * that - so adding a format is an enum entry rather than another set of widget assignments.</p>
 */
public final class MemoryDumperPanel extends JPanel {
	private static final int MAX_ROWS = 2;

	private final AppContext m_context;

	private final MemoryCellGroup m_group;

	private final MemoryCellGroupTable m_grid;

	private final JTextField m_startAddr = new JTextField(9);

	private final JTextField m_endAddr = new JTextField(9);

	private final JTextField m_entryAddr = new JTextField(9);

	private final JLabel m_entryLabel = new JLabel("Entry address:");

	private final JComboBox<MemoryFileFormat> m_format = new JComboBox<>(MemoryFileFormat.values());

	private final List<JLabel> m_fileLabels = new ArrayList<>();

	private final List<JTextField> m_fileFields = new ArrayList<>();

	private final List<JButton> m_fileBrowse = new ArrayList<>();

	private final JButton m_examine = new JButton("Examine all");

	private final JButton m_dump = new JButton("Write file");

	private final JLabel m_statusLabel = new JLabel();

	/** The status line, and where a value that cannot be used is reported. See {@link FieldStatus}. */
	private final FieldStatus m_status = new FieldStatus(m_statusLabel, UiColors.SECONDARY_TEXT);

	public MemoryDumperPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][][grow][]"));
		m_context = context;

		m_group = context.getMemoryCellGroups().addGroup(addressType(context), "Memory dump");
		m_group.setUsageTag("memorydump");
		//-- What is in this grid is what is about to be written, so a refresh from elsewhere must
		//-- not silently change it. The Pascal sets the same flag, and permanently ({@code :180}).
		m_group.setPdpOverwritesEdit(false);
		m_grid = new MemoryCellGroupTable(context);
		m_grid.connectTo(m_group);
		m_grid.setOnUpdate(this::updateStatus);

		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, m_startAddr.getFont().getSize());
		m_startAddr.setFont(mono);
		m_endAddr.setFont(mono);
		m_entryAddr.setFont(mono);

		add(buildRangeBar(), "growx, wrap");
		add(buildFileBar(), "growx, wrap");
		add(m_grid, "grow, wrap");
		add(m_statusLabel, "growx");

		m_startAddr.setText(Address.of(m_group.getType(), 01000).toOctal());
		m_endAddr.setText(Address.of(m_group.getType(), 01776).toOctal());
		showFormat();
		updateButtons();
		updateStatus();
	}

	private static MemoryAddressType addressType(AppContext context) {
		Console console = context.getConnectionManager().getConsole();
		return console == null ? MemoryAddressType.PHYSICAL22 : console.physicalAddressType();
	}

	private JPanel buildRangeBar() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]8[][]16[]16[][]", "[]"));
		bar.add(new JLabel("From:"));
		bar.add(m_startAddr);
		bar.add(new JLabel("to:"));
		bar.add(m_endAddr);
		bar.add(m_examine);
		bar.add(m_entryLabel);
		bar.add(m_entryAddr);
		m_examine.setToolTipText("Read this range off the machine into the grid below");
		m_examine.addActionListener(e -> readFromMachine());
		m_startAddr.addActionListener(e -> readFromMachine());
		m_endAddr.addActionListener(e -> readFromMachine());
		m_entryAddr.setToolTipText("Where the loaded program starts. Blank means the start of the range.");
		return bar;
	}

	private JPanel buildFileBar() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][grow][]", "[][][]"));
		bar.add(new JLabel("Format:"));
		bar.add(m_format, "growx");
		bar.add(m_dump, "wrap");
		m_format.addActionListener(e -> showFormat());
		m_dump.addActionListener(e -> writeFile());

		//-- Two rows, built once and shown as the format needs them. A format wanting a third
		//-- file would add a row here; the original hands the same three widgets to five objects.
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

	/** Show one file row per file this format needs, and the entry address if it has one. */
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
		m_entryLabel.setVisible(f.hasEntryAddress());
		m_entryAddr.setVisible(f.hasEntryAddress());
		updateButtons();
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
		if(chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		m_fileFields.get(index).setText(chooser.getSelectedFile().getAbsolutePath());
		updateButtons();
	}

	// -------------------------------------------------------------------------------------
	// Reading and writing
	// -------------------------------------------------------------------------------------

	/** Build the cells for the range and read them off the machine. */
	private void readFromMachine() {
		MemoryAddressType type = m_group.getType();
		Address start = parse(m_startAddr, type);
		Address end = parse(m_endAddr, type);
		if(start == null || end == null)
			return;
		long lo = start.val() & ~1L;
		long hi = end.val() & ~1L;
		if(hi < lo)
			hi = lo;                                        // "silent stability", as the Pascal calls it
		int words = (int) ((hi - lo) / 2) + 1;

		m_group.shiftRange(Address.of(type, lo), words, true);
		m_startAddr.setText(Address.of(type, lo).toOctal());
		m_endAddr.setText(Address.of(type, hi).toOctal());
		m_grid.rebuild();
		if(m_context.getConnectionManager().isConnected())
			m_grid.examineAll(false, owner());
		//-- There are cells now, so there is something to write - which is what the Write button
		//-- waits for. Without this it stays dead until a connection event happens to arrive.
		updateButtons();
		updateStatus();
	}

	/** Write what is in the grid, in the selected format. */
	private void writeFile() {
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
		Address entry = null;
		if(f.hasEntryAddress()) {
			//-- Blank means the start of the range, which is what the Pascal falls back to
			//-- ({@code :330-333}).
			String text = m_entryAddr.getText().trim();
			entry = text.isEmpty()
				? Address.of(m_group.getType(), m_group.getRange().empty() ? 0 : m_group.getRange().lo())
				: parse(m_entryAddr, m_group.getType());
			if(entry == null)
				return;
		}
		try {
			MemoryDumper.Result r = MemoryDumper.save(f, m_group, files, entry);
			m_context.getLogger().log(LogChannel.OTHER, "Wrote %d words to %s", r.wordsWritten(), files.get(0));
			m_status.setText(r.wordsWritten() + " words written to " + files.get(0).getFileName()
				+ (f == MemoryFileFormat.ABSOLUTE_PAPERTAPE ? " in " + r.blocks() + " blocks" : "")
				+ (r.isComplete() ? ""
					: "  -  " + r.unknownWords() + " word" + (r.unknownWords() == 1 ? "" : "s")
						+ " had never been read from the machine"),
				r.isComplete() ? UiColors.OK_TEXT : UiColors.ERROR_TEXT);
		} catch(IOException | RuntimeException x) {
			m_context.reportFailure("Could not write " + files.get(0), x);
			m_status.setText("Nothing written", UiColors.ERROR_TEXT);
		}
	}

	private Address parse(JTextField field, MemoryAddressType type) {
		try {
			return Address.parseOctal(field.getText().trim(), type);
		} catch(RuntimeException x) {
			m_status.error("\"" + field.getText().trim() + "\" is not an octal address");
			return null;
		}
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	private void updateButtons() {
		m_examine.setEnabled(m_context.getConnectionManager().isConnected());
		//-- Writing needs cells, not a machine: a dump read earlier can be written after the
		//-- machine has gone away.
		m_dump.setEnabled(!m_group.isEmpty());
	}

	private void updateStatus() {
		if(m_group.isEmpty()) {
			m_status.setText("Nothing to write yet", UiColors.SECONDARY_TEXT);
			return;
		}
		long unread = m_group.getCells().stream().filter(c -> !c.getEditValue().isKnown()).count();
		m_status.setText(m_group.size() + " words from "
			+ Address.of(m_group.getType(), m_group.getRange().lo()).toOctal() + " to "
			+ Address.of(m_group.getType(), m_group.getRange().hi()).toOctal()
			+ (unread == 0 ? "" : "  -  " + unread + " not read from the machine"),
			UiColors.SECONDARY_TEXT);
	}

	private final ConnectionManager.Listener m_connectionListener = (manager, state) -> AppContext.onUi(() -> {
		MemoryAddressType type = addressType(m_context());
		//-- Only while there is nothing to lose, exactly as the Loader does. Re-expressing the
		//-- range at a new width throws away the words in it, and with no console addressType()
		//-- falls back to PHYSICAL22 - so plain disconnection from a 16 or 18-bit machine used
		//-- to destroy the dump just read, which is precisely what updateButtons() promises
		//-- survives ("a dump read earlier can be written after the machine has gone away").
		if(type != getGroup().getType() && getGroup().isEmpty()) {
			getGroup().shiftRange(Address.of(type, 01000), 1, false);
			m_startAddr.setText(Address.of(type, 01000).toOctal());
			m_endAddr.setText(Address.of(type, 01776).toOctal());
			getGrid().rebuild();
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

	public JTextField getEndField() {
		return m_endAddr;
	}

	public JTextField getEntryField() {
		return m_entryAddr;
	}

	public JButton getDumpButton() {
		return m_dump;
	}

	/** The file name field for one row, so a test can fill it in without a file dialog. */
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
