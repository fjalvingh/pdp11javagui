package to.etc.pdp11.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.SharedMemory;
import to.etc.pdp11.common.memfile.MemoryFileFormat;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.ui.disas.DisassemblerPanel;
import to.etc.pdp11.ui.load.MemoryLoaderPanel;
import to.etc.pdp11.ui.mem.MemoryPanel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows over shared memory: what one of them loads, the others show, before anything has been
 * deposited. PLAN.md §1, "Shared memory".
 */
class SharedMemoryWindowsTest {
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

	/** Load {@code text} into a new Loader window and wait for it to be read. */
	private static MemoryLoaderPanel load(AppContext ctx, Path dir, String text) throws Exception {
		Path file = dir.resolve("program.txt");
		Files.writeString(file, text, StandardCharsets.US_ASCII);
		MemoryLoaderPanel loader = Edt.call(() -> new MemoryLoaderPanel(ctx));
		Edt.run(() -> {
			loader.getFormatCombo().setSelectedItem(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE);
			loader.getFileField(0).setText(file.toString());
			loader.getLoadButton().doClick();
		});
		until("the load to finish", () -> Edt.call(() -> loader.getLoadButton().isEnabled()));
		return loader;
	}

	/** The request this exists for: a program loaded from a file can be read before it is deposited. */
	@Test
	void aLoadedProgramIsDisassembledWithoutBeingDeposited(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		load(ctx, dir, "001000: 012701 000200 000240\n");             // mov #200,r1; nop

		DisassemblerPanel disas = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> {
			disas.attach();
			disas.getStartField().setText("1000");
			disas.getStartField().postActionEvent();
		});

		List<String> lines = Edt.call(disas::getShownLines);
		assertTrue(lines.get(0).contains("mov") && lines.get(0).contains("#000200,r1"), lines.toString());
		assertTrue(lines.get(1).contains("nop"), lines.toString());
		assertTrue(Edt.call(() -> disas.getList().getModel().getElementAt(0).pending()),
			"and it says the machine does not hold it");
	}

	@Test
	void aMemoryWindowShowsWhatTheLoaderLoadedAsPending(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel memory = Edt.call(() -> new MemoryPanel(ctx, "1"));
		UiRenderer.layOut(memory, 900, 400);
		Edt.run(() -> {
			memory.attach();
			memory.getStartAddressField().setText("1000");
			memory.getStartAddressField().postActionEvent();
		});

		load(ctx, dir, "001000: 012701 000200\n");

		MemoryCell shown = Edt.call(() -> memory.cellAt(0, 1));
		assertEquals(Address.of(memory.getGroup().getType(), 01000), shown.getAddr());
		assertEquals(012701, shown.getEditValue().word());
		assertTrue(shown.isEdited(), "pending in this window too, because it is one word");
	}

	@Test
	void depositingFromTheMemoryWindowDepositsTheLoadersEdits(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			MemoryLoaderPanel loader = load(ctx, dir, "001000: 012701\n");
			MemoryPanel memory = Edt.call(() -> new MemoryPanel(ctx, "1"));
			UiRenderer.layOut(memory, 900, 400);
			Edt.run(() -> {
				memory.getStartAddressField().setText("1000");
				memory.getStartAddressField().postActionEvent();
			});

			Edt.run(() -> memory.getGrid().depositAll(true, null));
			until("the deposit to finish", () -> !loader.getGroup().cell(0).isEdited());

			var m = ctx.getConnectionManager();
			CellValue there = m.getConnection().call(() -> m.getConsole().examine(
				Address.of(m.getConsole().physicalAddressType(), 01000)));
			assertEquals(012701, there.word());
			assertEquals(0, ctx.getMemoryCellGroups().getSharedMemory().getPendingCount());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/** Running the machine makes what was read suspect, and showing a window reads it again. */
	@Test
	void aRunMakesValuesStaleAndTheNextLookRereadsThem(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			MemoryPanel memory = Edt.call(() -> new MemoryPanel(ctx, "1"));
			UiRenderer.layOut(memory, 900, 400);
			Edt.run(() -> {
				memory.getStartAddressField().setText("1000");
				memory.getStartAddressField().postActionEvent();
			});
			MemoryCell cell = Edt.call(() -> memory.cellAt(0, 1));
			Edt.run(() -> memory.getGrid().examineAll(true, null));
			until("the read", cell::isMachineValueCurrent);

			ctx.getMachineState().running();
			assertTrue(cell.isStale());

			Edt.run(() -> memory.getGrid().examineAll(true, null));
			until("the reread", cell::isMachineValueCurrent);
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * Going away makes what was read stale and keeps it; a new machine forgets it. What was loaded
	 * and not deposited survives both, because it is what should be there and not a fact about a
	 * machine.
	 */
	@Test
	void aConnectionComingAndGoing(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		SharedMemory shared = ctx.getMemoryCellGroups().getSharedMemory();
		try {
			var m = ctx.getConnectionManager();
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			MemoryLoaderPanel loader = load(ctx, dir, "001000: 000111\n");
			MemoryCell loaded = loader.getGroup().cell(0);
			MemoryPanel memory = Edt.call(() -> new MemoryPanel(ctx, "1"));
			UiRenderer.layOut(memory, 900, 400);
			Edt.run(() -> {
				memory.getStartAddressField().setText("1002");
				memory.getStartAddressField().postActionEvent();
			});
			MemoryCell read = Edt.call(() -> memory.cellAt(0, 1));
			Edt.run(() -> memory.getGrid().examineAll(true, null));
			until("the read", read::isMachineValueCurrent);

			m.disconnect();
			assertTrue(read.isStale(), "still the last thing the machine said");
			assertTrue(loaded.isEdited());

			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			assertFalse(read.getPdpValue().isKnown(), "nothing is known about this machine yet");
			assertEquals(0111, loaded.getEditValue().word());
			assertEquals(1, shared.getPendingCount());
		} finally {
			ctx.getConnectionManager().close();
		}
	}
}
