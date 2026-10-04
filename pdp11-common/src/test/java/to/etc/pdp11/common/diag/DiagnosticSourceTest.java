package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSourceTest {
	@Test
	void theBuiltInListReadsAndIsSensible() {
		List<DiagnosticSource> sources = DiagnosticSource.builtIn();
		assertTrue(sources.size() >= 5, sources.toString());
		Set<String> ids = new HashSet<>();
		for(DiagnosticSource s : sources) {
			assertTrue(ids.add(s.id()), "duplicate id " + s.id());
			assertTrue(s.id().matches("[a-z0-9-]+"), "the id names a directory: " + s.id());
			assertFalse(s.includes().isEmpty(), s.id() + " takes nothing");
			assertFalse(s.note().isEmpty(), s.id() + " says nothing about itself");
			for(var u : s.urls())
				assertEquals("https", u.getScheme(), s.id() + ": bitsavers' http-to-https redirect decodes %23, so ask for https");
		}
		assertTrue(sources.stream().anyMatch(DiagnosticSource::byDefault));
	}

	@Test
	void patternsMatchFileNamesInAnyCase() {
		DiagnosticSource s = new DiagnosticSource("x", "X", List.of(java.net.URI.create("https://a.b/c/")),
			List.of("*.rl02.gz", "11??_?.DSK"), false, true, "");
		assertTrue(s.wants("xxdp25.rl02.gz"));
		assertTrue(s.wants("XXDP25.RL02.GZ"));
		assertTrue(s.wants("1134_1.dsk"));
		assertFalse(s.wants("xxdp25.rl02"));
		assertFalse(s.wants("11XXDP.DSK"));
		assertFalse(s.wants("a+b.rl02.gzip"), "a + or a . in a pattern is not a regular expression");
	}

	@Test
	void aBlockIsReadWithDefaults() throws Exception {
		List<DiagnosticSource> list = DiagnosticSource.parse(new StringReader("""
			# comment
			[one]
			url = https://example.org/a/
			mirror = https://mirror.example.org/a/
			include = *.dsk *.img
			note = first
			note = second
			"""));
		DiagnosticSource s = list.get(0);
		assertEquals("one", s.title(), "no title: the id stands in");
		assertEquals(2, s.urls().size());
		assertEquals(List.of("*.dsk", "*.img"), s.includes());
		assertFalse(s.recurse());
		assertTrue(s.byDefault());
		assertEquals("first second", s.note());
	}

	@Test
	void mistakesNameTheirLine() {
		IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
			() -> DiagnosticSource.parse(new StringReader("[a]\nurl = https://x.org/\nrecurse = maybe\n")));
		assertTrue(x.getMessage().contains("line 3"), x.getMessage());
		assertThrows(IllegalArgumentException.class, () -> DiagnosticSource.parse(new StringReader("[a]\nurl = https://x.org/file.dsk\n")),
			"a url must be a directory");
		assertThrows(IllegalArgumentException.class, () -> DiagnosticSource.parse(new StringReader("[a]\ntitle = no url\n")));
	}
}
