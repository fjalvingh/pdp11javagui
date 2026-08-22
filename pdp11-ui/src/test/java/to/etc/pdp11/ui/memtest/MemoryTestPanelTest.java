package to.etc.pdp11.ui.memtest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.memtest.ChipSize;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Memory Test window: the range, the buttons, and running a test against a machine.
 *
 * <p>The tests themselves are checked in {@code MemoryTesterTest}, against memory broken on
 * purpose. What matters here is the window's own rules - that nothing can be run before a range
 * has been set, that editing the range invalidates it again, and that a run reaches the log.</p>
 */
class MemoryTestPanelTest {
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

	@Test
	void nothingCanBeRunUntilARangeIsSet(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		UiRenderer.layOut(panel, 900, 480);

		assertFalse(panel.getDataLinesButton().isEnabled());
		assertFalse(panel.getRandomButton().isEnabled());
		//-- It offers all of memory below the I/O page, which is the most that could be tested.
		assertEquals("00000000", panel.getStartField().getText());
		assertEquals("17757776", panel.getEndField().getText(), "everything below the 22-bit I/O page");
	}

	@Test
	void settingTheRangeBuildsTheCellsAndArmsTheTests(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Edt.run(() -> {
				panel.getStartField().setText("1000");
				panel.getEndField().setText("1176");
				panel.getSetButton().doClick();
			});

			assertEquals(01000, panel.getGroup().getRange().lo());
			assertEquals(01176, panel.getGroup().getRange().hi());
			assertEquals(64, panel.getGroup().size());
			assertTrue(panel.getDataLinesButton().isEnabled());
			assertTrue(panel.getLog().getText().contains("Range set"), panel.getLog().getText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aRangeTheWrongWayRoundIsSwappedAndAnOddAddressIsMadeEven(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("1177");
			panel.getEndField().setText("1001");
			panel.getSetButton().doClick();
		});
		//-- Both made even, then swapped: 01176..01000 becomes 01000..01176.
		assertEquals(01000, panel.getGroup().getRange().lo());
		assertEquals(01176, panel.getGroup().getRange().hi());
	}

	@Test
	void aRangeRunningIntoTheIoPageIsCutShort(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("17757770");
			panel.getEndField().setText("17777776");
			panel.getSetButton().doClick();
		});
		//-- Device registers are not memory, and writing test patterns into them would do
		//-- something rather than nothing.
		assertEquals(MemoryAddressType.PHYSICAL22.getIopageBase() - 2, panel.getGroup().getRange().hi());
	}

	@Test
	void editingTheRangeInvalidatesIt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Edt.run(() -> {
				panel.getStartField().setText("1000");
				panel.getEndField().setText("1176");
				panel.getSetButton().doClick();
			});
			assertTrue(panel.getDataLinesButton().isEnabled());

			//-- The range on screen is no longer the range that was set, however it got that way.
			Edt.run(() -> panel.getStartField().setText("2000"));
			assertFalse(panel.getDataLinesButton().isEnabled(), "the buttons wait for Set again");

			//-- And setting it again re-arms them, without the panel's own writes to the fields
			//-- counting as edits.
			Edt.run(() -> panel.getSetButton().doClick());
			assertTrue(panel.getDataLinesButton().isEnabled());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aTestRunsAgainstTheMachineAndReachesTheLog(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Edt.run(() -> {
				panel.getStartField().setText("1000");
				panel.getEndField().setText("1076");
				panel.getChipSizeCombo().setSelectedItem(ChipSize.WORD);
				panel.getSetButton().doClick();
			});
			Edt.run(() -> panel.getRandomButton().doClick());

			until("the test to finish", () -> panel.getStatusText().contains("Test of random"));
			assertTrue(panel.getStatusText().contains("passed"), panel.getStatusText());
			assertTrue(panel.getLog().getText().contains("Random test"), panel.getLog().getText());
			assertTrue(panel.getLog().getText().contains("OK."), panel.getLog().getText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aBrokenMachineIsReportedAsBroken(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			//-- Bit 8 tied high, which is what the data line test exists to find.
			ctx.getConnectionManager().setSimulatedRunMode(false);
			breakTheMemory(ctx);
			Edt.run(() -> {
				panel.getStartField().setText("1000");
				panel.getEndField().setText("1076");
				panel.getChipSizeCombo().setSelectedItem(ChipSize.WORD);
				panel.getSetButton().doClick();
			});
			Edt.run(() -> panel.getDataLinesButton().doClick());

			until("the test to finish", () -> panel.getStatusText().contains("Test of data lines"));
			assertTrue(panel.getStatusText().contains("data line looks dead"), panel.getStatusText());
			assertTrue(panel.getLog().getText().contains("permanent \"high\""), panel.getLog().getText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/** Reach the simulated machine behind the transport and tie a data line high. */
	private static void breakTheMemory(AppContext ctx) {
		var transport = ctx.getConnectionManager().getConnection().getTransport();
		((to.etc.pdp11.core.io.FakeTransport) transport).getFake().setStuckDataLines(1 << 8, 0);
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryTestPanel panel = Edt.call(() -> new MemoryTestPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("1000");
			panel.getEndField().setText("17776");
			panel.getSetButton().doClick();
			panel.getLog().append("[2: 13:41:07] Test data lines, addr range = 1000..17776, chip size = 10000\n");
			panel.getLog().append("[3: 13:41:07] Testing moving ones at first addr of memory chips ...\n");
			panel.getLog().append("[4: 13:41:08] Data lines not OK.\n");
			panel.getLog().append("[5: 13:41:08] Lines detected as permanent \"high\": 000400,  bits= 8\n");
			panel.getLog().append("[6: 13:41:08] Lines detected as permanent \"low\" : 000000, bits = \n");
		});
		Path file = UiRenderer.renderToFile(panel, 900, 420, Path.of("target", "ui-render", "memory-test.png"));
		assertTrue(Files.size(file) > 0);
	}
}
