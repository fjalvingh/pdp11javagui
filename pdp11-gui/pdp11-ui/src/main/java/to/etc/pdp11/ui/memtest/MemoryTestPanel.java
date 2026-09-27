package to.etc.pdp11.ui.memtest;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.memtest.ChipSize;
import to.etc.pdp11.core.memtest.MemoryTestResult;
import to.etc.pdp11.core.memtest.MemoryTester;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.ui.FieldStatus;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.ProgressDialog;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.awt.Window;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Four tests that say whether this machine's memory works, and which part of it does not.
 *
 * <p>Ported from {@code TFormMemoryTest} ({@code FormMemoryTestU.pas}). The tests themselves are
 * {@link MemoryTester}, in the core, where they are run against a simulated machine with a
 * deliberate fault in it; what is here is the range to test, how big a memory chip is, the four
 * buttons and the log.</p>
 *
 * <p><b>No grid.</b> The Pascal window has one and never shows it - "Do not update MemoryGrid, es
 * ist die ganze Zeit invisible" ({@code :181-182}) - so what it really is, is a log with some
 * controls above it. The cells are still updated as the tests run, because they are on the
 * propagation bus and a memory window looking at the same range will show them.</p>
 *
 * <p><b>These tests write to memory and do not put it back.</b> They are for a machine that is
 * not running anything, which is the same assumption the original makes.</p>
 */
public final class MemoryTestPanel extends JPanel {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** How many cells the random test writes. {@code TestRandom(32)} ({@code :836}). */
	private static final int RANDOM_COUNT = 32;

	private final AppContext m_context;

	private final MemoryCellGroup m_group;

	private final JTextField m_startAddr = new JTextField(9);

	private final JTextField m_endAddr = new JTextField(9);

	private final JComboBox<ChipSize> m_chipSize = new JComboBox<>(ChipSize.values());

	private final JButton m_set = new JButton("Set range");

	private final JButton m_dataLines = new JButton("Test data lines");

	private final JButton m_addressLines = new JButton("Test address lines");

	private final JButton m_dataBits = new JButton("Test data bits");

	private final JButton m_random = new JButton("Random test");

	private final JTextArea m_log = new JTextArea();

	private final JLabel m_statusLabel = new JLabel();

	/** The status line, and where a value that cannot be used is reported. See {@link FieldStatus}. */
	private final FieldStatus m_status = new FieldStatus(m_statusLabel, UiColors.SECONDARY_TEXT);

	/** The Pascal's {@code TheState}: 0 until the range has been set, 1 after. */
	private boolean m_rangeSet;

	/** Set while this panel is writing the fields itself, so it does not undo its own work. */
	private boolean m_writingFields;

	private boolean m_running;

	public MemoryTestPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][][grow][]"));
		m_context = context;

		m_group = context.getMemoryCellGroups().addGroup(addressType(context), "Memory test");
		m_group.setUsageTag("memorytest");
		//-- The test writes patterns, not values the user typed, so nothing here needs
		//-- protecting from what the machine says.
		m_group.setPdpOverwritesEdit(true);

		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, m_startAddr.getFont().getSize());
		m_startAddr.setFont(mono);
		m_endAddr.setFont(mono);
		m_log.setFont(mono);
		m_log.setEditable(false);
		m_chipSize.setSelectedItem(ChipSize.getDefault());

		add(buildRangeBar(), "growx, wrap");
		add(buildTestBar(), "growx, wrap");
		add(new JScrollPane(m_log), "grow, wrap");
		add(m_statusLabel, "growx");

		resetRange();
		updateButtons();
	}

	private static MemoryAddressType addressType(AppContext context) {
		Console console = context.getConnectionManager().getConsole();
		return console == null ? MemoryAddressType.PHYSICAL22 : console.physicalAddressType();
	}

	private JPanel buildRangeBar() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]8[][]12[][]12[]", "[]"));
		bar.add(new JLabel("From:"));
		bar.add(m_startAddr);
		bar.add(new JLabel("to:"));
		bar.add(m_endAddr);
		bar.add(new JLabel("Memory chip size:"));
		m_chipSize.setToolTipText("How much address space one chip covers."
			+ " The tests read a few words per chip rather than every word, so this decides what they can see.");
		bar.add(m_chipSize);
		bar.add(m_set);
		m_set.addActionListener(e -> applyRange());
		//-- Editing the range invalidates it, as the Pascal drops TheState to 0 on every keystroke
		//-- in either field ({@code :219-236}). On the document rather than on the keyboard,
		//-- because a range can also be pasted in, and a pasted range is just as stale.
		javax.swing.event.DocumentListener invalidate = new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				rangeEdited();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				rangeEdited();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				rangeEdited();
			}
		};
		m_startAddr.getDocument().addDocumentListener(invalidate);
		m_endAddr.getDocument().addDocumentListener(invalidate);
		m_startAddr.addActionListener(e -> applyRange());
		m_endAddr.addActionListener(e -> applyRange());
		return bar;
	}

	private JPanel buildTestBar() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][][][]16[]", "[]"));
		bar.add(m_dataLines);
		bar.add(m_addressLines);
		bar.add(m_dataBits);
		bar.add(m_random);
		JButton clear = new JButton("Clear");
		bar.add(clear);

		m_dataLines.setToolTipText("Move a one and a zero through the sixteen data bits;"
			+ " finds a data line tied high or low");
		m_addressLines.setToolTipText("Write each address into itself; finds a dead or shorted address line");
		m_dataBits.setToolTipText("A moving-one pattern in every chip; finds a dead memory chip");
		m_random.setToolTipText("Random values at random addresses; finds what the patterns miss");

		m_dataLines.addActionListener(e -> runDataLines());
		m_addressLines.addActionListener(e -> runAddressLines());
		m_dataBits.addActionListener(e -> runDataBits());
		m_random.addActionListener(e -> runRandom());
		clear.addActionListener(e -> m_log.setText(""));
		return bar;
	}

	// -------------------------------------------------------------------------------------
	// The range
	// -------------------------------------------------------------------------------------

	/** All of memory below the I/O page, which is the most that could be tested. */
	private void resetRange() {
		MemoryAddressType type = m_group.getType();
		setFields(Address.of(type, 0).toOctal(), Address.of(type, type.getIopageBase() - 2).toOctal());
		m_rangeSet = false;
	}

	/**
	 * Take the range from the fields and build the cells for it.
	 *
	 * <p>Ported from {@code SetStartAddrButtonClick} ({@code :246-292}), including the two
	 * corrections it makes silently: both addresses are forced even, and a range the wrong way
	 * round is swapped rather than refused.</p>
	 */
	private void applyRange() {
		MemoryAddressType type = m_group.getType();
		Address start = parse(m_startAddr, type);
		Address end = parse(m_endAddr, type);
		if(start == null || end == null)
			return;
		long lo = start.val() & ~1L;
		long hi = end.val() & ~1L;
		if(hi < lo) {
			long t = lo;
			lo = hi;
			hi = t;
		}
		//-- Never past the I/O page: those addresses are device registers, and writing test
		//-- patterns into them would do something rather than nothing.
		long maxEnd = type.getIopageBase() - 2;
		if(hi > maxEnd)
			hi = maxEnd;
		int words = (int) ((hi - lo) / 2) + 1;

		m_group.shiftRange(Address.of(type, lo), words, false);
		//-- The corrected range goes back into the fields, and that must not read as an edit.
		setFields(Address.of(type, lo).toOctal(), Address.of(type, hi).toOctal());
		m_rangeSet = true;
		updateButtons();
		append("Range set: " + Octal.format(lo, 1) + ".." + Octal.format(hi, 1)
			+ ", " + words + " words, chip size " + chipSize().getLabel());
	}

	private Address parse(JTextField field, MemoryAddressType type) {
		try {
			return Address.parseOctal(field.getText().trim(), type);
		} catch(RuntimeException x) {
			m_status.error("\"" + field.getText().trim() + "\" is not an octal address");
			return null;
		}
	}

	/** What the range fields showing something other than what was set means. */
	private void rangeEdited() {
		if(m_writingFields)
			return;
		m_rangeSet = false;
		updateButtons();
	}

	/** Write the fields without that counting as the user editing them. */
	private void setFields(String start, String end) {
		m_writingFields = true;
		try {
			m_startAddr.setText(start);
			m_endAddr.setText(end);
		} finally {
			m_writingFields = false;
		}
	}

	private ChipSize chipSize() {
		ChipSize s = (ChipSize) m_chipSize.getSelectedItem();
		return s == null ? ChipSize.getDefault() : s;
	}

	// -------------------------------------------------------------------------------------
	// Running the tests
	// -------------------------------------------------------------------------------------

	/**
	 * Moving one, then moving zero if that passed.
	 *
	 * <p>{@code TestDataLinesButtonClick} ({@code :822-831}): there is no point moving a zero
	 * through lines that already failed with a one.</p>
	 */
	private void runDataLines() {
		run("data lines", (tester, pm) -> {
			MemoryTestResult first = tester.testDataLines(true, pm);
			if(!first.passed() || first.cancelled())
				return first;
			return tester.testDataLines(false, pm);
		});
	}

	private void runAddressLines() {
		run("address lines", (tester, pm) -> {
			MemoryTestResult first = tester.testAddressLines(1, pm);
			if(!first.passed() || first.cancelled())
				return first;
			//-- Phase 2 is the one that finds a short rather than a break, and it is only
			//-- meaningful once phase 1 has passed.
			return tester.testAddressLines(2, pm);
		});
	}

	private void runDataBits() {
		run("data bits", (tester, pm) -> {
			MemoryTestResult first = tester.testDataBits(1, pm);
			if(!first.passed() || first.cancelled())
				return first;
			return tester.testDataBits(2, pm);
		});
	}

	private void runRandom() {
		run("random", (tester, pm) -> tester.testRandom(RANDOM_COUNT, new Random(), pm));
	}

	@FunctionalInterface
	private interface TestRun {
		MemoryTestResult run(MemoryTester tester, to.etc.pdp11.core.util.ProgressMonitor pm) throws ConsoleException;
	}

	private void run(String what, TestRun work) {
		if(!m_rangeSet) {
			m_status.error("Set the address range first");
			return;
		}
		MemoryAddressType type = m_group.getType();
		Address start = Address.of(type, m_group.getRange().lo());
		Address end = Address.of(type, m_group.getRange().hi());
		ChipSize size = chipSize();
		m_running = true;
		updateButtons();
		m_status.setText("Testing " + what + " ...", UiColors.SECONDARY_TEXT);

		ProgressDialog progress = new ProgressDialog(owner());
		boolean started = m_context.onConsole("Memory test", console -> {
			try {
				//-- Lines reach the log as they happen, so a long test can be watched rather
				//-- than only reported on.
				MemoryTester tester = new MemoryTester(console, m_group, start, end, size,
					line -> AppContext.onUi(() -> append(line)));
				MemoryTestResult r = work.run(tester, progress);
				AppContext.onUi(() -> finished(what, r));
			} catch(RuntimeException | ConsoleException x) {
				AppContext.onUi(() -> failed(what));
				throw x;
			}
		});
		if(!started)
			failed(what);
	}

	private void finished(String what, MemoryTestResult r) {
		m_running = false;
		updateButtons();
		m_status.setText("Test of " + what + ": "
			+ (r.cancelled() ? "stopped early"
				: r.passed() ? "passed"
					: r.errorCount() + " bad word" + (r.errorCount() == 1 ? "" : "s")
						+ (r.hasStuckLines() ? ", and a data line looks dead" : "")),
			r.passed() && !r.cancelled() ? UiColors.OK_TEXT
				: r.cancelled() ? UiColors.SECONDARY_TEXT : UiColors.ERROR_TEXT);
	}

	private void failed(String what) {
		m_running = false;
		updateButtons();
		m_status.setText("Test of " + what + " could not run", UiColors.ERROR_TEXT);
	}

	private void updateButtons() {
		boolean can = m_rangeSet && !m_running && m_context.getConnectionManager().isConnected();
		m_dataLines.setEnabled(can);
		m_addressLines.setEnabled(can);
		m_dataBits.setEnabled(can);
		m_random.setEnabled(can);
		m_set.setEnabled(!m_running);
	}

	/** One line, stamped, the way the Pascal's {@code Log} does ({@code :296-303}). */
	private void append(String line) {
		m_log.append("[" + (m_log.getLineCount()) + ": " + LocalTime.now().format(TIME) + "] " + line + "\n");
		m_log.setCaretPosition(m_log.getDocument().getLength());
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	private final ConnectionManager.Listener m_connectionListener = (manager, state) -> AppContext.onUi(() -> {
		//-- A different machine may be a different address width, so the range has to be set
		//-- again. The Pascal drops TheState to 0 in OnConsoleChanged for the same reason.
		//-- Through the accessors: a lambda in a field initializer may not read a blank final.
		MemoryAddressType type = addressType(m_context());
		if(type != getGroup().getType()) {
			getGroup().shiftRange(Address.of(type, 0), 1, false);
			resetRange();
		}
		m_rangeSet = false;
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

	public MemoryCellGroup getGroup() {
		return m_group;
	}

	private AppContext m_context() {
		return m_context;
	}

	public JTextField getStartField() {
		return m_startAddr;
	}

	public JTextField getEndField() {
		return m_endAddr;
	}

	public JComboBox<ChipSize> getChipSizeCombo() {
		return m_chipSize;
	}

	public JButton getSetButton() {
		return m_set;
	}

	public JButton getDataLinesButton() {
		return m_dataLines;
	}

	public JButton getRandomButton() {
		return m_random;
	}

	public JTextArea getLog() {
		return m_log;
	}

	public String getStatusText() {
		return m_status.getText();
	}
}
