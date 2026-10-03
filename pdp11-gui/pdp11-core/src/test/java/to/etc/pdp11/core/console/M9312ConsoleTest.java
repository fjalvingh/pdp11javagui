package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.common.util.Scheduler;
import to.etc.pdp11.core.fake.FakePdp11M9301;
import to.etc.pdp11.core.fake.FakePdp11M9312;
import to.etc.pdp11.core.io.FakeTransport;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M9312 and M9301 boot ROM console driven end to end against the ported fakes, and its
 * decoder fed by hand.
 *
 * <p>The fakes were ported in phase 3 from the M9312 technical manual (EK-M9312-TM-003); what
 * this adds is the driver against them. Most of what is interesting about this console is what
 * kills it, so a good half of these tests check that the driver refuses to send the things the
 * fake would halt on - and that when the machine does stop, the exception says what happened.</p>
 */
class M9312ConsoleTest {
	private static final MemoryAddressType MAT = MemoryAddressType.PHYSICAL16;

	/** The fake has 32 KB fitted; everything from here to the I/O page is not there. */
	private static final long NONEXISTENT = 0100000;

	private static final class Rig implements AutoCloseable {
		final Scheduler.Manual machineClock = new Scheduler.Manual();

		final FakePdp11M9312 fake;

		final MemoryCellGroups groups = new MemoryCellGroups();

		final ConsoleConnection connection;

		final M9312Console console;

		/** Everything the machine said, our echoes included. */
		final StringBuffer wire = new StringBuffer();

		Rig() throws ConsoleException {
			this(BootRom.M9312);
		}

		Rig(BootRom rom) throws ConsoleException {
			fake = rom == BootRom.M9301
				? new FakePdp11M9301(machineClock, new Random(7))
				: new FakePdp11M9312(machineClock, new Random(7));
			fake.powerOn();
			connection = new ConsoleConnection(new FakeTransport(fake), Logger.NULL);
			connection.setTerminalSink(wire::append);
			console = new M9312Console(groups, rom, rom.getDefaultMonitorEntry(), Logger.NULL);
			connection.attach(console);
			//-- The handshake waits out a full timeout twice against an idle emulator, by design:
			//-- the power-on prompt has already gone by, and the first CR is only remembered. That
			//-- is two seconds a test against a fake that answers in microseconds, so the wait is
			//-- shortened for the handshake alone and put back for the test itself.
			console.setCommandTimeoutMillis(300);
			connection.run(() -> console.init(connection));
			console.setCommandTimeoutMillis(M9312Console.CMD_TIMEOUT_MS);
			ConsoleRigs.drainPowerOnStop(connection);
		}

		/** How many times an address was loaded, counted from our own echoes. */
		int loads() {
			String s = wire.toString();
			int n = 0;
			for(int i = s.indexOf("L "); i >= 0; i = s.indexOf("L ", i + 1)) {
				n++;
			}
			return n;
		}

		@Override
		public void close() {
			connection.close();
		}
	}

	private static Address phys(long v) {
		return Address.of(MAT, v);
	}

	// ---------------------------------------------------------------------------------------
	// What it is
	// ---------------------------------------------------------------------------------------

	@Test
	void itCanStartAProgramAndDoNothingElse() throws Exception {
		try(Rig rig = new Rig()) {
			assertEquals(EnumSet.of(ConsoleFeature.ACTION_RESET_AND_START_CPU), rig.console.features());
			assertEquals(MAT, rig.console.physicalAddressType());
			assertThrows(ConsoleException.class, () -> rig.connection.call(() -> rig.console.haltCpu()));
			assertThrows(ConsoleException.class, () -> rig.connection.run(rig.console::continueCpu));
			assertThrows(ConsoleException.class, () -> rig.connection.run(rig.console::singleStep));
			assertThrows(ConsoleException.class,
				() -> rig.connection.run(() -> rig.console.resetMachine(Address.of(MemoryAddressType.VIRTUAL, 01000))));
		}
	}

	@Test
	void theM9312HasAMonitorEntryAndTheM9301DoesNot() {
		assertEquals(Address.of(MemoryAddressType.VIRTUAL, 0165020), BootRom.M9312.getDefaultMonitorEntry());
		assertNull(BootRom.M9301.getDefaultMonitorEntry());
	}

	@Test
	void itsLineEndIsTheLineFeedAndTheCarriageReturnsAreFill() throws Exception {
		try(Rig rig = new Rig()) {
			assertFalse(rig.console.terminalProfile().crIsNewline());
			assertTrue(rig.console.terminalProfile().lfIsNewline());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Examine and deposit
	// ---------------------------------------------------------------------------------------

	@Test
	void aWordDepositedReadsBack() throws Exception {
		try(Rig rig = new Rig()) {
			rig.connection.run(() -> rig.console.deposit(phys(01000), 0123456));
			assertEquals(0123456, rig.fake.getMem(phys(01000)));
			rig.fake.setMem(phys(01002), 0654321 & 0xFFFF);
			assertEquals(0654321 & 0xFFFF, rig.connection.call(() -> rig.console.examine(phys(01002))).word());
		}
	}

	@Test
	void aVirtualAddressGoesThroughTheMmuWhichIsOffSoItIsTheSameWord() throws Exception {
		try(Rig rig = new Rig()) {
			rig.fake.setMem(phys(02000), 0777);
			assertEquals(0777, rig.connection.call(
				() -> rig.console.examine(Address.of(MemoryAddressType.VIRTUAL, 02000))).word());
		}
	}

	@Test
	void consecutiveWordsInAGroupDepositNeedOnlyOneLoad() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			g.add(03000, 5);
			for(int i = 0; i < 5; i++) {
				g.cell(i).setEditValue(CellValue.of(060000 + i));
			}
			int before = rig.loads();
			rig.connection.run(() -> rig.console.deposit(g, false, ProgressMonitor.NULL));
			for(int i = 0; i < 5; i++) {
				assertEquals(060000 + i, rig.fake.getMem(phys(03000 + 2L * i)), "word " + i);
			}
			//-- The emulator's own auto-increment does the rest - which only works because the
			//-- first D after an L does not advance and every later one does.
			assertEquals(1, rig.loads() - before, "one L for one run of words");
		}
	}

	@Test
	void aGapInAGroupDepositLoadsTheAddressAgain() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			g.add(phys(04000)).setEditValue(CellValue.of(1));
			g.add(phys(04002)).setEditValue(CellValue.of(2));
			g.add(phys(04010)).setEditValue(CellValue.of(3));
			int before = rig.loads();
			rig.connection.run(() -> rig.console.deposit(g, false, ProgressMonitor.NULL));
			assertEquals(1, rig.fake.getMem(phys(04000)));
			assertEquals(2, rig.fake.getMem(phys(04002)));
			assertEquals(3, rig.fake.getMem(phys(04010)));
			assertEquals(0, rig.fake.getMem(phys(04004)), "the gap must not have been written");
			assertEquals(2, rig.loads() - before);
		}
	}

	@Test
	void aGroupIsReadWithOneLoadPerRunAndOneExamineEach() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			g.add(05000, 6);
			g.add(07000, 2);
			for(int i = 0; i < 6; i++) {
				rig.fake.setMem(phys(05000 + 2L * i), 040000 + i);
			}
			rig.fake.setMem(phys(07000), 0111);
			rig.fake.setMem(phys(07002), 0222);
			int before = rig.loads();
			rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));
			for(int i = 0; i < 6; i++) {
				assertEquals(040000 + i, g.cell(i).getPdpValue().word(), "cell " + i);
			}
			assertEquals(0111, g.cell(6).getPdpValue().word());
			assertEquals(0222, g.cell(7).getPdpValue().word());
			assertEquals(2, rig.loads() - before);
		}
	}

	@Test
	void examinedValuesReachEveryGroupHoldingTheSameAddress() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup a = rig.groups.addGroup(MAT, "one");
			MemoryCellGroup b = rig.groups.addGroup(MAT, "two");
			a.add(01000);
			var mirror = b.add(01000);
			rig.fake.setMem(phys(01000), 0654);
			rig.connection.run(() -> rig.console.examine(a, false, ProgressMonitor.NULL));
			assertEquals(0654, mirror.getPdpValue().word());
		}
	}

	// ---------------------------------------------------------------------------------------
	// What would stop it, and what does
	// ---------------------------------------------------------------------------------------

	/**
	 * The registers cannot be reached, and the emulator halts on most of them. Examining one says
	 * "unknown" without asking, as the Pascal does; depositing one is refused out loud, where the
	 * Pascal skips it silently and a group deposit would have marked the cell written.
	 */
	@Test
	void theRegistersAreNeverAskedAbout() throws Exception {
		try(Rig rig = new Rig()) {
			assertFalse(rig.connection.call(() -> rig.console.examine(phys(0177700))).isKnown());
			assertThrows(ConsoleException.class, () -> rig.connection.run(() -> rig.console.deposit(phys(0177702), 1)));
			assertEquals(FakePdp11M9312.State.PROMPT, rig.fake.getState(), "the emulator should still be alive");
			//-- And it still answers.
			rig.connection.run(() -> rig.console.deposit(phys(01000), 7));
			assertEquals(7, rig.connection.call(() -> rig.console.examine(phys(01000))).word());
		}
	}

	@Test
	void anOddAddressIsRefusedBeforeTheEmulatorCanHaltOnIt() throws Exception {
		try(Rig rig = new Rig()) {
			assertThrows(ConsoleException.class, () -> rig.connection.call(() -> rig.console.examine(phys(01001))));
			assertThrows(ConsoleException.class, () -> rig.connection.run(() -> rig.console.deposit(phys(01001), 1)));
			assertThrows(ConsoleException.class, () -> rig.connection.run(
				() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01001))));
			assertEquals(FakePdp11M9312.State.PROMPT, rig.fake.getState());
			assertFalse(rig.fake.isRunning());
		}
	}

	/**
	 * Every other console answers a nonexistent address with "unknown". This one cannot: the bus
	 * error halts the CPU it is running on. The driver cannot prevent that, so it has to say so.
	 */
	@Test
	void aNonexistentAddressStopsTheMachineAndTheExceptionSaysSo() throws Exception {
		try(Rig rig = new Rig()) {
			NoConsolePromptException x = assertThrows(NoConsolePromptException.class,
				() -> rig.connection.call(() -> rig.console.examine(phys(NONEXISTENT))));
			assertTrue(x.getMessage().contains("front panel"), x.getMessage());
			assertEquals(FakePdp11M9312.State.HALTED, rig.fake.getState());
		}
	}

	@Test
	void whatWasReadBeforeTheMachineStoppedIsKept() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			var missing = g.add(phys(NONEXISTENT));
			var first = g.add(phys(06000));
			var second = g.add(phys(06002));
			rig.fake.setMem(phys(06000), 0123);
			rig.fake.setMem(phys(06002), 0456);
			//-- Read in address order, so the two that exist are read before the one that kills it.
			assertThrows(NoConsolePromptException.class,
				() -> rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL)));
			assertEquals(0123, first.getPdpValue().word());
			assertEquals(0456, second.getPdpValue().word());
			assertFalse(missing.getPdpValue().isKnown());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Running
	// ---------------------------------------------------------------------------------------

	/**
	 * Start, and then nothing until somebody reboots the machine from the front panel - at which
	 * point the register dump and the prompt arrive, and that is the stop. The fake takes ESC for
	 * the front panel's CTRL-BOOT.
	 */
	@Test
	void aStartedProgramIsOnlyHeardFromAgainAfterAReboot() throws Exception {
		try(Rig rig = new Rig()) {
			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			assertTrue(rig.fake.isRunning(), "the program should be running");

			CountDownLatch stopped = new CountDownLatch(1);
			AtomicBoolean called = new AtomicBoolean();
			AtomicReference<Address> reported = new AtomicReference<>(Address.of(MAT, 0));
			rig.console.setExecutionStopListener((console, pc) -> {
				called.set(true);
				reported.set(pc);
				stopped.countDown();
			});
			rig.machineClock.fireAll();
			assertEquals(FakePdp11M9312.State.HALTED, rig.fake.getState());
			assertFalse(stopped.await(300, TimeUnit.MILLISECONDS), "a halted M9312 says nothing a driver can read");

			rig.connection.sendUserInput("\u001b");
			assertTrue(stopped.await(5, TimeUnit.SECONDS), "the reboot should have reported a stop");
			assertTrue(called.get());
			assertNull(reported.get(), "the register dump is the emulator's own registers, not the program's PC");

			//-- And the console is usable again.
			rig.connection.run(() -> rig.console.deposit(phys(01000), 5));
			assertEquals(5, rig.fake.getMem(phys(01000)));
		}
	}

	// ---------------------------------------------------------------------------------------
	// The M9301
	// ---------------------------------------------------------------------------------------

	/** A {@code $} prompt with a NUL after it; the scanner drops the NUL like every other. */
	@Test
	void theM9301IsTheSameConsoleWithADollarPrompt() throws Exception {
		try(Rig rig = new Rig(BootRom.M9301)) {
			assertEquals("PDP-11 M9301 console", rig.console.name());
			rig.connection.run(() -> rig.console.deposit(phys(01000), 0321));
			assertEquals(0321, rig.connection.call(() -> rig.console.examine(phys(01000))).word());
		}
	}

	// ---------------------------------------------------------------------------------------
	// The decoder, by hand
	// ---------------------------------------------------------------------------------------

	private static final String TRANSCRIPT = "\n\r\r\r000000 173400 165212 001000 \n\r\r\r@"
		+ "L 001000\n\r\r\r@"
		+ "E 001000 000123 \n\r\r\r@";

	private static List<AnswerPhrase> significant(M9312Console console) {
		return console.getAnswers().snapshot().stream()
			.filter(p -> !(p instanceof AnswerPhrase.OtherLine))
			.toList();
	}

	private static void assertTranscriptDecoded(List<AnswerPhrase> phrases) {
		assertEquals(5, phrases.size(), phrases.toString());
		assertInstanceOf(AnswerPhrase.Halt.class, phrases.get(0));
		assertNull(((AnswerPhrase.Halt) phrases.get(0)).haltAddr());
		assertInstanceOf(AnswerPhrase.Prompt.class, phrases.get(1));
		assertInstanceOf(AnswerPhrase.Prompt.class, phrases.get(2));
		AnswerPhrase.ExamineResult r = assertInstanceOf(AnswerPhrase.ExamineResult.class, phrases.get(3));
		assertEquals(01000, r.examineAddr().val());
		assertEquals(0123, r.value().word());
		assertInstanceOf(AnswerPhrase.Prompt.class, phrases.get(4));
	}

	@Test
	void aTranscriptDecodesTheSameWhole() {
		M9312Console console = new M9312Console(new MemoryCellGroups(), BootRom.M9312, null, Logger.NULL);
		console.onSerialReceive(TRANSCRIPT);
		assertTranscriptDecoded(significant(console));
	}

	/**
	 * A serial line delivers a byte at a time, and every phrase has to survive being cut anywhere.
	 * In particular the prompt, which the decoder reads one symbol past: a number cut short right
	 * after it must not make the decoder rewind and publish the prompt a second time.
	 */
	@Test
	void andOneByteAtATime() {
		M9312Console console = new M9312Console(new MemoryCellGroups(), BootRom.M9312, null, Logger.NULL);
		for(int i = 0; i < TRANSCRIPT.length(); i++) {
			console.onSerialReceive(String.valueOf(TRANSCRIPT.charAt(i)));
		}
		assertTranscriptDecoded(significant(console));
	}

	@Test
	void aDumpThatIsNotFourSixDigitNumbersIsNotAStop() {
		M9312Console console = new M9312Console(new MemoryCellGroups(), BootRom.M9312, null, Logger.NULL);
		console.onSerialReceive("\n\r000000 1734 165212 001000 \n\r@");
		List<AnswerPhrase> phrases = significant(console);
		assertEquals(1, phrases.size(), phrases.toString());
		assertInstanceOf(AnswerPhrase.Prompt.class, phrases.get(0));
	}
}
