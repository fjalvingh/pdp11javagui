package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.diag.media.MediaFile;
import to.etc.pdp11.common.diag.media.MediaVolume;
import to.etc.pdp11.common.diag.media.TestMedia;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticLibraryTest {
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

	private static final byte[] FKAA = TestMedia.absoluteLoader(01000, TestMedia.pattern(2000, 1), 01000, new byte[]{1, 2, 3});

	private static MediaVolume disk(String name, MediaFile... files) {
		return new MediaVolume(name, "TU58 tape, DOS-11 file system", TestMedia.pattern(1024, 9), List.of(files), List.of());
	}

	private static LibraryFile only(DiagnosticLibrary lib, String name) {
		List<LibraryFile> f = lib.files().stream().filter(x -> x.name().equals(name)).toList();
		assertEquals(1, f.size(), f.toString());
		return f.get(0);
	}

	@Test
	void aMediumIsStoredWithItsFilesAndItsImage(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		int added = lib.add("ak6dn-tu58", "https://x/1134_1.DSK", disk("1134_1.DSK",
			new MediaFile("FKAAC0.BIC", LocalDate.of(1989, 3, 1), FKAA)), TODAY);
		assertEquals(1, added);
		assertArrayEquals(FKAA, Files.readAllBytes(dir.resolve("files/FKAAC0.BIC")));
		assertTrue(Files.exists(dir.resolve("media/ak6dn-tu58/1134_1.DSK")));
		LibraryMedium m = lib.media().get(0);
		assertEquals("media/ak6dn-tu58/1134_1.DSK", m.imagePath());
		assertEquals(1, m.fileCount());
		assertTrue(lib.hasDownloaded("https://x/1134_1.DSK"));
	}

	@Test
	void whatIsSavedIsWhatIsReadBack(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		lib.add("a", "https://x/one.dsk", new MediaVolume("one.dsk", "RK05 disk", new byte[10],
			List.of(new MediaFile("FKAAC0.BIC", LocalDate.of(1989, 3, 1), FKAA), new MediaFile("HELP.TXT", null, new byte[]{65}, true)),
			List.of("first problem", "second problem")), TODAY);
		lib.add("b", "https://y/two.zip", new MediaVolume("two.zip/inner.tap", "magnetic tape", new byte[10],
			List.of(new MediaFile("FKAAC0.BIC", null, FKAA)), List.of()), TODAY);
		lib.save();

		DiagnosticLibrary again = DiagnosticLibrary.open(dir);
		assertEquals(0, again.getUnreadableLines());
		assertEquals(lib.media(), again.media());
		assertEquals(lib.files(), again.files());
		assertEquals(List.of("first problem", "second problem"), again.media().get(0).problems());
		assertTrue(only(again, "HELP.TXT").damaged());
		assertEquals(2, only(again, "FKAAC0.BIC").foundOn().size());
	}

	@Test
	void theSameProgramOnTwoMediaIsOneFile(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		lib.add("a", "https://x/1", disk("1.DSK", new MediaFile("FKAAC0.BIC", null, FKAA)), TODAY);
		//-- The same program out of another image, with other rubbish in the last block's slack.
		byte[] otherSlack = TestMedia.absoluteLoader(01000, TestMedia.pattern(2000, 1), 01000, new byte[]{7, 7, 7, 7, 7});
		int added = lib.add("a", "https://x/2", disk("2.DSK", new MediaFile("FKAAC0.BIC", null, otherSlack)), TODAY);
		assertEquals(0, added);
		LibraryFile f = only(lib, "FKAAC0.BIC");
		assertEquals(List.of("https://x/1!1.DSK", "https://x/2!2.DSK"), f.foundOn());
		assertFalse(Files.exists(dir.resolve("files/variants")));
	}

	@Test
	void aDifferentProgramUnderTheSameNameIsKeptBesideIt(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		lib.add("a", "https://x/1", disk("1.DSK", new MediaFile("FKAAC0.BIC", null, FKAA)), TODAY);
		byte[] other = TestMedia.absoluteLoader(01000, TestMedia.pattern(2000, 2), 01000, new byte[0]);
		assertEquals(1, lib.add("a", "https://x/2", disk("2.DSK", new MediaFile("FKAAC0.BIC", null, other)), TODAY));
		List<LibraryFile> both = lib.files();
		assertEquals("files/FKAAC0.BIC", both.get(0).path());
		assertTrue(both.get(1).path().startsWith("files/variants/"), both.get(1).path());
		assertArrayEquals(other, Files.readAllBytes(dir.resolve(both.get(1).path())));
		assertArrayEquals(FKAA, Files.readAllBytes(dir.resolve("files/FKAAC0.BIC")), "the first keeps its place");
	}

	@Test
	void aWholeCopyTakesThePlainNameFromADamagedOne(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		byte[] broken = java.util.Arrays.copyOf(FKAA, 1000);
		lib.add("a", "https://x/partial.imd", disk("partial.imd", new MediaFile("FKAAC0.BIC", null, broken, true)), TODAY);
		lib.add("a", "https://x/good.dsk", disk("good.dsk", new MediaFile("FKAAC0.BIC", null, FKAA)), TODAY);
		assertArrayEquals(FKAA, Files.readAllBytes(dir.resolve("files/FKAAC0.BIC")));
		LibraryFile damaged = lib.files().stream().filter(LibraryFile::damaged).findFirst().orElseThrow();
		assertArrayEquals(broken, Files.readAllBytes(dir.resolve(damaged.path())), "moved, not lost");
	}

	@Test
	void collectingAMediumAgainReplacesItsRecord(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		lib.add("a", "https://x/1", disk("1.DSK", new MediaFile("FKAAC0.BIC", null, FKAA)), TODAY);
		lib.add("a", "https://x/1", disk("1.DSK", new MediaFile("FKAAC0.BIC", null, FKAA)), TODAY);
		assertEquals(1, lib.media().size());
		assertEquals(1, only(lib, "FKAAC0.BIC").foundOn().size());
	}

	@Test
	void aSingleProgramIsKeptOnlyAsAFile(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		byte[] bin = TestMedia.absoluteLoader(0, new byte[4], 0, new byte[0]);
		lib.add("ak6dn-diagnostics", "https://x/dl11.bin",
			new MediaVolume("dl11.bin", "program file", bin, List.of(new MediaFile("DL11.BIN", null, bin)), List.of()), TODAY);
		assertEquals("", lib.media().get(0).imagePath());
		assertFalse(Files.exists(dir.resolve("media")));
		assertTrue(Files.exists(dir.resolve("files/DL11.BIN")));
	}

	@Test
	void namesFromTheWebAreMadeSafeForTheFileSystem() {
		assertEquals("BA-F021K-MC_DYDP+3_LSI-11_#1.DSK", DiagnosticLibrary.safeName("BA-F021K-MC_DYDP+3_LSI-11_#1.DSK"));
		assertEquals("a_b_c_.dsk", DiagnosticLibrary.safeName("a:b|c?.dsk"));
		assertEquals("_", DiagnosticLibrary.safeName(".."));
	}

	@Test
	void aDamagedIndexLosesItsBadLinesOnly(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir);
		lib.add("a", "https://x/1", disk("1.DSK", new MediaFile("FKAAC0.BIC", null, FKAA)), TODAY);
		lib.save();
		Path index = dir.resolve(DiagnosticLibrary.INDEX_FILE);
		String text = Files.readString(index) + "F\tonly\tthree\n" + "M\ta\tb\tc\td\te\tnot-a-number\t\t\n" + "garbage\n";
		Files.writeString(index, text, StandardCharsets.UTF_8);
		DiagnosticLibrary again = DiagnosticLibrary.open(dir);
		assertEquals(3, again.getUnreadableLines());
		assertEquals(1, again.files().size());
		assertEquals(1, again.media().size());
	}

	@Test
	void aLibraryThatDoesNotExistYetIsEmpty(@TempDir Path dir) throws Exception {
		DiagnosticLibrary lib = DiagnosticLibrary.open(dir.resolve("not/yet"));
		assertTrue(lib.isEmpty());
		assertFalse(Files.exists(dir.resolve("not")), "opening creates nothing");
	}
}
