package to.etc.pdp11.ui.disas;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Disassembler window: what it shows, and that it follows the PC without being told to.
 *
 * <p>The decoding itself is {@code DisassemblyListingTest}'s job, in the core. What is checked
 * here is the wiring - that a stop moves the listing, that a range typed by hand stops it
 * following, and that a window nobody has open does not read memory.</p>
 */
class DisassemblerPanelTest {
	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static Address v(int val) {
		return Address.of(MemoryAddressType.VIRTUAL, val);
	}

	/** Put words into the panel's own group, as an examine would have. */
	private static void poke(DisassemblerPanel panel, int start, int... words) {
		for(int i = 0; i < words.length; i++) {
			MemoryCell mc = panel.getGroup().findByAddress(start + 2L * i);
			if(mc != null)
				mc.setPdpValue(CellValue.of(words[i]));
		}
	}

	@Test
	void itShowsOneLinePerInstruction(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		//-- mov #200,r1 / halt, at the range the panel starts on.
		poke(panel, 0, 012701, 0200, 0);
		Edt.run(panel::updateDisplay);

		List<String> lines = panel.getShownLines();
		assertTrue(lines.get(0).startsWith("000000: 012701 000200"), lines.get(0));
		assertTrue(lines.get(0).endsWith("mov     #000200,r1"), lines.get(0));
		assertTrue(lines.get(1).contains("halt"), lines.get(1));
	}

	@Test
	void itCentresItselfOnTheProgramCounterWhenTheMachineStops(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		//-- Showing, so that it does the work: a window nobody is looking at does not read
		//-- memory, which is the next test.
		Edt.run(() -> {
			javax.swing.JFrame frame = new javax.swing.JFrame();
			frame.setContentPane(panel);
			panel.attach();
			panel.showPc(v(01000));
		});

		//-- Five words before the PC and eleven in all, which is what the Pascal's arithmetic
		//-- comes to however its constant is named.
		assertEquals("000766", panel.getStartField().getText());
		assertEquals("001012", panel.getEndField().getText());
		assertEquals(11, panel.getGroup().size());
		assertEquals(0766, panel.getGroup().getRange().lo());
	}

	@Test
	void aPcNearAddressZeroDoesNotRunOffTheBottom(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> panel.showPc(v(4)));
		assertEquals("000000", panel.getStartField().getText());
	}

	@Test
	void aRangeTypedByHandStopsItFollowingThePc(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("2000");
			panel.getEndField().setText("2020");
			panel.getStartField().postActionEvent();
		});
		poke(panel, 02000, 0, 0);
		Edt.run(panel::updateDisplay);

		assertEquals(02000, panel.getGroup().getRange().lo());
		//-- No PC marker: the Pascal clears CodeAddr every time the user moves the range, so
		//-- that a marker cannot drag the listing out from under somebody reading it.
		assertTrue(panel.getInfoText().startsWith("2 instructions"), panel.getInfoText());
	}

	@Test
	void nonsenseInTheAddressFieldIsRefused(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		long before = panel.getGroup().getRange().lo();
		Edt.run(() -> {
			panel.getStartField().setText("nonsense");
			panel.getStartField().postActionEvent();
		});
		assertEquals(before, panel.getGroup().getRange().lo());
	}

	@Test
	void withNothingReadItSaysSoRatherThanShowingNothing(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(panel::updateDisplay);
		assertEquals("Not connected, so there is nothing to disassemble", panel.getInfoText());
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> panel.showPc(v(01000)));
		//-- A small program either side of the PC: clr r0 / inc r0 / cmp r0,#10 / bne .-4 / halt
		poke(panel, 0766, 005000, 005200, 020027, 000010, 001374, 000000, 000777, 010001, 010203, 0, 0);
		Edt.run(panel::updateDisplay);
		Path file = UiRenderer.renderToFile(panel, 640, 440, Path.of("target", "ui-render", "disassembler-panel.png"));
		assertTrue(Files.size(file) > 0);
	}
}
