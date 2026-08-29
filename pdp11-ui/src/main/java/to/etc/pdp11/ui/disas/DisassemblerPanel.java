package to.etc.pdp11.ui.disas;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.disas.DisassemblyListing;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.FieldStatus;
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

	/**
	 * How many instructions a page of the listing is.
	 *
	 * <p>The window used to be told a range - "from here to there" - and a range of addresses is
	 * not what anybody reading code wants: an instruction is one, two or three words, so an end
	 * address is a guess at how much of the program it covers. It asks for a number of
	 * instructions instead, and {@code >} asks for the next lot.</p>
	 */
	private static final int LINES_PER_PAGE = 100;

	/** How far {@code <} steps back, in bytes. */
	private static final int BACK_STEP_BYTES = 32;

	/** No PDP-11 instruction is longer than three words, so this is the most a page can need. */
	private static final int MAX_WORDS_PER_LINE = 3;

	/** The top of the 64 KB a program can see; a listing cannot run past it. */
	private static final long LAST_WORD = 0177776;

	private final AppContext m_context;

	private final MemoryCellGroup m_group;

	private final JTextField m_startAddr = new JTextField(8);

	private final JCheckBox m_useCache = new JCheckBox("Use cached values", true);

	private final JButton m_back = new JButton("<");

	private final JButton m_forward = new JButton(">");

	private final JButton m_show = new JButton("Show");

	private final DefaultListModel<DisassemblyListing.Line> m_model = new DefaultListModel<>();

	private final JList<DisassemblyListing.Line> m_list = new JList<>(m_model);

	private final JLabel m_info = new JLabel();

	/** The status line, and where a mistyped address is reported. See {@link FieldStatus}. */
	private final FieldStatus m_status = new FieldStatus(m_info);

	private Address m_start = Address.of(MemoryAddressType.VIRTUAL, 0);

	private Address m_end = Address.of(MemoryAddressType.VIRTUAL, 2L * (WORDS_SHOWN - 1));

	/**
	 * How many lines the listing showing now was asked for.
	 *
	 * <p>Which is not the same as how many it has: a page runs out early where the machine has
	 * not been read. {@code >} asks for what is on the screen plus another page, so a short page
	 * grows rather than stopping the listing for good.</p>
	 */
	private int m_maxLines = LINES_PER_PAGE;

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
		m_group.shiftRange(m_start, pageWords(m_start, LINES_PER_PAGE), false);
		m_end = endOf(m_start, pageWords(m_start, LINES_PER_PAGE));

		m_list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_list.getFont().getSize()));
		m_list.setCellRenderer(new LineRenderer());
		add(buildControls(), "growx, wrap");
		add(new JScrollPane(m_list), "grow, wrap");
		add(m_info, "growx");

		m_startAddr.setText(m_start.toOctal());
		updateDisplay();
	}

	private JPanel buildControls() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]8[]4[]12[]16[]", "[]"));
		bar.add(new JLabel("From:"));
		bar.add(m_startAddr);
		m_back.setToolTipText("Start " + BACK_STEP_BYTES + " bytes earlier and list again - where an instruction"
			+ " begins is a guess, and this is how you correct one");
		m_forward.setToolTipText("List the next " + LINES_PER_PAGE
			+ " instructions, carrying on from where this listing left off");
		bar.add(m_back);
		bar.add(m_forward);
		bar.add(m_show);
		bar.add(m_useCache);

		m_show.addActionListener(e -> setRangeFromFields());
		m_back.addActionListener(e -> stepBack());
		m_forward.addActionListener(e -> showNextPage());
		m_startAddr.addActionListener(e -> setRangeFromFields());
		return bar;
	}

	// -------------------------------------------------------------------------------------
	// The range
	// -------------------------------------------------------------------------------------

	private void setRangeFromFields() {
		Address start = parse(m_startAddr);
		if(start == null)
			return;
		showPage(start, LINES_PER_PAGE, 0);
	}

	/**
	 * {@code <}: back up {@value #BACK_STEP_BYTES} bytes and list again from there.
	 *
	 * <p>Not a page backwards, and deliberately not: where an instruction begins cannot be known
	 * from an address, so a listing that starts a little earlier decodes the same bytes
	 * differently and this is the only way to correct a page that started on the wrong word. The
	 * listing is thrown away and built again from the new start - continuing the old one would
	 * keep the boundaries that were wrong.</p>
	 */
	private void stepBack() {
		long start = Math.max(0, m_start.val() - BACK_STEP_BYTES);
		showPage(Address.of(MemoryAddressType.VIRTUAL, start), LINES_PER_PAGE, 0);
	}

	/**
	 * {@code >}: another {@value #LINES_PER_PAGE} instructions, after the ones already showing.
	 *
	 * <p>The new lines are added to the listing rather than replacing it, and they begin where it
	 * left off - which is why the whole thing is decoded again from the same start rather than
	 * decoded afresh from the last address. Restarting there would re-guess the boundaries, and a
	 * page break is not a reason for the same bytes to become different instructions.</p>
	 */
	private void showNextPage() {
		showPage(m_start, m_model.size() + LINES_PER_PAGE, m_model.size());
	}

	/**
	 * List {@code lines} instructions from {@code start}, and scroll to line {@code scrollTo}.
	 *
	 * <p>The user moved the listing, so the PC marker goes: the Pascal clears {@code CodeAddr}
	 * every time the range is moved by hand ({@code :404, 417, 428}), and a marker that drags the
	 * listing out from under somebody reading it is worse than no marker.</p>
	 */
	private void showPage(Address start, int lines, int scrollTo) {
		m_start = start;
		m_maxLines = lines;
		m_pc = null;
		m_startAddr.setText(m_start.toOctal());
		fillAndShow(scrollTo);
	}

	/**
	 * Grow the range until it holds the instructions that were asked for, reading as it goes.
	 *
	 * <p>A page is a number of instructions and the machine is read in words, and the two cannot
	 * be converted without decoding: an instruction is one, two or three words. Reading three
	 * words per line outright would read twice what a page needs, which over a serial line is the
	 * difference between a window that answers and one that does not - so it reads a page's worth
	 * of words, decodes, and asks for as many more as the lines it is still short of. Each turn
	 * re-reads nothing, because everything it already has was read moments ago in this same
	 * operation.</p>
	 */
	private void fillAndShow(int scrollTo) {
		Address start = m_start;
		Address pc = m_pc;
		int want = m_maxLines;
		boolean cached = m_useCache.isSelected();
		if(!m_context.getConnectionManager().isConnected()) {
			//-- Nothing to read from, so there is nothing to grow towards. Size the range to what
			//-- a page can need at worst and show whatever is already in it.
			m_group.shiftRange(start, pageWords(start, want), cached);
			m_end = endOf(start, pageWords(start, want));
			updateDisplay(scrollTo);
			return;
		}
		MemoryCellGroup group = m_group;
		ProgressDialog progress = new ProgressDialog(owner());
		m_context.onConsole("Reading code", console -> {
			int cap = pageWords(start, want);
			int words = Math.min(want, cap);
			boolean reuse = cached;
			boolean tailRead = false;
			DisassemblyListing listing;
			for(;;) {
				//-- optimize, always: the words read on the previous turn are inside the new range
				//-- and must survive it. Whether they are read again is the examine's business,
				//-- just below.
				group.shiftRange(start, words, true);
				console.examine(group, reuse, progress);
				//-- Anything still missing after the first turn was missing from the machine, not
				//-- from the cache: re-reading what this loop just read would be reading the same
				//-- words twice for one listing.
				reuse = true;
				listing = DisassemblyListing.of(group, start, endOf(start, words), pc, want);
				int got = listing.getLines().size();
				if(got >= want) {
					//-- The page is full, but its last instruction may be one the end of the range
					//-- cut in half: its operand word has not been read, and the decoder will not
					//-- invent one - it shows the bare word instead. So the last line of a page
					//-- would be wrong whenever the break fell inside an instruction. Two more
					//-- words is the most any instruction can still be short of.
					if(tailRead || words >= cap)
						break;
					tailRead = true;
					words = Math.min(cap, words + MAX_WORDS_PER_LINE - 1);
					continue;
				}
				if(words >= cap)
					break;
				words = Math.min(cap, words + (want - got));
				tailRead = false;
			}
			//-- The range as the listing actually used it, rather than the word or two over that
			//-- was read to settle the last line. This is what "where the page ends" means, and
			//-- both the status line and the next page quote it.
			int used = (int) ((listing.nextAddress().val() - start.val()) / 2);
			Address end = endOf(start, Math.min(words, used));
			AppContext.onUi(() -> {
				m_end = end;
				updateDisplay(scrollTo);
			});
		});
	}

	/** The last address of a range of {@code words} words starting at {@code start}. */
	private static Address endOf(Address start, int words) {
		return Address.of(MemoryAddressType.VIRTUAL, start.val() + 2L * (Math.max(1, words) - 1));
	}

	/**
	 * The most words a page of {@code lines} instructions can need, from {@code start}.
	 *
	 * <p>Three words each, or whatever is left below the top of the address space - a listing
	 * cannot run off the end of the 64 KB a program can see.</p>
	 */
	private static int pageWords(Address start, int lines) {
		int available = (int) ((LAST_WORD - start.val()) / 2) + 1;
		return Math.min(lines * MAX_WORDS_PER_LINE, available);
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
		m_maxLines = LINES_PER_PAGE;
		m_startAddr.setText(m_start.toOctal());

		int words = (int) ((m_end.val() - m_start.val()) / 2) + 1;
		m_group.shiftRange(m_start, words, m_useCache.isSelected());
		if(examine && m_context.getConnectionManager().isConnected()) {
			examineAndShow();
		} else {
			updateDisplay();
		}
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
		updateDisplay(0);
	}

	/**
	 * The same, scrolling to a line - which for {@code >} is the first of the ones just added.
	 *
	 * <p>A page appended to the bottom of a listing that is already a hundred lines long is off
	 * the screen, and a button that appears to do nothing is worse than one that is slow.</p>
	 */
	private void updateDisplay(int scrollTo) {
		DisassemblyListing listing = DisassemblyListing.of(m_group, m_start, m_end, m_pc, m_maxLines);
		m_model.clear();
		for(DisassemblyListing.Line line : listing.getLines()) {
			m_model.addElement(line);
		}
		if(listing.pcLine() >= 0) {
			m_list.ensureIndexIsVisible(listing.pcLine());
			m_status.setText("PC at " + m_pc.toOctal()
				+ (listing.startAddress().val() == m_start.val()
					? ""
					: "  -  listing realigned to " + listing.startAddress().toOctal()
						+ ", because the PC is inside an instruction that starts earlier"));
		} else if(m_model.isEmpty()) {
			m_status.setText(m_context.getConnectionManager().isConnected()
				? "Nothing has been read from this range yet"
				: "Not connected, so there is nothing to disassemble");
		} else {
			if(scrollTo > 0 && scrollTo < m_model.size())
				m_list.ensureIndexIsVisible(scrollTo);
			m_status.setText(m_model.size() + " instructions from " + m_start.toOctal()
				+ " to " + listing.getLines().get(m_model.size() - 1).address().toOctal()
				+ "  -  the next page starts at " + listing.nextAddress().toOctal());
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
			m_status.error("\"" + field.getText().trim() + "\" is not an octal address");
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

	public JButton getShowButton() {
		return m_show;
	}

	/** {@code <} - back {@value #BACK_STEP_BYTES} bytes and list again. */
	public JButton getBackButton() {
		return m_back;
	}

	/** {@code >} - the next {@value #LINES_PER_PAGE} instructions, added to what is showing. */
	public JButton getForwardButton() {
		return m_forward;
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
