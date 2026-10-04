package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fast loader's machine-free half: that it assembles wherever it may be put, where it is put,
 * and what its blocks look like. Whether the loader then <i>works</i> is
 * {@link M9312FastLoadTest}'s, against a fake that executes it.
 */
class FastLoaderTest {
	/** Words from {@code from}, {@code count} of them. */
	private static TreeSet<Integer> words(int from, int count) {
		TreeSet<Integer> s = new TreeSet<>();
		for(int i = 0; i < count; i++) {
			s.add(from + 2 * i);
		}
		return s;
	}

	@Test
	void itAssemblesAnywhereToTheSameSizeAndCodeLength() {
		FastLoader.Image first = FastLoader.assemble(0, 0165020);
		for(int origin : new int[] {0, 01000, 037400, 0157000}) {
			FastLoader.Image im = FastLoader.assemble(origin, 0165020);
			assertEquals(origin, im.origin());
			assertEquals(origin + FastLoader.SIZE, im.end());
			assertEquals(first.code().size(), im.code().size());
			assertEquals(origin, im.code().get(0).address());
			assertTrue(im.code().get(im.code().size() - 1).address() < im.probe(), "the code ends before the space does");
		}
	}

	/**
	 * Small enough that depositing it the slow way is a few seconds, and the threshold that
	 * decides whether to bother is set against this.
	 */
	@Test
	void theLoaderIsAboutSixtyWordsOfCode() {
		int n = FastLoader.assemble(01000, 0165020).code().size();
		assertTrue(n <= 64, n + " words");
		assertTrue(M9312Console.FAST_LOAD_MIN_WORDS > n);
	}

	@Test
	void itReturnsToTheEntryItIsGiven() {
		List<FastLoader.Word> code = FastLoader.assemble(01000, 0165020).code();
		FastLoader.Word last = code.get(code.size() - 1);
		assertEquals(0165020, last.value());
		assertEquals(000137, code.get(code.size() - 2).value(), "JMP @#");
	}

	@Test
	void itGoesInTheHighestGapItFits() {
		TreeSet<Integer> t = words(01000, 0100);           // 01000..01176, and 0..776 free below it
		t.addAll(words(02000, 0400));                       // 02000..02776: 01200..01776 free, 384 bytes
		t.addAll(words(03000, 0400));                       // touching: no gap
		assertEquals(02000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	@Test
	void aGapTooSmallIsPassedOverForALowerOne() {
		TreeSet<Integer> t = words(02000, 0400);            // gap below: 0..01776
		t.addAll(words(02000 + 01000 + 0100, 0400));        // a 64-byte gap above it
		assertEquals(02000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	/** Nothing below and nothing between: the top of the image, never above it. */
	@Test
	void withNoGapItOverlaysTheTopOfTheImage() {
		TreeSet<Integer> t = words(0, 01000);               // 0..01776
		assertEquals(02000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	@Test
	void anImageSmallerThanTheLoaderHasNowhereToPutIt() {
		assertEquals(-1, FastLoader.place(words(0, 10), FastLoader.SIZE));
		assertEquals(-1, FastLoader.place(new TreeSet<>(), FastLoader.SIZE));
	}

	@Test
	void blocksBreakAtGapsAndAtTheBlockSize() {
		TreeMap<Integer, Integer> w = new TreeMap<>();
		for(int i = 0; i < FastLoader.BLOCK_WORDS + 3; i++) {
			w.put(01000 + 2 * i, i);
		}
		w.put(05000, 7);
		List<FastLoader.Block> b = FastLoader.blocks(w);
		assertEquals(3, b.size());
		assertEquals(01000, b.get(0).address());
		assertEquals(FastLoader.BLOCK_WORDS, b.get(0).values().length);
		assertEquals(01000 + 2 * FastLoader.BLOCK_WORDS, b.get(1).address());
		assertEquals(3, b.get(1).values().length);
		assertEquals(05000, b.get(2).address());
		assertEquals(7, b.get(2).values()[0]);
	}

	/** Count, address, data and checksum, little-endian, adding up to zero as the loader checks. */
	@Test
	void aBlockIsEncodedAsTheLoaderReadsIt() {
		byte[] e = new FastLoader.Block(01000, new int[] {0177777, 0123456}).encode();
		assertEquals(1 + 2 * 4, e.length);
		assertEquals(2, e[0]);
		int sum = e[0];
		for(int i = 1; i < e.length; i += 2) {
			sum += (e[i] & 0xFF) | (e[i + 1] & 0xFF) << 8;
		}
		assertEquals(0, sum & 0xFFFF);
		assertEquals(0, e[1] & 0xFF);
		assertEquals(02, e[2] & 0xFF);
	}

	/** The padding finishes the longest block there is, and the commands are all seven-bit. */
	@Test
	void paddingCoversAWholeBlockAndTheCommandsSurviveASevenBitLine() {
		assertTrue(FastLoader.RESYNC_PADDING >= 1 + 2 * (FastLoader.BLOCK_WORDS + 2));
		assertTrue(FastLoader.SYNC < 0200 && FastLoader.EXIT < 0200 && FastLoader.BLOCK_WORDS < FastLoader.SYNC);
		for(int b : FastLoader.probe(FastLoader.assemble(01000, 0165020)).values()) {
			assertTrue((b & 0200) != 0 && (b & 0100000) != 0, "every probe byte has its top bit set");
		}
	}
}
