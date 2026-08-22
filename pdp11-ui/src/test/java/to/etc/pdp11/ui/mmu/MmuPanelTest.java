package to.etc.pdp11.ui.mmu;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mmu.CpuMode;
import to.etc.pdp11.core.mmu.Pdp11Mmu;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.awt.Rectangle;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MMU window against a simulated machine, with no display.
 *
 * <p>{@code MmuMemoryMapTest} covers what the map says; this covers what the window does with it
 * - which mode it starts on, that changing the selector changes the tables, that the two spaces
 * are shown separately, and that it says why when there is nothing to show.</p>
 */
class MmuPanelTest {
	private static final int WIDTH = 760;

	private static final int HEIGHT = 460;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static AppContext connected(Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		//-- An 11/44 has memory management, which is what makes this window worth opening.
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
		return ctx;
	}

	private static Pdp11Mmu mmu(AppContext ctx) {
		return ctx.getConnectionManager().getConsole().getMmu();
	}

	/** Set an MMU register the way a machine would have, and let the MMU recompute. */
	private static void set(AppContext ctx, String name, int value) {
		for(MemoryCell mc : mmu(ctx).getRegisterGroup().getCells()) {
			if(name.equals(mc.getName())) {
				mc.setPdpValue(CellValue.of(value));
				ctx.getMemoryCellGroups().syncMemoryCells(mc);
				mmu(ctx).evalAll();
				return;
			}
		}
		throw new IllegalArgumentException("No MMU register called " + name);
	}

	private static String cell(javax.swing.JTable table, int row, int column) {
		return Edt.call(() -> String.valueOf(table.getValueAt(row, column)));
	}

	@Test
	void anUnmappedMachineShowsMemoryAndTheIoPage(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);

			assertEquals(2, Edt.call(() -> panel.getInstructionTable().getRowCount()));
			assertEquals("000000 .. 157776", cell(panel.getInstructionTable(), 0, 1));
			//-- Physical addresses are eight octal digits on a 22-bit machine, and the same
			//-- memory: this is what "virtual is physical" looks like written out.
			assertEquals("00000000 .. 00157776", cell(panel.getInstructionTable(), 0, 2));
			assertTrue(cell(panel.getInstructionTable(), 1, 3).contains("I/O page"),
				cell(panel.getInstructionTable(), 1, 3));
			assertTrue(Edt.call(panel::getStatusText).contains("Relocation disabled"),
				Edt.call(panel::getStatusText));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aRelocatedPageShowsWhereItWentAndTheDataTabSaysDataSpaceIsOff(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			set(ctx, "MMR0", 1);
			set(ctx, "KIPAR0", 04000);
			set(ctx, "KIPDR0", 077406);
			//-- The MMU's change listener is what redraws, and it coalesces onto the event
			//-- thread; this is that pass.
			Edt.run(() -> {
			});

			assertEquals("00400000 .. 00417776", cell(panel.getInstructionTable(), 0, 2));
			assertTrue(cell(panel.getInstructionTable(), 0, 3).contains("8 KB"),
				cell(panel.getInstructionTable(), 0, 3));
			//-- D space is off in this machine, so the data map is the instruction map and the
			//-- tab says so rather than showing a duplicate nobody can explain.
			assertEquals("Data space (off)", Edt.call(() -> panel.getTabs().getTitleAt(1)));
			assertEquals(cell(panel.getInstructionTable(), 0, 2), cell(panel.getDataTable(), 0, 2));
			assertTrue(Edt.call(panel::getStatusText).contains("data space disabled"),
				Edt.call(panel::getStatusText));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void withDataSpaceOnTheTwoTablesDiffer(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			set(ctx, "MMR0", 1);
			set(ctx, "MMR3", 07);
			set(ctx, "KIPAR0", 04000);
			set(ctx, "KIPDR0", 077406);
			set(ctx, "KDPAR0", 010000);
			set(ctx, "KDPDR0", 077406);
			Edt.run(() -> {
			});

			assertEquals("Data space", Edt.call(() -> panel.getTabs().getTitleAt(1)));
			assertEquals("00400000 .. 00417776", cell(panel.getInstructionTable(), 0, 2));
			assertEquals("01000000 .. 01017776", cell(panel.getDataTable(), 0, 2));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void itStartsOnTheModeTheMachineIsInAndAnyModeCanBeLookedAt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			//-- PSW bits 15..14 are the mode: 11 is user.
			set(ctx, "PSW", 0140000);
			set(ctx, "MMR0", 1);
			set(ctx, "UIPAR0", 020000);
			set(ctx, "UIPDR0", 077406);
			set(ctx, "KIPAR0", 04000);
			set(ctx, "KIPDR0", 077406);

			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			assertEquals(CpuMode.USER, Edt.call(() -> panel.getModeSelector().getSelectedItem()),
				"the mode the machine is in");
			assertTrue(Edt.call(panel::getCurrentModeText).contains("the mode the machine is in"),
				Edt.call(panel::getCurrentModeText));
			assertEquals("02000000 .. 02017776", cell(panel.getInstructionTable(), 0, 2));

			//-- The whole reason for the selector: the kernel map, while the machine is in user
			//-- mode. The Pascal can only show the current one.
			Edt.run(() -> panel.getModeSelector().setSelectedItem(CpuMode.KERNEL));
			assertEquals("00400000 .. 00417776", cell(panel.getInstructionTable(), 0, 2));
			assertTrue(Edt.call(panel::getCurrentModeText).contains("machine is in User"),
				Edt.call(panel::getCurrentModeText));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aPageLengthErrorIsNamedRatherThanCalledUnassigned(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			set(ctx, "MMR0", 1);
			set(ctx, "KIPAR0", 04000);
			set(ctx, "KIPDR0", 06);                         // one 64-byte block long
			Edt.run(() -> {
			});

			assertEquals("000000 .. 000076", cell(panel.getInstructionTable(), 0, 1));
			assertEquals("page length error", cell(panel.getInstructionTable(), 1, 2));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void withNothingConnectedItIsEmptyAndSaysWhy(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
		Edt.run(panel::attach);
		assertEquals(0, Edt.call(() -> panel.getInstructionTable().getRowCount()));
		assertFalse(Edt.call(() -> panel.getRefreshButton().isEnabled()));
		assertEquals("Not connected to a machine", Edt.call(panel::getStatusText));
	}

	@Test
	void itFollowsTheMachineItIsConnectedToAcrossAReconnect(@TempDir Path dir) throws Exception {
		//-- Every connection builds its own MMU, so a window that kept a reference to the first
		//-- one would quietly show a machine that is no longer there.
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
			Edt.run(() -> {
			});
			set(ctx, "MMR0", 1);
			set(ctx, "KIPAR0", 04000);
			set(ctx, "KIPDR0", 077406);
			Edt.run(() -> {
			});
			assertEquals("00400000 .. 00417776", cell(panel.getInstructionTable(), 0, 2),
				"the new machine's map");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * The button, end to end: deposit page registers into a machine, press Refresh, and the map
	 * on screen is the one the machine holds.
	 *
	 * <p>This is the path {@code ExamineMMU} takes and the reason it has two steps. Propagation
	 * excludes the cell it started from, so examining the MMU's own register group notifies the
	 * MMU of nothing; without the {@code evalAll()} that follows, this window would show the
	 * state from before the button was pressed and look like it had done nothing.</p>
	 */
	@Test
	void refreshReadsTheRegistersOutOfTheMachine(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			//-- Written into the simulated machine, not into the MMU: the window has to go and
			//-- fetch them. KIPAR0 is 0172340, KIPDR0 0172300, MMR0 0177572.
			deposit(ctx, 0172340, 04000);
			deposit(ctx, 0172300, 077406);
			deposit(ctx, 0177572, 1);

			Edt.run(() -> panel.getRefreshButton().doClick());
			until("the map to come back from the machine",
				() -> cell(panel.getInstructionTable(), 0, 2).startsWith("00400000"));
			assertEquals("00400000 .. 00417776", cell(panel.getInstructionTable(), 0, 2));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/** Write one I/O page register into the machine, the way any window does. */
	private static void deposit(AppContext ctx, int addr16, int value) {
		ctx.onConsole("deposit", console -> console.deposit(
			to.etc.pdp11.core.addr.Address.of(to.etc.pdp11.core.addr.MemoryAddressType.PHYSICAL16, addr16)
				.withWidth(to.etc.pdp11.core.addr.MemoryAddressType.PHYSICAL22), value));
	}

	/** Wait for something the command thread will get round to. */
	private static void until(String what, java.util.function.BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + 10_000;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean())
				return;
			try {
				Thread.sleep(10);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(x);
			}
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	@Test
	void theTablesGetTheRoomAndTheControlsOneRow(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			Edt.run(() -> UiRenderer.layOut(panel, WIDTH, HEIGHT));

			Rectangle tabs = Edt.call(() -> panel.getTabs().getBounds());
			assertTrue(tabs.height > HEIGHT / 2, "the tables get the room: " + tabs);
			assertTrue(tabs.y > 0 && tabs.y < 80, "the controls are one row: " + tabs);
			assertTrue(tabs.x + tabs.width <= WIDTH, "and it stays inside the panel");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			set(ctx, "MMR0", 1);
			set(ctx, "KIPAR0", 04000);
			set(ctx, "KIPDR0", 077406);
			set(ctx, "KIPAR1", 04200);
			set(ctx, "KIPDR1", 077406);
			set(ctx, "KIPAR6", 01000);
			set(ctx, "KIPDR6", (0176 << 8) | 010 | 06);      // a stack page, expanding downward
			Edt.run(() -> {
			});
			Path file = Edt.call(() -> UiRenderer.renderToFile(panel, WIDTH, HEIGHT,
				Path.of("target", "ui-render", "mmu-panel.png")));
			assertTrue(java.nio.file.Files.size(file) > 0);
		} finally {
			ctx.getConnectionManager().close();
		}
	}
}
