package to.etc.pdp11.ui.scan;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.machine.IoPageScanner;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.ProgressDialog;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.mem.MemoryCellGroupList;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
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

	private boolean m_scanning;

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
		add(m_status, "growx");

		m_list.setOnUpdate(this::updateStatus);
		updateButtons();
		updateStatus();
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
		//-- The group's cells are about to be thrown away and rebuilt, so nothing may be showing
		//-- them while that happens. This is the useful half of the Pascal's Disconnect.
		m_list.disconnect();
		m_scanning = true;
		updateButtons();
		m_description.setText("");

		MemoryCellGroup group = m_group;
		ProgressDialog progress = new ProgressDialog(owner());
		boolean started = m_context.onConsole("Scanning the I/O page", c -> {
			try {
				IoPageScanner.Result r = IoPageScanner.scan(c, m_context.getMemoryCellGroups(), group, progress);
				AppContext.onUi(() -> finished(r));
			} catch(RuntimeException | to.etc.pdp11.core.console.ConsoleException x) {
				AppContext.onUi(this::failed);
				throw x;
			}
		});
		if(!started)
			failed();
	}

	private void finished(IoPageScanner.Result r) {
		m_scanning = false;
		//-- The group's type follows the machine, and the scan just rebuilt it.
		m_list.connectTo(m_group);
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
		m_list.connectTo(m_group);
		updateButtons();
		updateStatus();
	}

	private void updateButtons() {
		boolean connected = m_context.getConnectionManager().isConnected();
		m_scan.setEnabled(connected && !m_scanning);
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
}
