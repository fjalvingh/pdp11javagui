package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticCatalogTest {
	private static final DiagnosticCatalog CATALOG = DiagnosticCatalog.builtIn();

	@Test
	void theIndexListsAboutTwelveHundredDiagnostics() {
		int n = CATALOG.entries().size();
		assertTrue(n > 1100 && n < 1300, "found " + n);
	}

	@Test
	void anEntryHasItsTitleWithoutThePartNumberAndEveryRevisionAndKit() {
		CatalogEntry rl = CATALOG.get("ZRLG").orElseThrow();
		assertEquals("RL11/RLV11/CTRL1", rl.title());
		assertTrue(rl.revisions().contains("E0"), rl.revisions().toString());
		assertEquals("E0", rl.latestRevision());
		assertTrue(rl.packages().contains("DDDP52 V2 RL02"), rl.packages().toString());
		assertEquals(DiagnosticFamily.PERIPHERALS, rl.family());
	}

	@Test
	void anXxdpFileNameIsItsDiagnosticAndRevision() {
		DiagnosticCatalog.Identification id = CATALOG.identify("fkaac0.bic");
		assertEquals("FKAA", id.id());
		assertEquals("C0", id.revision());
		assertEquals("11/34 BSC INST TST", id.title());
		assertEquals(DiagnosticFamily.PDP11_34, id.family());
	}

	@Test
	void aRevisionTheIndexDoesNotListIsStillThatDiagnostic() {
		DiagnosticCatalog.Identification id = CATALOG.identify("KKAAZ9.BIC");
		assertEquals("KKAA", id.id());
		assertNotNull(id.entry());
		assertEquals(DiagnosticFamily.PDP11_44, id.family());
	}

	@Test
	void theV25MonitorsAndDriversAreFoundByTheirNewNames() {
		assertEquals("XXDP V2 EXTENDED MON", CATALOG.identify("XXDPXM.SYS").title());
		assertEquals("XXDP V2 DD DRVR/BOOT", CATALOG.identify("DD.SYS").title());
		assertEquals(DiagnosticFamily.XXDP, CATALOG.identify("DRSSM.SYS").family());
	}

	@Test
	void everyAliasNamesADiagnosticTheIndexHas() throws IOException {
		try(var in = Objects.requireNonNull(DiagnosticCatalog.class.getResourceAsStream(DiagnosticCatalog.ALIAS_RESOURCE));
			var r = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
			for(String line = r.readLine(); line != null; line = r.readLine()) {
				String s = line.strip();
				if(s.isEmpty() || s.startsWith("#"))
					continue;
				String[] p = s.split("\\s+");
				assertEquals(2, p.length, line);
				assertTrue(CATALOG.get(p[1]).isPresent(), p[0] + " -> " + p[1] + " is not in the index");
			}
		}
	}

	@Test
	void namesThatAreNotDiagnosticsAreNotGuessedAt() {
		DiagnosticCatalog.Identification help = CATALOG.identify("SYSTEM.CCC");
		assertNull(help.id());
		assertEquals(DiagnosticFamily.OTHER, help.family());
		assertNull(CATALOG.identify("XXDPZZ.SYS").id(), "not a revision at the end");
		assertNull(CATALOG.identify("DL11.BIN").id());
	}

	@Test
	void thePartNumberIsTakenOffTheFrontOfATitleInEachSpellingDecUsed() {
		assertEquals("RL11/RLV11/CTRL1", DiagnosticCatalog.stripPartNumber("CZRLGE0 RL11/RLV11/CTRL1", "ZRLG"));
		assertEquals("11/34 EIS INST TST", DiagnosticCatalog.stripPartNumber("DFKACA1 11/34 EIS INST TST", "FKAC"));
		assertEquals("TRDP+ TR79 MT", DiagnosticCatalog.stripPartNumber("HMTRDPB0 TRDP+ TR79 MT", "TRDP"));
		assertEquals("PL23HD.BIC", DiagnosticCatalog.stripPartNumber("PL23HD.BIC", "PSCD"));
		assertEquals("RK11 MODULE", DiagnosticCatalog.stripPartNumber("RK11 MODULE", "XRKA"));
	}

	@Test
	void theReportFormatIsParsedPackageByPackage() throws IOException {
		String report = """
			********************************************************************************
			MEDIA PACKAGE	PACKAGE	ECO
			  CZYBZ		Q	CZYBZQ0 DDDP52 V2 RL02
			\tZRLG\tD0\tCZRLGD0 RL11 OLD TITLE
			\tZRLH\tB0
			  CZZL2		C	CZZL2C0 UNSUPPORTED RL02
			\tZRLG\tE0\tCZRLGE0 RL11/RLV11/CTRL1
			  CZZZ4		[	OBSOLETE
			\tZRLI\tA0\tCZRLIA0 SOMETHING
			""";
		DiagnosticCatalog c = DiagnosticCatalog.parse(new StringReader(report), null);
		CatalogEntry rl = c.get("ZRLG").orElseThrow();
		assertEquals("RL11/RLV11/CTRL1", rl.title(), "the latest revision's title");
		assertEquals(java.util.List.of("D0", "E0"), rl.revisions());
		assertEquals(java.util.List.of("DDDP52 V2 RL02", "UNSUPPORTED RL02"), rl.packages());
		assertEquals("", c.get("ZRLH").orElseThrow().title());
		assertEquals(java.util.List.of(), c.get("ZRLI").orElseThrow().packages(), "an obsolete package is not a kit");
	}
}
