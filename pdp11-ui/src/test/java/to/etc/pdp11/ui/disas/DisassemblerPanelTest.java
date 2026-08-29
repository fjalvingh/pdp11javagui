package to.etc.pdp11.ui.disas;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
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

	private static final long TIMEOUT_MS = 30_000;

	private static void until(String what, java.util.function.BooleanSupplier condition) {
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

	/** {@code count} one-word instructions - {@code clr r0} - as an examine would have left them. */
	private static void pokeClrR0(DisassemblerPanel panel, int start, int count) {
		int[] words = new int[count];
		java.util.Arrays.fill(words, 005000);
		poke(panel, start, words);
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

	/**
	 * Opened after the machine stopped, it reads around the PC rather than showing nothing.
	 *
	 * <p>{@code ToolWindow.showWindow} runs {@code onShowing()} - and so {@code attach()} -
	 * before {@code setVisible(true)}, so {@code isShowing()} is false on every single open.
	 * Handing that flag to the catch-up read meant the read never happened: opening the window
	 * while connected and stopped showed "Nothing has been read from this range yet" beside a
	 * comment promising the opposite. The window does not have to be visible for this test to
	 * mean what it says - that is the whole point.</p>
	 */
	@Test
	void openingItAfterTheMachineStoppedReadsAroundThePc(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			//-- Two instructions at 1000, and a machine that stopped there before this window
			//-- was ever opened.
			var m = ctx.getConnectionManager();
			m.getConnection().run(() -> {
				m.getConsole().deposit(Address.of(m.getConsole().physicalAddressType(), 01000), 0005000);
				m.getConsole().deposit(Address.of(m.getConsole().physicalAddressType(), 01002), 0005200);
			});
			ctx.getMachineState().stopped(v(01000));

			DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
			//-- Exactly what DisassemblerWindow.onShowing does, and it is not showing yet.
			Edt.run(panel::attach);
			try {
				until("the catch-up read", () -> {
					MemoryCell mc = panel.getGroup().findByAddress(01000L);
					return mc != null && mc.getPdpValue().isKnown();
				});
				assertEquals(0005000, panel.getGroup().findByAddress(01000L).getPdpValue().word());
			} finally {
				Edt.run(panel::detach);
			}
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/** A window nobody has open still does not read memory when the machine stops. */
	@Test
	void aHiddenWindowStillDoesNotReadOnEveryStop(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
			Edt.run(panel::attach);
			//-- Attached, hidden, and the machine stops somewhere else: no examine, because
			//-- twenty-one words per stop over a serial line is what the flag exists to avoid.
			Edt.run(() -> panel.showPc(v(02000)));
			Thread.sleep(200);
			MemoryCell mc = panel.getGroup().findByAddress(02000L);
			assertTrue(mc == null || !mc.getPdpValue().isKnown(), "nothing was read");
			Edt.run(panel::detach);
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * A page is a hundred instructions, however many words that turns out to be.
	 *
	 * <p>The window used to be given a range - "from here to there" - and an end address is a
	 * guess at how much of a program it covers, because an instruction is one, two or three
	 * words. It is asked for a number of instructions now, and the range it reads follows from
	 * that rather than the other way round.</p>
	 */
	@Test
	void aPageIsAHundredInstructions(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("1000");
			panel.getStartField().postActionEvent();
		});
		pokeClrR0(panel, 01000, 250);
		Edt.run(panel::updateDisplay);

		assertEquals(100, panel.getShownLines().size());
		assertTrue(panel.getShownLines().get(0).startsWith("001000: 005000"), panel.getShownLines().get(0));
		assertTrue(panel.getInfoText().startsWith("100 instructions from 001000"), panel.getInfoText());
	}

	/**
	 * {@code >} adds the next hundred, beginning exactly where the last hundred stopped.
	 *
	 * <p>Not "start again a hundred lines further on": where an instruction begins cannot be
	 * known from an address, so a page that restarted at a guessed boundary could decode the same
	 * bytes into different instructions across the page break.</p>
	 */
	@Test
	void theNextPageCarriesOnWhereTheLastLeftOff(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("1000");
			panel.getStartField().postActionEvent();
		});
		pokeClrR0(panel, 01000, 250);
		Edt.run(panel::updateDisplay);
		String lastOfTheFirstPage = panel.getShownLines().get(99);

		Edt.run(() -> panel.getForwardButton().doClick());
		pokeClrR0(panel, 01000, 250);                       // the range grew; fill in what it added
		Edt.run(panel::updateDisplay);

		List<String> lines = panel.getShownLines();
		assertEquals(200, lines.size(), "a second page, added to the first");
		assertEquals(lastOfTheFirstPage, lines.get(99), "and the first page is still there, unchanged");
		//-- One word per instruction here, so the hundred-and-first is a hundred words on.
		assertTrue(lines.get(100).startsWith("001310: 005000"), lines.get(100));
		assertEquals("001000", panel.getStartField().getText(), "the listing still starts where it did");
	}

	/**
	 * Against a real machine it reads what the page needs, and not three words a line.
	 *
	 * <p>A hundred instructions is somewhere between a hundred and three hundred words and there
	 * is no way to know which before they have been decoded. Reading the worst case outright
	 * would double what a page costs, and over a serial line that is the difference between a
	 * window that answers and one that does not - so it reads a page's worth of words, decodes,
	 * and asks for as many more as it is still short of. The code here is three words for every
	 * two instructions, so a hundred lines is a hundred and fifty words.</p>
	 */
	@Test
	void itReadsOnlyAsManyWordsAsThePageNeeds(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			var m = ctx.getConnectionManager();
			m.getConnection().run(() -> {
				var type = m.getConsole().physicalAddressType();
				//-- clr r0 / mov #000200,r1, over and over.
				for(int i = 0; i < 100; i++) {
					long a = 01000 + i * 6L;
					m.getConsole().deposit(Address.of(type, a), 0005000);
					m.getConsole().deposit(Address.of(type, a + 2), 0012701);
					m.getConsole().deposit(Address.of(type, a + 4), 0000200);
				}
			});

			DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
			Edt.run(() -> {
				panel.getStartField().setText("1000");
				panel.getStartField().postActionEvent();
			});
			until("the page to fill", () -> Edt.call(() -> panel.getShownLines().size()) >= 100);

			List<String> lines = Edt.call(panel::getShownLines);
			assertEquals(100, lines.size());
			assertTrue(lines.get(0).startsWith("001000: 005000"), lines.get(0));
			assertTrue(lines.get(1).startsWith("001002: 012701 000200"), lines.get(1));
			//-- The hundredth line is a two-word instruction that the end of the page falls
			//-- inside. It has to be whole: the decoder will not invent an operand it has not
			//-- read, so a page that stopped a word short showed its last line as a bare
			//-- ".word 012701" instead.
			assertTrue(lines.get(99).endsWith("mov     #000200,r1"), lines.get(99));
			int words = panel.getGroup().size();
			assertTrue(words >= 150, "it read the whole page: " + words + " words");
			assertTrue(words < 300, "and not three words a line: " + words + " words");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * {@code <} backs up 32 bytes and lists again from there.
	 *
	 * <p>It is the boundary-correction button: a listing that started on the wrong word decodes
	 * everything after it wrongly too, and starting a little earlier is the only way to get a
	 * different guess. So the listing is thrown away rather than extended backwards.</p>
	 */
	@Test
	void steppingBackRestartsTheListingThirtyTwoBytesEarlier(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("1000");
			panel.getStartField().postActionEvent();
			panel.getForwardButton().doClick();             // two pages showing, to be thrown away
			panel.getBackButton().doClick();
		});
		assertEquals("000740", panel.getStartField().getText(), "32 bytes is 040 octal");

		pokeClrR0(panel, 0740, 250);
		Edt.run(panel::updateDisplay);
		assertEquals(100, panel.getShownLines().size(), "one page again, not the two that were showing");
		assertTrue(panel.getShownLines().get(0).startsWith("000740: 005000"), panel.getShownLines().get(0));
	}

	/** And it stops at the bottom of memory rather than asking for a negative address. */
	@Test
	void steppingBackStopsAtAddressZero(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DisassemblerPanel panel = Edt.call(() -> new DisassemblerPanel(ctx));
		Edt.run(() -> {
			panel.getStartField().setText("20");
			panel.getStartField().postActionEvent();
			panel.getBackButton().doClick();
		});
		assertEquals("000000", panel.getStartField().getText());
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
