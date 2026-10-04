package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.common.util.Scheduler;
import to.etc.pdp11.core.fake.FakePdp11M9301;
import to.etc.pdp11.core.fake.FakePdp11M9312;
import to.etc.pdp11.core.io.FakeTransport;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large deposits on the M9312 console, through the {@link FastLoader}, against a fake that
 * <i>executes</i> the loader rather than imitating it.
 *
 * <p>So what is checked here is the words that will be deposited into a real 11/05, run by an
 * instruction interpreter against the fake's memory and console line - including what they do
 * when the line drops, garbles or doubles a character, or loses the eighth bit, which are
 * injected into the fake. Every test reads back what it deposited through the ordinary examine,
 * and compares: the deposit and the examine are each other's check.</p>
 */
class M9312FastLoadTest {
	private static final MemoryAddressType MAT = MemoryAddressType.PHYSICAL16;

	private static final class Rig implements AutoCloseable {
		final FakePdp11M9312 fake;

		final MemoryCellGroups groups = new MemoryCellGroups();

		final ConsoleConnection connection;

		final M9312Console console;

		final StringBuffer wire = new StringBuffer();

		final AtomicInteger stops = new AtomicInteger();

		Rig(BootRom rom) throws ConsoleException {
			Scheduler.Manual clock = new Scheduler.Manual();
			fake = rom == BootRom.M9301 ? new FakePdp11M9301(clock, new Random(7)) : new FakePdp11M9312(clock, new Random(7));
			fake.powerOn();
			connection = new ConsoleConnection(new FakeTransport(fake), Logger.NULL);
			connection.setTerminalSink(wire::append);
			console = new M9312Console(groups, rom, rom.getDefaultMonitorEntry(), Logger.NULL);
			connection.attach(console);
			console.setCommandTimeoutMillis(300);
			connection.run(() -> console.init(connection));
			console.setCommandTimeoutMillis(M9312Console.CMD_TIMEOUT_MS);
			ConsoleRigs.drainPowerOnStop(connection);
			console.setExecutionStopListener((c, pc) -> stops.incrementAndGet());
		}

		Rig() throws ConsoleException {
			this(BootRom.M9312);
		}

		/** A group of {@code count} words from {@code start}, each with an edit to deposit. */
		MemoryCellGroup program(String name, int start, int count, int seed) {
			MemoryCellGroup g = groups.addGroup(MAT, name);
			g.add(start, count);
			Random r = new Random(seed);
			for(int i = 0; i < count; i++) {
				g.cell(i).setEditValue(CellValue.of(r.nextInt(0x10000)));
			}
			return g;
		}

		void deposit(MemoryCellGroup g) throws ConsoleException {
			connection.run(() -> console.deposit(g, false, ProgressMonitor.NULL));
		}

		/** What the fake holds at every cell of {@code g} is what the cell said to deposit. */
		void assertHolds(MemoryCellGroup g, List<Integer> expected) {
			for(int i = 0; i < expected.size(); i++) {
				MemoryCell mc = g.cell(i);
				assertEquals(expected.get(i).intValue(), fake.getMem(mc.getAddr()),
					"word at " + mc.getAddr().toOctal());
			}
		}

		/** Read the group back over the console, and compare with what was meant. */
		void assertReadsBack(MemoryCellGroup g, List<Integer> expected) throws ConsoleException {
			connection.run(() -> console.examine(g, false, ProgressMonitor.NULL));
			for(int i = 0; i < expected.size(); i++) {
				assertEquals(CellValue.of(expected.get(i)), g.cell(i).getPdpValue(), "read back at " + g.cell(i).getAddr().toOctal());
				assertFalse(g.cell(i).isEdited(), "still pending at " + g.cell(i).getAddr().toOctal());
			}
		}

		boolean usedLoader() {
			return wire.indexOf(String.valueOf(FastLoader.READY)) >= 0;
		}

		/** Let any stop event already posted to the command thread run. */
		void flush() throws ConsoleException {
			connection.call(() -> null);
		}

		@Override
		public void close() {
			connection.close();
		}
	}

	private static List<Integer> edits(MemoryCellGroup g) {
		return g.getCells().stream().map(mc -> mc.getEditValue().word()).toList();
	}

	@Test
	void aBigDepositGoesThroughTheLoaderAndReadsBackTheSame() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.program("diag", 01000, 3000, 1);
			List<Integer> want = edits(g);
			rig.deposit(g);
			assertTrue(rig.usedLoader());
			assertFalse(rig.fake.isLoaderRunning());
			assertEquals(FakePdp11M9312.State.PROMPT, rig.fake.getState());
			rig.assertHolds(g, want);
			rig.assertReadsBack(g, want);
		}
	}

	/**
	 * Coming back from the loader prints the register dump that otherwise means a program halted.
	 * Nothing halted, and nothing may say so: the execution window would show a stop that did not
	 * happen.
	 */
	@Test
	void returningFromTheLoaderIsNotReportedAsAStop() throws Exception {
		try(Rig rig = new Rig()) {
			rig.deposit(rig.program("diag", 01000, 500, 2));
			rig.flush();
			assertEquals(0, rig.stops.get());
		}
	}

	/** About sixty words of loader and a START, and then the data: a handful of L commands, not one per run. */
	@Test
	void theLoaderIsDepositedTheSlowWayAndTheDataIsNot() throws Exception {
		try(Rig rig = new Rig()) {
			int before = rig.wire.length();
			rig.deposit(rig.program("diag", 01000, 2000, 3));
			String said = rig.wire.substring(before);
			int deposits = said.split("D ", -1).length - 1;
			assertTrue(deposits < 80, "only the loader is deposited word by word, not " + deposits + " words");
		}
	}

	/** An image below 8KW - most of them - has the loader just under 8KW, and nothing slow after it. */
	@Test
	void underEightKwTheLoaderGoesAboveTheImage() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.program("diag", 0, 3000, 13);
			List<Integer> want = edits(g);
			int before = rig.wire.length();
			rig.deposit(g);
			String said = rig.wire.substring(before);
			String afterExit = said.substring(said.lastIndexOf(String.valueOf(FastLoader.ACK)));
			assertFalse(afterExit.contains("D "), "nothing deposited one word at a time after the loader");
			FastLoader.Image image = FastLoader.assemble(040000 - FastLoader.SIZE, 0165020);
			for(FastLoader.Word w : image.code()) {
				assertEquals(w.value(), rig.fake.getMem(Address.of(MAT, w.address())));
			}
			rig.assertReadsBack(g, want);
		}
	}

	/**
	 * Above 8KW the loader goes into the highest gap below the image that it fits in, and what the
	 * application knew of that memory is forgotten, since the loader is there now.
	 */
	@Test
	void theLoaderGoesInAGapAndWhatWasKnownThereIsForgotten() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup gap = rig.groups.addGroup(MAT, "gap");
			gap.add(044000, 02000);                         // 044000..047776
			rig.connection.run(() -> rig.console.examine(gap, false, ProgressMonitor.NULL));
			assertTrue(gap.cell(0).getPdpValue().isKnown());

			MemoryCellGroup high = rig.program("high", 050000, 01000, 5); // 050000..051776
			List<Integer> wantHigh = edits(high);
			rig.deposit(high);
			rig.assertHolds(high, wantHigh);

			int origin = 050000 - FastLoader.SIZE;
			FastLoader.Image image = FastLoader.assemble(origin, 0165020);
			for(FastLoader.Word w : image.code()) {
				assertEquals(w.value(), rig.fake.getMem(Address.of(MAT, w.address())));
			}
			MemoryCell under = gap.findByAddress(origin);
			assertFalse(under.getPdpValue().isKnown(), "the loader is there now");
			assertTrue(gap.cell(0).getPdpValue().isKnown(), "below the loader nothing changed");
		}
	}

	/** An image from 0 past 8KW has no gap: the loader sits on its top and those words go last. */
	@Test
	void withNoGapTheLoaderOverlaysTheTopAndThoseWordsAreDepositedAfter() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.program("from zero", 0, 9000, 6);
			List<Integer> want = edits(g);
			rig.deposit(g);
			assertTrue(rig.usedLoader());
			rig.assertHolds(g, want);
			rig.assertReadsBack(g, want);
		}
	}

	@Test
	void aSmallDepositDoesNotBother() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.program("patch", 01000, M9312Console.FAST_LOAD_MIN_WORDS - 1, 7);
			List<Integer> want = edits(g);
			rig.deposit(g);
			assertFalse(rig.usedLoader());
			rig.assertHolds(g, want);
		}
	}

	@Test
	void switchedOffItIsNotUsed() throws Exception {
		try(Rig rig = new Rig()) {
			rig.console.setFastLoad(false);
			MemoryCellGroup g = rig.program("diag", 01000, 300, 8);
			List<Integer> want = edits(g);
			rig.deposit(g);
			assertFalse(rig.usedLoader());
			rig.assertHolds(g, want);
		}
	}

	/** The M9301's console entry depends on its switches, so a loader could not get back. */
	@Test
	void anM9301HasNowhereToComeBackToAndDepositsOneWordAtATime() throws Exception {
		try(Rig rig = new Rig(BootRom.M9301)) {
			MemoryCellGroup g = rig.program("diag", 01000, 300, 9);
			List<Integer> want = edits(g);
			rig.deposit(g);
			assertFalse(rig.usedLoader());
			rig.assertHolds(g, want);
		}
	}

	/**
	 * A console port strapped for seven data bits: the probe block fails, the loader is sent back
	 * with the seven-bit exit, and the deposit is done one word at a time instead - completely.
	 */
	@Test
	void aSevenBitLineIsFoundByTheProbeAndTheDepositStillHappens() throws Exception {
		try(Rig rig = new Rig()) {
			rig.fake.setSevenBitLine(true);
			MemoryCellGroup g = rig.program("diag", 01000, 200, 10);
			List<Integer> want = edits(g);
			rig.deposit(g);
			assertTrue(rig.usedLoader());
			assertTrue(rig.wire.indexOf(String.valueOf(FastLoader.NAK)) >= 0);
			assertFalse(rig.fake.isLoaderRunning());
			rig.assertHolds(g, want);
			rig.assertReadsBack(g, want);
			rig.flush();
			assertEquals(0, rig.stops.get());
		}
	}

	/**
	 * A character lost, garbled or doubled on the way - in the probe (5), in the first data block
	 * (60), and further in (700) - is recovered from, and the result is exact.
	 */
	@ParameterizedTest
	@CsvSource({"5, CORRUPT", "60, CORRUPT", "700, CORRUPT", "60, DUPLICATE", "700, DUPLICATE", "60, DROP"})
	void aBadCharacterOnTheLineIsRecoveredFrom(int n, FakePdp11M9312.LineFault fault) throws Exception {
		try(Rig rig = new Rig()) {
			rig.fake.injectLineFault(n, fault);
			MemoryCellGroup g = rig.program("diag", 01000, 1000, 11);
			List<Integer> want = edits(g);
			rig.deposit(g);
			assertTrue(rig.wire.indexOf(String.valueOf(FastLoader.NAK)) >= 0 || fault == FakePdp11M9312.LineFault.DROP);
			rig.assertHolds(g, want);
			rig.assertReadsBack(g, want);
		}
	}
}
