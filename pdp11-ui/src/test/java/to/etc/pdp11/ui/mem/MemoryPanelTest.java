package to.etc.pdp11.ui.mem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.AbstractButton;
import javax.swing.JTable;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memory view, without a display: what the grid shows, what editing does to it, and what
 * moving the range does.
 *
 * <p>All of it on a {@code JPanel}, which needs no screen - see {@link UiRenderer}. Examining
 * and depositing against a real console are covered in {@code ConnectionManagerTest}; what is
 * worth checking here is everything that happens between a cell value and a pixel.</p>
 */
class MemoryPanelTest {
	private static final int WIDTH = 900;

	private static final int HEIGHT = 460;

	private static final long TIMEOUT_MS = 30_000;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static void until(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean())
				return;
			try {
				Thread.sleep(20);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(x);
			}
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	private static boolean allEnabled(MemoryPanel panel) {
		return panel.getMachineControls().stream().allMatch(AbstractButton::isEnabled);
	}

	/**
	 * Examine, Deposit and Verify are dead with nothing connected, and live again once there is.
	 *
	 * <p>This window used to be one of the three that never called {@code setEnabled} at all, so
	 * clicking Examine offline produced a modal "Not connected to a machine" dialog where the
	 * same gesture in the Loader or the Scanner is simply a dead button.</p>
	 */
	@Test
	void theMachineControlsFollowTheConnection(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel panel = Edt.call(() -> new MemoryPanel(ctx, "1"));
		for(AbstractButton b : panel.getMachineControls())
			assertFalse(b.isEnabled(), b.getText() + " needs a machine");
		//-- Show, < and > are not machine controls and stay usable offline.
		assertTrue(panel.getStartAddressField().isEnabled());

		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			until("the buttons to arm", () -> allEnabled(panel));
			ctx.getConnectionManager().disconnect();
			until("the buttons to go dead again", () -> !allEnabled(panel));
		} finally {
			ctx.getConnectionManager().close();
			Edt.run(panel::detach);
		}
	}

	private static MemoryPanel panel(Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel panel = new MemoryPanel(ctx, "1");
		UiRenderer.layOut(panel, WIDTH, HEIGHT);
		return panel;
	}

	@Test
	void itShowsAGridOfWordsWithAddressesDownTheSide(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		JTable table = panel.getGrid().getTable();

		assertEquals(9, table.getColumnCount(), "one address column and eight words");
		assertEquals(8, table.getRowCount(), "64 words at 8 a row");
		assertEquals("start \\ offset", table.getColumnName(0));
		assertEquals("+0", table.getColumnName(1));
		assertEquals("+16", table.getColumnName(8), "the offsets are octal, so the eighth is +16");

		//-- Row addresses go up by 8 words = 020 bytes a row, and are shown at the group's width.
		assertEquals("00000000", table.getValueAt(0, 0));
		assertEquals("00000020", table.getValueAt(1, 0));
	}

	@Test
	void aWordNobodyHasReadSaysSoRatherThanShowingAZero(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		//-- The Pascal shows 177777 here, which is a real value and is not what is in memory.
		assertEquals("?", panel.getGrid().getTable().getValueAt(0, 1));
	}

	@Test
	void typingAValueMarksTheCellChangedAndStopsOtherWindowsOverwritingIt(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		MemoryCell cell = panel.cellAt(0, 1);
		assertNotNull(cell);
		cell.setPdpValue(CellValue.of(0));
		panel.getGrid().refresh();
		assertTrue(panel.getGroup().isPdpOverwritesEdit(), "nothing typed yet, so it follows the machine");

		panel.getGrid().getTable().setValueAt("123456", 0, 1);

		assertEquals(0123456, cell.getEditValue().word());
		assertTrue(cell.isEdited());
		assertEquals(UiColors.EDITED_BACKGROUND, panel.getGrid().backgroundOf(0, 1),
			"a value typed and not deposited is the changed colour");
		assertFalse(panel.getGroup().isPdpOverwritesEdit(),
			"and while it is uncommitted, another window's examine must not clobber it");
	}

	/**
	 * The bug the Pascal frame's own comment describes ({@code FrameMemoryCellGroupGridU.pas:38-48}),
	 * and which the Pascal's plain memory window still has because it leaves the flag at true.
	 */
	@Test
	void anotherWindowExaminingTheSameAddressDoesNotEatAnUncommittedEdit(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel panel = new MemoryPanel(ctx, "1");
		UiRenderer.layOut(panel, WIDTH, HEIGHT);
		MemoryCell mine = panel.cellAt(0, 1);
		panel.getGrid().getTable().setValueAt("777", 0, 1);

		//-- Some other window reads the same address and the value propagates.
		MemoryCellGroups groups = ctx.getMemoryCellGroups();
		MemoryCellGroup other = groups.addGroup(panel.getGroup().getType(), "somebody else");
		MemoryCell theirs = other.add(0);
		theirs.setPdpValue(CellValue.of(0111111));
		groups.syncMemoryCells(theirs);

		assertEquals(0777, mine.getEditValue().word(), "what the user typed is still there");
	}

	@Test
	void movingTheRangeKeepsWhatIsStillInIt(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		for(MemoryCell mc : panel.getGroup().getCells()) {
			mc.setPdpValue(CellValue.of(042));
		}
		//-- One row later: 8 words move out at the front, unread ones arrive at the back.
		panel.getBlockSizeField().setText("100");
		panel.getStartAddressField().setText("20");
		panel.getStartAddressField().postActionEvent();

		assertEquals(020, panel.getGroup().getRange().lo());
		assertEquals(0100, panel.getGroup().size(), "the word count is octal, like everything else");
		assertEquals(042, panel.getGroup().cell(0).getPdpValue().word(), "address 20 was already read");
		assertFalse(panel.getGroup().cell(070).getPdpValue().isKnown(), "the new tail has not been");
	}

	@Test
	void aRangeLongerThanTheMaximumIsClamped(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		panel.getBlockSizeField().setText("7777");
		panel.getBlockSizeField().postActionEvent();
		assertEquals(MemoryPanel.MAX_BLOCK_SIZE, panel.getGroup().size());
	}

	@Test
	void nonsenseInTheAddressFieldIsRefusedRatherThanGuessedAt(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		long before = panel.getGroup().getRange().lo();
		panel.getStartAddressField().setText("banana");
		panel.getStartAddressField().postActionEvent();
		assertEquals(before, panel.getGroup().getRange().lo());
	}

	@Test
	void aGapInTheAddressesShowsAsAGapInTheGrid(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroupTable grid = new MemoryCellGroupTable(ctx);
		MemoryCellGroup group = ctx.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "sparse");
		//-- What a group assembled from a MACRO-11 listing looks like: two islands of code.
		group.add(01000, 2);
		group.add(01100, 2);
		grid.connectTo(group);
		UiRenderer.layOut(grid, 700, 300);

		assertNotNull(grid.cellAt(0, 1), "01000 is there");
		assertNull(grid.cellAt(0, 3), "01004 is not");
		assertEquals(01100, grid.cellAt(4, 1).getAddr().val(), "and the second island lands where its address says");
	}

	@Test
	void theGridGetsTheSpaceAndTheControlsStayOneLineEach(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		Rectangle grid = panel.getGrid().getBounds();
		assertTrue(grid.height > HEIGHT / 2, "the grid is what the window is for: " + grid);
		assertTrue(grid.width > WIDTH - 40, "and it uses the width: " + grid);
	}

	@Test
	void theSimhScriptIsDepositCommandsForWhatIsShown(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		panel.getBlockSizeField().setText("2");
		panel.getStartAddressField().setText("1000");
		panel.getStartAddressField().postActionEvent();
		panel.getGrid().getTable().setValueAt("000777", 0, 1);
		panel.getGrid().getTable().setValueAt("000123", 0, 2);

		assertEquals("d 00001000 000777\nd 00001002 000123\n", panel.getGrid().toSimhScript());
	}

	@Test
	void fillWithAddressWritesEachWordsOwnWordAddress(@TempDir Path dir) {
		MemoryPanel panel = panel(dir);
		panel.getGrid().fillWithAddress();
		//-- Address 4 is word 2. A wrong value in a memory test then says where it came from.
		assertEquals(2, panel.cellAt(0, 3).getEditValue().word());
	}

	@Test
	void connectingToANarrowerMachineReExpressesTheAddresses(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel panel = new MemoryPanel(ctx, "1");
		assertEquals(MemoryAddressType.PHYSICAL22, panel.getGroup().getType(),
			"22 bits until something says otherwise");

		//-- Point it at the switch register on a 22-bit machine, then connect a 16-bit ODT.
		panel.getBlockSizeField().setText("1");
		panel.getStartAddressField().setText(Address.of(MemoryAddressType.PHYSICAL22, 017777570).toOctal());
		panel.getStartAddressField().postActionEvent();

		ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_16));
		try {
			Edt.run(panel::onConnectionChanged);
			assertEquals(MemoryAddressType.PHYSICAL16, panel.getGroup().getType());
			assertEquals(0177570, panel.getGroup().getRange().lo(),
				"the same register, spelled the way a 16-bit machine spells it");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel panel = new MemoryPanel(ctx, "1");
		int v = 010000;
		for(MemoryCell mc : panel.getGroup().getCells()) {
			mc.setPdpValue(CellValue.of(v));
			mc.setEditValue(CellValue.of(v));
			v += 011;
		}
		panel.getGrid().refresh();
		panel.getGrid().getTable().setValueAt("007777", 1, 3);
		Path file = UiRenderer.renderToFile(panel, WIDTH, HEIGHT, Path.of("target", "ui-render", "memory-panel.png"));
		assertTrue(Files.size(file) > 0);
	}
}
