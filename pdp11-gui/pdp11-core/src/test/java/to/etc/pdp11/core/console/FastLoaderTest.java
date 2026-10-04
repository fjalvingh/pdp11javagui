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

	/**
	 * Every PDP-11 has 8KW, so an image that ends below that has the space above it free, and
	 * that is where the loader goes - at the top of it, out of the image's way.
	 */
	@Test
	void anImageBelow8kwHasTheLoaderJustUnder8kw() {
		assertEquals(040000 - FastLoader.SIZE, FastLoader.place(words(0, 01000), FastLoader.SIZE));
		TreeSet<Integer> t = words(0, 01000);
		t.addAll(words(030000, 0100));                      // a gap in between does not matter
		assertEquals(040000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	/** An image that ends just under 8KW leaves too little: then the gaps below. */
	@Test
	void anImageEndingJustUnder8kwFallsBackToAGap() {
		TreeSet<Integer> t = words(0, 01000);               // 0..01776, then 02000..
		t.addAll(words(010000, (040000 - 010000) / 2 - 010)); // up to 037756: 16 bytes left above
		assertEquals(010000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	/** Above 8KW nothing is known beyond the image's top, so the highest gap below it. */
	@Test
	void aBigImageUsesItsHighestGapItFits() {
		TreeSet<Integer> t = words(0, 024000);               // 0..047776
		t.addAll(words(051000, 0100));                      // 050000..050776 free, 512 bytes
		t.addAll(words(052000, 0400));                      // 051200..051776 free, 384 bytes
		t.addAll(words(053000, 0400));                      // touching: no gap
		assertEquals(052000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	@Test
	void aGapTooSmallIsPassedOverForALowerOne() {
		TreeSet<Integer> t = words(0, 020000);              // 0..037776
		t.addAll(words(042000, 0400));                      // 040000..041776 free
		t.addAll(words(043000 + 0100, 0400));               // a 64-byte gap above it
		assertEquals(042000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	/** Above 8KW, with nothing below and nothing between: the top of the image, never above it. */
	@Test
	void aBigImageWithNoGapIsOverlaidAtItsTop() {
		TreeSet<Integer> t = words(0, 024000);              // 0..047776
		assertEquals(050000 - FastLoader.SIZE, FastLoader.place(t, FastLoader.SIZE));
	}

	@Test
	void nothingToDepositHasNowhereToPutIt() {
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
