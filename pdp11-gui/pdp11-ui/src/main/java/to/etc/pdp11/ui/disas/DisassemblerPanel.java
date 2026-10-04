package to.etc.pdp11.ui.disas;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.common.disas.DataMarks;
import to.etc.pdp11.common.disas.DisassemblyListing;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.ui.FieldStatus;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.ProgressDialog;
import to.etc.pdp11.ui.UiColors;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 *
 * <h2>Data</h2>
 *
 * <p>Every word decodes as something, so which words are tables and strings is the user's to say:
 * select lines, or click one word in the raw column to select just that word, and the context
 * menu marks them as words, bytes, strings or code again. The marks are {@link DataMarks} on the
 * {@link AppContext}, so they last the session, are shared by every Disassembler window and go
 * with "Forget all".</p>
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

	private final DataMarks m_marks;

	private final LineRenderer m_renderer = new LineRenderer();

	/**
	 * The one word picked out of a line by clicking it in the raw column, or -1. When there is
	 * one, the context menu marks that word rather than the selected lines.
	 */
	private int m_selectedWord = -1;

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
		m_marks = context.getDataMarks();

		//-- Virtual addresses: an instruction stream only means anything in the 64 KB a program
		//-- can see, whatever the physical machine is.
		m_group = context.getMemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "Disassembly");
		m_group.setUsageTag("disassembler");
		//-- Code is never edited here, so nothing needs protecting from incoming values. What it
		//-- shows is what should be in memory: what the machine holds, or what was loaded or
		//-- assembled over it and not deposited yet, which is marked as such.
		m_group.setPdpOverwritesEdit(true);
		m_group.shiftRange(m_start, pageWords(m_start, LINES_PER_PAGE), false);
		m_end = endOf(m_start, pageWords(m_start, LINES_PER_PAGE));

		m_list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, m_list.getFont().getSize()));
		m_list.setCellRenderer(m_renderer);
		m_list.addMouseListener(new MarkMouse());
		m_list.addListSelectionListener(e -> {
			//-- Moving the selection anywhere else - the keyboard, a shift-click - is a selection
			//-- of lines again.
			if(m_selectedWord >= 0 && !selectionIsJustTheLineOf(m_selectedWord)) {
				m_selectedWord = -1;
				m_list.repaint();
			}
		});
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
				listing = DisassemblyListing.of(group, start, endOf(start, words), pc, want, m_marks);
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
			int used = (int) ((listing.nextAddress().val() - start.val() + 1) / 2);
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
		DisassemblyListing listing = DisassemblyListing.of(m_group, m_start, m_end, m_pc, m_maxLines, m_marks);
		//-- Decoding again - after marking, typically - must not lose what was selected: the lines
		//-- over the same words are selected again, so a format can be changed straight away.
		Set<Integer> selectedWords = new HashSet<>();
		for(DisassemblyListing.Line line : m_list.getSelectedValuesList()) {
			for(int w = line.firstWord(); w <= line.lastWord(); w += 2) {
				selectedWords.add(w);
			}
		}
		int word = m_selectedWord;
		m_model.clear();
		List<Integer> reselect = new ArrayList<>();
		for(DisassemblyListing.Line line : listing.getLines()) {
			if(selectedWords.contains(line.firstWord()) || selectedWords.contains(line.lastWord()))
				reselect.add(m_model.size());
			m_model.addElement(line);
		}
		m_list.setSelectedIndices(reselect.stream().mapToInt(Integer::intValue).toArray());
		int wordLine = word < 0 ? -1 : lineShowing(word);
		if(wordLine >= 0) {
			m_list.setSelectedIndex(wordLine);
			m_selectedWord = word;
		} else {
			m_selectedWord = -1;
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
		//-- What a machine going or coming means for what was read from it is shared memory's
		//-- business, and ConnectionManager sees to it for every window at once.
		updateDisplay();
	});

	/**
	 * Something was loaded, typed, deposited or discarded somewhere in shared memory - perhaps
	 * in this range. Decoding a page is cheap, so it is simply done again, once per burst.
	 */
	private final java.util.concurrent.atomic.AtomicBoolean m_redecodeQueued = new java.util.concurrent.atomic.AtomicBoolean();

	private final Runnable m_pendingListener = () -> {
		if(m_redecodeQueued.compareAndSet(false, true)) {
			AppContext.onUi(() -> {
				m_redecodeQueued.set(false);
				updateDisplay();
			});
		}
	};

	public void attach() {
		detach();
		m_marks.addListener(m_pendingListener);
		m_context.getMemoryCellGroups().getSharedMemory().addChangeListener(m_pendingListener);
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
		m_marks.removeListener(m_pendingListener);
		m_context.getMemoryCellGroups().getSharedMemory().removeChangeListener(m_pendingListener);
		m_context.getMachineState().removeListener(m_machineListener);
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	/** Give the group back when this window goes for good. */
	public void dispose() {
		detach();
		m_context.getMemoryCellGroups().removeGroup(m_group);
	}

	// -------------------------------------------------------------------------------------
	// Marking data
	// -------------------------------------------------------------------------------------

	/** The menu items, in order: what a word marked this way is, and how the menu says it. */
	private static final DataMarks.Format[] MENU_FORMATS = {null, DataMarks.Format.WORDS, DataMarks.Format.BYTES,
		DataMarks.Format.ASCIZ};

	private static String menuText(DataMarks.Format format) {
		if(format == null)
			return "Code";
		return switch(format) {
			case WORDS -> "Words (.WORD)";
			case BYTES -> "Bytes (.BYTE)";
			case ASCIZ -> "Strings (.ASCIZ)";
		};
	}

	/**
	 * The words the context menu would mark, as inclusive {@code [first, last]} ranges: the one
	 * word picked out, or every selected line's.
	 */
	private List<int[]> markTargets() {
		List<int[]> ranges = new ArrayList<>();
		if(m_selectedWord >= 0) {
			ranges.add(new int[]{m_selectedWord, m_selectedWord});
			return ranges;
		}
		for(DisassemblyListing.Line line : m_list.getSelectedValuesList()) {
			ranges.add(new int[]{line.firstWord(), line.lastWord()});
		}
		return ranges;
	}

	/** Mark what is selected - the word picked out, or the selected lines - as {@code format}, or as code for null. */
	public void markSelected(DataMarks.Format format) {
		for(int[] r : markTargets()) {
			m_marks.mark(r[0], r[1], format);
		}
	}

	/**
	 * The context menu for what is selected, or null when nothing is. The format everything
	 * selected already has is ticked, so it shows what is there as well as what can be.
	 */
	JPopupMenu buildMarkMenu() {
		List<int[]> targets = markTargets();
		if(targets.isEmpty())
			return null;
		Set<DataMarks.Format> current = new HashSet<>();
		boolean anyCode = false;
		int words = 0;
		for(int[] r : targets) {
			for(int w = r[0]; w <= r[1]; w += 2) {
				DataMarks.Format f = m_marks.formatAt(w);
				if(f == null)
					anyCode = true;
				else
					current.add(f);
				words++;
			}
		}
		JPopupMenu menu = new JPopupMenu();
		String what = m_selectedWord >= 0
			? "Word " + Address.of(MemoryAddressType.VIRTUAL, m_selectedWord).toOctal()
			: words + (words == 1 ? " word" : " words") + " from "
				+ Address.of(MemoryAddressType.VIRTUAL, targets.get(0)[0]).toOctal();
		JMenuItem title = new JMenuItem("Mark " + what + " as");
		title.setEnabled(false);
		menu.add(title);
		menu.addSeparator();
		for(DataMarks.Format format : MENU_FORMATS) {
			JCheckBoxMenuItem item = new JCheckBoxMenuItem(menuText(format));
			boolean uniform = format == null ? current.isEmpty() : !anyCode && current.size() == 1 && current.contains(format);
			item.setSelected(uniform);
			item.addActionListener(e -> markSelected(format));
			menu.add(item);
		}
		return menu;
	}

	/** Which line shows this word in its raw column, or -1. */
	private int lineShowing(int word) {
		for(int i = 0; i < m_model.size(); i++) {
			if(m_model.get(i).columnsOfWord(word) != null)
				return i;
		}
		return -1;
	}

	private boolean selectionIsJustTheLineOf(int word) {
		int[] sel = m_list.getSelectedIndices();
		return sel.length == 1 && m_model.get(sel[0]).columnsOfWord(word) != null;
	}

	/**
	 * Pick out one word of a line, or none with -1: what clicking it in the raw column does. The
	 * line it is on becomes the selection.
	 */
	public void selectWord(int word) {
		int line = word < 0 ? -1 : lineShowing(word);
		if(line < 0) {
			m_selectedWord = -1;
		} else {
			m_list.setSelectedIndex(line);
			m_selectedWord = word;
		}
		m_list.repaint();
	}

	/** The word picked out of a line, or -1 when it is lines that are selected. */
	public int getSelectedWord() {
		return m_selectedWord;
	}

	/**
	 * A press on a word in the raw column picks out that word; a press anywhere else is an
	 * ordinary line selection. A right-click outside the selection selects what it is on first,
	 * so the menu is about what was clicked; inside it, the selection stays as it is.
	 */
	private final class MarkMouse extends MouseAdapter {
		@Override
		public void mousePressed(MouseEvent e) {
			int index = indexAt(e.getPoint());
			if(index >= 0) {
				boolean right = SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger();
				if(!right || !m_list.isSelectedIndex(index)) {
					int word = e.isShiftDown() || e.isControlDown() ? -1 : wordAt(index, e.getPoint());
					if(word >= 0) {
						selectWord(word);
					} else {
						if(right)
							m_list.setSelectedIndex(index);
						if(m_selectedWord >= 0) {
							m_selectedWord = -1;
							m_list.repaint();
						}
					}
				}
			}
			popup(e);
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			popup(e);
		}

		private void popup(MouseEvent e) {
			if(!e.isPopupTrigger())
				return;
			JPopupMenu menu = buildMarkMenu();
			if(menu != null)
				menu.show(m_list, e.getX(), e.getY());
		}

		private int indexAt(Point p) {
			int index = m_list.locationToIndex(p);
			if(index < 0)
				return -1;
			Rectangle bounds = m_list.getCellBounds(index, index);
			return bounds != null && bounds.contains(p) ? index : -1;
		}

		private int wordAt(int index, Point p) {
			Rectangle bounds = m_list.getCellBounds(index, index);
			int column = m_renderer.columnAt(m_list, p.x - bounds.x);
			return column < 0 ? -1 : m_model.get(index).wordAtColumn(column);
		}
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

	/**
	 * The line the PC is on, marked the way the Pascal marks it: pink, per {@code AuxU.pas:47};
	 * data in its own colour; and the word picked out of a line, boxed.
	 */
	private final class LineRenderer extends DefaultListCellRenderer {
		/** The word to box on the line being painted, as columns {@code [first, end)}, or null. */
		private int[] m_box;

		/** Which column of the text this x, measured from the cell's left edge, is in; -1 before the text. */
		int columnAt(JList<?> list, int x) {
			getListCellRendererComponent(list, null, -1, false, false);
			int left = getInsets().left;
			int width = getFontMetrics(list.getFont()).charWidth('0');
			return x < left || width <= 0 ? -1 : (x - left) / width;
		}

		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean selected, boolean focused) {
			Component c = super.getListCellRendererComponent(list, value, index, selected, focused);
			m_box = null;
			if(value instanceof DisassemblyListing.Line line) {
				setText(line.toDisplayString());
				if(m_selectedWord >= 0)
					m_box = line.columnsOfWord(m_selectedWord);
				if(line.atPc()) {
					c.setBackground(UiColors.PC_BACKGROUND);
					c.setForeground(UiColors.PC_TEXT);
				} else if(line.pending() && !selected) {
					//-- Code that is loaded or assembled and not deposited: what the machine
					//-- would run is something else.
					c.setBackground(UiColors.EDITED_BACKGROUND);
					c.setForeground(UiColors.EDITED_TEXT);
				} else if(line.format() != null && !selected) {
					c.setForeground(UiColors.DATA_TEXT);
				}
			}
			return c;
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			if(m_box == null)
				return;
			Insets in = getInsets();
			int width = getFontMetrics(getFont()).charWidth('0');
			int x = in.left + m_box[0] * width - 1;
			int w = (m_box[1] - m_box[0]) * width + 1;
			g.setColor(UiColors.SELECTED_WORD);
			g.fillRect(x, 0, w, getHeight() - 1);
			g.setColor(UiColors.SELECTED_WORD_OUTLINE);
			g.drawRect(x, 0, w, getHeight() - 1);
		}
	}
}
