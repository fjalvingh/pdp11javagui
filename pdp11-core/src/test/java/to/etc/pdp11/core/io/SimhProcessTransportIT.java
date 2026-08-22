package to.etc.pdp11.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Launches a real SimH and drives it over its remote console, which exercises the whole
 * "SimH direct" path: port probe, generated configuration, child process, and both telnet
 * channels.
 *
 * <p><b>Skipped when SimH is not on {@code PATH}</b>, which is the case on CI - PLAN.md
 * records that CI has no SimH, no {@code macro11} and no Free Pascal, and that anything
 * needing one either ships a fixture or skips. Locally this is the only thing that exercises
 * {@link SimhProcessTransport} against the program it was written for, and it is what
 * established the handshake documented on that class and in PLAN.md phase 3.</p>
 *
 * <p>If SimH is present but will not start, this fails rather than skipping: a broken SimH is
 * worth hearing about, and it has happened here - the binary was linked against a
 * {@code libvdeplug.so.2} no longer packaged under that name.</p>
 */
class SimhProcessTransportIT {
	private static final String SIMH = "pdp11";

	/** Enter multiple-command mode, which is what gets a {@code sim>} prompt. */
	private static final byte CTRL_E = 5;

	private static final long TIMEOUT_MS = 10_000;

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

	/**
	 * A running SimH plus everything it has said.
	 *
	 * <p>The reading happens on a daemon thread rather than inline. {@link PhysicalTransport}
	 * blocks by contract, and JUnit's per-test timeout cannot interrupt a thread parked in a
	 * socket read - so a test waiting for something SimH never sends would hang the entire
	 * build instead of failing, which it duly did. Ask this class to wait instead, and it
	 * fails on a deadline it can actually observe.</p>
	 */
	private static final class Session implements AutoCloseable {
		private final SimhProcessTransport m_transport;

		private final StringBuilder m_received = new StringBuilder();

		private Session(SimhProcessTransport transport) {
			m_transport = transport;
			Thread reader = new Thread(() -> {
				byte[] buf = new byte[4096];
				try {
					int n;
					while((n = transport.read(buf, 0, buf.length)) > 0) {
						synchronized(m_received) {
							m_received.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
						}
					}
				} catch(IOException x) {
					//-- Closed; nothing more to read.
				}
			}, "simh-it-reader");
			reader.setDaemon(true);
			reader.start();
		}

		static Session start(Path userIni, Path dir) throws IOException {
			return new Session(SimhProcessTransport.launch(SIMH, userIni, dir, Logger.NULL));
		}

		SimhProcessTransport transport() {
			return m_transport;
		}

		String text() {
			synchronized(m_received) {
				return m_received.toString();
			}
		}

		void send(String s) throws IOException {
			m_transport.write(s.getBytes(StandardCharsets.ISO_8859_1));
		}

		/** Wait for {@code needle} to appear in everything received so far. */
		String await(String needle) {
			return awaitAfter(0, needle);
		}

		/**
		 * Wait for {@code needle} to appear <i>after</i> {@code offset} characters. Needed
		 * because SimH says the same things repeatedly - every command produces a prompt, and
		 * getting to one at all costs an {@code Unknown command} - so searching the whole
		 * transcript matches something that arrived before the command under test was sent.
		 */
		String awaitAfter(int offset, String needle) {
			long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
			while(System.nanoTime() < deadline) {
				String s = text();
				if(s.length() > offset && s.substring(offset).contains(needle))
					return s;
				try {
					Thread.sleep(20);
				} catch(InterruptedException x) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			throw new AssertionError("Timed out waiting for '" + needle + "' after offset " + offset
				+ ".\nReceived:\n" + text() + "\nSimH's own output:\n" + m_transport.getProcessOutput());
		}

		@Override
		public void close() {
			m_transport.close();
		}
	}

	private static Path writeMachineIni(Path dir) throws IOException {
		Path userIni = dir.resolve("machine.ini");
		Files.writeString(userIni, String.join("\n",
			"set cpu 11/70",
			"set cpu 256k",
			//-- A directive of the kind the generated copy has to strip and replace.
			"set remote telnet=9999"));
		return userIni;
	}

	@Test
	void simhStartsAndItsRemoteConsoleGreetsUs(@TempDir Path dir) throws IOException {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");

		try(Session s = Session.start(writeMachineIni(dir), dir)) {
			assertTrue(s.transport().isOpen());
			String banner = s.await("Remote Console");
			assertTrue(banner.contains("REM-CON"), banner);
			//-- The port pair is whatever the probe picked, and stays adjacent.
			assertTrue(s.transport().getPorts().console() == s.transport().getPorts().remote() + 1);
			//-- Nothing is asserted about getProcessOutput() here: SimH's stdout is a pipe, so
			//-- its writes are block-buffered and usually still sitting in libc while it runs.
			//-- That output is for diagnosing a launch that failed, not for synchronising on.
		}
	}

	/**
	 * <b>The command handshake is deliberately not tested here.</b> Getting SimH's remote
	 * console to answer a command needs more than a connection: {@code ^E} to reach a prompt,
	 * and then a synchronisation that survives SimH printing its prompt <i>before</i> echoing
	 * the command it is about to run. Ad-hoc waiting in a test can be made to pass and then
	 * fails on the next run - it did, repeatedly - because what is actually needed is the
	 * phase 4 scanner: a restartable lexer that knows a prompt from an echo from an answer.
	 *
	 * <p>Everything observed live is recorded in PLAN.md phase 3 as input to that work. What
	 * this class asserts is what phase 3 owns and what is stable: SimH launches, the ports are
	 * probed, both channels connect, IAC is stripped, and the banner arrives intact.</p>
	 */

	/** The generated configuration is left on disk deliberately, for when a launch goes wrong. */
	@Test
	void theGeneratedConfigurationSurvivesTheProcess(@TempDir Path dir) throws IOException {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");

		Path generated;
		try(Session s = Session.start(writeMachineIni(dir), dir)) {
			generated = s.transport().getIniFile();
			s.await("Remote Console");
		}
		assertTrue(Files.exists(generated), "the generated .ini should still be there afterwards");
		String text = Files.readString(generated);
		assertTrue(text.contains("set remote master"), text);
		assertTrue(!text.contains("9999"), "the user's own remote port should have been stripped");
	}
}
