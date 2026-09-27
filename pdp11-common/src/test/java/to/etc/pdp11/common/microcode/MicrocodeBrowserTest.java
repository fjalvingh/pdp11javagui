package to.etc.pdp11.common.microcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.microcode.MicrocodeBrowser.SearchBy;
import to.etc.pdp11.common.util.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The navigation both microcode views share, with no view at all.
 *
 * <p>The desktop window's own test drives the same things through its widgets; this one is what
 * the web page stands on, which has no layout test to catch it.</p>
 */
class MicrocodeBrowserTest {
	private static MicrocodeBrowser browser(MicrocodeSource source) {
		MicrocodeBrowser b = new MicrocodeBrowser(Logger.NULL);
		b.choose(source);
		return b;
	}

	@Test
	void choosingADocumentStartsAtItsFirstMicroword() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		assertEquals(MicrocodeSource.PDP1144, b.getSource());
		assertEquals(1018, b.getMicrocode().size());
		assertEquals(0, b.getCurrent().getAddress());
		assertTrue(b.statusText().startsWith("µPC = 0000"), b.statusText());
		assertFalse(b.canGoBack(), "nowhere to go back to yet");
		assertTrue(b.canGoNext());
	}

	@Test
	void searchNextAndBackWalkAndReturn() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		assertNull(b.searchFor("0732"));
		assertEquals("2-J", b.getCurrent().getSymbolicTag());

		assertNull(b.next());
		assertEquals(043, b.getCurrent().getAddress(), "2-J falls through to 2-L");

		b.back();
		assertEquals(0732, b.getCurrent().getAddress());
		b.back();
		assertEquals(0, b.getCurrent().getAddress(), "back to where it started");
		assertFalse(b.canGoBack(), "and the history is spent");
	}

	@Test
	void theThreeWaysOfSearchingFindTheSameMicroword() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		b.searchFor("0732");
		int line = b.getCurrent().getLineNumber();

		b.setSearchBy(SearchBy.TAG);
		b.searchFor("2-J");
		assertEquals(0732, b.getCurrent().getAddress());
		b.setSearchBy(SearchBy.LINE);
		b.searchFor(String.valueOf(line));
		assertEquals(0732, b.getCurrent().getAddress());
	}

	@Test
	void theSearchIndexIsInTheSearchOrder() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		assertEquals(1018, b.searchIndex().size());
		assertEquals("0000", b.searchIndex().get(0));
		b.setSearchBy(SearchBy.TAG);
		assertEquals("1-A", b.searchIndex().get(0));
	}

	/** Not found says why and moves nothing, where the Pascal silently shows microword 0. */
	@Test
	void whatIsNotThereIsSaidAndNothingMoves() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		b.searchFor("0732");
		MicroInstruction before = b.getCurrent();

		String why = b.searchFor("nonsense");
		assertNotNull(why);
		assertTrue(why.contains("not an octal address"), why);
		assertSame(before, b.getCurrent());
	}

	/** A µPC read off the KM11 can be a real location the listing does not print. */
	@Test
	void anUnprintedControlStoreLocationIsNotCalledATypo() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1105_F);
		String why = b.searchFor("377");
		assertNotNull(why);
		assertTrue(why.contains("does not print"), why);
	}

	@Test
	void goingToAPredecessorIsRemembered() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		b.searchFor("043");
		MicroInstruction from = b.predecessors().stream()
			.filter(mi -> mi.getAddress() == 0732).findFirst().orElseThrow();
		assertTrue(b.goTo(from.getAddress()));
		assertEquals("2-J", b.getCurrent().getSymbolicTag());
		b.back();
		assertEquals(043, b.getCurrent().getAddress());
	}

	@Test
	void theFieldsTheOtherRevisionDisagreesOnAreKnown() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1105_F);
		b.setSearchBy(SearchBy.TAG);
		//-- U1-1 is one of the five where rev E has AUX=1, CKO=0 and rev F has AUX=0, CKO=1.
		b.searchFor("U1-1");
		assertEquals(Set.of(Kd11bFields.AUX, Kd11bFields.CKO),
			b.differingFields().stream().map(MicrocodeField::name).collect(Collectors.toSet()));
		List<String> marked = b.rows().stream().filter(MicrowordRow::differs).map(MicrowordRow::label).toList();
		assertEquals(List.of(Kd11bFields.AUX, Kd11bFields.CKO), marked, "and the rows say so");

		//-- And what the other revision has there instead: rev E has AUX=1 where rev F has 0.
		MicrowordFieldValue aux = b.fieldValues().stream()
			.filter(v -> v.field().name().equals(Kd11bFields.AUX)).findFirst().orElseThrow();
		assertTrue(aux.differs());
		assertEquals(0, aux.value());
		assertEquals(1, b.otherRevisionField(aux.field()).value());

		b.searchFor("B-1");
		assertEquals(Set.of(), b.differingFields(), "200 of the 214 are the same in both");
	}

	@Test
	void theKd11bCannotBeSearchedByListingLine() {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		b.setSearchBy(SearchBy.LINE);
		b.choose(MicrocodeSource.PDP1105_F);
		assertEquals(SearchBy.ADDRESS, b.getSearchBy(), "a transcription has no line numbers to search");
	}

	@Test
	void openingAnotherCopyKeepsThePlaceAndAFailedOneChangesNothing(@TempDir Path dir) throws Exception {
		MicrocodeBrowser b = browser(MicrocodeSource.PDP1144);
		b.searchFor("0732");
		Microcode before = b.getMicrocode();

		Path missing = dir.resolve("missing.txt");
		assertThrows(Exception.class, () -> b.open(MicrocodeSource.PDP1144, List.of(missing)));
		assertSame(before, b.getMicrocode());
		assertEquals(0732, b.getCurrent().getAddress());

		Path copy = dir.resolve("copy.txt");
		try(var is = MicrocodeBrowserTest.class.getResourceAsStream("/microcode/EY-C3012-RB-001_Microcode_Listing_Apr81.txt")) {
			Files.copy(is, copy);
		}
		b.open(MicrocodeSource.PDP1144, List.of(copy));
		assertEquals("copy.txt", b.getMicrocode().getSourceName());
		assertEquals(0732, b.getCurrent().getAddress(), "still on the same microword");
	}
}
