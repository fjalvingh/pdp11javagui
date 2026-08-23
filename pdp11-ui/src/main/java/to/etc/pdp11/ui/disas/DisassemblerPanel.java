package to.etc.pdp11.ui.disas;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.disas.DisassemblyListing;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.ProgressDialog;
import to.etc.pdp11.ui.UiColors;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Font;
import java.awt.Window;

/**
 * Memory read back from the machine, shown as instructions, with the line the PC is on marked.
 *
 * <p>Ported from {@code TFormDisas} ({@code FormDisasU.pas}). The decoding and the awkward part -
 * finding the PC when it falls inside an instruction rather than at the start of one - are in
 * {@link DisassemblyListing}, in the core, where they can be tested without a window. What is
 * left here is a range, a list, and the rule about when to re-read the machine.</p>
 *
 * <h2>It follows the PC without being told to</h2>
 *
 * <p>The Pascal is called at: {@code TFormExecute.SetAndShowPc} names
 * {@code FormMain.FormDisas.ShowNewPcAddr} directly ({@code FormExecuteU.pas:214}). Here this
 * window watches {@link MachineState}, so it updates whether or not the execution-control window
 * is open, and the execution-control window does not know this one exists.</p>
 *
 * <p>The window that is not visible does not read memory - the Pascal is careful about this too
 * ({@code FormDisasU.pas:398-402}), and it matters: every stop would otherwise cost twenty-one
 * examines over a serial line for a window nobody is looking at.</p>
 */
public final class DisassemblerPanel extends JPanel {
	/**
	 * How many words to show around the PC, and how many of them come before it.
	 *
	 * <p>From {@code disas_pcaddr_window_size = 10} ({@code FormDisasU.pas:114}) and the
	 * arithmetic that uses it ({@code :390-396}): the listing starts
	 * {@code (2 * size) div 2} <i>bytes</i> before the PC and runs {@code 2 * size} bytes, which
	 * is five words before and eleven words in all. The Pascal's own name for it suggests
	 * "ten words either side", and that is not what the code does - so the numbers are spelled
	 * out here instead.</p>
	 */
	private static final int WORDS_BEFORE_PC = 5;

	private static final int WORDS_SHOWN = 11;

	private final AppContext m_context;

	private final MemoryCellGroup m_group;

	private final JTextField m_startAddr = new JTextField(8);

	private final JTextField m_endAddr = new JTextField(8);

	private final JCheckBox m_useCache = new JCheckBox("Use cached values", true);

	private final DefaultListModel<DisassemblyListing.Line> m_model = new DefaultListModel<>();

	private final JList<DisassemblyListing.Line> m_list = new JList<>(m_model);

	private final JLabel m_info = new JLabel();

	private Address m_start = Address.of(MemoryAddressType.VIRTUAL, 0);

	private Address m_end = Address.of(MemoryAddressType.VIRTUAL, 2L * (WORDS_SHOWN - 1));

	/** Where the PC is, or null when it should not be shown - see {@link #setRange}. */
	private Address m_pc;

	public DisassemblerPanel(AppContext context) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;

		//-- Virtual addresses: an instruction stream only means anything in the 64 KB a program
		//-- can see, whatever the physical machine is.
		m_group = context.getMemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "Disassembly");
		m_group.setUsageTag("disassembler");
		//-- Code being examined is never edited here, so nothing needs protecting from incoming
		//-- values; the whole point of this window is to show what the machine actually holds.
		m_group.setPdpOverwritesEdit(true);
		m_group.shiftRange(m_start, WORDS_SHOWN, false);

		m_list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_list.getFont().getSize()));
		m_list.setCellRenderer(new LineRenderer());
		add(buildControls(), "growx, wrap");
		add(new JScrollPane(m_list), "grow, wrap");
		add(m_info, "growx");

		m_startAddr.setText(m_start.toOctal());
		m_endAddr.setText(m_end.toOctal());
		updateDisplay();
	}

	private JPanel buildControls() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]8[][]8[]4[]12[]16[]", "[]"));
		bar.add(new JLabel("From:"));
		bar.add(m_startAddr);
		bar.add(new JLabel("to:"));
		bar.add(m_endAddr);
		JButton back = new JButton("<");
		back.setToolTipText("Start one word earlier - instruction boundaries are a guess, and this is how you correct it");
		JButton forward = new JButton(">");
		forward.setToolTipText("Start one word later");
		bar.add(back);
		bar.add(forward);
		JButton show = new JButton("Show");
		bar.add(show);
		bar.add(m_useCache);

		show.addActionListener(e -> setRangeFromFields());
		back.addActionListener(e -> nudge(-2));
		forward.addActionListener(e -> nudge(2));
		m_startAddr.addActionListener(e -> setRangeFromFields());
		m_endAddr.addActionListener(e -> setRangeFromFields());
		return bar;
	}

	// -------------------------------------------------------------------------------------
	// The range
	// -------------------------------------------------------------------------------------

	private void setRangeFromFields() {
		Address start = parse(m_startAddr);
		Address end = parse(m_endAddr);
		if(start == null || end == null)
			return;
		//-- The user chose an address range, so stop following the PC into it: a marker that
		//-- moves the listing out from under somebody reading it is worse than no marker.
		setRange(start, end, null, true);
	}

	/**
	 * Show this range, optionally marking a PC in it, and read what is missing from the machine.
	 *
	 * @param pc the PC to mark, or null for none - which is what the Pascal writes as
	 *           {@code CodeAddr.val := MEMORYCELL_ILLEGALVAL} every time the user moves the
	 *           range by hand ({@code :404, 417, 428})
	 */
	private void setRange(Address start, Address end, Address pc, boolean examine) {
		m_start = start;
		//-- CheckInput ({@code :246-249}): an end before the start is not a range.
		m_end = end.val() < start.val() ? start : end;
		m_pc = pc;
		m_startAddr.setText(m_start.toOctal());
		m_endAddr.setText(m_end.toOctal());

		int words = (int) ((m_end.val() - m_start.val()) / 2) + 1;
		m_group.shiftRange(m_start, words, m_useCache.isSelected());
		if(examine && m_context.getConnectionManager().isConnected()) {
			examineAndShow();
		} else {
			updateDisplay();
		}
	}

	/** One word either way, for when the decoder started on the wrong byte. */
	private void nudge(int delta) {
		long start = m_start.val() + delta;
		long end = m_end.val() + delta;
		if(start < 0 || end > 0177776)
			return;
		setRange(Address.of(MemoryAddressType.VIRTUAL, start), Address.of(MemoryAddressType.VIRTUAL, end), null, true);
	}

	/**
	 * Read the range from the machine, then redraw.
	 *
	 * <p>{@code useCache} is what decides whether cells that already have a value are read
	 * again. On a fast machine it costs nothing to re-read; over a serial line it is the
	 * difference between a window that keeps up with single-stepping and one that does not.</p>
	 */
	private void examineAndShow() {
		MemoryCellGroup group = m_group;
		boolean cached = m_useCache.isSelected();
		ProgressDialog progress = new ProgressDialog(owner());
		m_context.onConsole("Reading code", console -> {
			console.examine(group, cached, progress);
			AppContext.onUi(this::updateDisplay);
		});
	}

	/** Decode what is in the group and show it. On the EDT; talks to nothing. */
	public void updateDisplay() {
		DisassemblyListing listing = DisassemblyListing.of(m_group, m_start, m_end, m_pc);
		m_model.clear();
		for(DisassemblyListing.Line line : listing.getLines()) {
			m_model.addElement(line);
		}
		if(listing.pcLine() >= 0) {
			m_list.ensureIndexIsVisible(listing.pcLine());
			m_info.setText("PC at " + m_pc.toOctal()
				+ (listing.startAddress().val() == m_start.val()
					? ""
					: "  -  listing realigned to " + listing.startAddress().toOctal()
						+ ", because the PC is inside an instruction that starts earlier"));
		} else if(m_model.isEmpty()) {
			m_info.setText(m_context.getConnectionManager().isConnected()
				? "Nothing has been read from this range yet"
				: "Not connected, so there is nothing to disassemble");
		} else {
			m_info.setText(m_model.size() + " instructions from " + m_start.toOctal() + " to " + m_end.toOctal());
		}
	}

	// -------------------------------------------------------------------------------------
	// Following the machine
	// -------------------------------------------------------------------------------------

	/**
	 * Centre the listing on a new PC. Ported from {@code ShowNewPcAddr} ({@code :383-403}).
	 *
	 * <p>A null PC leaves the listing exactly where it is, which is the M9312 case: its console
	 * emulator cannot say where the PC is, and moving the display to nowhere would be worse than
	 * not moving it.</p>
	 */
	public void showPc(Address pc) {
		//-- Only when somebody is looking. Every stop would otherwise cost twenty-one examines
		//-- for a window that is not on the screen.
		showPc(pc, isShowing());
	}

	/**
	 * {@link #showPc(Address)} for a caller that knows whether the machine should be read,
	 * because {@link #isShowing()} cannot tell it.
	 *
	 * <p>Which is the case on the way in: {@code ToolWindow.showWindow} runs {@code onShowing()}
	 * - and so {@link #attach()} - <i>before</i> {@code setVisible(true)}, so a window being
	 * opened is not showing yet. Asking the component was how "catch up rather than waiting for
	 * the next stop" came to mean "show whatever was left over from last time": the flag was
	 * false on every single open.</p>
	 */
	public void showPc(Address pc, boolean examine) {
		if(pc == null)
			return;
		long before = 2L * WORDS_BEFORE_PC;
		long start = pc.val() < before ? 0 : pc.val() - before;
		setRange(Address.of(MemoryAddressType.VIRTUAL, start),
			Address.of(MemoryAddressType.VIRTUAL, start + 2L * (WORDS_SHOWN - 1)), pc, examine);
	}

	private final MachineState.Listener m_machineListener = state -> {
		if(state.getState() == MachineState.ExecutionState.STOPPED)
			showPc(state.getPc());
	};

	private final ConnectionManager.Listener m_connectionListener = (manager, state) -> AppContext.onUi(() -> {
		if(state != ConnectionManager.State.CONNECTED) {
			//-- Nothing read from the old machine can be trusted about the new one. Through the
			//-- accessor: a lambda in a field initializer may not read a blank final directly.
			getGroup().invalidate();
		}
		updateDisplay();
	});

	public void attach() {
		detach();
		m_context.getMachineState().addListener(m_machineListener);
		m_context.getConnectionManager().addListener(m_connectionListener);
		//-- Opened after the machine stopped, which is the ordinary case: catch up rather than
		//-- waiting for the next stop. Being attached is what "somebody is looking" means here -
		//-- the window is one statement away from visible - so the read is on if there is a
		//-- machine to read from.
		Address pc = m_context.getMachineState().getPc();
		if(pc != null)
			showPc(pc, m_context.getConnectionManager().isConnected());
		else
			updateDisplay();
	}

	public void detach() {
		m_context.getMachineState().removeListener(m_machineListener);
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	/** Give the group back when this window goes for good. */
	public void dispose() {
		detach();
		m_context.getMemoryCellGroups().removeGroup(m_group);
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	private Address parse(JTextField field) {
		try {
			return Address.parseOctal(field.getText().trim(), MemoryAddressType.VIRTUAL);
		} catch(RuntimeException x) {
			m_context.reportFailure("\"" + field.getText().trim() + "\" is not an octal address", null);
			return null;
		}
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	public JList<DisassemblyListing.Line> getList() {
		return m_list;
	}

	public MemoryCellGroup getGroup() {
		return m_group;
	}

	public String getInfoText() {
		return m_info.getText();
	}

	public JTextField getStartField() {
		return m_startAddr;
	}

	public JTextField getEndField() {
		return m_endAddr;
	}

	/** The listing as it is showing, one line per instruction. For tests. */
	public java.util.List<String> getShownLines() {
		java.util.List<String> l = new java.util.ArrayList<>();
		for(int i = 0; i < m_model.size(); i++) {
			l.add(m_model.get(i).toDisplayString());
		}
		return l;
	}

	/** The line the PC is on, marked the way the Pascal marks it: pink, per {@code AuxU.pas:47}. */
	private static final class LineRenderer extends DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean selected, boolean focused) {
			Component c = super.getListCellRendererComponent(list, value, index, selected, focused);
			if(value instanceof DisassemblyListing.Line line) {
				setText(line.toDisplayString());
				if(line.atPc()) {
					c.setBackground(UiColors.PC_BACKGROUND);
					c.setForeground(UiColors.PC_TEXT);
				}
			}
			return c;
		}
	}
}
