package to.etc.pdp11.ui.mmu;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.mmu.AccessSpace;
import to.etc.pdp11.core.mmu.CpuMode;
import to.etc.pdp11.core.mmu.MmuMemoryMap;
import to.etc.pdp11.core.mmu.Pdp11Mmu;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.ProgressDialog;
import to.etc.pdp11.ui.UiColors;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the machine's memory management unit is currently doing: which virtual addresses reach
 * which physical ones, in each space, for a chosen CPU mode.
 *
 * <p>Ported from {@code TFormMMU} ({@code FormMmuU.pas}). The walk over address space that turns
 * page registers into readable blocks is {@link MmuMemoryMap}, in the core, where it is tested;
 * what is here is the two tables, the mode selector and the one button.</p>
 *
 * <h2>Two things the original does not do</h2>
 *
 * <p><b>Any mode, not only the current one.</b> The Pascal shows {@code MMU.curCpuMode} - what
 * the PSW says right now - and there is no way to see the user map while the machine is stopped
 * in the kernel, which is exactly when you want it. The selector starts on the current mode and
 * says which that is.</p>
 *
 * <p><b>Why an address does not translate.</b> The Pascal prints "not assigned" for both ways
 * translation can fail, and one of them - a page length error, which is what a stack page's
 * unused end looks like - is not "not assigned" at all.</p>
 */
public final class MmuPanel extends JPanel {
	private final AppContext m_context;

	private final JComboBox<CpuMode> m_mode = new JComboBox<>(CpuMode.values());

	private final JLabel m_currentMode = new JLabel();

	private final JLabel m_status = new JLabel();

	private final JButton m_refresh = new JButton("Read the MMU registers");

	private final JTabbedPane m_tabs = new JTabbedPane();

	private final MmuMapTableModel m_instructionModel = new MmuMapTableModel();

	private final MmuMapTableModel m_dataModel = new MmuMapTableModel();

	private final JTable m_instructionTable = new JTable(m_instructionModel);

	private final JTable m_dataTable = new JTable(m_dataModel);

	/**
	 * Set while a rebuild is already queued, so a bulk examine redraws once rather than 99 times.
	 *
	 * <p>Atomic because the two ends are on different threads: the flag is set on the command
	 * thread, from the MMU's change listener, and cleared on the event thread by the runnable
	 * that listener queued. A plain boolean gives the command thread no reason ever to see the
	 * clear, and a stale {@code true} means no redraw is ever queued again - the map silently
	 * stops following the registers for the rest of the session.</p>
	 */
	private final AtomicBoolean m_updatePending = new AtomicBoolean();

	/** The MMU currently being watched, so the listener can be taken off the right one. */
	private Pdp11Mmu m_watched;

	private final Runnable m_mmuListener = this::scheduleUpdate;

	private final ConnectionManager.Listener m_connectionListener =
		(manager, state) -> AppContext.onUi(() -> {
			rebind();
			updateDisplay();
		});

	public MmuPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow]"));
		m_context = context;
		add(buildControls(), "growx, wrap");

		//-- Instruction space first: it is the one that is always in use. A mode with D space
		//-- disabled sends data accesses through this same map, and the status line says so.
		m_tabs.addTab("Instruction space", new JScrollPane(m_instructionTable));
		m_tabs.addTab("Data space", new JScrollPane(m_dataTable));
		configure(m_instructionTable);
		configure(m_dataTable);
		add(m_tabs, "grow");
		updateDisplay();
	}

	private JPanel buildControls() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]8[]12[grow][]", "[]"));
		m_mode.addActionListener(e -> updateDisplay());
		//-- "Kernel", not "KERNEL": the enum's name is an identifier and this is a word on a form.
		m_mode.setRenderer(new DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focused) {
				super.getListCellRendererComponent(list, value, index, selected, focused);
				if(value instanceof CpuMode mode)
					setText(label(mode));
				return this;
			}
		});
		m_currentMode.setForeground(UiColors.SECONDARY_TEXT);
		bar.add(new JLabel("CPU mode"));
		bar.add(m_mode);
		bar.add(m_currentMode);
		//-- "wmin 0" because a JLabel's minimum width is the width of its text, and this label's
		//-- text is a sentence: without it the row cannot be laid out narrower than the sentence
		//-- and the whole window refuses to be resized below it. Swing clips it with an ellipsis,
		//-- and the tooltip has the rest.
		bar.add(m_status, "growx, wmin 0");
		m_refresh.setToolTipText("Examine the PSW, MMR0, MMR3 and all four sets of page registers");
		m_refresh.addActionListener(e -> refresh());
		bar.add(m_refresh);
		return bar;
	}

	private static void configure(JTable table) {
		table.setAutoCreateRowSorter(false);
		table.getTableHeader().setReorderingAllowed(false);
		for(int i = 0; i < MmuMapTableModel.WIDTHS.length; i++) {
			TableColumn c = table.getColumnModel().getColumn(i);
			//-- Both, not just the preferred width: any auto-resize mode redistributes preferred
			//-- widths on the first layout pass and keeps the result.
			c.setPreferredWidth(MmuMapTableModel.WIDTHS[i]);
			c.setMinWidth(MmuMapTableModel.WIDTHS[i] / 2);
		}
	}

	// -------------------------------------------------------------------------------------
	// Showing and hiding
	// -------------------------------------------------------------------------------------

	public void attach() {
		detach();
		m_context.getConnectionManager().addListener(m_connectionListener);
		rebind();
		//-- Start on the mode the machine is in, which is what the Pascal shows and the only
		//-- one it shows.
		Pdp11Mmu mmu = mmu();
		if(mmu != null)
			m_mode.setSelectedItem(mmu.getCpuMode());
		updateDisplay();
	}

	public void detach() {
		m_context.getConnectionManager().removeListener(m_connectionListener);
		if(m_watched != null) {
			m_watched.removeChangeListener(m_mmuListener);
			m_watched = null;
		}
	}

	/** Follow whichever MMU is live now - there is a new one per connection. */
	private void rebind() {
		Pdp11Mmu mmu = mmu();
		if(mmu == m_watched)
			return;
		if(m_watched != null)
			m_watched.removeChangeListener(m_mmuListener);
		m_watched = mmu;
		if(mmu != null)
			mmu.addChangeListener(m_mmuListener);
	}

	private Pdp11Mmu mmu() {
		Console console = m_context.getConnectionManager().getConsole();
		return console == null ? null : console.getMmu();
	}

	// -------------------------------------------------------------------------------------
	// Showing what it says
	// -------------------------------------------------------------------------------------

	/**
	 * Redraw, once, soon.
	 *
	 * <p>Called from the MMU's change listener, which fires per register - so an examine of the
	 * whole group fires it 99 times, on the command thread. Coalescing to one pass on the event
	 * thread is the difference between one map and ninety-nine.</p>
	 */
	private void scheduleUpdate() {
		if(!m_updatePending.compareAndSet(false, true))
			return;
		SwingUtilities.invokeLater(() -> {
			m_updatePending.set(false);
			updateDisplay();
		});
	}

	/**
	 * Redraw everything from the MMU, or say why there is nothing to draw.
	 *
	 * <h2>One question, asked once</h2>
	 *
	 * <p>Whether there is a machine to show is <b>one</b> decision - is the manager connected
	 * <i>and</i> is there an MMU - and the button, the mode selector and the tables all follow
	 * it. They used to be asked separately: the button wanted a connection, the tables only
	 * wanted an MMU, and a console exists from the moment {@code connect} builds it, which is
	 * before its handshake has finished. In between, this window showed a full memory map of a
	 * machine that was not answering yet, with the Refresh button greyed out beside it.</p>
	 *
	 * <h2>A window that fails must not go on showing its last answer</h2>
	 *
	 * <p>Building the two maps is 65536 translations and it used to happen between the line that
	 * enables the button and the line that sets the status. Anything thrown in there - and one
	 * page register reading back all ones was enough - left the panel half updated: an enabled
	 * button beside a status line still saying "Not connected to a machine", with a stack trace
	 * on stderr where nobody was looking. The maps are built first and the widgets are set
	 * afterwards, and a failure goes into the status line where it can be seen.</p>
	 */
	private void updateDisplay() {
		Pdp11Mmu mmu = mmu();
		boolean live = mmu != null && m_context.getConnectionManager().isConnected();
		m_refresh.setEnabled(live);
		m_mode.setEnabled(live);
		if(!live) {
			m_currentMode.setText("");
			setStatus("Not connected to a machine", UiColors.ERROR_TEXT);
			m_instructionModel.setMap(null);
			m_dataModel.setMap(null);
			return;
		}
		CpuMode mode = (CpuMode) m_mode.getSelectedItem();
		if(mode == null)
			mode = mmu.getCpuMode();

		MmuMemoryMap instruction;
		MmuMemoryMap data;
		try {
			instruction = MmuMemoryMap.of(mmu, mode, AccessSpace.INSTRUCTION);
			data = MmuMemoryMap.of(mmu, mode, AccessSpace.DATA);
		} catch(RuntimeException x) {
			m_context.getLogger().log(LogChannel.OTHER, "MMU window: cannot build the memory map: " + x);
			m_currentMode.setText("");
			m_instructionModel.setMap(null);
			m_dataModel.setMap(null);
			setStatus("Cannot work out the memory map from these registers: " + x, UiColors.ERROR_TEXT);
			return;
		}
		m_currentMode.setText(mode == mmu.getCpuMode()
			? "(the mode the machine is in)"
			: "(the machine is in " + label(mmu.getCpuMode()) + ")");
		m_instructionModel.setMap(instruction);
		m_dataModel.setMap(data);
		m_tabs.setTitleAt(1, data.isUsingInstructionMapForData() ? "Data space (off)" : "Data space");
		setStatus(describe(mmu, mode, data), UiColors.SECONDARY_TEXT);
	}

	private void setStatus(String text, java.awt.Color colour) {
		m_status.setText(text);
		m_status.setToolTipText(text);
		m_status.setForeground(colour);
	}

	/**
	 * The one line that says what the tables below mean.
	 *
	 * <p>Ported from {@code SpecialInfoLabel} ({@code FormMmuU.pas:165-172}), which says the same
	 * three things in the same order.</p>
	 */
	private static String describe(Pdp11Mmu mmu, CpuMode mode, MmuMemoryMap data) {
		if(!mmu.isRelocationEnabled())
			return "Relocation disabled: virtual is physical, apart from the I/O page";
		String where = mmu.isMapping22Bit() ? "22-bit mapping" : "18-bit mapping";
		return label(mode) + " " + (data.isUsingInstructionMapForData()
			? "data space disabled, so data goes through the instruction map"
			: "data space enabled") + " · " + where;
	}

	private static String label(CpuMode mode) {
		String s = mode.name().toLowerCase();
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// -------------------------------------------------------------------------------------
	// Reading the registers
	// -------------------------------------------------------------------------------------

	/**
	 * Examine every register the MMU watches, then recompute from them.
	 *
	 * <p>{@code ExamineMMU} ({@code Pdp11MmuU.pas:365-370}) is these two steps, and the second is
	 * not optional: cell propagation deliberately excludes the cell it started from, so examining
	 * the MMU's <b>own</b> register group never reaches the MMU's own listener.</p>
	 */
	private void refresh() {
		Pdp11Mmu mmu = mmu();
		if(mmu == null)
			return;
		ProgressDialog progress = new ProgressDialog(owner());
		m_context.onConsole("Reading the MMU registers", console -> {
			console.examine(mmu.getRegisterGroup(), false, progress);
			mmu.evalAll();
			AppContext.onUi(() -> {
				//-- The registers have just said which mode the machine is in; follow it.
				m_mode.setSelectedItem(mmu.getCpuMode());
				updateDisplay();
			});
		});
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	// -------------------------------------------------------------------------------------
	// For tests
	// -------------------------------------------------------------------------------------

	public JTable getInstructionTable() {
		return m_instructionTable;
	}

	public JTable getDataTable() {
		return m_dataTable;
	}

	public JTabbedPane getTabs() {
		return m_tabs;
	}

	public JComboBox<CpuMode> getModeSelector() {
		return m_mode;
	}

	public JButton getRefreshButton() {
		return m_refresh;
	}

	public String getStatusText() {
		return m_status.getText();
	}

	public String getCurrentModeText() {
		return m_currentMode.getText();
	}
}
