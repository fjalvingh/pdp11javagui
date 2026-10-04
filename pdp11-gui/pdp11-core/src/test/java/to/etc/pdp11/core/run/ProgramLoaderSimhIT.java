package to.etc.pdp11.core.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.memfile.AbsoluteLoaderTape;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.common.util.Scheduler;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.conn.TransportConfig;
import to.etc.pdp11.core.console.Console;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A real DEC diagnostic, deposited into a real SimH and started, passes.
 *
 * <p>The diagnostic is {@code FKAAC0.BIC}, the PDP-11/34 basic instruction test, from a collected
 * library: set {@code PDP11_DIAG_LIBRARY} to its {@code diagnostics} directory. It is DEC's and
 * cannot be in this repository, so without it - or without SimH's {@code pdp11} on the PATH -
 * this skips. SimH is told it is an 11/34 through the user configuration the connection
 * includes; the program is put in by {@link ProgramLoader}, a deposit per word over SimH's
 * console, started at 200 as it has no transfer address, and prints {@code END PASS} at the end of
 * its first pass. SimH's own {@code load} command gives the same output, which is how the test was
 * first checked.</p>
 */
class ProgramLoaderSimhIT {
	private static final String SIMH = "pdp11";

	private static final String VARIABLE = "PDP11_DIAG_LIBRARY";

	private static final long PASS_TIMEOUT_MS = 60_000;

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

	@Test
	@org.junit.jupiter.api.Timeout(180)
	void the1134BasicInstructionTestPassesAfterBeingDeposited(@TempDir Path dir) throws Exception {
		assumeTrue(simhOnPath(), "SimH's " + SIMH + " is not on PATH");
		String library = System.getProperty(VARIABLE, System.getenv(VARIABLE));
		assumeTrue(library != null && !library.isBlank(), VARIABLE + " is not set");
		Path program = Path.of(library, "files", "FKAAC0.BIC");
		assumeTrue(Files.exists(program), program + " has not been collected");

		AbsoluteLoaderTape tape = AbsoluteLoaderTape.parse(Files.readAllBytes(program), "FKAAC0.BIC");
		assertEquals(0200, tape.startAddress(), "FKAAC0 halts after loading, so it starts at 200");
		Path ini = dir.resolve("1134.ini");
		Files.writeString(ini, "set cpu 11/34\n");

		MemoryCellGroups groups = new MemoryCellGroups();
		try(ConnectionManager m = new ConnectionManager(groups, Logger.NULL, Scheduler.systemScheduler(), dir)) {
			m.connect(new ConnectionProfile("simh", ConsoleProtocol.SIMH, TransportConfig.simhProcess(null, ini.toString())));
			Console console = m.getConsole();
			long t0 = System.currentTimeMillis();
			ProgramLoader.Loaded loaded = m.getConnection().call(() -> ProgramLoader.load(console, groups, tape, null, ProgressMonitor.NULL));
			long loadMillis = System.currentTimeMillis() - t0;
			assertTrue(loaded.words() > 5000, "the whole program: " + loaded.words() + " words");
			//-- A deposit at a time took five minutes; batched, a few seconds. See SimhConsole.deposit.
			assertTrue(loadMillis < 60_000, "the deposits are batched: " + loadMillis + " ms");
			m.getConnection().run(() -> ProgramLoader.start(console, tape.startAddress()));

			long deadline = System.currentTimeMillis() + PASS_TIMEOUT_MS;
			while(!m.getMachineConsole().getText().contains("END PASS") && System.currentTimeMillis() < deadline)
				Thread.sleep(100);
			String out = m.getMachineConsole().getText();
			assertTrue(out.contains("11/34 BSC INST TST"), "the program announces itself: \"" + out + "\"");
			assertTrue(out.contains("END PASS"), "and finishes a pass without an error: \"" + out + "\"");
		}
	}
}
