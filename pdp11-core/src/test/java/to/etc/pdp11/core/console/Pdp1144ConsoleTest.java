package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.fake.FakePdp11;
import to.etc.pdp11.core.fake.FakePdp1144;
import to.etc.pdp11.core.fake.FakePdp1144V340c;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PDP-11/44 console driven end to end against both firmwares' fakes.
 *
 * <p>The two say the same things in different words, so almost every test here runs twice - once
 * against each - and the ones that do not are the ones about a difference.</p>
 */
class Pdp1144ConsoleTest {
	private static final MemoryAddressType MAT = MemoryAddressType.PHYSICAL22;

	private static final long REG_BASE = 017777700L;

	/**
	 * A transport that can be told to swallow everything written to it - a line that goes quiet
	 * mid-conversation, which is the only way to see a command time out without a real one.
	 */
	private static final class DeafableTransport implements to.etc.pdp11.core.io.PhysicalTransport {
		private final FakeTransport m_real;

		private volatile boolean m_deaf;

		DeafableTransport(FakePdp11 fake) {
			m_real = new FakeTransport(fake);
		}

		void setDeaf(boolean deaf) {
			m_deaf = deaf;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws java.io.IOException {
			return m_real.read(buf, off, len);
		}

		@Override
		public void write(byte[] buf, int off, int len) throws java.io.IOException {
			if(!m_deaf)
				m_real.write(buf, off, len);
		}

		@Override
		public boolean isOpen() {
			return m_real.isOpen();
		}

		@Override
		public void close() {
			m_real.close();
		}

		@Override
		public String describe() {
			return "deafable " + m_real.describe();
		}
	}

	/** A connected 11/44 console and the fake machine behind it. */
	private static final class Rig implements AutoCloseable {
		final Scheduler.Manual machineClock = new Scheduler.Manual();

		final FakePdp11 fake;

		final MemoryCellGroups groups = new MemoryCellGroups();

		final ConsoleConnection connection;

		final Pdp1144Console console;

		final DeafableTransport transport;

		Rig(Pdp1144Firmware firmware) throws ConsoleException {
			fake = firmware == Pdp1144Firmware.V340C
				? new FakePdp1144V340c(machineClock, new Random(3))
				: new FakePdp1144(machineClock, new Random(3));
			fake.powerOn();
			transport = new DeafableTransport(fake);
			connection = new ConsoleConnection(transport, Logger.NULL);
			console = new Pdp1144Console(groups, firmware, Logger.NULL);
			connection.attach(console);
			connection.run(() -> console.init(connection));
			//-- The prompt this machine came up at is a halt report, and it is not any test's
			//-- stop. See ConsoleRigs.
			ConsoleRigs.drainPowerOnStop(connection);
		}

		@Override
		public void close() {
			connection.close();
		}
	}

	private static Address phys(long v) {
		return Address.of(MAT, v);
	}

	/** Every firmware, so a test written once covers both. */
	private static List<Pdp1144Firmware> firmwares() {
		return List.of(Pdp1144Firmware.CLASSIC, Pdp1144Firmware.V340C);
	}

	// ---------------------------------------------------------------------------------------
	// Connecting
	// ---------------------------------------------------------------------------------------

	@Test
	void connectingSendsControlCAndGetsThePrompt() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				//-- ^C rather than a return, because it also abandons any half-typed line - which
				//-- is the state a resync is usually getting out of.
				assertEquals(MAT, rig.console.physicalAddressType(), f.toString());
				assertFalse(rig.console.features().contains(ConsoleFeature.RESET_CPU_SETS_PC),
					"\"I\" initialises but leaves the PC alone");
			}
		}
	}

	// ---------------------------------------------------------------------------------------
	// Examine and deposit
	// ---------------------------------------------------------------------------------------

	@Test
	void aDepositedWordReadsBack() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				Address a = phys(01000);
				rig.connection.run(() -> rig.console.deposit(a, 0123456));
				assertEquals(0123456, rig.fake.getMem(a), f + ": the machine really has it");
				assertEquals(0123456, rig.connection.call(() -> rig.console.examine(a)).word(), f.toString());
			}
		}
	}

	@Test
	void aRunOfExaminesStaysInStep() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				for(int i = 0; i < 10; i++) {
					Address a = phys(02000 + 2L * i);
					int v = 01000 + i;
					rig.fake.setMem(a, v);
					assertEquals(v, rig.connection.call(() -> rig.console.examine(a)).word(),
						f + " round " + i);
				}
			}
		}
	}

	@Test
	void theGlobalRegistersAreReachedThroughSlashG() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				//-- R0..R7 and the second set live at sixteen consecutive byte addresses, and the
				//-- console will not take an address for them - "E/G 3" is the only way in.
				Address r3 = phys(REG_BASE + 3);
				rig.connection.run(() -> rig.console.deposit(r3, 04321));
				assertEquals(04321, rig.connection.call(() -> rig.console.examine(r3)).word(), f.toString());
			}
		}
	}

	@Test
	void consecutiveDepositsSayPlusInsteadOfRepeatingTheAddress() throws Exception {
		try(Rig rig = new Rig(Pdp1144Firmware.CLASSIC)) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			g.add(03000, 4);
			for(int i = 0; i < 4; i++) {
				g.cell(i).setEditValue(CellValue.of(050000 + i));
			}
			rig.connection.run(() -> rig.console.deposit(g, false, ProgressMonitor.NULL));
			for(int i = 0; i < 4; i++) {
				assertEquals(050000 + i, rig.fake.getMem(phys(03000 + 2L * i)), "word " + i);
			}
			//-- The point of "+" is the characters it saves on a line where characters are the
			//-- cost; that it lands in the right place is what matters here.
			rig.connection.run(() -> rig.console.deposit(phys(04000), 077));
			rig.connection.run(() -> rig.console.deposit(phys(04002), 0177));
			assertEquals(077, rig.fake.getMem(phys(04000)));
			assertEquals(0177, rig.fake.getMem(phys(04002)));
		}
	}

	@Test
	void aRegisterDepositDoesNotLeavePlusPointingAtIt() throws Exception {
		try(Rig rig = new Rig(Pdp1144Firmware.CLASSIC)) {
			//-- "+" means the next memory word. After a "D/G" there is no such thing, so the next
			//-- deposit has to name its address again.
			rig.connection.run(() -> rig.console.deposit(phys(01000), 011));
			rig.connection.run(() -> rig.console.deposit(phys(REG_BASE + 1), 022));
			rig.connection.run(() -> rig.console.deposit(phys(01002), 033));
			assertEquals(011, rig.fake.getMem(phys(01000)));
			assertEquals(022, rig.fake.getMem(phys(REG_BASE + 1)));
			assertEquals(033, rig.fake.getMem(phys(01002)));
		}
	}

	@Test
	void aNonexistentAddressIsAUnibusTimeoutNotAFailure() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				//-- Above the fitted memory and below the I/O page. Both firmwares call it
				//-- something different and both mean the same thing.
				CellValue v = rig.connection.call(() -> rig.console.examine(phys(010000000L)));
				assertFalse(v.isKnown(), f.toString());
				//-- And the console is still usable afterwards.
				rig.connection.run(() -> rig.console.deposit(phys(01000), 0777));
				assertEquals(0777, rig.connection.call(() -> rig.console.examine(phys(01000))).word(), f.toString());
			}
		}
	}

	// ---------------------------------------------------------------------------------------
	// Bulk examine - the reason this console is worth having
	// ---------------------------------------------------------------------------------------

	@Test
	void aBlockOfMemoryComesBackInOneCommand() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
				g.add(05000, 16);
				for(int i = 0; i < 16; i++) {
					rig.fake.setMem(phys(05000 + 2L * i), 060000 + i);
				}
				rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));
				for(int i = 0; i < 16; i++) {
					assertEquals(060000 + i, g.cell(i).getPdpValue().word(), f + " cell " + i);
				}
			}
		}
	}

	@Test
	void aBlockThatRunsIntoNothingStopsThereAndTheRestIsRetried() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				MemoryCellGroup g = rig.groups.addGroup(MAT, "with a hole");
				//-- Two real words, two that do not exist, one more that does. The console stops
				//-- at the first bad address and does not say which it was, so it has to be
				//-- worked out from the last one that answered.
				g.add(01000);
				g.add(01002);
				g.add(010000000L);
				g.add(010000002L);
				g.add(01004);
				rig.fake.setMem(phys(01000), 01);
				rig.fake.setMem(phys(01002), 02);
				rig.fake.setMem(phys(01004), 03);

				rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));
				assertEquals(01, g.findByAddress(01000).getPdpValue().word(), f.toString());
				assertEquals(02, g.findByAddress(01002).getPdpValue().word(), f.toString());
				assertEquals(03, g.findByAddress(01004).getPdpValue().word(), f.toString());
				assertFalse(g.findByAddress(010000000L).getPdpValue().isKnown(), f.toString());
				assertFalse(g.findByAddress(010000002L).getPdpValue().isKnown(), f.toString());
			}
		}
	}

	@Test
	void registersAndMemoryGoOutSeparatelyBecauseTheyStepDifferently() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				MemoryCellGroup g = rig.groups.addGroup(MAT, "mixed");
				g.add(01000, 2);
				g.add(REG_BASE);
				g.add(REG_BASE + 1);
				rig.fake.setMem(phys(01000), 011111);
				rig.fake.setMem(phys(01002), 022222);
				rig.fake.setMem(phys(REG_BASE), 033333);
				rig.fake.setMem(phys(REG_BASE + 1), 044444);

				rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));
				assertEquals(011111, g.cell(0).getPdpValue().word(), f.toString());
				assertEquals(022222, g.cell(1).getPdpValue().word(), f.toString());
				assertEquals(033333, g.cell(2).getPdpValue().word(), f.toString());
				assertEquals(044444, g.cell(3).getPdpValue().word(), f.toString());
			}
		}
	}

	@Test
	void unknownOnlyLeavesWhatIsAlreadyKnownAlone() throws Exception {
		try(Rig rig = new Rig(Pdp1144Firmware.CLASSIC)) {
			MemoryCellGroup g = rig.groups.addGroup(MAT, "memory");
			g.add(06000, 4);
			for(int i = 0; i < 4; i++) {
				rig.fake.setMem(phys(06000 + 2L * i), 0777);
			}
			g.cell(0).setPdpValue(CellValue.of(01));
			g.cell(1).setPdpValue(CellValue.of(02));
			rig.connection.run(() -> rig.console.examine(g, true, ProgressMonitor.NULL));
			assertEquals(01, g.cell(0).getPdpValue().word(), "already known, so not asked for again");
			assertEquals(02, g.cell(1).getPdpValue().word());
			assertEquals(0777, g.cell(2).getPdpValue().word());
			assertEquals(0777, g.cell(3).getPdpValue().word());
		}
	}

	@Test
	void examinedValuesReachEveryGroupHoldingTheSameAddress() throws Exception {
		try(Rig rig = new Rig(Pdp1144Firmware.CLASSIC)) {
			MemoryCellGroup a = rig.groups.addGroup(MAT, "one");
			MemoryCellGroup b = rig.groups.addGroup(MAT, "two");
			a.add(01000);
			MemoryCell mirror = b.add(01000);
			rig.fake.setMem(phys(01000), 0654);
			rig.connection.run(() -> rig.console.examine(a, false, ProgressMonitor.NULL));
			assertEquals(0654, mirror.getPdpValue().word());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Execution control
	// ---------------------------------------------------------------------------------------

	@Test
	void aStopReportIsAlsoAnExamineAnswerAndHasToBeBoth() throws Exception {
		//-- "17777707 000114" means the CPU stopped at 000114 - and it is exactly what "E/G 7"
		//-- answers. One line, two phrases, halt first, because it is the prompt after them that
		//-- fires the stop event and the prompt looks past the examine for it.
		Pdp1144Console c = new Pdp1144Console(new MemoryCellGroups(), Pdp1144Firmware.CLASSIC, Logger.NULL);
		c.onSerialReceive("17777707 000114\r\n>>>");
		List<AnswerPhrase> l = c.getAnswers().snapshot();
		assertEquals(3, l.size(), l.toString());
		AnswerPhrase.Halt halt = (AnswerPhrase.Halt) l.get(0);
		assertEquals(0114, halt.haltAddr().val());
		assertEquals(MemoryAddressType.VIRTUAL, halt.haltAddr().type(), "a console reports the PC as the program sees it");
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) l.get(1);
		assertEquals(017777707L, r.examineAddr().val());
		assertEquals(0114, r.value().word());
		assertEquals(0114, c.getExecutionStopPc().val(), "the prompt is what makes it actionable");
	}

	@Test
	void thatAddressOnlyCountsAtTheStartOfALine() throws Exception {
		//-- 17777707 appearing anywhere else is just an address, so a line that merely contains
		//-- it is not a stop report.
		Pdp1144Console c = new Pdp1144Console(new MemoryCellGroups(), Pdp1144Firmware.CLASSIC, Logger.NULL);
		c.onSerialReceive("E 17777707\r\n");
		assertTrue(c.getAnswers().snapshot().stream().noneMatch(p -> p instanceof AnswerPhrase.Halt),
			c.getAnswers().snapshot().toString());
	}

	@Test
	void theV340cSaysTheSameThingInWords() throws Exception {
		Pdp1144Console c = new Pdp1144Console(new MemoryCellGroups(), Pdp1144Firmware.V340C, Logger.NULL);
		c.onSerialReceive("  Halted at 000114\r\n>>>");
		AnswerPhrase.Halt halt = (AnswerPhrase.Halt) c.getAnswers().get(0);
		assertEquals(0114, halt.haltAddr().val());
		assertEquals(0114, c.getExecutionStopPc().val());
	}

	@Test
	void theV340cNamesTheSpaceItReadFrom() throws Exception {
		Pdp1144Console c = new Pdp1144Console(new MemoryCellGroups(), Pdp1144Firmware.V340C, Logger.NULL);
		c.onSerialReceive("  P  00001000  123456\r\n");
		AnswerPhrase.ExamineResult p = (AnswerPhrase.ExamineResult) c.getAnswers().get(0);
		assertEquals(01000, p.examineAddr().val());
		assertEquals(0123456, p.value().word());
		//-- And under G it prints the register number, so the base has to go back on.
		c.onSerialReceive("  G  00000001  000222\r\n");
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) c.getAnswers().get(1);
		assertEquals(REG_BASE + 1, r.examineAddr().val());
		assertEquals(0222, r.value().word());
	}

	@Test
	void haltingReportsWhereItStopped() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				CountDownLatch stopped = new CountDownLatch(1);
				AtomicReference<Address> reported = new AtomicReference<>();
				rig.console.setExecutionStopListener((console, pc) -> {
					reported.set(pc);
					stopped.countDown();
				});
				rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
				assertTrue(rig.fake.isRunning(), f + ": the machine should be off and running");

				Address pc = rig.connection.call(() -> rig.console.haltCpu());
				assertNotNull(pc, f.toString());
				assertEquals(01000, pc.val(), f.toString());
				assertFalse(rig.fake.isRunning(), f.toString());
				assertTrue(stopped.await(5, TimeUnit.SECONDS), f + ": no stop event arrived");
				assertEquals(01000, reported.get().val(), f.toString());
			}
		}
	}

	/**
	 * The interface says null for "a machine that had already stopped", and the execution window
	 * calls halt unconditionally, so every redundant click lands here.
	 *
	 * <p>The two firmwares differ, which is the whole point. The classic console answers
	 * {@code H} with an ordinary stop report saying where it is; V3.40C answers
	 * {@code ?Already halted} and draws its prompt with no report at all. Waiting for the report
	 * that is not coming used to sit out the whole command timeout and then throw "Stopping the
	 * CPU failed: no answer" at somebody who clicked Halt twice.</p>
	 */
	@Test
	void haltingAnAlreadyHaltedMachineDoesNotStallAndThrow() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				assertFalse(rig.fake.isRunning(), f + ": nothing has been started");
				long startedAt = System.nanoTime();
				Address pc = rig.connection.call(() -> rig.console.haltCpu());
				long tookMs = (System.nanoTime() - startedAt) / 1_000_000;

				assertTrue(tookMs < 2_000, f + ": it waited " + tookMs + "ms for an answer that never comes");
				if(f == Pdp1144Firmware.V340C)
					assertNull(pc, f + ": ?Already halted says nothing about where it is");
				else
					assertNotNull(pc, f + ": the classic console reports where it is anyway");
			}
		}
	}

	/** And the console is still usable afterwards - the redundant halt consumed its own prompt. */
	@Test
	void aRedundantHaltLeavesTheConsoleAbleToTakeTheNextCommand() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				rig.connection.call(() -> rig.console.haltCpu());
				rig.fake.setMem(phys(01000), 0123456);
				assertEquals(0123456, rig.connection.call(() -> rig.console.examine(phys(01000))).word(),
					f.toString());
			}
		}
	}

	/**
	 * {@code D +} means "the word after the one you last deposited into", and the machine's own
	 * idea of that only moves when the deposit actually happened. Recording it before the prompt
	 * confirmed the command meant a failed deposit left the console one word ahead of the
	 * machine, and the next sequential deposit landed at the wrong address without saying so.
	 */
	@Test
	void aDepositThatWasNeverConfirmedDoesNotAdvanceTheSequentialAddress() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				rig.console.setCommandTimeoutMillis(250);   // no need to sit out a real timeout
				rig.connection.run(() -> rig.console.deposit(phys(01000), 0111));
				assertEquals(0111, rig.fake.getMem(phys(01000)), f.toString());

				//-- The line goes quiet: the command never reaches the machine and no prompt
				//-- comes back, which is exactly what the caller sees as "no console prompt".
				rig.transport.setDeaf(true);
				assertThrows(ConsoleException.class,
					() -> rig.connection.run(() -> rig.console.deposit(phys(01002), 0222)), f.toString());
				rig.transport.setDeaf(false);

				//-- The machine still last deposited into 01000, so "D +" here would write 01002.
				rig.connection.run(() -> rig.console.deposit(phys(01004), 0333));
				assertEquals(0333, rig.fake.getMem(phys(01004)),
					f + ": the value went somewhere else");
				assertEquals(0, rig.fake.getMem(phys(01002)),
					f + ": and it did not land in the word the failed deposit named");
			}
		}
	}

	@Test
	void aSingleStepAdvancesThePcAndReportsIt() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				rig.fake.setMem(rig.fake.getProgramCounterAddr(), 01000);
				CountDownLatch stopped = new CountDownLatch(1);
				AtomicReference<Address> reported = new AtomicReference<>();
				rig.console.setExecutionStopListener((console, pc) -> {
					reported.set(pc);
					stopped.countDown();
				});
				rig.connection.run(() -> rig.console.singleStep());
				assertTrue(stopped.await(5, TimeUnit.SECONDS), f + ": a step ends in a stop like any other");
				assertEquals(01002, reported.get().val(), f.toString());
			}
		}
	}

	@Test
	void aProgramThatHaltsOnItsOwnIsReportedTheSameWay() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				CountDownLatch stopped = new CountDownLatch(1);
				AtomicReference<Address> reported = new AtomicReference<>();
				rig.console.setExecutionStopListener((console, pc) -> {
					reported.set(pc);
					stopped.countDown();
				});
				rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
				rig.machineClock.fireAll();
				assertTrue(stopped.await(5, TimeUnit.SECONDS), f + ": no stop event arrived");
				assertNotNull(reported.get(), f.toString());
				assertEquals(MemoryAddressType.VIRTUAL, reported.get().type(), f.toString());
			}
		}
	}

	@Test
	void initialisingDoesNotTouchThePc() throws Exception {
		for(Pdp1144Firmware f : firmwares()) {
			try(Rig rig = new Rig(f)) {
				rig.fake.setMem(rig.fake.getProgramCounterAddr(), 01000);
				rig.connection.run(() -> rig.console.resetMachine(Address.of(MemoryAddressType.VIRTUAL, 04000)));
				assertEquals(01000, rig.fake.getMem(rig.fake.getProgramCounterAddr()), f.toString());
			}
		}
	}

	@Test
	void continuingStartsTheMachineWithoutAnInitialise() throws Exception {
		try(Rig rig = new Rig(Pdp1144Firmware.CLASSIC)) {
			rig.fake.setMem(rig.fake.getProgramCounterAddr(), 01000);
			rig.connection.run(() -> rig.console.continueCpu());
			assertTrue(rig.fake.isRunning());
		}
	}
}
