package to.etc.pdp11.common.disas;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning what the machine answered into a listing, and finding the PC in it.
 *
 * <p>All of this is what the Disassembler window shows, and none of it needs the window: the
 * point of {@link DisassemblyListing} living in the core is that the awkward part - a PC that
 * lands inside an instruction rather than at the start of one - is checkable here.</p>
 */
class DisassemblyListingTest {
	private static Address v(int val) {
		return Address.of(MemoryAddressType.VIRTUAL, val);
	}

	/** A group of consecutive virtual words holding {@code words}, starting at {@code start}. */
	private static MemoryCellGroup code(int start, int... words) {
		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
		g.add(start, words.length);
		for(int i = 0; i < words.length; i++) {
			g.cell(i).setPdpValue(CellValue.of(words[i]));
		}
		return g;
	}

	@Test
	void oneLinePerInstructionWithItsRawWordsBesideIt() {
		//-- mov #200,r1 / halt
		MemoryCellGroup g = code(01000, 012701, 0200, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), null);

		assertEquals(2, l.getLines().size());
		assertEquals(v(01000), l.getLines().get(0).address());
		assertEquals("mov     #000200,r1", l.getLines().get(0).text().stripTrailing());
		assertEquals(v(01004), l.getLines().get(1).address());
		assertEquals("halt", l.getLines().get(1).text().stripTrailing());
		//-- The two-word instruction shows both its words, and the padding leaves room for three.
		assertTrue(l.getLines().get(0).words().startsWith("012701 000200 "), l.getLines().get(0).words());
	}

	@Test
	void wordsTheMachineNeverAnsweredAreNotInvented() {
		MemoryCellGroup g = code(01000, 010001, 010203);
		g.cell(0).setPdpValue(CellValue.UNKNOWN);                   // never examined
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null);

		assertEquals(1, l.getLines().size(), "the unread word is skipped, not decoded as zero");
		assertEquals(v(01002), l.getLines().get(0).address());
	}

	/**
	 * What should be in memory is what is disassembled - a program loaded or assembled can be
	 * read before it goes to the machine - but it is not what the processor would execute, and
	 * the line says so.
	 */
	@Test
	void anEditedValueIsShownAndMarkedAsNotOnTheMachine() {
		MemoryCellGroup g = code(01000, 010001, 010203);
		g.cell(0).setEditValue(CellValue.of(0240));                 // typed, not deposited
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null);

		assertEquals("nop", l.getLines().get(0).text().stripTrailing());
		assertTrue(l.getLines().get(0).pending());
		assertFalse(l.getLines().get(1).pending(), "the machine holds this one");
	}

	/** An operand word not deposited makes its whole instruction something the CPU would not run. */
	@Test
	void anEditedOperandMarksTheInstructionItBelongsTo() {
		MemoryCellGroup g = code(01000, 012700, 0);                 // mov #0,r0
		g.cell(1).setEditValue(CellValue.of(0123));
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null);

		assertEquals(1, l.getLines().size());
		assertTrue(l.getLines().get(0).pending());
	}

	/** And with nothing read, a program loaded from a file is still there to read. */
	@Test
	void aProgramNeverDepositedCanBeDisassembled() {
		MemoryCellGroups groups = new MemoryCellGroups();
		MemoryCellGroup loaded = groups.addGroup(MemoryAddressType.PHYSICAL22, "Loader");
		loaded.add(01000).setEditValue(CellValue.of(0240));
		MemoryCellGroup disassembly = groups.addGroup(MemoryAddressType.VIRTUAL, "Disassembly");
		disassembly.shiftRange(v(01000), 1, true);

		DisassemblyListing l = DisassemblyListing.of(disassembly, v(01000), v(01000), null);

		assertEquals("nop", l.getLines().get(0).text().stripTrailing());
		assertTrue(l.getLines().get(0).pending());
	}

	@Test
	void thePcMarksItsLine() {
		MemoryCellGroup g = code(01000, 010001, 010203, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), v(01002));

		assertEquals(1, l.pcLine());
		assertTrue(l.getLines().get(1).atPc());
		assertEquals(v(01002), l.getLines().get(1).address());
	}

	/**
	 * The reason {@link DisassemblyListing#startAddress()} exists.
	 *
	 * <p>{@code mov #200,r1} occupies 1000 and 1002. Start at 1000 and the PC at 1002 is inside
	 * that instruction, not at the start of a line - so the listing has to begin at 1002
	 * instead, which is exactly what the Pascal's retry loop does
	 * ({@code FormDisasU.pas:274-279}).</p>
	 */
	@Test
	void aPcInsideAnInstructionMovesTheStartOfTheListing() {
		MemoryCellGroup g = code(01000, 012701, 0200, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), v(01002));

		assertEquals(v(01002), l.startAddress());
		assertEquals(0, l.pcLine());
		assertEquals(v(01002), l.getLines().get(0).address());
	}

	@Test
	void aPcThatCannotBeFoundLeavesTheListingWhereItWas() {
		MemoryCellGroup g = code(01000, 010001);
		//-- The PC is outside the range shown, which happens whenever the user has scrolled away.
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01000), v(02000));
		assertEquals(-1, l.pcLine());
		assertEquals(1, l.getLines().size(), "the listing stays where it was asked to be");
		assertEquals(v(01000), l.startAddress());
	}

	@Test
	void noPcAtAllIsOrdinary() {
		//-- The M9312's console emulator cannot say where the PC is, and the Pascal spells that
		//-- with its illegal-value sentinel. Here it is simply null.
		MemoryCellGroup g = code(01000, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01000), null);
		assertEquals(-1, l.pcLine());
		assertEquals("001000: 000000                halt    \n", l.toText());
	}

	/**
	 * A PC inside the range whose own word was never examined: there is no line to mark, and
	 * the lines before it must not be thrown away looking for one.
	 *
	 * <p>The realignment loop walks {@code from} up towards the PC two bytes at a time, hunting
	 * for a decode that lands on it. When the PC's word is unread that hunt cannot ever succeed
	 * - {@code build} skips unread words - so the loop used to run out at the PC and return the
	 * listing it happened to be holding, which starts <i>at</i> the PC. Everything between the
	 * requested start and the PC vanished, and {@code startAddress()} said the PC. What the
	 * class promises for a PC it cannot find is the listing as asked for, with
	 * {@code pcLine() == -1}.</p>
	 */
	@Test
	void aPcInRangeWhoseWordWasNeverReadKeepsTheLinesBeforeIt() {
		MemoryCellGroup g = code(01000, 010001, 010203, 010405, 010607);
		g.cell(2).setPdpValue(CellValue.UNKNOWN);                   // 001004 never examined
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01006), v(01004));

		assertEquals(-1, l.pcLine(), "there is no line at the PC to mark");
		assertEquals(v(01000), l.startAddress(), "the listing starts where it was asked to");
		assertEquals(3, l.getLines().size(), "the two lines before the PC are still there");
		assertEquals(v(01000), l.getLines().get(0).address());
		assertEquals(v(01002), l.getLines().get(1).address());
		assertEquals(v(01006), l.getLines().get(2).address());
	}

	@Test
	void onlyTheRangeAskedForIsShown() {
		MemoryCellGroup g = code(01000, 0, 0, 0, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01002), v(01004), null);
		assertEquals(2, l.getLines().size());
		assertEquals(v(01002), l.getLines().get(0).address());
		assertEquals(v(01004), l.getLines().get(1).address());
	}

	// -------------------------------------------------------------------------------------
	// Data marks
	// -------------------------------------------------------------------------------------

	/** Little-endian words holding these bytes, padded with a zero byte to a whole word. */
	private static int[] bytes(int... b) {
		int[] w = new int[(b.length + 1) / 2];
		for(int i = 0; i < b.length; i++) {
			w[i / 2] |= (b[i] & 0xFF) << (8 * (i % 2));
		}
		return w;
	}

	private static int[] text(String s) {
		int[] b = new int[s.length()];
		for(int i = 0; i < s.length(); i++) {
			b[i] = s.charAt(i);
		}
		return bytes(b);
	}

	private static List<String> texts(DisassemblyListing l) {
		return l.getLines().stream().map(line -> line.address().toOctal() + " " + line.text().stripTrailing()).toList();
	}

	@Test
	void wordsMarkedAsWordsAreAWordTableThreeToALine() {
		MemoryCellGroup g = code(01000, 1, 2, 3, 4, 0);
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01006, DataMarks.Format.WORDS);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01010), null, 100, marks);

		assertEquals(List.of("001000 .word   000001,000002,000003", "001006 .word   000004", "001010 halt"), texts(l));
		assertEquals(6, l.getLines().get(0).length());
		assertEquals(DataMarks.Format.WORDS, l.getLines().get(0).format());
		assertNull(l.getLines().get(2).format(), "an unmarked word is code");
	}

	@Test
	void wordsMarkedAsBytesAreFourToALineInOctal() {
		MemoryCellGroup g = code(01000, bytes(1, 2, 3, 0377, 5, 6));
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01004, DataMarks.Format.BYTES);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), null, 100, marks);

		assertEquals(List.of("001000 .byte   001,002,003,377", "001004 .byte   005,006"), texts(l));
		assertTrue(l.getLines().get(0).words().startsWith("001 002 003 377 "), l.getLines().get(0).words());
	}

	/**
	 * Strings are packed the way MACRO-11 packs them: the next one begins at the byte after the
	 * last one's zero, odd or not, and the control characters are written the way the assembler
	 * reads them.
	 */
	@Test
	void stringsEndAtTheirZeroByteAndTheNextBeginsRightAfterIt() {
		int[] w = bytes('H', 'i', 015, 012, 0, 'O', 'K', 0);
		MemoryCellGroup g = code(01000, w[0], w[1], w[2], w[3], 0);
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01006, DataMarks.Format.ASCIZ);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01010), null, 100, marks);

		assertEquals(List.of("001000 .asciz  /Hi/<15><12>", "001005 .asciz  /OK/", "001010 halt"), texts(l));
		assertEquals(v(01012), l.nextAddress());
	}

	@Test
	void aZeroBytePaddingAStringToAWordIsEven() {
		MemoryCellGroup g = code(01000, text("AB\0\0"));
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01002, DataMarks.Format.ASCIZ);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null, 100, marks);

		assertEquals(List.of("001000 .asciz  /AB/", "001003 .even"), texts(l));
	}

	@Test
	void aStringWithASlashInItIsDelimitedBySomethingElse() {
		MemoryCellGroup g = code(01000, text("a/b\0"));
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01002, DataMarks.Format.ASCIZ);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null, 100, marks);

		assertEquals("001000 .asciz  \"a/b\"", texts(l).get(0));
	}

	@Test
	void aStringWithNoZeroByteBeforeTheMarkEndsIsAscii() {
		MemoryCellGroup g = code(01000, text("abcd"));
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01002, DataMarks.Format.ASCIZ);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null, 100, marks);

		assertEquals(List.of("001000 .ascii  /abcd/"), texts(l));
	}

	/**
	 * An instruction does not take its operand from data: {@code mov #200,r1} with the
	 * {@code 200} marked as a word is a word of code that does not decode, and then the word.
	 */
	@Test
	void anInstructionDoesNotReachIntoData() {
		MemoryCellGroup g = code(01000, 012701, 0200);
		DataMarks marks = new DataMarks();
		marks.mark(01002, 01002, DataMarks.Format.WORDS);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null, 100, marks);

		assertEquals(2, l.getLines().size());
		assertEquals(2, l.getLines().get(0).length(), "the instruction is one word now");
		assertEquals("001002 .word   000200", texts(l).get(1));
	}

	@Test
	void markingBackAsCodeDecodesInstructionsAgain() {
		MemoryCellGroup g = code(01000, 012701, 0200);
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01002, DataMarks.Format.BYTES);
		marks.mark(01000, 01002, null);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null, 100, marks);

		assertEquals(List.of("001000 mov     #000200,r1"), texts(l));
		assertTrue(marks.isEmpty());
	}

	@Test
	void thePcStartsALineInsideATable() {
		MemoryCellGroup g = code(01000, 1, 2, 3);
		DataMarks marks = new DataMarks();
		marks.mark(01000, 01004, DataMarks.Format.WORDS);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), v(01002), 100, marks);

		assertEquals(List.of("001000 .word   000001", "001002 .word   000002,000003"), texts(l));
		assertEquals(1, l.pcLine());
	}

	/** What the window uses to tell which word is under the mouse, and to highlight it. */
	@Test
	void theRawColumnSaysWhichWordIsWhere() {
		MemoryCellGroup g = code(01000, 012701, 0200, 0, 0);
		DataMarks marks = new DataMarks();
		marks.mark(01004, 01006, DataMarks.Format.BYTES);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01006), null, 100, marks);
		DisassemblyListing.Line mov = l.getLines().get(0);
		String shown = mov.toDisplayString();

		assertEquals(01000, mov.wordAtColumn(shown.indexOf("012701")));
		assertEquals(01002, mov.wordAtColumn(shown.indexOf("000200") + 5));
		assertEquals(-1, mov.wordAtColumn(shown.indexOf("012701") + 6), "the space between words");
		assertEquals(-1, mov.wordAtColumn(shown.indexOf("000200") + 7), "no third word");
		assertEquals(-1, mov.wordAtColumn(0), "the address is not a word of memory");
		assertArrayEquals(new int[]{shown.indexOf("000200"), shown.indexOf("000200") + 6}, mov.columnsOfWord(01002));
		assertNull(mov.columnsOfWord(01004));

		DisassemblyListing.Line data = l.getLines().get(1);
		String bytes = data.toDisplayString();
		int raw = bytes.indexOf(": ") + 2;
		assertEquals(01004, data.wordAtColumn(raw + 4), "the second byte is in the first word");
		assertEquals(01006, data.wordAtColumn(raw + 8));
		assertArrayEquals(new int[]{raw + 8, raw + 15}, data.columnsOfWord(01006), "both its bytes");
		assertEquals(01004, data.firstWord());
		assertEquals(01006, data.lastWord());
	}
}
