package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryListingTest {
	private static LibraryFile file(String name) {
		return new LibraryFile(name, "d" + name, 100, null, "files/" + name, false, List.of());
	}

	private static final List<LibraryListing.Row> ROWS = LibraryListing.rows(List.of(
		file("ZRLGE0.BIC"), file("FKAAC0.BIC"), file("XXDPXM.SYS"), file("KKAAD1.BIC"), file("DL11.BIN"), file("FKTGC0.BIC")),
		DiagnosticCatalog.builtIn());

	private static List<String> names(List<LibraryListing.Row> rows) {
		return rows.stream().map(r -> r.file().name()).toList();
	}

	@Test
	void rowsComeInFamilyOrderThenByName() {
		assertEquals(List.of("XXDPXM.SYS", "FKAAC0.BIC", "FKTGC0.BIC", "KKAAD1.BIC", "ZRLGE0.BIC", "DL11.BIN"), names(ROWS));
	}

	@Test
	void aFamilyNarrowsTheList() {
		assertEquals(List.of("FKAAC0.BIC", "FKTGC0.BIC"), names(LibraryListing.filter(ROWS, DiagnosticFamily.PDP11_34, "")));
	}

	@Test
	void everyWordMustBeFoundSomewhereInAnyCase() {
		assertEquals(List.of("ZRLGE0.BIC"), names(LibraryListing.filter(ROWS, null, "rlv11")), "in the title");
		assertEquals(List.of("FKTGC0.BIC"), names(LibraryListing.filter(ROWS, null, "11/34 mem")), "two words, both in the title");
		assertEquals(List.of("KKAAD1.BIC"), names(LibraryListing.filter(ROWS, null, "kkaa")), "the diagnostic number");
		assertEquals(List.of("XXDPXM.SYS"), names(LibraryListing.filter(ROWS, null, "monitors")), "the family's name");
		assertEquals(6, LibraryListing.filter(ROWS, null, "   ").size());
		assertEquals(0, LibraryListing.filter(ROWS, DiagnosticFamily.PDP11_44, "rl11").size());
	}

	@Test
	void countsArePerFamily() {
		Map<DiagnosticFamily, Integer> counts = LibraryListing.counts(ROWS);
		assertEquals(2, counts.get(DiagnosticFamily.PDP11_34));
		assertEquals(1, counts.get(DiagnosticFamily.OTHER));
		assertEquals(List.of(DiagnosticFamily.XXDP, DiagnosticFamily.PDP11_34, DiagnosticFamily.PDP11_44, DiagnosticFamily.PERIPHERALS,
			DiagnosticFamily.OTHER), List.copyOf(counts.keySet()));
	}
}
