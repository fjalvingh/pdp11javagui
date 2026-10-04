package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.util.ProgressMonitor;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexPageScannerTest {
	private static final String ROOT = "https://bits.example/pdp11/Diagnostics/";

	private static final String MIRROR = "https://mirror.example/pdp11/Diagnostics/";

	private static DiagnosticSource source(boolean recurse, String... includes) {
		return new DiagnosticSource("test", "Test", List.of(URI.create(ROOT), URI.create(MIRROR)), List.of(includes), recurse, true, "");
	}

	private static FakeWeb web(String root) {
		return new FakeWeb()
			.page(root, FakeWeb.apacheIndex("RX01/", "RX02/", "readme.txt", "xxdp-rk.dsk"))
			.page(root + "RX01/", FakeWeb.apacheIndex("rxdp_01.dsk", "labels.txt"))
			.page(root + "RX02/", FakeWeb.apacheIndex("deeper/", "BA-F021K-MC_DYDP%231.DSK"))
			.page(root + "RX02/deeper/", FakeWeb.apacheIndex("deep.dsk"));
	}

	private static List<String> paths(List<IndexPageScanner.Found> found) {
		return found.stream().map(IndexPageScanner.Found::path).toList();
	}

	@Test
	void theWantedFilesAreFoundAndEverythingElseIsPassedBy() throws IOException {
		List<String> problems = new ArrayList<>();
		List<IndexPageScanner.Found> found = new IndexPageScanner(web(ROOT)).scan(source(true, "*.dsk"), problems, ProgressMonitor.NULL);
		assertEquals(List.of("xxdp-rk.dsk", "RX01/rxdp_01.dsk", "RX02/BA-F021K-MC_DYDP#1.DSK", "RX02/deeper/deep.dsk"), paths(found));
		assertEquals(List.of(), problems);
	}

	@Test
	void aHashInANameStaysEncodedInTheAddress() throws IOException {
		List<IndexPageScanner.Found> found = new IndexPageScanner(web(ROOT)).scan(source(true, "*%231.DSK", "*#1.DSK"), new ArrayList<>(), ProgressMonitor.NULL);
		assertEquals(ROOT + "RX02/BA-F021K-MC_DYDP%231.DSK", found.get(0).uri().toString());
		assertEquals("BA-F021K-MC_DYDP#1.DSK", found.get(0).fileName(), "the name is matched and stored decoded");
	}

	@Test
	void withoutRecursionOnlyTheTopPageIsRead() throws IOException {
		FakeWeb web = web(ROOT);
		List<IndexPageScanner.Found> found = new IndexPageScanner(web).scan(source(false, "*.dsk"), new ArrayList<>(), ProgressMonitor.NULL);
		assertEquals(List.of("xxdp-rk.dsk"), paths(found));
		assertEquals(List.of(ROOT), web.m_requests);
	}

	@Test
	void theParentAndTheSortingLinksAreNeverFollowed() throws IOException {
		FakeWeb web = web(ROOT);
		new IndexPageScanner(web).scan(source(true, "*.dsk"), new ArrayList<>(), ProgressMonitor.NULL);
		for(String r : web.m_requests) {
			assertTrue(r.startsWith(ROOT), r);
			assertFalse(r.contains("?"), r);
		}
	}

	@Test
	void linksElsewhereAreNotInside() {
		URI root = URI.create(ROOT);
		assertTrue(IndexPageScanner.isInside(root, URI.create(ROOT + "a.dsk")));
		assertFalse(IndexPageScanner.isInside(root, root), "the page itself");
		assertFalse(IndexPageScanner.isInside(root, URI.create("https://bits.example/pdp11/")));
		assertFalse(IndexPageScanner.isInside(root, URI.create("https://other.example/pdp11/Diagnostics/a.dsk")));
		assertFalse(IndexPageScanner.isInside(root, URI.create("http://bits.example/pdp11/Diagnostics/a.dsk")));
	}

	@Test
	void hrefsInEitherQuoteAreResolvedAgainstThePage() {
		List<URI> links = IndexPageScanner.links(URI.create("https://a.example/x/y/"),
			"<a href='one.dsk'>1</a> <A HREF=\"../two.dsk#top\">2</A> <a href=\"/three.dsk?x=1\">3</a> <a href=\"#here\">4</a>"
				+ "<a href=\"mailto:a@b\">5</a> <a href=\"https://b.example/four.dsk\">6</a>");
		assertEquals(List.of("https://a.example/x/y/one.dsk", "https://a.example/x/two.dsk", "https://a.example/three.dsk",
			"https://b.example/four.dsk"), links.stream().map(URI::toString).toList());
	}

	@Test
	void whenTheSiteIsDownTheMirrorIsScanned() throws IOException {
		FakeWeb web = web(MIRROR).down("bits.example");
		List<IndexPageScanner.Found> found = new IndexPageScanner(web).scan(source(false, "*.dsk"), new ArrayList<>(), ProgressMonitor.NULL);
		assertEquals(MIRROR + "xxdp-rk.dsk", found.get(0).uri().toString());
		assertEquals(URI.create(MIRROR), found.get(0).root());
		assertEquals(URI.create(ROOT + "xxdp-rk.dsk"), found.get(0).at(URI.create(ROOT)));
	}

	@Test
	void whenEveryLocationIsDownTheScanFails() {
		FakeWeb web = new FakeWeb().down("bits.example").down("mirror.example");
		assertThrows(IOException.class, () -> new IndexPageScanner(web).scan(source(false, "*.dsk"), new ArrayList<>(), ProgressMonitor.NULL));
	}

	@Test
	void aSubdirectoryThatFailsIsAProblemNotTheEnd() throws IOException {
		FakeWeb web = web(ROOT);
		web.m_hook = u -> {
			if(u.endsWith("RX01/"))
				throw new IOException(u + ": HTTP 500");
		};
		List<String> problems = new ArrayList<>();
		List<IndexPageScanner.Found> found = new IndexPageScanner(web).scan(source(true, "*.dsk"), problems, ProgressMonitor.NULL);
		assertEquals(3, found.size());
		assertEquals(1, problems.size());
		assertTrue(problems.get(0).contains("HTTP 500"));
	}
}
