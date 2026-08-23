package to.etc.pdp11.ui.load;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.memfile.MemoryDumper;
import to.etc.pdp11.core.memfile.MemoryFileFormat;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;
import to.etc.pdp11.ui.exec.ExecutionPanel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Memory Loader window: read a program out of a file and put it into a machine.
 *
 * <p>The file formats are checked in {@code MemoryFileLoaderTest}, round-tripped through the
 * dumper. What matters here is the window: that loading fills the grid without touching the
 * machine, that depositing then writes what was loaded, and that a format carrying an entry
 * address tells the execution window - which is a different window and does not know this one
 * exists.</p>
 */
class MemoryLoaderPanelTest {
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

	/**
	 * Wait for a load to finish: the file is read off the event thread, so the click that starts
	 * one returns before it has happened (FABLE-ISSUES #62). The Load button coming back is what
	 * says it is over, whether it worked or not - and a load that was refused before it started
	 * never disabled it, so this returns at once for those.
	 */
	private static void untilLoaded(MemoryLoaderPanel panel) {
		until("the load to finish", () -> Edt.call(() -> panel.getLoadButton().isEnabled()));
	}

	/** A text listing, which is the format that is easiest to write by hand. */
	private static Path textFile(Path dir, String content) throws Exception {
		Path f = dir.resolve("program.txt");
		Files.writeString(f, content, StandardCharsets.US_ASCII);
		return f;
	}

	@Test
	void theStartAddressIsOnlyOfferedForAFormatThatNeedsIt(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		UiRenderer.layOut(panel, 980, 520);

		//-- A byte stream is just bytes; it has to be told where to go.
		Edt.run(() -> panel.getFormatCombo().setSelectedItem(MemoryFileFormat.BYTE_STREAM));
		assertTrue(panel.getStartField().isVisible());
		assertFalse(panel.getEntryField().isVisible());

		//-- A paper tape image says where every word goes, so a start address would be ignored -
		//-- and a field that is ignored should not be offered.
		Edt.run(() -> panel.getFormatCombo().setSelectedItem(MemoryFileFormat.ABSOLUTE_PAPERTAPE));
		assertFalse(panel.getStartField().isVisible());
		assertTrue(panel.getEntryField().isVisible());
	}

	@Test
	void loadingFillsTheGridAndTouchesNothingElse(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		Path file = textFile(dir, "001000: 012701 000200 000000\n");

		Edt.run(() -> {
			panel.getFormatCombo().setSelectedItem(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE);
			panel.getFileField(0).setText(file.toString());
			panel.getLoadButton().doClick();
		});
		untilLoaded(panel);

		assertEquals(3, panel.getGroup().size());
		assertEquals(012701, panel.getGroup().cell(0).getEditValue().word());
		//-- Every word shows as changed, because the machine has not been told any of this.
		assertTrue(panel.getGroup().cell(0).isEdited());
		assertFalse(panel.getGroup().cell(0).getPdpValue().isKnown());
		assertTrue(panel.getStatusText().contains("Nothing has been written to the machine yet"),
			panel.getStatusText());
	}

	@Test
	void depositingWritesWhatWasLoadedIntoTheMachine(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Path file = textFile(dir, "001000: 012701 000200\n");
			Edt.run(() -> {
				panel.getFormatCombo().setSelectedItem(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE);
				panel.getFileField(0).setText(file.toString());
				panel.getLoadButton().doClick();
			});
			untilLoaded(panel);
			assertTrue(panel.getDepositAllButton().isEnabled());
			Edt.run(() -> panel.getDepositAllButton().doClick());
			until("the deposit to finish", () -> !panel.getGroup().cell(0).isEdited());

			//-- And the machine really has it.
			var m = ctx.getConnectionManager();
			var value = m.getConnection().call(() -> m.getConsole().examine(
				Address.of(m.getConsole().physicalAddressType(), 01000)));
			assertEquals(012701, value.word());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * A file that has been loaded but not yet deposited survives another window reading the same
	 * addresses off the machine.
	 *
	 * <p>This group turns {@code pdpOverwritesEdit} off in its constructor, permanently and for
	 * exactly this reason. The shared grid used to override that every time it refreshed - it set
	 * the flag from "are there edits right now", so the refresh after a successful Deposit all
	 * turned it back on, and the next Load had no protection at all. What arrived then was the
	 * machine's own values, over the top of the file the user was about to write, with Verify
	 * afterwards comparing the machine against itself and reporting agreement.</p>
	 */
	@Test
	void aLoadedFileIsNotOverwrittenByAnotherWindowReadingTheSameAddresses(@TempDir Path dir)
		throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		Edt.run(panel::attach);
		try {
			var m = ctx.getConnectionManager();
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));

			//-- File A, loaded and deposited. After this the grid holds no edits at all, which is
			//-- what used to turn the permanent opt-out back on.
			Path file = textFile(dir, "001000: 000111 000222\n");
			Edt.run(() -> {
				panel.getFormatCombo().setSelectedItem(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE);
				panel.getFileField(0).setText(file.toString());
				panel.getLoadButton().doClick();
			});
			untilLoaded(panel);
			Edt.run(() -> panel.getDepositAllButton().doClick());
			until("the deposit to finish", () -> !panel.getGroup().cell(0).isEdited());
			Edt.run(() -> {
			});

			//-- File B, loaded and deliberately not deposited: this is what has to survive.
			Files.writeString(file, "001000: 000333 000444\n", StandardCharsets.US_ASCII);
			Edt.run(() -> panel.getLoadButton().doClick());
			untilLoaded(panel);
			assertTrue(panel.getGroup().cell(0).isEdited(), "nothing has been written to the machine");

			//-- Another window examines the same addresses. Every group at those addresses is
			//-- offered what the machine said; this one has to refuse it.
			MemoryAddressType type = panel.getGroup().getType();
			MemoryCellGroup other = ctx.getMemoryCellGroups().addGroup(type, "another window");
			other.add(Address.of(type, 01000));
			other.add(Address.of(type, 01002));
			m.getConnection().run(() -> m.getConsole().examine(other, false,
				to.etc.pdp11.core.util.ProgressMonitor.NULL));
			Edt.run(() -> {
			});

			assertEquals(0111, other.cell(0).getPdpValue().word(), "the machine still holds file A");
			assertEquals(0333, panel.getGroup().cell(0).getEditValue().word(),
				"and the loader still holds file B, which is what Deposit would write");
			assertEquals(0444, panel.getGroup().cell(1).getEditValue().word());
			assertTrue(panel.getGroup().cell(0).isEdited(), "still undeposited, and still shown as such");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * The Pascal writes the execution window's Start PC field directly and then calls that
	 * field's own change handler by hand. Here the loader says where the program starts and the
	 * execution window shows it, without either knowing about the other.
	 */
	@Test
	void aPaperTapeEntryAddressReachesTheExecutionWindow(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryLoaderPanel loader = Edt.call(() -> new MemoryLoaderPanel(ctx));
		ExecutionPanel execution = Edt.call(() -> new ExecutionPanel(ctx));
		Edt.run(execution::attach);

		//-- A paper tape image with an entry address, written by the dumper.
		MemoryCellGroup source = new MemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "src");
		source.add(01000, 2);
		source.cell(0).setEditValue(CellValue.of(012701));
		source.cell(1).setEditValue(CellValue.of(0200));
		Path tape = dir.resolve("program.ptap");
		MemoryDumper.save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, source, List.of(tape),
			Address.of(MemoryAddressType.PHYSICAL16, 01000));

		Edt.run(() -> {
			loader.getFormatCombo().setSelectedItem(MemoryFileFormat.ABSOLUTE_PAPERTAPE);
			loader.getFileField(0).setText(tape.toString());
			loader.getLoadButton().doClick();
		});
		untilLoaded(loader);

		assertEquals("001000", loader.getEntryField().getText());
		assertEquals("001000", execution.getStartPcField().getText(),
			"the execution window is told, and does not know the loader exists");
		assertEquals(01000, ctx.getMachineState().getStartPc().val());
	}

	@Test
	void verifyingComparesTheMachineWithTheFileRatherThanReplacingIt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Path file = textFile(dir, "001000: 012701 000200\n");
			Edt.run(() -> {
				panel.getFormatCombo().setSelectedItem(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE);
				panel.getFileField(0).setText(file.toString());
				panel.getLoadButton().doClick();
			});
			untilLoaded(panel);

			//-- Nothing deposited yet, so the machine holds zeros and everything disagrees.
			Edt.run(() -> panel.getVerifyButton().doClick());
			until("the verify to finish", () -> panel.getStatusText().contains("differ from the file")
				|| panel.getStatusText().contains("exactly what was loaded"));
			assertTrue(panel.getStatusText().contains("differ from the file"), panel.getStatusText());
			//-- The file's values are still there: the read filled in what the machine has, it did
			//-- not replace what was loaded.
			assertEquals(012701, panel.getGroup().cell(0).getEditValue().word());

			//-- Deposit, verify again, and now they agree.
			Edt.run(() -> panel.getDepositAllButton().doClick());
			until("the deposit", () -> !panel.getGroup().cell(0).isEdited());
			Edt.run(() -> panel.getVerifyButton().doClick());
			until("the second verify",
				() -> panel.getStatusText().contains("exactly what was loaded"));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void loadingWithNoFileNameIsRefused(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		StringBuilder failures = new StringBuilder();
		ctx.setFailureHandler((message, cause) -> failures.append(message));
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		Edt.run(() -> panel.getLoadButton().doClick());
		assertTrue(failures.toString().contains("Choose a"), failures.toString());
	}

	@Test
	void aFileThatCannotBeReadIsReportedAndChangesNothing(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		StringBuilder failures = new StringBuilder();
		ctx.setFailureHandler((message, cause) -> failures.append(message));
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		Edt.run(() -> {
			panel.getFileField(0).setText(dir.resolve("nope.bin").toString());
			panel.getLoadButton().doClick();
		});
		untilLoaded(panel);
		assertTrue(failures.toString().contains("Could not read"), failures.toString());
		assertTrue(panel.getGroup().isEmpty());
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryLoaderPanel panel = Edt.call(() -> new MemoryLoaderPanel(ctx));
		StringBuilder text = new StringBuilder();
		int v = 012701;
		for(int line = 0; line < 8; line++) {
			text.append(String.format("%06o:", 01000 + line * 020));
			for(int i = 0; i < 8; i++) {
				text.append(String.format(" %06o", v));
				v = (v + 0311) & 0xFFFF;
			}
			text.append('\n');
		}
		Path file = textFile(dir, text.toString());
		Edt.run(() -> {
			panel.getFormatCombo().setSelectedItem(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE);
			panel.getFileField(0).setText(file.toString());
			panel.getLoadButton().doClick();
		});
		untilLoaded(panel);
		Path png = UiRenderer.renderToFile(panel, 980, 460, Path.of("target", "ui-render", "memory-loader.png"));
		assertTrue(Files.size(png) > 0);
	}
}
