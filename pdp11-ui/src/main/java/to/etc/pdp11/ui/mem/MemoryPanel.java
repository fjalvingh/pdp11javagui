package to.etc.pdp11.ui.mem;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.ui.FieldStatus;
import to.etc.pdp11.ui.AppContext;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A window onto memory: a start address, a length, and an editable grid of words.
 *
 * <p>Ported from {@code TFormMemoryTable} ({@code FormMemoryTableU.pas}) with
 * {@link MemoryCellGroupTable} standing in for the frame it wraps. The buttons are here and the
 * grid is there, which is how the Pascal splits it too - seven of its forms use the same
 * frame.</p>
 *
 * <h2>Scrolling is a range shift, not a scrollbar</h2>
 *
 * <p>Memory is 4 MB and the machine is at the end of a serial line, so what is shown is a window
 * of at most {@link #MAX_BLOCK_SIZE} words that gets moved and re-examined. Moving it keeps the
 * values of every address that stays in range - {@code MemoryCellGroup.shiftRange} - so stepping
 * a row at a time reads one row from the machine rather than all of it.</p>
 */
public final class MemoryPanel extends JPanel {
	/** {@code max_memoryblocksize} ({@code FormMemoryTableU.pas:44}). */
	public static final int MAX_BLOCK_SIZE = 256;

	private static final int DEFAULT_BLOCK_SIZE = 64;

	private final AppContext m_context;

	private final MemoryCellGroupTable m_grid;

	private final MemoryCellGroup m_group;

	private final JTextField m_startAddr = new JTextField(9);

	private final JTextField m_blockSize = new JTextField(5);

	private final JLabel m_info = new JLabel();

	/** The status line, and where a mistyped address is reported. See {@link FieldStatus}. */
	private final FieldStatus m_status = new FieldStatus(m_info);

	//-- The controls that reach the machine. Fields, because they are switched off while there
	//-- is no machine to reach: the alternative is a modal "Not connected" dialog per click,
	//-- which is what every other data window already avoids.
	private final JButton m_examineAll = new JButton("Examine all");

	private final JButton m_examineOne = new JButton("Examine cell");

	private final JButton m_depositChanged = new JButton("Deposit changed");

	private final JButton m_depositAll = new JButton("Deposit all");

	/**
	 * A button, like everywhere else.
	 *
	 * <p>It was the one interesting thing in the grid's right-click menu - and the only
	 * right-click menu in the application, so the one surface nobody would think to look at. The
	 * Loader and the Assembler both put Verify on their toolbars; there is no reason this window
	 * should hide the same action behind a gesture its siblings do not use.</p>
	 */
	private final JButton m_verify = new JButton("Verify");

	public MemoryPanel(AppContext context, String instanceId) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;
		//-- A view of the machine that happens to be typeable, so it protects what has been typed
		//-- and otherwise tracks what the machine says. Every other grid shows a document.
		m_grid = new MemoryCellGroupTable(context, MemoryCellGroupTable.OverwritePolicy.FOLLOW_EDITS);

		//-- The group belongs to this window and lives as long as it does. It is registered with
		//-- the application's groups because that is what puts it on the propagation bus: a
		//-- deposit here has to show up in every other window looking at the same address.
		m_group = context.getMemoryCellGroups().addGroup(addressType(context), "Memory " + instanceId);
		m_group.setUsageTag("memory-view");
		m_group.add(0, DEFAULT_BLOCK_SIZE);
		m_grid.connectTo(m_group);
		m_grid.setOnUpdate(this::updateInfo);

		add(buildControls(), "growx, wrap");
		add(m_grid, "grow, wrap");
		add(m_info, "growx");

		m_startAddr.setText(Address.of(m_group.getType(), 0).toOctal());
		m_blockSize.setText(String.valueOf(DEFAULT_BLOCK_SIZE));
		installPopupMenu();
		updateButtons();
		updateInfo();
	}

	/** How wide the connected machine's addresses are, or the widest there is if none is. */
	private static MemoryAddressType addressType(AppContext context) {
		Console console = context.getConnectionManager().getConsole();
		return console == null ? MemoryAddressType.PHYSICAL22 : console.physicalAddressType();
	}

	private JPanel buildControls() {
		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]4[]4[]12[][]16[][]8[][]4[]", "[]"));
		bar.add(new JLabel("Start:"));
		bar.add(m_startAddr);
		JButton back = new JButton("<");
		back.setToolTipText("One row earlier");
		JButton forward = new JButton(">");
		forward.setToolTipText("One row later");
		bar.add(back);
		bar.add(forward);

		bar.add(new JLabel("Words:"));
		bar.add(m_blockSize);

		JButton set = new JButton("Show");
		bar.add(set);
		bar.add(m_examineAll);
		bar.add(m_examineOne);
		bar.add(m_depositChanged);
		bar.add(m_depositAll);
		bar.add(m_verify);

		set.addActionListener(e -> applyRange(true));
		back.addActionListener(e -> step(-1));
		forward.addActionListener(e -> step(1));
		//-- Show, < and > stay live while disconnected: they move the range and re-lay the grid,
		//-- which is worth doing with no machine attached. Only the four that talk to one go dead.
		m_examineAll.addActionListener(e -> m_grid.examineAll(false, owner()));
		m_examineOne.addActionListener(e -> m_grid.examineCell(m_grid.getSelectedCell()));
		m_depositChanged.addActionListener(e -> m_grid.depositAll(true, owner()));
		m_depositAll.addActionListener(e -> m_grid.depositAll(false, owner()));
		m_verify.addActionListener(e -> verify());
		m_verify.setToolTipText("Read it all back without touching the edits; "
			+ "anything the machine disagrees with shows as changed");
		//-- Enter in either field is the same as pressing Show, which is what makes typing an
		//-- address feel like typing an address.
		m_startAddr.addActionListener(e -> applyRange(true));
		m_blockSize.addActionListener(e -> applyRange(true));
		return bar;
	}

	/** The popup {@code FrameMemoryCellGroupGridU} carries ({@code :64-71}). */
	private void installPopupMenu() {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem clear = new JMenuItem("Clear data");
		clear.addActionListener(e -> m_grid.clearData());
		JMenuItem fill = new JMenuItem("Fill data with address");
		fill.addActionListener(e -> m_grid.fillWithAddress());
		JMenuItem export = new JMenuItem("Export as SimH DO script ...");
		export.addActionListener(e -> exportSimhScript());
		menu.add(clear);
		menu.add(fill);
		menu.addSeparator();
		menu.add(export);

		m_grid.getTable().addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e) {
				//-- Which button raises a popup differs per platform, and isPopupTrigger is the
				//-- only thing that knows; the Pascal simply tests for the right button.
				if(e.isPopupTrigger())
					menu.show(e.getComponent(), e.getX(), e.getY());
			}
		});
	}

	// -------------------------------------------------------------------------------------
	// The range
	// -------------------------------------------------------------------------------------

	/**
	 * Move the shown range to what the fields say, and read what is not known yet.
	 *
	 * <p>Ported from {@code SetStartAddrButtonClick} ({@code :161-183}), including the clamp to
	 * {@link #MAX_BLOCK_SIZE}.</p>
	 *
	 * <p><b>Divergence: this examines and the Pascal's Show button does not.</b> There the
	 * examine is commented out with a reason - "auf M9312 console emulator führt jede nicht
	 * vorhandene Adresse zum stop", every nonexistent address stops that console emulator - and
	 * yet the {@code &lt;} and {@code &gt;} buttons two methods later examine unknown cells
	 * anyway ({@code :211}), so the hazard is only half avoided and the cost is a memory window
	 * that shows nothing until a second button is pressed. Examining the unknown cells only is
	 * what makes the difference bearable either way: a range that has already been read costs
	 * nothing to show again. <b>Revisit when the M9301/M9312 console lands</b> (deferred from
	 * phase 4) - that console wants this to be a deliberate action, and the right answer then is
	 * to ask the console rather than to ask the window.</p>
	 */
	private void applyRange(boolean examine) {
		Address start;
		try {
			start = Address.parseOctal(m_startAddr.getText().trim(), m_group.getType());
		} catch(RuntimeException x) {
			showFieldError("\"" + m_startAddr.getText().trim() + "\" is not an octal address");
			return;
		}
		int words;
		try {
			//-- Decimal, and the status line two rows below has always said so. A word count is
			//-- neither an address nor a value in memory - it is a count, like the ones the
			//-- memory test, the loader and the scanner report - and octal here meant the
			//-- default of 64 words showed as "100" in an unlabelled field while the line under
			//-- it read "64 words from ...".
			words = Integer.parseInt(m_blockSize.getText().trim());
		} catch(RuntimeException x) {
			showFieldError("\"" + m_blockSize.getText().trim() + "\" is not a word count");
			return;
		}
		words = Math.max(1, Math.min(MAX_BLOCK_SIZE, words));

		m_group.shiftRange(start, words, true);
		m_blockSize.setText(String.valueOf(words));
		m_startAddr.setText(start.toOctal());
		m_grid.rebuild();
		if(examine && m_context.getConnectionManager().isConnected())
			m_grid.examineAll(true, owner());
	}

	/**
	 * Move one grid row earlier or later. {@code IncDecAddrButtonClick} ({@code :188-215}).
	 *
	 * <p>A row, not a page: this is how you walk through a listing, and shifting by a row keeps
	 * everything already read and needs one row's worth of round trips.</p>
	 */
	private void step(int rows) {
		long delta = 2L * MemoryCellGroupTable.DEFAULT_COLUMNS * rows;
		long now = m_group.getRange().empty() ? 0 : m_group.getRange().lo();
		long next = now + delta;
		if(next < 0)
			next = 0;
		if(next > m_group.getType().getMaxAddress())
			return;
		m_startAddr.setText(Address.of(m_group.getType(), next).toOctal());
		applyRange(true);
	}

	/** Read it all back without touching the edits, so disagreements light up. */
	private void verify() {
		m_grid.verifyAll(owner(), null);
	}

	private void exportSimhScript() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Export as a SimH DO script");
		chooser.setSelectedFile(new java.io.File("memory.sim"));
		if(chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		Path file = chooser.getSelectedFile().toPath();
		try {
			Files.writeString(file, m_grid.toSimhScript(), StandardCharsets.US_ASCII);
			m_context.getLogger().log(to.etc.pdp11.core.util.LogChannel.OTHER, "Wrote " + file);
		} catch(IOException x) {
			m_context.reportFailure("Could not write " + file, x);
		}
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	/**
	 * Switch the machine-touching controls off while there is nothing to touch.
	 *
	 * <p>The grey-out is the application's one answer to "you cannot do that yet": Loader,
	 * Dumper, Memory Test, Scanner, MMU, SimH Console, Assembler and Execution all do this, and
	 * this window used to be one of three that instead let the click through to a modal
	 * "Not connected to a machine" dialog.</p>
	 */
	private void updateButtons() {
		boolean connected = m_context.getConnectionManager().isConnected();
		m_examineAll.setEnabled(connected);
		m_examineOne.setEnabled(connected);
		m_depositChanged.setEnabled(connected);
		m_depositAll.setEnabled(connected);
		m_verify.setEnabled(connected);
	}

	/** Something typed into one of the two fields cannot be used. Inline, not in a dialog. */
	private void showFieldError(String message) {
		m_status.error(message);
	}

	private void updateInfo() {
		int edited = m_grid.getEditedCells().size();
		m_status.setText(m_group.isEmpty()
			? "Nothing to show"
			: m_group.size() + " words from " + Address.of(m_group.getType(), m_group.getRange().lo()).toOctal()
				+ " to " + Address.of(m_group.getType(), m_group.getRange().hi()).toOctal()
				+ (edited == 0 ? "" : "  -  " + edited + " changed and not deposited"));
	}

	/**
	 * Re-express the range at the connected machine's address width.
	 *
	 * <p>A group built while nothing was connected is 22-bit, and connecting to a 16-bit ODT
	 * makes every address in it wrong by the I/O page offset. The Pascal has
	 * {@code ChangeAdddressWidth} for exactly this and calls it when the console type changes.</p>
	 */
	public void onConnectionChanged() {
		MemoryAddressType type = addressType(m_context);
		if(type != m_group.getType()) {
			long lo = m_group.getRange().empty() ? 0 : m_group.getRange().lo();
			Address start = Address.of(m_group.getType(), lo);
			//-- Convert if it can be converted; an address that does not exist on the smaller
			//-- machine simply starts again at zero rather than refusing to open.
			m_group.shiftRange(start.fitsWidth(type) ? start.withWidth(type) : Address.of(type, 0),
				m_group.size(), true);
			m_startAddr.setText(Address.of(type, m_group.getRange().empty() ? 0 : m_group.getRange().lo()).toOctal());
			m_grid.rebuild();
		}
		updateButtons();
		updateInfo();
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	public MemoryCellGroupTable getGrid() {
		return m_grid;
	}

	public MemoryCellGroup getGroup() {
		return m_group;
	}

	public String getInfoText() {
		return m_info.getText();
	}

	public JTextField getStartAddressField() {
		return m_startAddr;
	}

	public JTextField getBlockSizeField() {
		return m_blockSize;
	}

	/** The cell shown at a grid position, for a test that wants to know what is where. */
	public MemoryCell cellAt(int row, int column) {
		return m_grid.cellAt(row, column);
	}

	/** The controls that need a machine, for a test that wants to know whether they are live. */
	public List<AbstractButton> getMachineControls() {
		return List.of(m_examineAll, m_examineOne, m_depositChanged, m_depositAll, m_verify);
	}

	private final ConnectionManager.Listener m_connectionListener =
		(manager, state) -> AppContext.onUi(() -> {
			onConnectionChanged();
			//-- A machine arriving is a reason to read it, the same way opening the window is.
			if(state == ConnectionManager.State.CONNECTED)
				examineIfConnected();
		});

	public void attach() {
		detach();
		m_context.getConnectionManager().addListener(m_connectionListener);
		onConnectionChanged();
		examineIfConnected();
	}

	/**
	 * Read what is not known yet when this window is shown, and again when a machine arrives.
	 *
	 * <p>Unknown cells only, which is what makes showing a window cheap: a range that has already
	 * been read costs nothing to show again. Use Examine all to re-read what is already there.</p>
	 */
	public void examineIfConnected() {
		if(m_context.getConnectionManager().isConnected() && !m_group.isEmpty())
			m_grid.examineAll(true, owner());
	}

	public void detach() {
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	/** Give the group back when this window goes for good. */
	public void dispose() {
		detach();
		m_context.getMemoryCellGroups().removeGroup(m_group);
	}
}
