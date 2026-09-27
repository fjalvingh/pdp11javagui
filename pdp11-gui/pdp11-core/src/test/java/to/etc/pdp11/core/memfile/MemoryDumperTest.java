package to.etc.pdp11.core.memfile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing memory out in each of the four file formats.
 *
 * <p>These are file formats other programs and real hardware read: a ROM burner, a PDP-11's own
 * absolute loader. The bytes are the contract, so the tests assert bytes.</p>
 */
class MemoryDumperTest {
	/** A group of consecutive words holding {@code values} from {@code start}. */
	private static MemoryCellGroup group(long start, int... values) {
		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "dump");
		g.add(start, values.length);
		for(int i = 0; i < values.length; i++) {
			g.cell(i).setEditValue(CellValue.of(values[i]));
		}
		return g;
	}

	private static Result save(MemoryFileFormat f, MemoryCellGroup g, Path dir, Address entry) throws IOException {
		List<Path> files = new java.util.ArrayList<>();
		for(int i = 0; i < f.getFileCount(); i++) {
			files.add(dir.resolve("out" + i + "." + f.getDefaultExtension()));
		}
		MemoryDumper.Result r = MemoryDumper.save(f, g, files, entry);
		return new Result(r, files);
	}

	private record Result(MemoryDumper.Result result, List<Path> files) {
		byte[] bytes(int index) throws IOException {
			return Files.readAllBytes(files.get(index));
		}

		String text() throws IOException {
			return Files.readString(files.get(0), StandardCharsets.US_ASCII);
		}
	}

	// ---------------------------------------------------------------------------------------
	// Byte stream
	// ---------------------------------------------------------------------------------------

	@Test
	void aByteStreamIsWordsLowByteFirst(@TempDir Path dir) throws Exception {
		Result r = save(MemoryFileFormat.BYTE_STREAM, group(01000, 0000001, 0177777, 0125252), dir, null);
		//-- Little endian, like everything else on a PDP-11.
		assertArrayEquals(new byte[] {0x01, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xAA, (byte) 0xAA},
			r.bytes(0));
		assertEquals(3, r.result().wordsWritten());
		assertTrue(r.result().isComplete());
	}

	/**
	 * A dump is made of what was read back from a machine, and a word that was never read has no
	 * value. A positional format has to put something there; what it must not do is put something
	 * there quietly.
	 */
	@Test
	void aWordThatWasNeverReadIsZeroAndIsCounted(@TempDir Path dir) throws Exception {
		MemoryCellGroup g = group(01000, 0, 0, 0);
		g.cell(1).setEditValue(CellValue.UNKNOWN);
		Result r = save(MemoryFileFormat.BYTE_STREAM, g, dir, null);

		assertEquals(1, r.result().unknownWords());
		assertFalse(r.result().isComplete());
		//-- Zero, not the Pascal's truncated sentinel - which is 0177777, a real value, silently
		//-- in the middle of something about to be burned into a ROM.
		assertArrayEquals(new byte[6], r.bytes(0));
	}

	// ---------------------------------------------------------------------------------------
	// Split byte files
	// ---------------------------------------------------------------------------------------

	/**
	 * The Pascal writes {@code byte_h := w shl 8} where every other line in the unit shifts
	 * right, so the high byte file it produces is all zeros. Its own {@code Load} gets it right,
	 * which is what makes it a typo rather than a convention.
	 */
	@Test
	void theHighByteFileHoldsTheHighBytes(@TempDir Path dir) throws Exception {
		Result r = save(MemoryFileFormat.LOW_HIGH_BYTE_FILES, group(0, 0xAB12, 0xCD34), dir, null);
		assertArrayEquals(new byte[] {0x12, 0x34}, r.bytes(0), "low bytes");
		assertArrayEquals(new byte[] {(byte) 0xAB, (byte) 0xCD}, r.bytes(1), "high bytes");
	}

	// ---------------------------------------------------------------------------------------
	// Text
	// ---------------------------------------------------------------------------------------

	@Test
	void theTextFormatIsAnAddressAndUpToEightValues(@TempDir Path dir) throws Exception {
		Result r = save(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE,
			group(01000, 1, 2, 3, 4, 5, 6, 7, 8, 9), dir, null);
		//-- Eight to a line, and the ninth starts a new one because the line is full.
		assertEquals("001000: 000001 000002 000003 000004 000005 000006 000007 000010\n"
			+ "001020: 000011\n", r.text());
	}

	@Test
	void aGapInTheAddressesStartsANewLine(@TempDir Path dir) throws Exception {
		MemoryCellGroups groups = new MemoryCellGroups();
		MemoryCellGroup g = groups.addGroup(MemoryAddressType.PHYSICAL16, "sparse");
		g.add(01000).setEditValue(CellValue.of(01));
		g.add(01002).setEditValue(CellValue.of(02));
		g.add(01100).setEditValue(CellValue.of(03));
		Result r = save(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE, g, dir, null);
		assertEquals("001000: 000001 000002\n001100: 000003\n", r.text());
	}

	@Test
	void aWordThatWasNeverReadIsLeftOutOfTheTextEntirely(@TempDir Path dir) throws Exception {
		MemoryCellGroup g = group(01000, 1, 2, 3);
		g.cell(1).setEditValue(CellValue.UNKNOWN);
		Result r = save(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE, g, dir, null);
		//-- It can be left out because the format carries its addresses - and leaving it out is
		//-- what makes the next value start a new line.
		assertEquals("001000: 000001\n001004: 000003\n", r.text());
		assertEquals(1, r.result().unknownWords());
	}

	// ---------------------------------------------------------------------------------------
	// DEC absolute paper tape
	// ---------------------------------------------------------------------------------------

	@Test
	void aPaperTapeBlockHasAHeaderTheDataAndAChecksumThatSumsToZero(@TempDir Path dir) throws Exception {
		Result r = save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, group(01000, 0000001, 0000002), dir, null);
		byte[] out = r.bytes(0);

		//-- 01 00, size, address, two words, checksum, four stuff bytes.
		assertEquals(6 + 4 + 1 + 4, out.length);
		assertEquals(1, out[0]);
		assertEquals(0, out[1]);
		assertEquals(10, out[2] & 0xFF, "block size low: 6 header + 4 data");
		assertEquals(0, out[3] & 0xFF);
		assertEquals(01000 & 0xFF, out[4] & 0xFF, "start address low");
		assertEquals(01000 >> 8, out[5] & 0xFF);

		//-- The whole block including the checksum byte sums to zero, which is how the loader
		//-- knows it read the block correctly.
		int sum = 0;
		for(int i = 0; i < out.length - 4; i++) {
			sum += out[i] & 0xFF;
		}
		assertEquals(0, sum & 0xFF, "the checksum is what makes this zero");

		//-- And the four stuff bytes are a gap in the tape, outside the checksum.
		for(int i = out.length - 4; i < out.length; i++) {
			assertEquals(0, out[i]);
		}
	}

	@Test
	void aGapInTheAddressesIsASecondBlock(@TempDir Path dir) throws Exception {
		MemoryCellGroups groups = new MemoryCellGroups();
		MemoryCellGroup g = groups.addGroup(MemoryAddressType.PHYSICAL16, "sparse");
		g.add(01000).setEditValue(CellValue.of(1));
		g.add(01002).setEditValue(CellValue.of(2));
		g.add(02000).setEditValue(CellValue.of(3));
		Result r = save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, g, dir, null);
		assertEquals(2, r.result().blocks());
	}

	@Test
	void theEntryAddressIsABlockWithNoDataInIt(@TempDir Path dir) throws Exception {
		Address entry = Address.of(MemoryAddressType.PHYSICAL16, 01000);
		Result r = save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, group(01000, 0000001), dir, entry);
		byte[] out = r.bytes(0);
		assertEquals(2, r.result().blocks());

		//-- The last block: header of six, no data, checksum, four stuff bytes. Its address is
		//-- where the loader starts executing.
		int last = out.length - 11;
		assertEquals(1, out[last]);
		assertEquals(6, out[last + 2] & 0xFF, "a block with no data is six bytes");
		assertEquals(01000 & 0xFF, out[last + 4] & 0xFF);
		assertEquals(01000 >> 8, out[last + 5] & 0xFF);
	}

	@Test
	void anAddressTooBigForSixteenBitsIsRefusedRatherThanTruncated(@TempDir Path dir) throws Exception {
		MemoryCellGroups groups = new MemoryCellGroups();
		MemoryCellGroup g = groups.addGroup(MemoryAddressType.PHYSICAL22, "high");
		g.add(0400000).setEditValue(CellValue.of(1));
		IOException x = assertThrows(IOException.class,
			() -> MemoryDumper.save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, g,
				List.of(dir.resolve("t.ptap")), null));
		assertTrue(x.getMessage().contains("too large"), x.getMessage());
	}

	// ---------------------------------------------------------------------------------------
	// Refusals
	// ---------------------------------------------------------------------------------------

	@Test
	void anEmptyRangeIsRefusedWithAReason(@TempDir Path dir) {
		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "empty");
		IOException x = assertThrows(IOException.class,
			() -> MemoryDumper.save(MemoryFileFormat.BYTE_STREAM, g, List.of(dir.resolve("t.bin")), null));
		assertTrue(x.getMessage().contains("nothing to write"), x.getMessage());
	}

	@Test
	void aFormatNeedingTwoFilesSaysSoWhenGivenOne(@TempDir Path dir) {
		MemoryCellGroup g = group(0, 1);
		assertThrows(IllegalArgumentException.class,
			() -> MemoryDumper.save(MemoryFileFormat.LOW_HIGH_BYTE_FILES, g,
				List.of(dir.resolve("t.bin")), null));
	}

	@Test
	void everyFormatKnowsHowManyFilesItNeedsAndWhatToCallThem() {
		for(MemoryFileFormat f : MemoryFileFormat.values()) {
			assertEquals(f.getFileCount(), f.getFilePrompts().size(), f.name());
			assertTrue(f.getFileCount() >= 1);
		}
		assertEquals(2, MemoryFileFormat.LOW_HIGH_BYTE_FILES.getFileCount());
		assertTrue(MemoryFileFormat.ABSOLUTE_PAPERTAPE.hasEntryAddress());
		assertTrue(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE.isText());
	}
}
