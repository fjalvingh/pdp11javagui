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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading files back into memory, and round-tripping them through {@link MemoryDumper}.
 *
 * <p>The round trip is the test that matters: the Pascal's split-byte class does not survive one
 * in either direction - its save writes an all-zero high byte file and its load reads half the
 * words - and neither bug is visible from one side alone.</p>
 */
class MemoryFileLoaderTest {
	private static Address at(long v) {
		return Address.of(MemoryAddressType.PHYSICAL16, v);
	}

	private static MemoryCellGroup emptyGroup() {
		return new MemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "loaded");
	}

	private static MemoryCellGroup filled(long start, int... values) {
		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "source");
		g.add(start, values.length);
		for(int i = 0; i < values.length; i++) {
			g.cell(i).setEditValue(CellValue.of(values[i]));
		}
		return g;
	}

	private static List<Path> paths(Path dir, MemoryFileFormat f) {
		List<Path> l = new java.util.ArrayList<>();
		for(int i = 0; i < f.getFileCount(); i++) {
			l.add(dir.resolve("f" + i + "." + f.getDefaultExtension()));
		}
		return l;
	}

	// ---------------------------------------------------------------------------------------
	// Round trips
	// ---------------------------------------------------------------------------------------

	@Test
	void everyFormatSurvivesBeingWrittenAndReadBack(@TempDir Path dir) throws Exception {
		int[] values = {0000001, 0177777, 0125252, 0052525, 0000000, 0123456};
		for(MemoryFileFormat f : MemoryFileFormat.values()) {
			Path sub = Files.createDirectories(dir.resolve(f.name()));
			List<Path> files = paths(sub, f);
			MemoryCellGroup source = filled(01000, values);
			MemoryDumper.save(f, source, files, f.hasEntryAddress() ? at(01000) : null);

			MemoryCellGroup loaded = emptyGroup();
			MemoryFileLoader.Result r = MemoryFileLoader.load(f, loaded, files, at(01000));

			assertEquals(values.length, r.wordsLoaded(), f + " should load what it wrote");
			for(int i = 0; i < values.length; i++) {
				MemoryCell mc = loaded.findByAddress(at(01000 + 2L * i));
				assertNotNull(mc, f + " lost the word at " + Integer.toOctalString(01000 + 2 * i));
				assertEquals(values[i], mc.getEditValue().word(),
					f + " changed the word at " + Integer.toOctalString(01000 + 2 * i));
			}
		}
	}

	/**
	 * The Pascal's split-byte class fails this in both directions at once, which is why neither
	 * bug was ever noticed: its save writes an all-zero high byte file, and its load then reads
	 * {@code size div 2} words out of a file holding one byte per word.
	 */
	@Test
	void theSplitByteFormatRoundTripsBothHalvesOfEveryWord(@TempDir Path dir) throws Exception {
		MemoryCellGroup source = filled(0, 0xAB12, 0xCD34, 0x00FF, 0xFF00);
		List<Path> files = paths(dir, MemoryFileFormat.LOW_HIGH_BYTE_FILES);
		MemoryDumper.save(MemoryFileFormat.LOW_HIGH_BYTE_FILES, source, files, null);

		assertEquals(4, Files.size(files.get(0)), "one byte per word");
		assertEquals(4, Files.size(files.get(1)));

		MemoryCellGroup loaded = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.LOW_HIGH_BYTE_FILES,
			loaded, files, at(0));
		assertEquals(4, r.wordsLoaded(), "all four words, not two");
		assertEquals(0xAB12, loaded.cell(0).getEditValue().word());
		assertEquals(0xFF00, loaded.cell(3).getEditValue().word());
	}

	// ---------------------------------------------------------------------------------------
	// What a loaded cell means
	// ---------------------------------------------------------------------------------------

	/**
	 * A file says what memory <i>should</i> hold; the machine has not been told. So the value is
	 * an edit value and the machine value is unknown, which is what makes the grid show every
	 * word as changed and the Deposit button worth pressing.
	 */
	@Test
	void aLoadedWordIsAnEditNotSomethingTheMachineSaid(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("x.bin");
		Files.write(file, new byte[] {0x01, 0x00});
		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.load(MemoryFileFormat.BYTE_STREAM, g, List.of(file), at(01000));

		assertEquals(1, g.cell(0).getEditValue().word());
		assertFalse(g.cell(0).getPdpValue().isKnown(), "nothing has been read from a machine");
		assertTrue(g.cell(0).isEdited());
	}

	@Test
	void loadingReplacesWhateverWasThere(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("x.bin");
		Files.write(file, new byte[] {0x01, 0x00});
		MemoryCellGroup g = filled(04000, 1, 2, 3, 4, 5);
		MemoryFileLoader.load(MemoryFileFormat.BYTE_STREAM, g, List.of(file), at(01000));

		assertEquals(1, g.size(), "the old range is gone");
		assertEquals(01000, g.cell(0).getAddr().val());
	}

	// ---------------------------------------------------------------------------------------
	// Text
	// ---------------------------------------------------------------------------------------

	@Test
	void theTextFormatTakesAnAddressAndTheValuesFollowingIt(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("t.txt");
		Files.writeString(file, "001000: 000001 000002 000003\n002000: 000004\n", StandardCharsets.US_ASCII);
		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE,
			g, List.of(file), at(0));

		assertEquals(4, r.wordsLoaded());
		assertEquals(1, g.findByAddress(at(01000)).getEditValue().word());
		assertEquals(3, g.findByAddress(at(01004)).getEditValue().word(), "values follow on from the address");
		assertEquals(4, g.findByAddress(at(02000)).getEditValue().word());
		//-- Sorted, because a grid lays cells out by address and nothing about a file guarantees it.
		assertEquals(01000, g.cell(0).getAddr().val());
		assertEquals(02000, g.cell(3).getAddr().val());
	}

	/**
	 * The original is deliberately forgiving here: a line not starting with an octal digit is
	 * skipped, and inside a line anything that is not an octal digit is a separator. So the
	 * format's own colons need no handling, and neither does a comment somebody added.
	 */
	@Test
	void theTextFormatIgnoresAnythingThatIsNotAnOctalNumber(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("t.txt");
		Files.writeString(file, "; a comment\n"
			+ "\n"
			+ "001000:\t000001,000002   ; two words\n"
			+ "not an address at all\n", StandardCharsets.US_ASCII);
		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE,
			g, List.of(file), at(0));

		assertEquals(2, r.wordsLoaded());
		assertEquals(1, g.findByAddress(at(01000)).getEditValue().word());
		assertEquals(2, g.findByAddress(at(01002)).getEditValue().word());
		assertFalse(r.warnings().isEmpty(), "and it says how many lines it skipped");
	}

	@Test
	void aTextFileWithNothingUsableInItIsRefusedWithAReason(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("t.txt");
		Files.writeString(file, "nothing here\nnor here\n", StandardCharsets.US_ASCII);
		MemoryCellGroup g = emptyGroup();
		IOException x = assertThrows(IOException.class, () -> MemoryFileLoader.load(
			MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE, g, List.of(file), at(0)));
		assertTrue(x.getMessage().contains("No octal address"), x.getMessage());
	}

	// ---------------------------------------------------------------------------------------
	// Paper tape
	// ---------------------------------------------------------------------------------------

	@Test
	void aPaperTapeImageGivesUpItsBlocksAndItsEntryAddress(@TempDir Path dir) throws Exception {
		//-- Two blocks with a gap, plus an entry address, written by the dumper.
		MemoryCellGroups groups = new MemoryCellGroups();
		MemoryCellGroup source = groups.addGroup(MemoryAddressType.PHYSICAL16, "src");
		source.add(01000).setEditValue(CellValue.of(0111));
		source.add(01002).setEditValue(CellValue.of(0222));
		source.add(02000).setEditValue(CellValue.of(0333));
		Path file = dir.resolve("t.ptap");
		MemoryDumper.save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, source, List.of(file), at(01000));

		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.ABSOLUTE_PAPERTAPE,
			g, List.of(file), at(0));

		assertEquals(3, r.wordsLoaded());
		assertEquals(0111, g.findByAddress(at(01000)).getEditValue().word());
		assertEquals(0333, g.findByAddress(at(02000)).getEditValue().word());
		assertNotNull(r.entryAddress());
		assertEquals(01000, r.entryAddress().val(), "the zero length block says where to start");
	}

	@Test
	void leaderTapeAndStuffBytesBeforeABlockAreSkipped(@TempDir Path dir) throws Exception {
		MemoryCellGroup source = filled(01000, 0111);
		Path file = dir.resolve("t.ptap");
		MemoryDumper.save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, source, List.of(file), null);

		//-- Real tapes start with a run of blank frames, and the loader has to walk past them.
		byte[] body = Files.readAllBytes(file);
		byte[] withLeader = new byte[body.length + 40];
		System.arraycopy(body, 0, withLeader, 40, body.length);
		Files.write(file, withLeader);

		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.ABSOLUTE_PAPERTAPE,
			g, List.of(file), at(0));
		assertEquals(1, r.wordsLoaded());
		assertEquals(0111, g.findByAddress(at(01000)).getEditValue().word());
	}

	/**
	 * A damaged image would deposit wrong values into a machine and there is no way to say which,
	 * so it is refused rather than partly loaded.
	 */
	@Test
	void aBadChecksumStopsTheLoad(@TempDir Path dir) throws Exception {
		MemoryCellGroup source = filled(01000, 0111, 0222);
		Path file = dir.resolve("t.ptap");
		MemoryDumper.save(MemoryFileFormat.ABSOLUTE_PAPERTAPE, source, List.of(file), null);
		byte[] bytes = Files.readAllBytes(file);
		bytes[7] ^= 0x20;                                   // corrupt a data byte
		Files.write(file, bytes);

		MemoryCellGroup g = emptyGroup();
		IOException x = assertThrows(IOException.class, () -> MemoryFileLoader.load(
			MemoryFileFormat.ABSOLUTE_PAPERTAPE, g, List.of(file), at(0)));
		assertTrue(x.getMessage().contains("Checksum error"), x.getMessage());
	}

	@Test
	void anImageWithNoBlocksInItIsRefused(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("blank.ptap");
		Files.write(file, new byte[64]);                    // blank leader and nothing else
		MemoryCellGroup g = emptyGroup();
		assertThrows(IOException.class, () -> MemoryFileLoader.load(
			MemoryFileFormat.ABSOLUTE_PAPERTAPE, g, List.of(file), at(0)));
	}

	// ---------------------------------------------------------------------------------------
	// Refusals
	// ---------------------------------------------------------------------------------------

	@Test
	void anOddNumberOfBytesLoadsTheWholeWordsAndSaysSo(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("odd.bin");
		Files.write(file, new byte[] {0x01, 0x00, 0x02});
		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.BYTE_STREAM,
			g, List.of(file), at(01000));
		assertEquals(1, r.wordsLoaded());
		assertEquals(1, r.warnings().size());
	}

	/**
	 * A forgiving format stays forgiving. An address wider than the group used to reach
	 * {@code Address.of} and throw {@link IllegalArgumentException} out of a load documented as
	 * collecting warnings, losing every value already read.
	 */
	@Test
	void aTextAddressOutsideTheAddressSpaceIsAWarningAndNotAnException(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("t.txt");
		Files.writeString(file, "001000: 000001 000002\n"
			+ "7777777: 000003\n"
			+ "001100: 000004\n", StandardCharsets.US_ASCII);
		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE,
			g, List.of(file), at(0));

		assertEquals(3, r.wordsLoaded(), "the three in range, and not the one that is not");
		assertEquals(1, g.findByAddress(at(01000)).getEditValue().word());
		assertEquals(4, g.findByAddress(at(01100)).getEditValue().word(), "read after the bad line");
		assertTrue(r.warnings().stream().anyMatch(w -> w.contains("outside the 16 bit address space")),
			r.warnings().toString());
	}

	/** An over-wide value is masked, as the Pascal does - but it says so now. */
	@Test
	void aTextValueWiderThanAWordIsTruncatedAndSaidSo(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("t.txt");
		Files.writeString(file, "001000: 1234567\n", StandardCharsets.US_ASCII);
		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.TEXT_ONE_ADDR_PER_LINE,
			g, List.of(file), at(0));
		assertEquals(01234567 & 0xFFFF, g.findByAddress(at(01000)).getEditValue().word());
		assertTrue(r.warnings().stream().anyMatch(w -> w.contains("wider than 16 bits")),
			r.warnings().toString());
	}

	/**
	 * A byte stream that will not fit above its start address used to throw from inside
	 * {@code shiftRange}, after {@code clear()}, leaving the group holding half a load.
	 */
	@Test
	void aByteStreamRunningOffTheTopOfMemoryLoadsWhatFitsAndSaysSo(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("big.bin");
		Files.write(file, new byte[8]);                     // four words
		MemoryCellGroup g = emptyGroup();
		MemoryFileLoader.Result r = MemoryFileLoader.load(MemoryFileFormat.BYTE_STREAM,
			g, List.of(file), at(0177772));                 // room for three

		assertEquals(3, r.wordsLoaded());
		assertEquals(3, g.getCells().size());
		assertNotNull(g.findByAddress(at(0177776)));
		assertTrue(r.warnings().stream().anyMatch(w -> w.contains("only 3 fit")), r.warnings().toString());
	}

	@Test
	void aMissingFileIsReportedRatherThanThrowingSomethingObscure(@TempDir Path dir) {
		MemoryCellGroup g = emptyGroup();
		IOException x = assertThrows(IOException.class, () -> MemoryFileLoader.load(
			MemoryFileFormat.BYTE_STREAM, g, List.of(dir.resolve("nope.bin")), at(0)));
		assertTrue(x.getMessage().contains("Cannot read"), x.getMessage());
	}

	@Test
	void onlyTheFormatsThatCarryAddressesReportAnEntryAddress(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("x.bin");
		Files.write(file, new byte[] {0x01, 0x00});
		MemoryCellGroup g = emptyGroup();
		assertNull(MemoryFileLoader.load(MemoryFileFormat.BYTE_STREAM, g, List.of(file), at(0))
			.entryAddress());
		assertFalse(MemoryFileFormat.BYTE_STREAM.definesOwnAddresses());
		assertTrue(MemoryFileFormat.ABSOLUTE_PAPERTAPE.definesOwnAddresses());
	}
}
