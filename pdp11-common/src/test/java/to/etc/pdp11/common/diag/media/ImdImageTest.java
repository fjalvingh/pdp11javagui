package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImdImageTest {
	@Test
	void anImageDiskFileDecodesToThePhysicalImage() throws Exception {
		byte[] physical = TestMedia.pattern(77 * 26 * 128, 3);
		//-- Some sectors one byte repeated, which ImageDisk compresses.
		Arrays.fill(physical, 5 * 128, 9 * 128, (byte) 0345);
		ImdImage.Decoded d = ImdImage.decode(TestMedia.imd(physical, 128, 77));
		assertEquals(128, d.sectorSize());
		assertEquals(26, d.sectorsPerTrack());
		assertEquals(77, d.tracks());
		assertArrayEquals(physical, d.data());
	}

	@Test
	void anImagingRunThatStoppedEarlyIsShorter() throws Exception {
		byte[] physical = TestMedia.pattern(77 * 26 * 128, 3);
		ImdImage.Decoded d = ImdImage.decode(TestMedia.imd(physical, 128, 40));
		assertEquals(40, d.tracks());
		assertArrayEquals(Arrays.copyOf(physical, 40 * 26 * 128), d.data());
	}

	@Test
	void onlyImageDiskFilesAreTakenForThem() {
		assertTrue(ImdImage.looksLikeImd("IMD 1.18".getBytes()));
		assertFalse(ImdImage.looksLikeImd(new byte[512]));
		assertThrows(MediaFormatException.class, () -> ImdImage.decode(new byte[512]));
	}

	@Test
	void aTruncatedFileIsRefusedRatherThanReadPastItsEnd() {
		byte[] imd = TestMedia.imd(TestMedia.pattern(77 * 26 * 128, 3), 128, 2);
		assertThrows(MediaFormatException.class, () -> ImdImage.decode(Arrays.copyOf(imd, imd.length - 50)));
	}
}
