package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.fake.FakePdp11Odt;
import to.etc.pdp11.core.io.FakeTransport;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.ProgressMonitor;
import to.etc.pdp11.core.util.Scheduler;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ODT console driven end to end against the ported {@link FakePdp11Odt}.
 *
 * <p>Phase 3 ported the fake against a transcript taken from a real PDP-11/23 in 2008 - odd
 * addresses, nonexistent memory, echo-then-{@code ?}, the missing {@code @} on auto-advanced
 * lines. This is the other half of that: the driver, against the same behaviour, so both
 * dialects of a console nobody here owns the hardware for are exercised on every build.</p>
 */
class OdtConsoleTest {
	private static final MemoryAddressType MAT = MemoryAddressType.PHYSICAL22;

	/** A connected ODT console and the fake machine behind it. */
	private static final class Rig implements AutoCloseable {
		final Scheduler.Manual machineClock = new Scheduler.Manual();

		final FakePdp11Odt fake;

		final MemoryCellGroups groups = new MemoryCellGroups();

		final ConsoleConnection connection;

		final OdtConsole console;

		Rig() throws ConsoleException {
			this(FakePdp11Odt.OdtDialect.DEC, OdtDialect.DEC);
		}

		Rig(FakePdp11Odt.OdtDialect machineDialect, OdtDialect consoleDialect) throws ConsoleException {
			fake = new FakePdp11Odt(MAT, machineClock, new Random(7), machineDialect);
			fake.powerOn();
			connection = new ConsoleConnection(new FakeTransport(fake), Logger.NULL);
			console = new OdtConsole(groups, MAT, consoleDialect, Logger.NULL);
			connection.attach(console);
			connection.run(() -> console.init(connection));
		}

		@Override
		public void close() {
			connection.close();
		}
	}

	/**
	 * The command timeout is a setting, and every wait in an exchange honours it.
	 *
	 * <p>FABLE-ISSUES #54: {@code checkPromptAfter} asked for the current value while the waits
	 * inside examine, deposit and halt named the {@code CMD_TIMEOUT_MS} constant directly. Moving
	 * the setting therefore moved some of each exchange and left the rest where it was - which is
	 * worse than not being able to change it at all, because the console then behaves as though
	 * two timeouts were in force at once.</p>
	 *
	 * <p>The transport here is one that never answers, so what is measured is the wait itself: a
	 * quarter of the default has to give up in well under the default.</p>
	 */
	@Test
	void everyWaitInAnExchangeHonoursTheTimeoutSetting() throws Exception {
		SilentTransport silent = new SilentTransport();
		ConsoleConnection connection = new ConsoleConnection(silent, Logger.NULL);
		OdtConsole console = new OdtConsole(new MemoryCellGroups(), MAT, OdtDialect.DEC, Logger.NULL);
		connection.attach(console);
		try {
			//-- Connected as far as this console is concerned - there is simply nothing at the
			//-- other end. The handshake cannot succeed against silence, and is not the point.
			connection.run(() -> {
				try {
					console.init(connection);
				} catch(ConsoleException x) {
					//-- Expected: nothing answered the wake-up. What matters is that the console
					//-- now has its connection and will write and wait like any other.
				}
			});

			console.setCommandTimeoutMillis(OdtConsole.CMD_TIMEOUT_MS / 4);
			long started = System.nanoTime();
			//-- An examine against silence is two waits: for the answer, and for the prompt. At a
			//-- quarter of the default they come to half of it; with either of them still naming
			//-- the constant they come to more than the whole of it.
			assertThrows(NoConsolePromptException.class,
				() -> connection.call(() -> console.examine(phys(01000))));
			long tookMillis = (System.nanoTime() - started) / 1_000_000;

			assertTrue(tookMillis < OdtConsole.CMD_TIMEOUT_MS,
				"the examine waited " + tookMillis + " ms; the setting said "
					+ console.getCommandTimeoutMillis() + " ms and the constant says "
					+ OdtConsole.CMD_TIMEOUT_MS);
		} finally {
			connection.close();
		}
	}

	/** A transport that takes everything and answers nothing, until it is closed. */
	private static final class SilentTransport implements to.etc.pdp11.core.io.PhysicalTransport {
		private final Object m_lock = new Object();

		private boolean m_closed;

		@Override
		public int read(byte[] buf, int off, int len) {
			synchronized(m_lock) {
				while(!m_closed) {
					try {
						m_lock.wait();
					} catch(InterruptedException x) {
						Thread.currentThread().interrupt();
						return -1;
					}
				}
				return -1;
			}
		}

		@Override
		public void write(byte[] buf, int off, int len) {
			//-- Into the void, which is the point.
		}

		@Override
		public boolean isOpen() {
			synchronized(m_lock) {
				return !m_closed;
			}
		}

		@Override
		public String describe() {
			return "a machine that never answers";
		}

		@Override
		public void close() {
			synchronized(m_lock) {
				m_closed = true;
				m_lock.notifyAll();
			}
		}
	}

	private static Address phys(long v) {
		return Address.of(MAT, v);
	}

	private static Address reg(int n) {
		return Address.of(MAT, MAT.getIopageBase() + 017700 + n);
	}

	// ---------------------------------------------------------------------------------------
	// Connecting
	// ---------------------------------------------------------------------------------------

	@Test
	void connectingHitsReturnAndGetsThePrompt() throws Exception {
		try(Rig rig = new Rig()) {
			assertEquals(FakePdp11Odt.OdtState.PROMPT, rig.fake.getState());
			assertEquals(MAT, rig.console.physicalAddressType());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Examine and deposit
	// ---------------------------------------------------------------------------------------

	@Test
	void aDepositedWordReadsBack() throws Exception {
		try(Rig rig = new Rig()) {
			Address a = phys(01000);
			rig.connection.run(() -> rig.console.deposit(a, 0123456));
			assertEquals(0123456, rig.fake.getMem(a), "the machine really has it");
			assertEquals(0123456, rig.connection.call(() -> rig.console.examine(a)).word());
		}
	}

	@Test
	void examiningTwiceInARowStaysInStep() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Every character sent is echoed and the reply is glued to the echo, so losing
			//-- track by one symbol garbles everything after it. Repetition is the test.
			for(int i = 0; i < 10; i++) {
				Address a = phys(02000 + 2L * i);
				int v = 01000 + i;
				rig.fake.setMem(a, v);
				assertEquals(v, rig.connection.call(() -> rig.console.examine(a)).word(), "round " + i);
			}
		}
	}

	@Test
	void theCpuRegistersAreReachedByNameBecauseOdtWillNotAddressThemOctally() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Opening 017777700 by its octal address is an error on a real 11/23; R0 is the
			//-- only way in, and the fake refuses the octal form for exactly that reason.
			rig.connection.run(() -> rig.console.deposit(reg(3), 04321));
			assertEquals(04321, rig.connection.call(() -> rig.console.examine(reg(3))).word());
		}
	}

	@Test
	void thePswIsCalledRs() throws Exception {
		try(Rig rig = new Rig()) {
			Address psw = phys(MAT.getIopageBase() + 017776);
			assertEquals("RS", rig.console.addressText(psw));
			rig.connection.run(() -> rig.console.deposit(psw, 0340));
			assertEquals(0340, rig.connection.call(() -> rig.console.examine(psw)).word());
		}
	}

	@Test
	void anOddAddressIsRefusedWithAQuestionMarkAndTheConsoleCarriesOn() throws Exception {
		try(Rig rig = new Rig()) {
			//-- "@1001/?" - ODT answers the slash with a question mark on the same line. It is
			//-- an answer, not a failure, and the value is simply not known.
			CellValue v = rig.connection.call(() -> rig.console.examine(phys(01001)));
			assertFalse(v.isKnown());
			//-- And the conversation survives it, which is the part worth checking.
			rig.connection.run(() -> rig.console.deposit(phys(01000), 0777));
			assertEquals(0777, rig.connection.call(() -> rig.console.examine(phys(01000))).word());
		}
	}

	@Test
	void nonexistentMemoryReadsBackAsZeroBecauseThatIsWhatTheHardwareDoes() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Measured on a real 11/23 in 2008 and carried through the fake: an unimplemented
			//-- address answers 0 immediately rather than timing out.
			assertEquals(0, rig.connection.call(() -> rig.console.examine(phys(010000000L))).word());
		}
	}

	@Test
	void aWholeGroupIsReadOneLocationAtATimeBecauseOdtHasNoRanges() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			g.add(03000, 6);
			for(int i = 0; i < 6; i++) {
				rig.fake.setMem(phys(03000 + 2L * i), 040000 + i);
			}
			rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));
			for(int i = 0; i < 6; i++) {
				assertEquals(040000 + i, g.cell(i).getPdpValue().word(), "cell " + i);
			}
		}
	}

	@Test
	void examinedValuesReachEveryGroupHoldingTheSameAddress() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup a = rig.groups.addGroup(MAT, "one");
			MemoryCellGroup b = rig.groups.addGroup(MAT, "two");
			a.add(01000);
			MemoryCell mirror = b.add(01000);
			rig.fake.setMem(phys(01000), 0654);
			rig.connection.run(() -> rig.console.examine(a, false, ProgressMonitor.NULL));
			assertEquals(0654, mirror.getPdpValue().word());
		}
	}

	@Test
	void aBulkDepositWritesEveryEditedCell() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			g.add(05000, 4);
			for(int i = 0; i < 4; i++) {
				g.cell(i).setEditValue(CellValue.of(060000 + i));
			}
			rig.connection.run(() -> rig.console.deposit(g, false, ProgressMonitor.NULL));
			for(int i = 0; i < 4; i++) {
				assertEquals(060000 + i, rig.fake.getMem(phys(05000 + 2L * i)), "cell " + i);
			}
		}
	}

	// ---------------------------------------------------------------------------------------
	// The ENABLE/HALT switch
	// ---------------------------------------------------------------------------------------

	@Test
	void whatThisConsoleCanDoDependsOnWhereTheSwitchIs() throws Exception {
		try(Rig rig = new Rig()) {
			rig.console.setRunMode(ConsoleRunMode.HALT);
			assertTrue(rig.console.features().contains(ConsoleFeature.ACTION_SINGLE_STEP));
			assertTrue(rig.console.features().contains(ConsoleFeature.ACTION_RESET_MACHINE));
			assertFalse(rig.console.features().contains(ConsoleFeature.ACTION_CONTINUE_CPU));

			rig.console.setRunMode(ConsoleRunMode.RUN);
			assertTrue(rig.console.features().contains(ConsoleFeature.ACTION_CONTINUE_CPU));
			assertTrue(rig.console.features().contains(ConsoleFeature.ACTION_RESET_AND_START_CPU));
			assertFalse(rig.console.features().contains(ConsoleFeature.ACTION_SINGLE_STEP));

			//-- "nnnG" and "P" mean different things on each side of that switch, so asking for
			//-- the wrong one is refused rather than sent.
			assertThrows(ConsoleException.class, () -> rig.connection.run(() -> rig.console.singleStep()));
		}
	}

	@Test
	void odtCannotStopARunningMachineAndSaysSo() throws Exception {
		try(Rig rig = new Rig()) {
			//-- ODT is the CPU's own microcode: while it is executing a program, nobody is
			//-- listening. The Pascal leaves this method abstract, so calling it raises an
			//-- abstract-method error instead of explaining anything.
			ConsoleException x = assertThrows(ConsoleException.class,
				() -> rig.connection.call(() -> rig.console.haltCpu()));
			assertTrue(x.getMessage().contains("HALT switch"), x.getMessage());
			assertFalse(rig.console.features().contains(ConsoleFeature.ACTION_HALT_CPU));
		}
	}

	@Test
	void resettingWithTheSwitchAtHaltLoadsThePcAndStaysStopped() throws Exception {
		try(Rig rig = new Rig()) {
			rig.console.setRunMode(ConsoleRunMode.HALT);
			rig.fake.setRunMode(false);
			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			rig.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				stopped.countDown();
			});

			rig.connection.run(() -> rig.console.resetMachine(Address.of(MemoryAddressType.VIRTUAL, 04000)));
			assertTrue(stopped.await(5, TimeUnit.SECONDS), "the machine reports where it is");
			assertEquals(04000, reported.get().val());
			assertEquals(MemoryAddressType.VIRTUAL, reported.get().type());
			//-- The PC really moved, which is why RESET_CPU_SETS_PC is among the features.
			assertEquals(04000, rig.fake.getMem(rig.fake.getProgramCounterAddr()));
			assertTrue(rig.console.features().contains(ConsoleFeature.RESET_CPU_SETS_PC));
		}
	}

	@Test
	void aSingleStepAdvancesThePcByOneWord() throws Exception {
		try(Rig rig = new Rig()) {
			rig.console.setRunMode(ConsoleRunMode.HALT);
			rig.fake.setRunMode(false);
			rig.fake.setMem(rig.fake.getProgramCounterAddr(), 01000);
			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			rig.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				stopped.countDown();
			});

			rig.connection.run(() -> rig.console.singleStep());
			assertTrue(stopped.await(5, TimeUnit.SECONDS));
			assertEquals(01002, reported.get().val());
		}
	}

	@Test
	void startingTheMachineSendsNnnGAndExpectsNoPromptBack() throws Exception {
		try(Rig rig = new Rig()) {
			rig.console.setRunMode(ConsoleRunMode.RUN);
			rig.fake.setRunMode(true);
			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			assertTrue(rig.fake.isRunning(), "the machine should be off and running");

			//-- And when the pretend program hits its HALT, the address it stopped at arrives
			//-- as a line of its own followed by a prompt, which is ODT's whole halt protocol.
			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			rig.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				stopped.countDown();
			});
			rig.machineClock.fireAll();
			assertTrue(stopped.await(5, TimeUnit.SECONDS), "no stop event arrived");
			assertNotNull(reported.get());
			assertEquals(MemoryAddressType.VIRTUAL, reported.get().type());
		}
	}

	// ---------------------------------------------------------------------------------------
	// The K1630
	// ---------------------------------------------------------------------------------------

	@Test
	void theK1630SpeaksTheSameProtocolWithASpaceAndAnAddressSuffix() throws Exception {
		try(Rig rig = new Rig(FakePdp11Odt.OdtDialect.K1630, OdtDialect.K1630)) {
			//-- Its prompt is "@ " rather than "@", and a physical address is written "nnnnA".
			assertEquals("1000A", rig.console.addressText(phys(01000)));
			rig.connection.run(() -> rig.console.deposit(phys(01000), 07654));
			assertEquals(07654, rig.connection.call(() -> rig.console.examine(phys(01000))).word());
			//-- Registers keep their names, suffix or no suffix.
			assertEquals("R5", rig.console.addressText(reg(5)));
			rig.connection.run(() -> rig.console.deposit(reg(5), 01111));
			assertEquals(01111, rig.connection.call(() -> rig.console.examine(reg(5))).word());
		}
	}

	@Test
	void aDialectMismatchIsNotSilentlyToleratedInEitherDirection() throws Exception {
		//-- A DEC driver against a K1630 machine gets a prompt with a space after it and an
		//-- address it never suffixed. Space-gobbling is on for both dialects, so the prompt
		//-- still parses; what fails is the address suffix, and it fails as a refused command
		//-- rather than as a wrong value read from the wrong place.
		try(Rig rig = new Rig(FakePdp11Odt.OdtDialect.K1630, OdtDialect.DEC)) {
			assertEquals(0, rig.connection.call(() -> rig.console.examine(phys(01000))).wordOr(0),
				"a DEC driver can still read a K1630 - it just never writes the A suffix");
		}
	}

	// ---------------------------------------------------------------------------------------
	// The scanner
	// ---------------------------------------------------------------------------------------

	@Test
	void aReplyArrivingOneByteAtATimeDecodesTheSameAsOneArrivingWhole() throws Exception {
		OdtConsole whole = new OdtConsole(new MemoryCellGroups(), MAT, OdtDialect.DEC, Logger.NULL);
		OdtConsole piecemeal = new OdtConsole(new MemoryCellGroups(), MAT, OdtDialect.DEC, Logger.NULL);
		String transcript = "\r\n@1000/123456 \r\n@";
		whole.onSerialReceive(transcript);
		for(int i = 0; i < transcript.length(); i++) {
			piecemeal.onSerialReceive(transcript.substring(i, i + 1));
		}
		List<AnswerPhrase> a = whole.getAnswers().snapshot();
		List<AnswerPhrase> b = piecemeal.getAnswers().snapshot();
		assertEquals(a.toString(), b.toString());
		//-- Prompt, examine, the space ODT prints after the value, prompt. The space becomes a
		//-- phrase of its own because the examine rule stops at the value and nothing claims
		//-- what follows - the Pascal does exactly the same, and nothing minds, since only
		//-- prompts and examine results are ever waited for.
		assertEquals(4, a.size(), a.toString());
		//-- The point of the comparison above is that an octal number is never complete while
		//-- it might still grow, which is what makes the byte-at-a-time case come out the same.
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) a.get(1);
		assertEquals(01000, r.examineAddr().val());
		assertEquals(0123456, r.value().word());
	}

	@Test
	void anAddressAloneOnALineIsAHaltAndThePromptAfterItIsTheEvent() throws Exception {
		OdtConsole c = new OdtConsole(new MemoryCellGroups(), MAT, OdtDialect.DEC, Logger.NULL);
		c.onSerialReceive("\r\n001000\r\n@");
		List<AnswerPhrase> l = c.getAnswers().snapshot();
		assertEquals(2, l.size(), l.toString());
		AnswerPhrase.Halt h = (AnswerPhrase.Halt) l.get(0);
		assertEquals(01000, h.haltAddr().val());
		assertEquals(MemoryAddressType.VIRTUAL, h.haltAddr().type());
		assertEquals(01000, c.getExecutionStopPc().val(), "the prompt is what makes it actionable");
	}
}
