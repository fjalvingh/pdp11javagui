package to.etc.pdp11.ui.mmu;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.conn.TransportConfig;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mmu.CpuMode;
import to.etc.pdp11.core.mmu.Pdp11Mmu;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.awt.Rectangle;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

	/**
	 * Wait for whatever the window queued on the command thread, then flush the event thread.
	 *
	 * <p>Opening a data window against a live machine reads it. So a test that pokes values into
	 * the register cells has to let that read finish first, or the examine lands afterwards and
	 * replaces them with what the simulated machine actually holds - which is exactly the
	 * behaviour being relied on, arriving at the wrong moment. The command executor is single
	 * threaded and FIFO, so a job queued behind the examine runs after it.</p>
	 */
	private static void settle(AppContext ctx) throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		if(ctx.onConsole("settle", console -> done.countDown()))
			assertTrue(done.await(30, TimeUnit.SECONDS), "the command thread did not drain");
		Edt.run(() -> {
		});
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

	/**
	 * One auto-read policy, where there used to be three. This window read nothing at all: it
	 * opened showing a map built from eight page-register pairs nobody had examined - drawn as a
	 * map, with a button beside it - which is a different answer to the same question the
	 * Register Group and Memory windows were answering two other ways.
	 */
	@Test
	void showingTheWindowReadsTheRegisters(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MemoryCell first = mmu(ctx).getRegisterGroup().getCells().get(0);
			assertFalse(first.getPdpValue().isKnown(), "nothing has been read yet");

			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			settle(ctx);

			assertTrue(first.getPdpValue().isKnown(), "the window read the machine on the way in");
		} finally {
			ctx.getConnectionManager().close();
		}
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
			settle(ctx);
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
			settle(ctx);
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
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			settle(ctx);
			//-- PSW bits 15..14 are the mode: 11 is user. Set after the window's own read of the
			//-- machine, so these are the values it ends up showing.
			set(ctx, "PSW", 0140000);
			set(ctx, "MMR0", 1);
			set(ctx, "UIPAR0", 020000);
			set(ctx, "UIPDR0", 077406);
			set(ctx, "KIPAR0", 04000);
			set(ctx, "KIPDR0", 077406);
			Edt.run(() -> panel.getModeSelector().setSelectedItem(mmu(ctx).getCpuMode()));

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
			settle(ctx);
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

	/**
	 * A page register whose value carries the translation past the top of the bus.
	 *
	 * <p>The MMU window is 65536 translations per redraw, so a page register that made one of
	 * them throw did not cost a row: it aborted the redraw between the line that enables the
	 * Refresh button and the line that sets the status, and left the window showing whatever it
	 * had said last - "Not connected to a machine", beside an enabled button that worked - with
	 * the stack trace on stderr where nobody looks.</p>
	 */
	@Test
	void aPageRegisterThatCarriesPastTheTopOfTheBusDoesNotTakeTheWindowOut(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			settle(ctx);
			set(ctx, "MMR0", 1);
			set(ctx, "KIPDR0", 077406);                     // a full 8 KB page
			set(ctx, "KIPAR0", 0177777);                    // and a PAF of all ones over it
			Edt.run(() -> {
			});

			//-- The hardware's adder is as wide as the bus and drops the carry, so the top of
			//-- this page is at the top of physical memory and the rest wraps to the bottom.
			assertTrue(Edt.call(() -> panel.getInstructionTable().getRowCount()) > 0, "there is a map");
			assertTrue(Edt.call(panel::getStatusText).contains("data space disabled"),
				Edt.call(panel::getStatusText));
			assertTrue(Edt.call(() -> panel.getRefreshButton().isEnabled()));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * A connection that is still being made is not a machine to show.
	 *
	 * <p>Opening this window while one is being made used to show a full memory map - of an MMU
	 * whose registers nothing had ever answered about - with the Refresh button greyed out beside
	 * it, because the tables asked "is there an MMU" and the button asked "are we connected". It
	 * is one question now.</p>
	 *
	 * <p>The gap that made that visible has since been closed at the other end too: an attempt
	 * builds its console into locals and publishes it only once the handshake has succeeded, so
	 * {@code getConsole()} no longer answers with a console that has never spoken to anything.
	 * The window is opened here while the state is CONNECTING, which is that same moment.</p>
	 */
	@Test
	void aConnectionStillBeingMadeIsNotAMachineToShow(@TempDir Path dir) throws Exception {
		//-- A server that accepts and then says nothing: the console handshake waits on it, and
		//-- the connection sits between "console built" and "machine answered" meanwhile.
		try(ServerSocket mute = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
			AppContext ctx = TestContext.create(dir);
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Thread worker = new Thread(() -> {
				try {
					ctx.getConnectionManager().connect(new ConnectionProfile("mute", ConsoleProtocol.SIMH,
						TransportConfig.telnet(mute.getInetAddress().getHostAddress(), mute.getLocalPort())));
				} catch(Exception x) {
					//-- It never answers, so this ends in a failure whenever it gets round to it.
				}
			}, "mute-connect");
			worker.setDaemon(true);
			worker.start();
			try {
				until("the attempt to start", () -> ctx.getConnectionManager().getState()
					== ConnectionManager.State.CONNECTING);
				//-- The window is opened right then.
				Edt.run(panel::attach);
				assertEquals("Not connected to a machine", Edt.call(panel::getStatusText));
				assertFalse(Edt.call(() -> panel.getRefreshButton().isEnabled()));
				assertEquals(0, Edt.call(() -> panel.getInstructionTable().getRowCount()));
			} finally {
				Edt.run(panel::detach);
				ctx.getConnectionManager().close();
			}
		}
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
			settle(ctx);
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

	/**
	 * The map keeps following the registers over many changes, not just the first.
	 *
	 * <p>The redraws are coalesced: the MMU fires its change listener once per register, on the
	 * command thread, and the panel queues one pass on the event thread for the ninety-nine of
	 * them a bulk examine produces. The flag that does the coalescing is set on the command
	 * thread and cleared on the event thread, so it has to be an {@code AtomicBoolean} - a plain
	 * one leaves the command thread free to go on seeing a stale {@code true} for ever, and the
	 * window then silently stops redrawing. That is a memory-model bug and no test can be made
	 * to fail on it reliably; what this does check is the behaviour it destroys, over enough
	 * rounds to catch a flag that is left set.</p>
	 */
	@Test
	void everyRoundOfRegisterChangesReachesTheTable(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir);
		try {
			MmuPanel panel = Edt.call(() -> new MmuPanel(ctx));
			Edt.run(panel::attach);
			deposit(ctx, 0177572, 1);                       // relocation on

			//-- Move kernel I page 0 somewhere else, three times, reading it back each round.
			for(int round = 1; round <= 3; round++) {
				int par = round * 04000;
				deposit(ctx, 0172300, 077406);
				deposit(ctx, 0172340, par);
				Edt.run(() -> panel.getRefreshButton().doClick());
				String want = String.format("%08o", par * 0100L);
				until("round " + round + " to reach the table",
					() -> cell(panel.getInstructionTable(), 0, 2).startsWith(want));
			}
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
			settle(ctx);
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
