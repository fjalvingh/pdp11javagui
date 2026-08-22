package to.etc.pdp11.core.conn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.SimhConsole;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Scheduler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two channels of a real SimH, kept apart: the {@code sim>} protocol on one, the emulated
 * PDP-11's own console on the other.
 *
 * <p>{@link ConnectionManagerTest} covers the routing rules against the simulated machines and
 * runs everywhere. What it cannot show is that the channel the main window's terminal is wired
 * to is <b>really</b> the one the emulated machine prints on - that needs a machine that prints
 * something, so this one deposits a program that does and waits for the character to arrive.</p>
 *
 * <p><b>Skipped when SimH is not on {@code PATH}</b>, as CI is.</p>
 */
class ConnectionManagerSimhIT {
	private static final String SIMH = "pdp11";

	/** How long to wait for a character the emulated machine has been asked to print. */
	private static final long OUTPUT_TIMEOUT_MS = 15_000;

	private static boolean simhOnPath() {
		String path = System.getenv("PATH");
		if(path == null)
			return false;
		for(String dir : path.split(File.pathSeparator)) {
			if(Files.isExecutable(Path.of(dir, SIMH)))
				return true;
		}
		return false;
	}

	private static ConnectionManager manager(Path dir) {
		return new ConnectionManager(new MemoryCellGroups(), Logger.NULL, Scheduler.systemScheduler(), dir);
	}

	/**
	 * Connect, and if SimH came up mute, launch it again.
	 *
	 * <p>The same one-launch-in-thirty {@code SimhConsoleIT.startSession} documents: SimH accepts
	 * both connections, sends its banner and then says nothing. That is a machine that did not
	 * come up, not an assertion that failed.</p>
	 */
	private static void connect(ConnectionManager m) throws ConsoleException {
		ConnectionProfile profile = new ConnectionProfile("simh", ConsoleProtocol.SIMH,
			TransportConfig.simhProcess(null, null));
		try {
			m.connect(profile);
		} catch(ConsoleException x) {
			System.out.println("SimH did not come up; launching once more. First attempt said: " + x.getMessage());
			m.connect(profile);
		}
	}

	private static Address phys(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	@Test
	void theSimProtocolAndTheMachineConsoleAreDifferentChannels(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(ConnectionManager m = manager(dir)) {
			connect(m);
			assertTrue(m.hasSeparateMachineConsole(), "SimH direct has a console channel of its own");
			assertTrue(m.hasMachineConsole());

			//-- The handshake went over the sim> channel, and belongs only there.
			assertTrue(m.getProtocolChannel().getText().contains("sim>"), m.getProtocolChannel().getText());
			assertTrue(m.getProtocolChannel().getText().contains("sh cpu iospace"));
			assertFalse(m.getMachineConsole().getText().contains("sh cpu iospace"),
				"the machine's console is not where PDP11GUI talks to the simulator: \""
					+ m.getMachineConsole().getText() + "\"");
		}
	}

	/**
	 * What the machine prints reaches the channel the main window's terminal shows.
	 *
	 * <p>The program is the smallest thing that proves it: move {@code 'A'} into the console
	 * transmit buffer at {@code 177566} and halt. If the terminal were still wired to the
	 * {@code sim>} channel - which is what it was wired to before, and what made this window
	 * confusing - the character would appear nowhere.</p>
	 */
	@Test
	void whatTheMachinePrintsArrivesOnTheMachineConsole(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(ConnectionManager m = manager(dir)) {
			connect(m);
			SimhConsole console = (SimhConsole) m.getConsole();

			//--   001000: 012737 000101 177566   mov #101, @#177566  ; 'A' to the transmit buffer
			//--   001006: 105737 177564          tstb @#177564       ; transmitter status
			//--   001012: 100375                 bpl .-4             ; until it has gone
			//--   001014: 000000                 halt
			//--
			//-- The wait is not padding. SimH transmits on a scheduled event some instructions
			//-- later, and halting before it fires kills the pending event: the character is
			//-- never sent at all, and the test fails looking exactly like a misrouted channel.
			m.getConnection().run(() -> {
				console.deposit(phys(01000), 012737);
				console.deposit(phys(01002), 0101);
				console.deposit(phys(01004), 0177566);
				console.deposit(phys(01006), 0105737);
				console.deposit(phys(01010), 0177564);
				console.deposit(phys(01012), 0100375);
				console.deposit(phys(01014), 0);
			});
			m.getConnection().run(() -> console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));

			long deadline = System.currentTimeMillis() + OUTPUT_TIMEOUT_MS;
			while(!m.getMachineConsole().getText().contains("A") && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			assertTrue(m.getMachineConsole().getText().contains("A"),
				"the emulated machine's own console should have printed A, and holds \""
					+ m.getMachineConsole().getText() + "\"");
			//-- And it stayed off the other channel, where nothing typed the letter.
			assertFalse(m.getProtocolChannel().getText().contains("HALT instruction")
				&& m.getMachineConsole().getText().contains("HALT instruction"),
				"the halt report belongs to the sim> channel only");
		}
	}

	@Test
	void typingAtTheTerminalReachesTheEmulatedMachinesConsole(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		try(ConnectionManager m = manager(dir)) {
			connect(m);
			SimhConsole console = (SimhConsole) m.getConsole();

			//-- 001000: wait for a character, then halt with it in R0.
			//--   001000: 105737 177560   tstb @#177560      ; console receiver status
			//--   001004: 100375          bpl .-4
			//--   001006: 013700 177562   mov @#177562, r0   ; the character
			//--   001012: 000000          halt
			m.getConnection().run(() -> {
				console.deposit(phys(01000), 0105737);
				console.deposit(phys(01002), 0177560);
				console.deposit(phys(01004), 0100375);
				console.deposit(phys(01006), 013700);
				console.deposit(phys(01010), 0177562);
				console.deposit(phys(01012), 0);
			});
			m.getConnection().run(() -> console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, 01000)));

			//-- What the main window's terminal does with a keystroke.
			m.writeToMachineConsole("Z");

			long deadline = System.currentTimeMillis() + OUTPUT_TIMEOUT_MS;
			while(console.getCpuState() != SimhConsole.CpuState.HALTED && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			assertEquals(SimhConsole.CpuState.HALTED, console.getCpuState(),
				"the program waits for a character and halts on it, so it halted means it got one");
			//-- R0 holds what arrived. 0132 is 'Z'.
			m.getConnection().run(() -> {
				assertEquals(0132, console.examine(phys(017777700L)).word() & 0177,
					"the character the machine received");
			});
		}
	}
}
