package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XxdpVolumeTest {
	private static final LocalDate MARCH_1989 = LocalDate.of(1989, 3, 1);

	private static List<MediaFile> sampleFiles() {
		return List.of(
			new MediaFile("XXDPXM.SYS", MARCH_1989, TestMedia.pattern(39 * 510, 1)),
			new MediaFile("ZRLGE0.BIC", MARCH_1989, TestMedia.pattern(5000, 2)),
			new MediaFile("DD.SYS", null, TestMedia.pattern(100, 3)),
			new MediaFile("MMDP.SAV", MARCH_1989, TestMedia.pattern(17 * 512, 4)));
	}

	/** What the reader gives back is what was written, up to the end of the last block. */
	private static void assertSameFiles(List<MediaFile> written, List<MediaFile> read) {
		assertEquals(written.size(), read.size());
		for(int i = 0; i < written.size(); i++) {
			MediaFile w = written.get(i);
			MediaFile r = read.get(i);
			assertEquals(w.name(), r.name());
			assertEquals(w.date(), r.date(), w.name());
			assertArrayEquals(w.data(), Arrays.copyOf(r.data(), w.data().length), w.name());
			assertTrue(r.data().length - w.data().length < 512, w.name() + ": no more than one block's slack");
			assertFalse(r.damaged(), w.name());
		}
	}

	@Test
	void anXxdpPlusDiskReadsBack() throws Exception {
		List<MediaFile> files = sampleFiles();
		XxdpVolume v = XxdpVolume.open(BlockImage.linear(TestMedia.disk(TestMedia.Form.XXDP_PLUS, 20480, files)));
		assertEquals(XxdpVolume.Form.XXDP_PLUS, v.getForm());
		List<String> problems = new ArrayList<>();
		assertSameFiles(files, v.readAll(problems));
		assertEquals(List.of(), problems);
	}

	@Test
	void aDos11DiskReadsBack() throws Exception {
		List<MediaFile> files = sampleFiles();
		XxdpVolume v = XxdpVolume.open(BlockImage.linear(TestMedia.disk(TestMedia.Form.DOS11, 512, files)));
		assertEquals(XxdpVolume.Form.DOS11, v.getForm());
		assertSameFiles(files, v.readAll(new ArrayList<>()));
	}

	@Test
	void aSavFileIsContiguousAndKeepsAllOfEveryBlock() throws Exception {
		List<MediaFile> files = sampleFiles();
		XxdpVolume v = XxdpVolume.open(BlockImage.linear(TestMedia.disk(TestMedia.Form.DOS11, 512, files)));
		XxdpVolume.Entry sav = v.getEntries().get(3);
		assertTrue(sav.isContiguous());
		assertEquals(17 * 512, v.read(sav, new ArrayList<>()).length);
	}

	@Test
	void theTopBitOfTheDateMarksAContiguousFileUnderAnyName() throws Exception {
		byte[] data = TestMedia.pattern(3 * 512, 9);
		byte[] image = TestMedia.disk(TestMedia.Form.DOS11, 578, List.of(new MediaFile("D0AA0.SAV", LocalDate.of(1972, 9, 11), data)));
		//-- Rename it .SAC, as the 1972 DECtapes have it, and set the flag those tapes set.
		TestMedia.putWord(image, 3, 3, TestMedia.rad50("SAC"));
		TestMedia.putWord(image, 3, 4, 0100000 | TestMedia.dosDate(LocalDate.of(1972, 9, 11)));
		XxdpVolume v = XxdpVolume.open(BlockImage.linear(image));
		XxdpVolume.Entry e = v.getEntries().get(0);
		assertEquals("D0AA0.SAC", e.name());
		assertTrue(e.isContiguous());
		assertEquals(LocalDate.of(1972, 9, 11), e.date());
		assertArrayEquals(data, v.read(e, new ArrayList<>()));
	}

	@Test
	void aDirectoryOfMoreThanOneBlockIsFollowed() throws Exception {
		List<MediaFile> files = new ArrayList<>();
		for(int i = 0; i < 60; i++)
			files.add(new MediaFile(String.format(java.util.Locale.ROOT, "ZT%03dA0.BIN", i).substring(0, 6) + ".BIN", null, TestMedia.pattern(600, i)));
		XxdpVolume v = XxdpVolume.open(BlockImage.linear(TestMedia.disk(TestMedia.Form.XXDP_PLUS, 2000, files)));
		assertEquals(60, v.getEntries().size(), "three directory blocks, 28 + 28 + 4");
		assertEquals("ZT059A.BIN", v.getEntries().get(59).name());
	}

	@Test
	void theDecTapeDirectoryIsFoundAtBlock66WhenTheMfdIsEmpty() throws Exception {
		List<MediaFile> files = List.of(new MediaFile("DTMON.BIN", LocalDate.of(1972, 10, 1), TestMedia.pattern(9000, 6)));
		byte[] written = TestMedia.disk(TestMedia.Form.DOS11, 578, files);
		//-- Move the directory to 66 and empty the MFD, as on MAINDEC-11-DZQDD.
		byte[] image = new byte[578 * 512];
		System.arraycopy(written, 3 * 512, image, 66 * 512, 512);
		System.arraycopy(written, 40 * 512, image, 40 * 512, 20 * 512);
		XxdpVolume v = XxdpVolume.open(BlockImage.linear(image));
		assertEquals(XxdpVolume.Form.DECTAPE, v.getForm());
		assertSameFiles(files, v.readAll(new ArrayList<>()));
	}

	@Test
	void aBrokenChainIsCutAndTheFileMarkedDamaged() throws Exception {
		List<MediaFile> files = List.of(new MediaFile("SIZER.BIN", null, TestMedia.pattern(5 * 510, 7)));
		byte[] image = TestMedia.disk(TestMedia.Form.DOS11, 512, files);
		//-- The third block links off the end of the disk, as SIZER.BIN does on an RKDP pack.
		TestMedia.putWord(image, 42, 0, 4992);
		List<String> problems = new ArrayList<>();
		List<MediaFile> read = XxdpVolume.open(BlockImage.linear(image)).readAll(problems);
		assertTrue(read.get(0).damaged());
		assertEquals(3 * 510, read.get(0).data().length, "blocks 40, 41 and 42 are read; the link after them is the break");
		assertTrue(problems.get(0).contains("past the end"), problems.toString());
	}

	@Test
	void aLoopingChainEnds() throws Exception {
		List<MediaFile> files = List.of(new MediaFile("LOOP.BIN", null, TestMedia.pattern(3 * 510, 8)));
		byte[] image = TestMedia.disk(TestMedia.Form.DOS11, 512, files);
		TestMedia.putWord(image, 42, 0, 40);
		List<String> problems = new ArrayList<>();
		MediaFile f = XxdpVolume.open(BlockImage.linear(image)).readAll(problems).get(0);
		assertTrue(f.damaged());
		assertTrue(problems.get(0).contains("loops"), problems.toString());
	}

	@Test
	void programCodeIsNotTakenForADirectory() {
		byte[] code = TestMedia.pattern(512 * 512, 13);
		assertThrows(MediaFormatException.class, () -> XxdpVolume.open(BlockImage.linear(code)));
	}

	@Test
	void anEmptyDiskHasNoDirectory() {
		assertThrows(MediaFormatException.class, () -> XxdpVolume.open(BlockImage.linear(new byte[512 * 512])));
		assertThrows(MediaFormatException.class, () -> XxdpVolume.open(BlockImage.linear(new byte[100])));
	}

	@Test
	void aDirectoryChainThatLoopsIsRefused() {
		byte[] image = TestMedia.disk(TestMedia.Form.XXDP_PLUS, 512, sampleFiles());
		TestMedia.putWord(image, 3, 0, 3);
		MediaFormatException x = assertThrows(MediaFormatException.class, () -> XxdpVolume.open(BlockImage.linear(image)));
		assertTrue(x.getMessage().contains("loops"));
	}
}
