package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The RX interleave, pinned to positions worked out by hand rather than to the formula.
 *
 * <p>Logical sector {@code s} of logical track {@code t} is physical sector
 * {@code (2s (+1 past the middle) + 6t) mod 26} of physical track {@code t + 1}. So, for an RX02
 * (two 256-byte sectors to a block):</p>
 *
 * <ul>
 * <li>block 1 is logical sectors 2 and 3 of track 0, which are physical sectors 4 and 6 of track 1
 *     (0-based);</li>
 * <li>block 7 is sectors 14 and 15: past the middle, so 2*14+1 = 29 and 31, mod 26 = 3 and 5;</li>
 * <li>block 13 is sectors 0 and 1 of track 1: skewed by six, to 6 and 8 of physical track 2.</li>
 * </ul>
 */
class BlockImageTest {
	private static final int SPT = 26;

	private static byte[] markedPhysicalRx02() {
		byte[] physical = new byte[77 * SPT * 256];
		for(int sector = 0; sector < 77 * SPT; sector++) {
			for(int i = 0; i < 256; i++)
				physical[sector * 256 + i] = (byte) (sector >> (i % 2 == 0 ? 0 : 8));
		}
		return physical;
	}

	/** The physical sector number a 256-byte chunk of a block came from. */
	private static int sectorOf(byte[] block, int half) {
		return (block[half * 256] & 0xff) | (block[half * 256 + 1] & 0xff) << 8;
	}

	@Test
	void block1IsSectors4And6OfTrack1() {
		BlockImage image = BlockImage.rxInterleaved(markedPhysicalRx02(), 256);
		byte[] b = image.block(1);
		assertEquals(SPT + 4, sectorOf(b, 0));
		assertEquals(SPT + 6, sectorOf(b, 1));
	}

	@Test
	void pastTheMiddleOfATrackTheOddSectorsAreUsed() {
		byte[] b = BlockImage.rxInterleaved(markedPhysicalRx02(), 256).block(7);
		assertEquals(SPT + 3, sectorOf(b, 0));
		assertEquals(SPT + 5, sectorOf(b, 1));
	}

	@Test
	void eachTrackIsSkewedBySixSectorsFromTheLast() {
		byte[] b = BlockImage.rxInterleaved(markedPhysicalRx02(), 256).block(13);
		assertEquals(2 * SPT + 6, sectorOf(b, 0));
		assertEquals(2 * SPT + 8, sectorOf(b, 1));
	}

	@Test
	void anRxDisketteHasTheBlocksXxdpGivesIt() {
		assertEquals(988, BlockImage.rxInterleaved(new byte[512_512], 256).blockCount());
		assertEquals(494, BlockImage.rxInterleaved(new byte[256_256], 128).blockCount());
	}

	@Test
	void aLinearImageIsItsBlocksInOrder() {
		byte[] data = TestMedia.pattern(3 * 512 + 100, 5);
		BlockImage image = BlockImage.linear(data);
		assertEquals(3, image.blockCount(), "the partial block at the end is not a block");
		assertArrayEquals(java.util.Arrays.copyOfRange(data, 512, 1024), image.block(1));
		assertNull(image.block(3));
		assertNull(image.block(-1));
	}

	@Test
	void theTestWriterAgreesWithTheReader() {
		byte[] logical = TestMedia.pattern(988 * 512, 11);
		BlockImage image = BlockImage.rxInterleaved(TestMedia.rxPhysical(logical, 256), 256);
		for(int n : new int[]{0, 1, 7, 13, 500, 987})
			assertArrayEquals(java.util.Arrays.copyOfRange(logical, n * 512, n * 512 + 512), image.block(n), "block " + n);
	}
}
