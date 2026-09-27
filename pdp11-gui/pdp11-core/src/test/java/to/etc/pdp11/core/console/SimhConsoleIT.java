package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.io.PhysicalTransport;
import to.etc.pdp11.core.io.SimhProcessTransport;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.ProgressMonitor;
import to.etc.pdp11.core.util.Scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SimH console against SimH itself: launched by the test, driven over its own remote
 * console, examined, deposited into, run and halted.
 *
 * <p><b>Skipped when SimH is not on {@code PATH}</b>, which is the case on CI - PLAN.md records
 * that CI has no SimH and is not getting one, and that anything needing one either ships a
 * fixture or skips. {@link SimhConsoleTest} covers the same ground against {@link
 * to.etc.pdp11.core.fake.FakeSimh} and runs everywhere; the point of this class is to keep that
 * fake honest.</p>
 *
 * <p>Phase 3 deliberately left this out, having twice got an ad-hoc exchange working live only
 * to watch it fail on the next run. What makes it stable now is not a longer wait: it is that
 * {@link SimhConsole} anchors on SimH's echo of the command rather than on a prompt that may
 * belong to the previous one.</p>
 */
class SimhConsoleIT {
	private static final String SIMH = "pdp11";

	private static boolean simhOnPath() {
		String path = System.getenv("PATH");
		if(path == null)
			return false;
		for(String dir : path.split(java.io.File.pathSeparator)) {
			if(Files.isExecutable(Path.of(dir, SIMH)))
				return true;
		}
		return false;
	}

	/** A launched SimH with a console attached, and the console channel being drained. */
	private static final class Session implements AutoCloseable {
		final SimhProcessTransport transport;

		final MemoryCellGroups groups = new MemoryCellGroups();

		final ConsoleConnection connection;

		final SimhConsole console;

		private final Thread m_consoleDrain;

		/**
		 * What the emulated machine printed on its own console.
		 *
		 * <p>Kept rather than discarded because when the remote console fails to wake, this is
		 * the only part of the conversation nobody has looked at - and the failure looks exactly
		 * like the console channel not being there.</p>
		 */
		private final StringBuilder m_consoleText = new StringBuilder();

		Session(Path dir) throws IOException, ConsoleException {
			transport = SimhProcessTransport.launch(SIMH, writeMachineIni(dir), dir, Logger.NULL);
			//-- The emulated machine's own console has to be read by somebody, or SimH
			//-- eventually blocks on a socket nobody is emptying. The window that will do that
			//-- arrives in phase 6; until then, here.
			m_consoleDrain = drain(transport.getConsoleChannel(), m_consoleText);
			connection = new ConsoleConnection(transport, Logger.NULL);
			console = new SimhConsole(groups, Logger.NULL, Scheduler.systemScheduler());
			connection.attach(console);
			try {
				connection.run(() -> console.init(connection));
			} catch(ConsoleException x) {
				//-- Talking to another program: when the handshake fails, what it said and what
				//-- it wrote on its own stdout is the whole of the evidence. Losing that leaves
				//-- nothing to diagnose but the word "timeout".
				String detail = "SimH handshake failed: " + x.getMessage();
				if(x instanceof NoConsolePromptException np) {
					detail += "\n  unconsumed input: \"" + printable(np.getUnconsumedInput()) + "\""
						+ "\n  phrases decoded: " + np.getAnswers();
				}
				String consoleText;
				synchronized(m_consoleText) {
					consoleText = m_consoleText.toString();
				}
				detail += "\n  emulated console channel: \"" + printable(consoleText) + "\""
					+ "\n  console channel open: " + transport.getConsoleChannel().isOpen()
					+ "\n  remote channel open: " + transport.isOpen()
					+ "\n  SimH's own output:\n" + transport.getProcessOutput()
					+ "\n  ports: " + transport.getPorts()
					+ "\n  configuration: " + Files.readString(transport.getIniFile());
				close();
				throw new AssertionError(detail, x);
			}
		}

		private static String printable(String s) {
			StringBuilder sb = new StringBuilder();
			for(int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				sb.append(c < 0x20 || c == 0x7F ? String.format("<%02x>", (int) c) : String.valueOf(c));
			}
			return sb.toString();
		}

		private static Thread drain(PhysicalTransport t, StringBuilder into) {
			Thread thread = new Thread(() -> {
				byte[] buf = new byte[4096];
				try {
					int n;
					while((n = t.read(buf, 0, buf.length)) > 0) {
						synchronized(into) {
							into.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1));
						}
					}
				} catch(IOException x) {
					//-- Closed.
				}
			}, "simh-console-drain");
			thread.setDaemon(true);
			thread.start();
			return thread;
		}

		@Override
		public void close() {
			connection.close();
			transport.close();
			m_consoleDrain.interrupt();
		}
	}

	/**
	 * Launch, and if the remote console never wakes, launch again.
	 *
	 * <p><b>This is not a retry around a flaky assertion.</b> What is being retried is SimH
	 * coming up, and the distinction matters. Measured over fourteen full-suite runs, roughly
	 * one launch in thirty ends with SimH having accepted both connections, sent its banner, and
	 * then said nothing whatsoever - no prompt, no echo, no master-mode notice, and nothing on
	 * the emulated machine's own console either, with both channels still open. That is a
	 * machine that did not come up, and every one of these tests needs a machine that did.</p>
	 *
	 * <p>The console layer's own behaviour there is already right: {@code resync} reports a
	 * console that will not answer rather than pretending otherwise, which in the application is
	 * the "no console prompt" the user reconnects from. This does what that user does. What it
	 * must not do is hide the case, so the first failure's evidence is printed either way.</p>
	 *
	 * <p>PLAN.md phase 4 has the open item and what has been ruled out.</p>
	 */
	private static Session startSession(Path dir) throws IOException, ConsoleException {
		try {
			return new Session(dir);
		} catch(AssertionError x) {
			System.out.println("SimH did not come up; launching once more. First attempt said:\n" + x.getMessage());
			return new Session(dir);
		}
	}

	private static Path writeMachineIni(Path dir) throws IOException {
		Path userIni = dir.resolve("machine.ini");
		Files.writeString(userIni, String.join("\n",
			"set cpu 11/70",
			"set cpu 256k"));
		return userIni;
	}

	private static Address phys(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	@Test
	void connectingReachesThePromptAndTheSetupCommandsAreAccepted(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(Session s = startSession(dir)) {
			//-- init() ran resync(), which is four commands each confirmed by its own prompt.
			//-- Getting here at all is the assertion.
			assertEquals(SimhConsole.CpuState.HALTED, s.console.getCpuState());
			assertEquals(MemoryAddressType.PHYSICAL22, s.console.physicalAddressType());
		}
	}

	@Test
	void aDepositedWordReadsBack(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(Session s = startSession(dir)) {
			Address a = phys(01000);
			s.connection.run(() -> s.console.deposit(a, 0123456));
			assertEquals(0123456, s.connection.call(() -> s.console.examine(a)).word());

			//-- And again, several times over: one exchange working is what phase 3 could
			//-- already do. Doing it repeatedly without drifting out of step is the point.
			for(int i = 1; i <= 8; i++) {
				Address ai = phys(02000 + 2L * i);
				int v = 010000 + i;
				s.connection.run(() -> s.console.deposit(ai, v));
				assertEquals(v, s.connection.call(() -> s.console.examine(ai)).word(), "round " + i);
			}
		}
	}

	@Test
	void theCpuRegistersAreReachedByName(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(Session s = startSession(dir)) {
			//-- SimH keeps R0..R7 out of the address space, so this only works by name.
			Address r1 = phys(017777701L);
			s.connection.run(() -> s.console.deposit(r1, 04321));
			assertEquals(04321, s.connection.call(() -> s.console.examine(r1)).word());
		}
	}

	@Test
	void aNonexistentAddressIsAUnibusTimeoutNotAFailure(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(Session s = startSession(dir)) {
			//-- The machine has 256 KB; this is well past it and below the I/O page.
			CellValue v = s.connection.call(() -> s.console.examine(phys(017000000L)));
			assertTrue(!v.isKnown(), "expected a UNIBUS timeout, got " + v);
			//-- The console must still be usable afterwards.
			assertEquals(0123456, s.connection.call(() -> {
				s.console.deposit(phys(01000), 0123456);
				return s.console.examine(phys(01000));
			}).word());
		}
	}

	@Test
	void aWholeBlockOfMemoryComesBackInOneRoundTrip(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(Session s = startSession(dir)) {
			MemoryCellGroup g = s.groups.addGroup(MemoryAddressType.PHYSICAL22, "memory");
			g.add(04000, 16);
			for(int i = 0; i < 16; i++) {
				g.cell(i).setEditValue(CellValue.of(020000 + i));
			}
			s.connection.run(() -> s.console.deposit(g, false, ProgressMonitor.NULL));
			g.invalidate();
			s.connection.run(() -> s.console.examine(g, false, ProgressMonitor.NULL));
			for(int i = 0; i < 16; i++) {
				assertEquals(020000 + i, g.cell(i).getPdpValue().word(), "cell " + i);
			}
		}
	}

	@Test
	void aProgramCanBeStartedAndHalted(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(Session s = startSession(dir)) {
			//-- A branch to itself at 01000: "BR ." - the smallest program that genuinely runs.
			s.connection.run(() -> s.console.deposit(phys(01000), 0000777));

			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			s.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				stopped.countDown();
			});

			s.connection.run(() -> s.console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));
			assertEquals(SimhConsole.CpuState.RUNNING, s.console.getCpuState());

			Address pc = s.connection.call(() -> s.console.haltCpu());
			assertNotNull(pc, "^E should have stopped a genuinely running loop");
			assertEquals(01000, pc.val(), "the loop branches to itself, so it stops where it started");
			assertEquals(SimhConsole.CpuState.HALTED, s.console.getCpuState());
			assertTrue(stopped.await(10, TimeUnit.SECONDS), "the stop event should follow the prompt");
			assertEquals(01000, reported.get().val());
		}
	}

	@Test
	void aSingleStepAdvancesThePc(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(Session s = startSession(dir)) {
			//-- NOP at 01000, then another; stepping one instruction lands on the second.
			s.connection.run(() -> s.console.deposit(phys(01000), 0000240));
			s.connection.run(() -> s.console.deposit(phys(01002), 0000240));
			s.connection.run(() -> s.console.deposit(phys(017777707L), 01000));

			CountDownLatch stopped = new CountDownLatch(1);
			AtomicReference<Address> reported = new AtomicReference<>();
			s.console.setExecutionStopListener((console, pc) -> {
				reported.set(pc);
				stopped.countDown();
			});
			s.connection.run(() -> s.console.singleStep());
			assertTrue(stopped.await(10, TimeUnit.SECONDS), "a step ends in a stop like any other");
			assertEquals(01002, reported.get().val());
		}
	}
}
