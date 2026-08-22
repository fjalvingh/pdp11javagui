package to.etc.pdp11.ui.dump;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.memfile.MemoryFileFormat;
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
 * The Memory Dumper window: reading a range off a machine and writing it out.
 *
 * <p>The file formats are checked byte for byte in {@code MemoryDumperTest}. What matters here is
 * the window's own behaviour - that the file rows follow the chosen format, that a dump read
 * earlier can still be written after the machine has gone, and that a range with holes in it says
 * so rather than writing zeros quietly.</p>
 */
class MemoryDumperPanelTest {
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
	void theFileRowsFollowTheChosenFormat(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryDumperPanel panel = Edt.call(() -> new MemoryDumperPanel(ctx));
		UiRenderer.layOut(panel, 980, 520);

		//-- One file, and no entry address, for a plain byte stream.
		Edt.run(() -> panel.getFormatCombo().setSelectedItem(MemoryFileFormat.BYTE_STREAM));
		assertTrue(panel.getFileLabel(0).isVisible());
		assertFalse(panel.getFileLabel(1).isVisible());
		assertFalse(panel.getEntryField().isVisible());
		assertEquals("Byte stream file:", panel.getFileLabel(0).getText());

		//-- Two files for the byte-split format, each with its own name.
		Edt.run(() -> panel.getFormatCombo().setSelectedItem(MemoryFileFormat.LOW_HIGH_BYTE_FILES));
		assertTrue(panel.getFileLabel(1).isVisible());
		assertEquals("Low byte file:", panel.getFileLabel(0).getText());
		assertEquals("High byte file:", panel.getFileLabel(1).getText());

		//-- And paper tape is the one format that records where to start executing.
		Edt.run(() -> panel.getFormatCombo().setSelectedItem(MemoryFileFormat.ABSOLUTE_PAPERTAPE));
		assertTrue(panel.getEntryField().isVisible());
		assertFalse(panel.getFileLabel(1).isVisible());
	}

	@Test
	void thereIsNothingToWriteUntilSomethingHasBeenRead(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryDumperPanel panel = Edt.call(() -> new MemoryDumperPanel(ctx));
		assertFalse(panel.getDumpButton().isEnabled());
		assertEquals("Nothing to write yet", panel.getStatusText());
	}

	@Test
	void aRangeIsReadOffTheMachineAndWrittenToAFile(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryDumperPanel panel = Edt.call(() -> new MemoryDumperPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));

			//-- Put something recognisable in memory first.
			var m = ctx.getConnectionManager();
			m.getConnection().run(() -> {
				for(int i = 0; i < 4; i++) {
					m.getConsole().deposit(
						to.etc.pdp11.core.addr.Address.of(m.getConsole().physicalAddressType(), 01000 + 2L * i),
						0100 + i);
				}
			});

			Edt.run(() -> {
				panel.getStartField().setText("1000");
				panel.getEndField().setText("1006");
				panel.getStartField().postActionEvent();
			});
			until("the read to finish", () -> panel.getGroup().size() == 4
				&& panel.getGroup().cell(3).getEditValue().isKnown());

			Path out = dir.resolve("dump.bin");
			Edt.run(() -> {
				panel.getFormatCombo().setSelectedItem(MemoryFileFormat.BYTE_STREAM);
				panel.getFileField(0).setText(out.toString());
				panel.getDumpButton().doClick();
			});

			assertTrue(Files.exists(out));
			byte[] bytes = Files.readAllBytes(out);
			assertEquals(8, bytes.length, "four words");
			assertEquals(0100, bytes[0] & 0xFF);
			assertEquals(0103, bytes[6] & 0xFF);
			assertTrue(panel.getStatusText().contains("4 words written"), panel.getStatusText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * A dump read earlier is still a dump. Writing it does not need the machine, which matters
	 * because reading 4 KB off a real one over a serial line is not something to repeat because
	 * a cable came out.
	 */
	@Test
	void aDumpCanBeWrittenAfterTheMachineHasGone(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryDumperPanel panel = Edt.call(() -> new MemoryDumperPanel(ctx));
		Edt.run(panel::attach);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		Edt.run(() -> {
			panel.getStartField().setText("1000");
			panel.getEndField().setText("1002");
			panel.getStartField().postActionEvent();
		});
		until("the read to finish", () -> panel.getGroup().cell(0).getEditValue().isKnown());
		ctx.getConnectionManager().disconnect();

		Path out = dir.resolve("late.bin");
		Edt.run(() -> {
			panel.getFileField(0).setText(out.toString());
			panel.getDumpButton().doClick();
		});
		assertTrue(Files.exists(out));
		assertEquals(4, Files.readAllBytes(out).length);
	}

	@Test
	void aRangeWithWordsNobodyHasReadSaysSoRatherThanWritingZerosQuietly(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryDumperPanel panel = Edt.call(() -> new MemoryDumperPanel(ctx));
		//-- A range built but never read: every word is unknown.
		Edt.run(() -> {
			panel.getStartField().setText("1000");
			panel.getEndField().setText("1006");
			panel.getStartField().postActionEvent();
		});
		assertTrue(panel.getStatusText().contains("not read from the machine"), panel.getStatusText());

		Path out = dir.resolve("holes.bin");
		Edt.run(() -> {
			panel.getFileField(0).setText(out.toString());
			panel.getDumpButton().doClick();
		});
		assertTrue(panel.getStatusText().contains("had never been read"), panel.getStatusText());
	}

	@Test
	void writingWithNoFileNameIsRefused(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		StringBuilder failures = new StringBuilder();
		ctx.setFailureHandler((message, cause) -> failures.append(message));
		MemoryDumperPanel panel = Edt.call(() -> new MemoryDumperPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("1000");
			panel.getEndField().setText("1002");
			panel.getStartField().postActionEvent();
			panel.getDumpButton().doClick();
		});
		assertTrue(failures.toString().contains("Choose a"), failures.toString());
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryDumperPanel panel = Edt.call(() -> new MemoryDumperPanel(ctx));
		Edt.run(() -> {
			panel.getFormatCombo().setSelectedItem(MemoryFileFormat.ABSOLUTE_PAPERTAPE);
			panel.getFileField(0).setText("/home/jal/tapes/maindec-11-d0na.ptap");
			panel.getEntryField().setText("001000");
			panel.getStartField().setText("1000");
			panel.getEndField().setText("1176");
			panel.getStartField().postActionEvent();
			int v = 012701;
			for(MemoryCell mc : panel.getGroup().getCells()) {
				mc.setPdpValue(CellValue.of(v));
				mc.setEditValue(CellValue.of(v));
				v = (v + 0311) & 0xFFFF;
			}
			panel.getGrid().refresh();
		});
		Path file = UiRenderer.renderToFile(panel, 980, 460, Path.of("target", "ui-render", "memory-dumper.png"));
		assertTrue(Files.size(file) > 0);
	}
}
