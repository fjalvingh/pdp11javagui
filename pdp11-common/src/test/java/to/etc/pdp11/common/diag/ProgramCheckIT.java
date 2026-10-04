package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The standalone check over a real, collected library, which this repository cannot contain.
 *
 * <p>Set {@code PDP11_DIAG_LIBRARY} (or the system property) to a {@code diagnostics} directory a
 * collection made, and this runs; otherwise it skips. The counts are what the full default
 * collection gave in October 2026: 2,490 programs, of which 283 carry a DRS header and some 1,360
 * are standalone - most of them started at 200.</p>
 */
class ProgramCheckIT {
	private static final String VARIABLE = "PDP11_DIAG_LIBRARY";

	@Test
	void aCollectedLibrarySortsIntoWhatCanRunAndWhatCannot() throws IOException {
		String dir = System.getProperty(VARIABLE, System.getenv(VARIABLE));
		assumeTrue(dir != null && !dir.isBlank(), VARIABLE + " is not set");
		DiagnosticLibrary lib = DiagnosticLibrary.open(Path.of(dir));
		assumeTrue(!lib.isEmpty(), dir + " holds no library");
		DiagnosticCatalog catalog = DiagnosticCatalog.builtIn();
		Map<ProgramCheck.Kind, Integer> counts = new EnumMap<>(ProgramCheck.Kind.class);
		Map<String, ProgramCheck.Verdict> byName = new java.util.HashMap<>();
		for(LibraryFile f : lib.files()) {
			ProgramCheck.Verdict v = ProgramCheck.check(f.name(), Files.readAllBytes(lib.resolve(f.path())), f.damaged(),
				catalog.identify(f.name()));
			counts.merge(v.kind(), 1, Integer::sum);
			byName.putIfAbsent(f.name(), v);
		}
		assertTrue(counts.getOrDefault(ProgramCheck.Kind.NEEDS_SUPERVISOR, 0) > 200, counts.toString());
		assertTrue(counts.getOrDefault(ProgramCheck.Kind.STANDALONE, 0) > 1000, counts.toString());
		if(byName.containsKey("ZRQBC1.BIN"))
			assertEquals(ProgramCheck.Kind.NEEDS_SUPERVISOR, byName.get("ZRQBC1.BIN").kind());
		if(byName.containsKey("FKAAC0.BIC")) {
			assertEquals(ProgramCheck.Kind.STANDALONE, byName.get("FKAAC0.BIC").kind());
			assertEquals(0200, byName.get("FKAAC0.BIC").tape().startAddress());
		}
		if(byName.containsKey("XXDPXM.SYS"))
			assertEquals(ProgramCheck.Kind.NEEDS_MONITOR, byName.get("XXDPXM.SYS").kind());
	}
}
