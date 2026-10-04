package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaReaderTest {
	private static final List<MediaFile> FILES = List.of(
		new MediaFile("FKAAC0.BIC", null, TestMedia.pattern(24 * 510, 1)),
		new MediaFile("DD.SYS", null, TestMedia.pattern(3 * 510, 2)));

	private static MediaVolume only(List<MediaVolume> volumes) {
		assertEquals(1, volumes.size(), volumes.toString());
		return volumes.get(0);
	}

	@Test
	void aTu58ImageIsNamedForWhatItIs() {
		MediaVolume v = only(MediaReader.read("1134_1.DSK", TestMedia.disk(TestMedia.Form.DOS11, 512, FILES)));
		assertEquals("TU58 tape, DOS-11 file system", v.description());
		assertEquals(2, v.files().size());
		assertFalse(v.isLooseFile());
	}

	@Test
	void aGzippedRl02IsDecompressedAndRenamed() {
		byte[] rl02 = TestMedia.disk(TestMedia.Form.XXDP_PLUS, 20480, FILES);
		MediaVolume v = only(MediaReader.read("xxdp25.rl02.gz", TestMedia.gzip(rl02)));
		assertEquals("xxdp25.rl02", v.name());
		assertEquals("RL02 disk, XXDP+ file system", v.description());
		assertEquals(rl02.length, v.image().length, "the image kept is the decompressed one");
	}

	@Test
	void aPhysicalRx02ImageIsReadThroughTheInterleave() {
		byte[] logical = TestMedia.disk(TestMedia.Form.DOS11, 988, FILES);
		MediaVolume v = only(MediaReader.read("1134_1.RX2", TestMedia.rxPhysical(logical, 256)));
		assertEquals("RX02 floppy, DOS-11 file system", v.description());
		assertEquals("FKAAC0.BIC", v.files().get(0).name());
	}

	@Test
	void aLogicalRx01ImageIsReadAsItIs() {
		MediaVolume v = only(MediaReader.read("rxdp_01.dsk", TestMedia.disk(TestMedia.Form.DOS11, 494, FILES)));
		assertEquals("RX01 floppy, DOS-11 file system", v.description());
		assertEquals(2, v.files().size());
	}

	@Test
	void anImageDiskRx01ThatStoppedEarlyIsReadAsFarAsItGoes() {
		byte[] physical = TestMedia.rxPhysical(TestMedia.disk(TestMedia.Form.DOS11, 494, FILES), 128);
		MediaVolume v = only(MediaReader.read("rxdp1.imd", TestMedia.imd(physical, 128, 40)));
		assertTrue(v.description().startsWith("ImageDisk RX01 floppy"), v.description());
		assertEquals(2, v.files().size());
		assertTrue(v.problems().get(0).contains("Only 40"), v.problems().toString());
	}

	@Test
	void aSimhTapeIsReadAsOne() {
		MediaVolume v = only(MediaReader.read("mmdp.tap", TestMedia.tape(FILES)));
		assertEquals("magnetic tape, DOS-11 format", v.description());
		assertEquals(2, v.files().size());
	}

	@Test
	void aZipHoldsAVolumePerMediumAndTheLabelPhotosAreIgnored() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try(ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("set/"));
			zip.putNextEntry(new ZipEntry("set/tape1.tap"));
			zip.write(TestMedia.tape(FILES));
			zip.putNextEntry(new ZipEntry("set/disk.dsk.gz"));
			zip.write(TestMedia.gzip(TestMedia.disk(TestMedia.Form.DOS11, 512, FILES)));
			zip.putNextEntry(new ZipEntry("set/label.jpg"));
			zip.write(TestMedia.pattern(3000, 1));
			zip.putNextEntry(new ZipEntry("set/.DS_Store"));
			zip.write(new byte[]{0, 0, 0, 1});
		}
		List<MediaVolume> volumes = MediaReader.read("MSDP.zip", bytes.toByteArray());
		assertEquals(List.of("MSDP.zip/set/tape1.tap", "MSDP.zip/set/disk.dsk"), volumes.stream().map(MediaVolume::name).toList());
	}

	@Test
	void aSingleProgramIsALooseFileUnderItsXxdpName() {
		byte[] bin = TestMedia.absoluteLoader(01000, TestMedia.pattern(40, 1), 01000, new byte[0]);
		MediaVolume v = only(MediaReader.read("dl11.bin", bin));
		assertTrue(v.isLooseFile());
		assertEquals("DL11.BIN", v.files().get(0).name());
		assertSame(bin, v.files().get(0).data());
	}

	@Test
	void whatCannotBeReadSaysWhy() {
		MediaVolume v = only(MediaReader.read("photo.jpg", TestMedia.pattern(3000, 1)));
		assertFalse(v.isReadable());
		assertEquals("not a medium this can read", v.problems().get(0));

		MediaVolume rk = only(MediaReader.read("rkdp.dsk", TestMedia.pattern(2_457_600, 1)));
		assertTrue(rk.problems().get(0).contains("directory may be damaged"), rk.problems().toString());

		MediaVolume gz = only(MediaReader.read("broken.dsk.gz", new byte[]{1, 2, 3}));
		assertTrue(gz.problems().get(0).startsWith("the archive is damaged"), gz.problems().toString());
	}
}
