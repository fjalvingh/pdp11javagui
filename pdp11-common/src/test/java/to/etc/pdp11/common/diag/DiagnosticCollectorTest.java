package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.diag.media.MediaFile;
import to.etc.pdp11.common.diag.media.TestMedia;
import to.etc.pdp11.common.util.OperationCancelledException;
import to.etc.pdp11.common.util.ProgressMonitor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end, against a web held in memory: scan, download, read, store.
 */
class DiagnosticCollectorTest {
	private static final String ROOT = "https://bits.example/diag/";

	private static final String MIRROR = "https://mirror.example/diag/";

	private static final List<MediaFile> TU58_FILES = List.of(
		new MediaFile("FKAAC0.BIC", LocalDate.of(1989, 3, 1), TestMedia.absoluteLoader(01000, TestMedia.pattern(3000, 1), 01000, new byte[0])),
		new MediaFile("DD.SYS", LocalDate.of(1989, 3, 1), TestMedia.pattern(1477, 2)));

	private static final List<MediaFile> RL02_FILES = List.of(
		new MediaFile("FKAAC0.BIC", LocalDate.of(1989, 3, 1), TestMedia.absoluteLoader(01000, TestMedia.pattern(3000, 1), 01000, new byte[0])),
		new MediaFile("ZRLGE0.BIC", LocalDate.of(1989, 3, 1), TestMedia.absoluteLoader(01000, TestMedia.pattern(5000, 3), 01000, new byte[0])));

	private static DiagnosticSource source() {
		return new DiagnosticSource("test", "Test", List.of(URI.create(ROOT), URI.create(MIRROR)), List.of("*.DSK", "*.rl02.gz", "*.bin"),
			false, true, "");
	}

	private static FakeWeb web(String root) {
		return web(root, new FakeWeb());
	}

	private static FakeWeb web(String root, FakeWeb web) {
		return web
			.page(root, FakeWeb.apacheIndex("1134_1.DSK", "xxdp25.rl02.gz", "dl11.bin", "notes.txt"))
			.file(root + "1134_1.DSK", TestMedia.disk(TestMedia.Form.DOS11, 512, TU58_FILES))
			.file(root + "xxdp25.rl02.gz", TestMedia.gzip(TestMedia.disk(TestMedia.Form.XXDP_PLUS, 20480, RL02_FILES)))
			.file(root + "dl11.bin", TestMedia.absoluteLoader(0, new byte[]{1, 2}, 0, new byte[0]));
	}

	private static DiagnosticCollector collector(DiagnosticLibrary lib, FakeWeb web) {
		return new DiagnosticCollector(lib, web, () -> LocalDate.of(2026, 10, 4));
	}

	@Test
	void everythingFoundIsDownloadedReadAndStored(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		DiagnosticCollector c = collector(lib, web(ROOT));
		DiagnosticCollector.ScanResult scan = c.scan(List.of(source()), ProgressMonitor.NULL);
		assertEquals(3, scan.found().size());
		DiagnosticCollector.CollectResult r = c.collect(scan.found(), ProgressMonitor.NULL);
		assertEquals(3, r.downloaded());
		assertEquals(3, r.media());
		assertEquals(4, r.newFiles(), "FKAAC0.BIC is on both images and counts once");
		assertEquals(List.of(), r.problems());

		assertEquals(List.of("FKAAC0.BIC", "DD.SYS", "ZRLGE0.BIC", "DL11.BIN"), lib.files().stream().map(LibraryFile::name).toList());
		assertTrue(Files.exists(dir.resolve("media/test/xxdp25.rl02")), "kept decompressed, for SimH");
		assertEquals(20480 * 512, Files.size(dir.resolve("media/test/xxdp25.rl02")));
		assertEquals(4, DiagnosticLibrary.open(dir).files().size(), "and the index was saved");
	}

	@Test
	void whatIsCollectedIsNotOfferedAgain(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		FakeWeb web = web(ROOT);
		DiagnosticCollector c = collector(lib, web);
		List<IndexPageScanner.Found> found = c.scan(List.of(source()), ProgressMonitor.NULL).found();
		c.collect(found.subList(0, 1), ProgressMonitor.NULL);
		List<IndexPageScanner.Found> rest = c.notCollected(found);
		assertEquals(List.of("xxdp25.rl02.gz", "dl11.bin"), rest.stream().map(IndexPageScanner.Found::fileName).toList());
	}

	@Test
	void aDownloadThatFailsIsAProblemAndTheRestGoOn(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		FakeWeb web = web(ROOT);
		web.m_hook = u -> {
			if(u.endsWith(".DSK"))
				throw new IOException(u + ": HTTP 503");
		};
		DiagnosticCollector c = collector(lib, web);
		DiagnosticCollector.CollectResult r = c.collect(c.scan(List.of(source()), ProgressMonitor.NULL).found(), ProgressMonitor.NULL);
		assertEquals(2, r.downloaded());
		assertEquals(1, r.problems().size());
		assertTrue(r.problems().get(0).startsWith("1134_1.DSK: "), r.problems().toString());
	}

	@Test
	void aFileTheSiteHasLostIsFetchedFromTheMirror(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		FakeWeb web = web(ROOT);
		web.file(MIRROR + "1134_1.DSK", TestMedia.disk(TestMedia.Form.DOS11, 512, TU58_FILES));
		web.m_hook = u -> {
			if(u.equals(ROOT + "1134_1.DSK"))
				throw new IOException(u + ": HTTP 404");
		};
		DiagnosticCollector c = collector(lib, web);
		DiagnosticCollector.CollectResult r = c.collect(c.scan(List.of(source()), ProgressMonitor.NULL).found(), ProgressMonitor.NULL);
		assertEquals(3, r.downloaded());
		assertTrue(r.problems().stream().anyMatch(p -> p.contains("mirror.example")), r.problems().toString());
		assertTrue(lib.hasDownloaded(ROOT + "1134_1.DSK"), "recorded under the address the scan found");
	}

	@Test
	void cancellingKeepsWhatWasAlreadyCollected(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		FakeWeb web = web(ROOT);
		AtomicBoolean cancel = new AtomicBoolean();
		web.m_hook = u -> {
			if(u.endsWith(".gz"))
				cancel.set(true);
		};
		ProgressMonitor monitor = new ProgressMonitor() {
			@Override
			public void begin(String task, int total) {
			}

			@Override
			public void step(int amount, String note) {
			}

			@Override
			public boolean isCancelled() {
				return cancel.get();
			}

			@Override
			public void done() {
			}
		};
		DiagnosticCollector c = collector(lib, web);
		List<IndexPageScanner.Found> found = c.scan(List.of(source()), ProgressMonitor.NULL).found();
		assertThrows(OperationCancelledException.class, () -> c.collect(found, monitor));
		DiagnosticLibrary again = DiagnosticLibrary.open(dir);
		assertEquals(List.of("FKAAC0.BIC", "DD.SYS"), again.files().stream().map(LibraryFile::name).toList());
		assertTrue(again.hasDownloaded(ROOT + "1134_1.DSK"));
		assertTrue(!again.hasDownloaded(ROOT + "xxdp25.rl02.gz"), "the download cut short was never added");
	}

	@Test
	void aDownloadShorterThanTheServerSaidIsRefused(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		FakeWeb web = web(ROOT, new FakeWeb() {
			@Override
			public Response get(URI uri) throws IOException {
				Response r = super.get(uri);
				return uri.toString().endsWith(".DSK") ? new Response(r.body(), r.length() + 10) : r;
			}
		});
		DiagnosticCollector c = collector(lib, web);
		DiagnosticCollector.CollectResult r = c.collect(c.scan(List.of(source()), ProgressMonitor.NULL).found(), ProgressMonitor.NULL);
		assertTrue(r.problems().get(0).contains("stopped at"), r.problems().toString());
	}
}
