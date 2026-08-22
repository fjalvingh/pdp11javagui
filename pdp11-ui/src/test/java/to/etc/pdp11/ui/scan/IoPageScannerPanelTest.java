package to.etc.pdp11.ui.scan;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.machine.IoPageScanner;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
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
 * The I/O page scanner window: the button, the two panes, and what the scan leaves behind.
 *
 * <p>The scan itself is checked in {@code IoPageScannerTest}, in {@code pdp11-app}, where the
 * machine description is. What is worth checking here is the window's own promises: that it
 * cannot be started without a machine, that it shows what was found on one side and the text to
 * paste on the other, and that the buttons that act on results are dead until there are some.</p>
 */
class IoPageScannerPanelTest {
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
	void withNoMachineThereIsNothingToScan(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		IoPageScannerPanel panel = Edt.call(() -> new IoPageScannerPanel(ctx));
		UiRenderer.layOut(panel, 1000, 520);

		assertFalse(panel.getScanButton().isEnabled());
		assertEquals("Not connected, so there is nothing to scan", panel.getStatusText());
		//-- The hint says what the right-hand pane is for before there is anything in it.
		assertTrue(panel.getDescriptionArea().getText().contains("paste"),
			panel.getDescriptionArea().getText());
	}

	@Test
	void connectingArmsTheScanButton(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		IoPageScannerPanel panel = Edt.call(() -> new IoPageScannerPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			until("the button to arm", () -> panel.getScanButton().isEnabled());
			assertEquals("Nothing scanned yet", panel.getStatusText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void scanningFillsBothPanesAndSaysWhatItFound(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		IoPageScannerPanel panel = Edt.call(() -> new IoPageScannerPanel(ctx));
		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			until("the button to arm", () -> panel.getScanButton().isEnabled());

			Edt.run(() -> panel.getScanButton().doClick());
			until("the scan to finish", () -> !panel.getGroup().isEmpty()
				&& panel.getStatusText().contains("answered"));

			//-- The left pane lists what answered.
			assertTrue(panel.getList().getRowCount() > 0);
			assertEquals(panel.getGroup().size(), panel.getList().getRowCount());
			assertTrue(panel.getStatusText().contains("of " + IoPageScanner.IOPAGE_WORDS + " addresses"),
				panel.getStatusText());

			//-- And the right pane holds something a machine description would accept.
			String text = panel.getDescriptionArea().getText();
			assertTrue(text.startsWith("[Device_") || text.startsWith("\n[Device_") || text.startsWith(";"),
				"the description pane should hold sections or say why it does not: " + text);

			//-- A register the CPU always has, found by the scan and named by the MMU's own group.
			MemoryCell psw = panel.getGroup().findByAddress(
				to.etc.pdp11.core.addr.Address.of(MemoryAddressType.PHYSICAL22, 017777776));
			assertTrue(psw != null && psw.getPdpValue().isKnown(), "the PSW answered");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		IoPageScannerPanel panel = Edt.call(() -> new IoPageScannerPanel(ctx));
		//-- A result built by hand, so the picture shows a full window without needing a machine.
		Edt.run(() -> {
			int addr = 0177560;
			String[] names = {"DL11.RCSR", "DL11.RBUF", "DL11.XCSR", "DL11.XBUF"};
			String[] infos = {"Receiver status", "Receiver buffer", "Transmitter status", "Transmitter buffer"};
			for(int i = 0; i < names.length; i++) {
				MemoryCell mc = panel.getGroup().add(
					to.etc.pdp11.core.addr.Address.of(MemoryAddressType.PHYSICAL22, 017777560L + 2L * i));
				mc.setName(names[i]);
				mc.setInfo(infos[i]);
				mc.setPdpValue(CellValue.of(0100 * (i + 1)));
				mc.setEditValue(CellValue.of(0100 * (i + 1)));
			}
			for(int i = 0; i < 3; i++) {
				MemoryCell mc = panel.getGroup().add(
					to.etc.pdp11.core.addr.Address.of(MemoryAddressType.PHYSICAL22, 017772000L + 2L * i));
				mc.setName("device_172000.reg_" + Integer.toOctalString(2 * i));
				mc.setInfo("device base at 172000, word #" + i + ", octal offset +" + Integer.toOctalString(2 * i));
				mc.setPdpValue(CellValue.of(0));
				mc.setEditValue(CellValue.of(0));
			}
			panel.getList().rebuild();
			panel.getDescriptionArea().setText("\n[Device_172000]\n"
				+ "Info=register block at 172000 with 3 words len.\n"
				+ "Enabled=true\n"
				+ "Register_0=172000;\"device base at 172000, word #0, octal offset +0\"\n"
				+ "Register_2=172002;\"device base at 172000, word #1, octal offset +2\"\n"
				+ "Register_4=172004;\"device base at 172000, word #2, octal offset +4\"\n");
		});
		Path file = UiRenderer.renderToFile(panel, 1000, 420, Path.of("target", "ui-render", "iopage-scanner.png"));
		assertTrue(Files.size(file) > 0);
	}
}
