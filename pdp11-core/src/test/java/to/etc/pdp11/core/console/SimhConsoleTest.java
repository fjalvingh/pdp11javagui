package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.fake.FakeSimh;
import to.etc.pdp11.core.io.FakeTransport;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.mmu.CpuMode;
import to.etc.pdp11.core.mmu.Pdp11Mmu;
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
 * The SimH console driven end to end against {@link FakeSimh}, through a real reader thread and
 * a real command executor.
 *
 * <p>This is what PLAN.md phase 4 asks for - examine and deposit against a fake, headlessly -
 * and it is the only test of this console that runs on CI, which has no SimH. What it cannot
 * prove is that the fake tells the truth about SimH; {@code SimhConsoleIT} does that against the
 * real program, and the two are deliberately written to the same expectations.</p>
 */
class SimhConsoleTest {
	/** A connected console, its fake machine, and the clocks both of them run on. */
	private static final class Rig implements AutoCloseable {
		/** Drives the fake's pretend run-to-halt; nothing fires until a test says so. */
		final Scheduler.Manual machineClock = new Scheduler.Manual();

		/** Drives the console's deferred silent-halt resolution. */
		final Scheduler.Manual consoleClock = new Scheduler.Manual();

		final FakeSimh fake;

		final FakeTransport transport;

		final MemoryCellGroups groups = new MemoryCellGroups();

		final ConsoleConnection connection;

		final SimhConsole console;

		Rig() throws ConsoleException {
			fake = new FakeSimh(machineClock, new Random(42));
			fake.powerOn();
			transport = new FakeTransport(fake);
			connection = new ConsoleConnection(transport, Logger.NULL);
			console = new SimhConsole(groups, Logger.NULL, consoleClock);
			connection.attach(console);
			connection.run(() -> console.init(connection));
		}

		@Override
		public void close() {
			connection.close();
		}
	}

	private static Address phys(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	// ---------------------------------------------------------------------------------------
	// Connecting
	// ---------------------------------------------------------------------------------------

	@Test
	void connectingSendsCtrlEAndReachesThePrompt() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Multiple-command mode is what produces a prompt at all; without the ^E a real
			//-- remote console answers nothing whatsoever, however long you wait.
			assertTrue(rig.fake.isMasterMode());
			assertEquals(SimhConsole.CpuState.HALTED, rig.console.getCpuState());
			//-- And the setup commands the Pascal's Resync sends, each confirmed by its own
			//-- prompt rather than by the previous command's.
			assertEquals(List.of("sh cpu iospace", "set throttle 5M", "deposit tti time 1300",
				"SET CPU HISTORY=100"), rig.fake.getCommands());
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
			assertEquals(0123456, rig.connection.call(() -> rig.console.examine(a)).word());
			assertTrue(rig.fake.getCommands().contains("D 1000 123456"));
			assertTrue(rig.fake.getCommands().contains("E 1000"));
		}
	}

	@Test
	void theCpuRegistersAreReachedByNameBecauseSimhKeepsThemOutOfTheAddressSpace() throws Exception {
		try(Rig rig = new Rig()) {
			//-- SimH does not map R0..R7 into memory; asking for 017777707 by address gets
			//-- "illegal address space", so the console has to know their names.
			Address pc = phys(017777707L);
			rig.connection.run(() -> rig.console.deposit(pc, 01234));
			assertEquals(01234, rig.connection.call(() -> rig.console.examine(pc)).word());
			assertTrue(rig.fake.getCommands().contains("D PC 1234"), rig.fake.getCommands().toString());
			assertTrue(rig.fake.getCommands().contains("E PC"));
		}
	}

	/**
	 * The PSW is answered with its bit-fields spelled out after the value, and that is still an
	 * answer.
	 *
	 * <p>A register SimH declares with a {@code BITFIELD} table is shown decoded - {@code E PSW}
	 * on an 11/70 answers {@code PSW:\t000340\tCM=K PM=K RS0 ... C0}. A decoder that insists on
	 * exactly two words files that as an ordinary line, and then the examine waits out its whole
	 * {@link SimhConsole#CMD_TIMEOUT_MS} for an answer that arrived in milliseconds: eight
	 * seconds of progress dialog for nothing, and a PSW that stays unknown afterwards, so the
	 * MMU never learns which mode the machine is in.</p>
	 */
	@Test
	void thePswIsAnsweredEvenThoughSimhDecoratesItWithItsBitfields() throws Exception {
		try(Rig rig = new Rig()) {
			Address psw = phys(017777776L);
			rig.connection.run(() -> rig.console.deposit(psw, 0140340));
			assertEquals(0140340, rig.connection.call(() -> rig.console.examine(psw)).word());

			//-- And in bulk, which is what "Read the MMU registers" does: the group the MMU
			//-- watches ends with the PSW, and the CPU mode comes from nowhere else.
			Pdp11Mmu mmu = rig.console.getMmu();
			rig.connection.run(() -> rig.console.examine(mmu.getRegisterGroup(), false, ProgressMonitor.NULL));
			assertTrue(mmu.getPswCell().getPdpValue().isKnown(), "the PSW was waited out, not read");
			//-- evalAll() as ExamineMMU does: propagation skips the cell it started from, so
			//-- examining the MMU's own group never reaches the MMU's own listener.
			mmu.evalAll();
			assertEquals(CpuMode.USER, mmu.getCpuMode());
		}
	}

	/**
	 * 017777710..717 are marked {@code "?"}: the second register set some machines have and SimH
	 * does not model, so there is nothing to ask about.
	 *
	 * <p><b>Reading one answers "unknown"; writing one is an error.</b> That asymmetry is
	 * deliberate. To a reader an address SimH has never heard of is indistinguishable from one
	 * that times out on the bus - the test below this says so for a real timeout - and the I/O
	 * page scanner walks all 4096 words of the page, so a throw would abort the scan eight
	 * addresses in. A deposit is the other way round: silently dropping a write is how somebody
	 * ends up debugging a machine that never received what they typed.</p>
	 */
	@Test
	void aRegisterSimhDoesNotHaveReadsAsAbsentAndRefusesToBeWritten() throws Exception {
		try(Rig rig = new Rig()) {
			CellValue v = rig.connection.call(() -> rig.console.examine(phys(017777710L)));
			assertFalse(v.isKnown(), "nothing is there, which is an answer");

			assertThrows(ConsoleException.class,
				() -> rig.connection.run(() -> rig.console.deposit(phys(017777710L), 0123)));

			//-- Either way SimH is never asked: "E ?" and "D ? 123" are not commands.
			assertFalse(rig.fake.getCommands().stream().anyMatch(c -> c.startsWith("E 177777")));
			assertFalse(rig.fake.getCommands().stream().anyMatch(c -> c.contains("?")),
				rig.fake.getCommands().toString());
		}
	}

	@Test
	void aNonexistentAddressIsAUnibusTimeoutNotAFailure() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Above the fitted memory but below the I/O page: SimH answers "Address space
			//-- exceeded", which is an answer - the value is unknown, and nothing is thrown.
			CellValue v = rig.connection.call(() -> rig.console.examine(phys(010000000L)));
			assertFalse(v.isKnown());
		}
	}

	@Test
	void aCommandSentWhileAnUnterminatedLineIsPendingStillFindsItsEcho() throws Exception {
		try(Rig rig = new Rig()) {
			//-- SimH prints "Simulator Running..." with no line ending at all, so anything sent
			//-- while that fragment is still unconsumed comes back glued to it:
			//-- "Simulator Running...E 1000". Matching the echo on equality loses the anchor
			//-- there and the command waits out its whole timeout for an echo that has already
			//-- arrived - which is exactly how this was found, as a five-second flake.
			rig.console.onSerialReceive("Simulator Running...");
			rig.connection.run(() -> rig.console.deposit(phys(01000), 0123456));
			assertEquals(0123456, rig.connection.call(() -> rig.console.examine(phys(01000))).word());
		}
	}

	@Test
	void aRejectedCommandIsReportedRatherThanSilentlyIgnored() throws Exception {
		try(Rig rig = new Rig()) {
			//-- A deposit produces no output when it works, so any line at all after the echo
			//-- means SimH refused it. A plain prompt check does not notice, because a refused
			//-- command still returns to the prompt.
			ConsoleException x = assertThrows(ConsoleException.class,
				() -> rig.connection.run(() -> rig.console.deposit(phys(010000000L), 07)));
			assertTrue(x.getMessage().contains("rejected"), x.getMessage());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Bulk examine
	// ---------------------------------------------------------------------------------------

	@Test
	void consecutiveAddressesAreBatchedIntoOneCommand() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MemoryAddressType.PHYSICAL22, "memory");
			g.add(01000, 8);
			for(int i = 0; i < 8; i++) {
				rig.fake.setMem(phys(01000 + 2L * i), 070000 + i);
			}
			rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));

			for(int i = 0; i < 8; i++) {
				assertEquals(070000 + i, g.cell(i).getPdpValue().word(), "cell " + i);
			}
			//-- One round trip for the lot: "E 1000-1016". A command per word is what this
			//-- batching exists to avoid, and on a telnet link it is the difference between a
			//-- screenful appearing at once and appearing a line at a time.
			assertTrue(rig.fake.getCommands().contains("E 1000-1016"), rig.fake.getCommands().toString());
		}
	}

	@Test
	void registersAndMemoryAreAskedForSeparatelyBecauseTheirAddressesStepDifferently() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MemoryAddressType.PHYSICAL22, "mixed");
			g.add(01000, 2);
			g.add(017777700L);                              // R0
			g.add(017777701L);                              // R1
			rig.fake.setMem(phys(01000), 011111);
			rig.fake.setMem(phys(01002), 022222);
			rig.fake.setMem(phys(017777700L), 033333);
			rig.fake.setMem(phys(017777701L), 044444);

			rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));
			assertEquals(011111, g.cell(0).getPdpValue().word());
			assertEquals(022222, g.cell(1).getPdpValue().word());
			assertEquals(033333, g.cell(2).getPdpValue().word());
			assertEquals(044444, g.cell(3).getPdpValue().word());
			//-- The pseudo-registers are one byte apart, not two, so they can never join a
			//-- range - they go out as a comma list of names in a command of their own.
			assertTrue(rig.fake.getCommands().contains("E 1000-1002"), rig.fake.getCommands().toString());
			assertTrue(rig.fake.getCommands().contains("E R0,R1"), rig.fake.getCommands().toString());
		}
	}

	@Test
	void aUnibusTimeoutEndsItsBlockAndTheRestIsRetried() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MemoryAddressType.PHYSICAL22, "with a hole");
			//-- Two words of real memory, then two that do not exist, then two more that do.
			g.add(01000);
			g.add(01002);
			g.add(010000000L);
			g.add(010000002L);
			g.add(01004);
			rig.fake.setMem(phys(01000), 01);
			rig.fake.setMem(phys(01002), 02);
			rig.fake.setMem(phys(01004), 03);

			rig.connection.run(() -> rig.console.examine(g, false, ProgressMonitor.NULL));
			assertEquals(01, g.findByAddress(01000).getPdpValue().word());
			assertEquals(02, g.findByAddress(01002).getPdpValue().word());
			assertEquals(03, g.findByAddress(01004).getPdpValue().word());
			assertFalse(g.findByAddress(010000000L).getPdpValue().isKnown());
			assertFalse(g.findByAddress(010000002L).getPdpValue().isKnown());
		}
	}

	@Test
	void unknownOnlyLeavesWhatIsAlreadyKnownAlone() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MemoryAddressType.PHYSICAL22, "memory");
			g.add(01000, 4);
			for(int i = 0; i < 4; i++) {
				rig.fake.setMem(phys(01000 + 2L * i), 0777);
			}
			g.cell(0).setPdpValue(CellValue.of(01));
			g.cell(1).setPdpValue(CellValue.of(02));
			rig.connection.run(() -> rig.console.examine(g, true, ProgressMonitor.NULL));

			assertEquals(01, g.cell(0).getPdpValue().word(), "already known, so not asked for again");
			assertEquals(02, g.cell(1).getPdpValue().word());
			assertEquals(0777, g.cell(2).getPdpValue().word());
			assertEquals(0777, g.cell(3).getPdpValue().word());
			assertTrue(rig.fake.getCommands().contains("E 1004-1006"), rig.fake.getCommands().toString());
		}
	}

	@Test
	void aBulkDepositSkipsWhatWasNeverEdited() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup g = rig.groups.addGroup(MemoryAddressType.PHYSICAL22, "memory");
			g.add(01000, 3);
			g.cell(0).setEditValue(CellValue.of(0111));
			g.cell(2).setEditValue(CellValue.of(0333));
			rig.connection.run(() -> rig.console.deposit(g, false, ProgressMonitor.NULL));

			assertEquals(0111, rig.fake.getMem(phys(01000)));
			assertEquals(0333, rig.fake.getMem(phys(01004)));
			//-- The middle cell had nothing typed into it. The Pascal would have sent its
			//-- illegal-value sentinel to the machine as a value; here it cannot.
			assertEquals(0, rig.fake.getMem(phys(01002)));
			assertEquals(0111, g.cell(0).getPdpValue().word(), "a successful deposit makes the two agree");
		}
	}

	// ---------------------------------------------------------------------------------------
	// Execution control
	// ---------------------------------------------------------------------------------------

	@Test
	void startingTheCpuIsFireAndForgetButTheRunStateIsSetAtOnce() throws Exception {
		try(Rig rig = new Rig()) {
			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			//-- Set synchronously and optimistically, which closes a real race: a halt clicked
			//-- before the decoder has seen "Simulator Running..." would otherwise decide there
			//-- was nothing to halt while the CPU was genuinely running.
			assertEquals(SimhConsole.CpuState.RUNNING, rig.console.getCpuState());
			assertTrue(rig.fake.getCommands().contains("run -q 001000"), rig.fake.getCommands().toString());
		}
	}

	@Test
	void haltingARunningCpuReportsWhereItStopped() throws Exception {
		try(Rig rig = new Rig()) {
			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			Address pc = rig.connection.call(() -> rig.console.haltCpu());
			assertNotNull(pc);
			assertEquals(MemoryAddressType.VIRTUAL, pc.type());
			assertEquals(01000, pc.val());
			assertEquals(SimhConsole.CpuState.HALTED, rig.console.getCpuState());
		}
	}

	@Test
	void haltingAStoppedCpuSendsNothingAtAll() throws Exception {
		try(Rig rig = new Rig()) {
			//-- ^E is inert unless SimH considers itself mid-RUN, and the execution window
			//-- calls halt unconditionally. Knowing the confirmed state is what keeps a stray
			//-- control character out of the stream.
			int before = rig.fake.getCommands().size();
			assertNull(rig.connection.call(() -> rig.console.haltCpu()));
			assertEquals(before, rig.fake.getCommands().size());
		}
	}

	@Test
	void aStopEventArrivesAndItsHandlerMayIssueFurtherCommands() throws Exception {
		try(Rig rig = new Rig()) {
			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			AtomicReference<CellValue> readBack = new AtomicReference<>();
			rig.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				try {
					//-- The whole point of posting the event onto the command executor: the
					//-- handler is on the command thread already, so it can go straight on to
					//-- use the console without deadlocking against it.
					readBack.set(console.examine(phys(017777707L)));
				} catch(ConsoleException x) {
					throw new IllegalStateException(x);
				}
				stopped.countDown();
			});

			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			//-- The pretend program runs into its HALT.
			rig.machineClock.fireAll();

			assertTrue(stopped.await(5, TimeUnit.SECONDS), "no stop event arrived");
			assertNotNull(reported.get());
			assertTrue(readBack.get().isKnown());
		}
	}

	@Test
	void aStopNobodyAnnouncedIsChasedDown() throws Exception {
		try(Rig rig = new Rig()) {
			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			rig.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				stopped.countDown();
			});
			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			//-- Where the machine actually ended up, which is not where it was told to start
			//-- and is the only reason the lookup is worth doing at all.
			rig.fake.setMem(phys(017777707L), 04321);

			//-- A machine that started on zeroed memory executes the implicit HALT at address 0
			//-- and stops without ever saying so: the next prompt is the only sign there will
			//-- ever be. Feed exactly that.
			rig.console.onSerialReceive("sim> ");
			//-- Which schedules the PC lookup rather than doing it inline - resetAndStart is
			//-- fire-and-forget and must stay that way.
			assertTrue(rig.consoleClock.hasPending(), "the silent halt should have been scheduled");
			assertEquals(SimhConsole.SILENT_HALT_DELAY_MS, rig.consoleClock.lastDelayMillis());
			rig.consoleClock.fireAll();

			assertTrue(stopped.await(5, TimeUnit.SECONDS), "no stop event arrived");
			assertEquals(MemoryAddressType.VIRTUAL, reported.get().type());
			assertEquals(04321, reported.get().val());
		}
	}

	/**
	 * Continue is a start like any other start, so it drops the same two pieces of state
	 * {@code resetAndStart} drops. It used to drop neither: a stop flag describing where the
	 * machine no longer is survived into RUNNING, and a silent-halt resolution scheduled just
	 * before Continue still fired afterwards - sending {@code E PC} at a machine that is now
	 * running, which SimH does not answer, so the command thread sat out the whole timeout and
	 * logged a failure that had not happened.
	 */
	@Test
	void continuingDropsTheStopStateAndAPendingSilentHaltResolution() throws Exception {
		try(Rig rig = new Rig()) {
			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			rig.console.onSerialReceive("sim> ");           // stopped without saying so
			assertTrue(rig.consoleClock.hasPending(), "the silent halt should have been scheduled");
			assertEquals(SimhConsole.CpuState.HALTED, rig.console.getCpuState());

			rig.connection.run(() -> rig.console.continueCpu());
			assertEquals(SimhConsole.CpuState.RUNNING, rig.console.getCpuState());
			assertNull(rig.console.getExecutionStopPc(),
				"it is running, so where it last stopped is not where it is");

			//-- The scheduled resolution still fires; it must find nothing left to do rather
			//-- than examine the PC of a running machine.
			int commandsBefore = rig.fake.getCommands().size();
			rig.consoleClock.fireAll();
			rig.connection.run(() -> {
			});                                             // let the queued job drain
			assertEquals(commandsBefore, rig.fake.getCommands().size(),
				"the stale silent-halt resolution should not have sent anything: "
					+ rig.fake.getCommands());
		}
	}

	@Test
	void aSingleStepAdvancesThePcAndReportsIt() throws Exception {
		try(Rig rig = new Rig()) {
			rig.fake.setMem(phys(017777707L), 01000);
			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			rig.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				stopped.countDown();
			});
			rig.connection.run(() -> rig.console.singleStep());

			assertTrue(stopped.await(5, TimeUnit.SECONDS), "a step ends in a stop like any other");
			assertEquals(01002, reported.get().val());
			assertEquals(SimhConsole.CpuState.HALTED, rig.console.getCpuState());
		}
	}

	@Test
	void thePcCannotBeMovedWhileTheCpuIsRunning() throws Exception {
		try(Rig rig = new Rig()) {
			rig.connection.run(() -> rig.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			//-- SimH answers "Invalid argument" to this. Knowing the run state means saying so
			//-- at once rather than round-tripping to find out.
			ConsoleException x = assertThrows(ConsoleException.class,
				() -> rig.connection.run(() -> rig.console.deposit(phys(017777707L), 02000)));
			assertTrue(x.getMessage().contains("running"), x.getMessage());
		}
	}

	@Test
	void resettingDoesNotSetThePcWhichIsWhyItIsNotAFeature() throws Exception {
		try(Rig rig = new Rig()) {
			rig.fake.setMem(phys(017777707L), 01000);
			rig.connection.run(() -> rig.console.resetMachine(Address.of(MemoryAddressType.VIRTUAL, 04000)));
			assertEquals(01000, rig.fake.getMem(phys(017777707L)));
			assertFalse(rig.console.features().contains(ConsoleFeature.RESET_CPU_SETS_PC));
			assertEquals(SimhConsole.CpuState.HALTED, rig.console.getCpuState());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Commands typed by a person - the SimH Console window
	// ---------------------------------------------------------------------------------------

	@Test
	void aTypedCommandIsSentAndItsAnswerCollected() throws Exception {
		try(Rig rig = new Rig()) {
			AtomicReference<SimhConsole.CommandResult> result = new AtomicReference<>();
			rig.connection.run(() -> result.set(rig.console.command("show cpu")));

			assertTrue(rig.fake.getCommands().contains("show cpu"), "SimH was asked");
			assertTrue(result.get().prompted(), "and prompted again afterwards");
			assertEquals("show cpu", result.get().command());
			assertTrue(result.get().lines().get(0).startsWith("CPU"),
				"what SimH said: " + result.get().lines());
		}
	}

	@Test
	void aCommandSimhRejectsIsAnAnswerRatherThanAFailure() throws Exception {
		//-- The remote console only accepts a whitelist, and typing something outside it is an
		//-- ordinary thing to do. Its complaint is a line like any other; the window shows it.
		try(Rig rig = new Rig()) {
			AtomicReference<SimhConsole.CommandResult> result = new AtomicReference<>();
			rig.connection.run(() -> result.set(rig.console.command("frobnicate the widget")));

			assertTrue(result.get().prompted());
			assertEquals(List.of("Unknown command"), result.get().lines());
		}
	}

	@Test
	void aSilentCommandAnswersNothingAndThatIsNotAnError() throws Exception {
		try(Rig rig = new Rig()) {
			AtomicReference<SimhConsole.CommandResult> result = new AtomicReference<>();
			rig.connection.run(() -> result.set(rig.console.command("reset")));

			assertTrue(result.get().prompted());
			assertEquals(List.of(), result.get().lines(), "RESET says nothing when it works");
		}
	}

	@Test
	void anEmptyCommandIsRefusedRatherThanSent() throws Exception {
		//-- A bare RETURN makes SimH repeat its last command, which would be one of PDP11GUI's.
		try(Rig rig = new Rig()) {
			assertThrows(ConsoleException.class, () -> rig.connection.run(() -> rig.console.command("   ")));
			assertFalse(rig.fake.getCommands().contains(""), "nothing was sent");
		}
	}

	@Test
	void aCommandThatStartsTheMachineReportsNoPromptWithoutFailing() throws Exception {
		//-- "go" hands the machine control, and there is no prompt until it stops again. The
		//-- window says so; it is not an error, and it must not throw one.
		try(Rig rig = new Rig()) {
			rig.fake.setMem(phys(017777707L), 01000);
			//-- Protected, and this test is in the console's own package: the wait below is the point
			//-- of the test, and eight seconds of it is not.
			rig.console.setCommandTimeoutMillis(300);
			AtomicReference<SimhConsole.CommandResult> result = new AtomicReference<>();
			rig.connection.run(() -> result.set(rig.console.command("go 1000")));

			assertFalse(result.get().prompted(), "the simulation is running");
			assertTrue(rig.fake.isRunning(), "and really is");
		}
	}

	// ---------------------------------------------------------------------------------------
	// Propagation
	// ---------------------------------------------------------------------------------------

	@Test
	void examinedValuesReachEveryGroupHoldingTheSameAddress() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryCellGroup a = rig.groups.addGroup(MemoryAddressType.PHYSICAL22, "one");
			MemoryCellGroup b = rig.groups.addGroup(MemoryAddressType.PHYSICAL22, "two");
			a.add(01000);
			MemoryCell mirror = b.add(01000);
			rig.fake.setMem(phys(01000), 0654);

			rig.connection.run(() -> rig.console.examine(a, false, ProgressMonitor.NULL));
			assertEquals(0654, mirror.getPdpValue().word(), "the same word in another window");
		}
	}
}
