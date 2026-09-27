package to.etc.pdp11.ui.scan;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.machine.IoPageScanner;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.util.ProgressMonitor;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.mem.MemoryCellGroupList;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.awt.Window;

/**
 * What is actually plugged into this machine: read every I/O page address and see which answer.
 *
 * <p>Ported from {@code TFormIopageScanner} ({@code FormIoPageScannerU.pas}). The scan itself is
 * {@link IoPageScanner}, in the core, where it is tested against the simulated machines; what is
 * here is the list of what was found, the generated machine-description text beside it, and the
 * one button.</p>
 *
 * <p>The two halves are the point of the window: on the left the addresses that answered, named
 * from the loaded description where it knows them; on the right an {@code .ini} section for
 * everything it does not, ready to be pasted into a description of your own machine. That is how
 * you write a description for hardware nobody has documented.</p>
 */
public final class IoPageScannerPanel extends JPanel {
	private final AppContext m_context;

	private final MemoryCellGroup m_group;

	private final MemoryCellGroupList m_list;

	private final JTextArea m_description = new JTextArea();

	private final JLabel m_status = new JLabel();

	private final JButton m_scan = new JButton("Scan the I/O page");

	private final JButton m_examineAll = new JButton("Examine all");

	private final JButton m_examineOne = new JButton("Examine cell");

	private final JButton m_depositChanged = new JButton("Deposit changed");

	private final JProgressBar m_progressBar = new JProgressBar();

	private final JButton m_cancel = new JButton("Cancel");

	private boolean m_scanning;

	/**
	 * The scan that is running, or null. Read on the event thread and on the command thread,
	 * because closing the window cancels it and closing happens on neither reliably.
	 */
	private volatile ScanProgress m_progress;

	public IoPageScannerPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;
		m_list = new MemoryCellGroupList(context);

		m_group = context.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL22, "I/O page scan");
		m_group.setUsageTag("iopagescan");
		m_list.connectTo(m_group);
		m_list.setOnSelect(context.getCellSelection()::select);

		m_description.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_description.getFont().getSize()));
		m_description.setText(IoPageScanner.emptyDescriptionHint());
		//-- Editable, because it is a scratch pad: the user is going to trim it before pasting.
		m_description.setEditable(true);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, m_list, new JScrollPane(m_description));
		split.setResizeWeight(0.6);
		split.setBorder(null);

		m_status.setForeground(UiColors.SECONDARY_TEXT);
		add(buildControls(), "growx, wrap");
		add(split, "grow, wrap");
		add(buildStatusBar(), "growx");

		m_list.setOnUpdate(this::updateStatus);
		updateButtons();
		updateStatus();
	}

	/**
	 * The status line, and beside it the running scan's progress and the way to stop it.
	 *
	 * <p>A modal dialog is what this used to be, and it was the wrong shape for the one operation
	 * in the application that fills the window it belongs to: the dialog stood in front of the
	 * list that was filling in, so the thing worth watching was behind the thing telling you to
	 * wait. The bar and its Cancel live on the window, the window stays usable, and
	 * {@code hidemode 3} takes them away again when nothing is running.</p>
	 */
	private JPanel buildStatusBar() {
		JPanel bar = new JPanel(new MigLayout("insets 0, hidemode 3", "[grow][200!][]", "[]"));
		bar.add(m_status, "growx");
		m_progressBar.setStringPainted(true);
		bar.add(m_progressBar);
		bar.add(m_cancel);
		m_cancel.setToolTipText("Stop the scan. What it has found so far is kept.");
		m_cancel.addActionListener(e -> cancelScan());
		m_progressBar.setVisible(false);
		m_cancel.setVisible(false);
		return bar;
	}

	private JPanel buildControls() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[]16[][]12[]", "[]"));
		bar.add(m_scan);
		bar.add(m_examineAll);
		bar.add(m_examineOne);
		bar.add(m_depositChanged);
		m_scan.setToolTipText("Read all " + IoPageScanner.IOPAGE_WORDS
			+ " words of the I/O page and see which addresses answer");
		m_scan.addActionListener(e -> startScan());
		m_examineAll.addActionListener(e -> m_list.examineAll(owner()));
		m_examineOne.addActionListener(e -> m_list.examineCell(m_list.getSelectedCell()));
		m_depositChanged.addActionListener(e -> m_list.depositAll(true, owner()));
		return bar;
	}

	// -------------------------------------------------------------------------------------
	// Scanning
	// -------------------------------------------------------------------------------------

	private void startScan() {
		Console console = m_context.getConnectionManager().getConsole();
		if(console == null) {
			m_context.reportFailure("Not connected to a machine", null);
			return;
		}
		//-- The list stays connected through the scan, which is the point of it: the cells arrive
		//-- one at a time now rather than all at the end, and the window shows them arriving. The
		//-- Pascal's Disconnect is not needed for that - it guards against a repaint reading freed
		//-- TMemoryCell pointers, and nothing here holds a raw pointer to anything.
		m_scanning = true;
		m_description.setText("");
		ScanProgress progress = new ScanProgress();
		m_progress = progress;
		updateButtons();
		m_status.setText("Scanning ...");

		MemoryCellGroup group = m_group;
		boolean started = m_context.onConsole("Scanning the I/O page", c -> {
			try {
				IoPageScanner.Result r = IoPageScanner.scan(c, m_context.getMemoryCellGroups(), group,
					progress, m_scanListener);
				AppContext.onUi(() -> finished(r));
			} catch(RuntimeException | to.etc.pdp11.core.console.ConsoleException x) {
				AppContext.onUi(this::failed);
				throw x;
			}
		});
		if(!started)
			failed();
	}

	/**
	 * Stop the scan and keep what it found.
	 *
	 * <p>Also what closing the window does - see {@link #detach()}. The scan itself notices
	 * between one address and the next, so this returns long before it stops; the button says so
	 * rather than looking as though nothing happened.</p>
	 */
	private void cancelScan() {
		ScanProgress progress = m_progress;
		if(progress == null)
			return;
		progress.cancel();
		m_cancel.setEnabled(false);
		m_cancel.setText("Stopping ...");
	}

	/**
	 * What the scan reports as it runs. On the command thread; every method marshals.
	 *
	 * <p>Rebuilding the whole list per address found is not as wasteful as it looks: an I/O page
	 * holds a couple of hundred devices at the outside, and the alternative - a model that tracks
	 * inserts - is machinery for a table nobody scrolls while it is filling.</p>
	 */
	private final IoPageScanner.Listener m_scanListener = new IoPageScanner.Listener() {
		@Override
		public void scanStarted() {
			AppContext.onUi(() -> m_list.rebuild());
		}

		@Override
		public void addressFound(MemoryCell cell) {
			AppContext.onUi(() -> {
				m_list.rebuild();
				//-- Keep the newest row in view, so the window is visibly filling rather than
				//-- visibly redrawing.
				JTable table = m_list.getTable();
				int last = table.getRowCount() - 1;
				if(last >= 0)
					table.scrollRectToVisible(table.getCellRect(last, 0, true));
				m_status.setText(m_group.size() + " addresses so far");
			});
		}
	};

	private void finished(IoPageScanner.Result r) {
		m_scanning = false;
		m_progress = null;
		//-- The names the last pass wrote - device_nnnnnn.reg_n for everything the description
		//-- does not know - are not on the rows yet.
		m_list.rebuild();
		m_description.setText(r.description().isEmpty()
			? "; Every address that answered is already named by the loaded machine description.\n"
			: r.description());
		m_description.setCaretPosition(0);
		m_status.setText(r.found() + " of " + r.examined() + " addresses answered; "
			+ r.named() + " named by the machine description, "
			+ r.blocks().size() + " register block" + (r.blocks().size() == 1 ? "" : "s") + " found"
			+ (r.cancelled() ? "  -  stopped early" : ""));
		updateButtons();
	}

	private void failed() {
		m_scanning = false;
		m_progress = null;
		m_list.rebuild();
		updateButtons();
		updateStatus();
	}

	private void updateButtons() {
		boolean connected = m_context.getConnectionManager().isConnected();
		m_scan.setEnabled(connected && !m_scanning);
		m_progressBar.setVisible(m_scanning);
		m_cancel.setVisible(m_scanning);
		if(m_scanning) {
			m_cancel.setEnabled(true);
			m_cancel.setText("Cancel");
		}
		//-- Nothing to examine or deposit until there is a result, which is the Pascal's
		//-- iopsEmpty/iopsReady distinction ({@code :97-127}).
		boolean haveResults = !m_scanning && !m_group.isEmpty();
		m_examineAll.setEnabled(connected && haveResults);
		m_examineOne.setEnabled(connected && haveResults);
		m_depositChanged.setEnabled(connected && haveResults);
	}

	private void updateStatus() {
		if(m_scanning)
			return;
		if(m_group.isEmpty()) {
			m_status.setText(m_context.getConnectionManager().isConnected()
				? "Nothing scanned yet"
				: "Not connected, so there is nothing to scan");
		}
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	private final ConnectionManager.Listener m_connectionListener = (manager, state) -> AppContext.onUi(() -> {
		updateButtons();
		updateStatus();
	});

	public void attach() {
		detach();
		m_context.getConnectionManager().addListener(m_connectionListener);
		updateButtons();
		updateStatus();
	}

	/**
	 * The window is going away, so the scan goes with it.
	 *
	 * <p>A scan is minutes of console traffic on the one thread every other window's buttons
	 * queue behind. Leaving it running after its window has been closed would be the application
	 * ignoring every one of them for a result nobody is going to look at.</p>
	 */
	public void detach() {
		m_context.getConnectionManager().removeListener(m_connectionListener);
		cancelScan();
	}

	public void dispose() {
		detach();
		m_context.getMemoryCellGroups().removeGroup(m_group);
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	public MemoryCellGroupList getList() {
		return m_list;
	}

	public MemoryCellGroup getGroup() {
		return m_group;
	}

	public JTextArea getDescriptionArea() {
		return m_description;
	}

	public JButton getScanButton() {
		return m_scan;
	}

	public String getStatusText() {
		return m_status.getText();
	}

	/** The scan's progress bar, showing only while one is running. */
	public JProgressBar getProgressBar() {
		return m_progressBar;
	}

	/** The scan's Cancel, showing only while one is running. */
	public JButton getCancelButton() {
		return m_cancel;
	}

	/**
	 * The scan's progress bar and its Cancel, driven from the command thread.
	 *
	 * <p>{@link ProgressDialog} is the other implementation of {@link ProgressMonitor} and the
	 * usual one; this window wants the opposite of a modal dialog, so it has its own. The
	 * cancelled flag is written on the event thread and read on the command thread, which is what
	 * makes it volatile - the interface says so, and it is the whole mechanism.</p>
	 */
	private final class ScanProgress implements ProgressMonitor {
		private volatile boolean m_cancelled;

		/** Command thread only, so it needs no guarding of its own. */
		private int m_done;

		private int m_total = 1;

		@Override
		public void begin(String task, int total) {
			m_done = 0;
			m_total = Math.max(1, total);
			int max = m_total;
			AppContext.onUi(() -> {
				m_progressBar.setMinimum(0);
				m_progressBar.setMaximum(max);
				m_progressBar.setValue(0);
				m_progressBar.setString("0 of " + max);
			});
		}

		@Override
		public void step(int amount, String note) {
			m_done += amount;
			int now = m_done;
			int max = m_total;
			AppContext.onUi(() -> {
				m_progressBar.setValue(now);
				m_progressBar.setString(now + " of " + max);
			});
		}

		@Override
		public boolean isCancelled() {
			return m_cancelled;
		}

		@Override
		public void done() {
			//-- Deliberately does not fill the bar in: a scan that was stopped early did not get
			//-- to the end, and a full bar over "stopped early" says two different things. The bar
			//-- goes away with the scan - see updateButtons.
		}

		void cancel() {
			m_cancelled = true;
		}
	}
}
