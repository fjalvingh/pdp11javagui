package to.etc.pdp11.core.macro11;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.macro11.Macro11Listing.Problem;
import to.etc.pdp11.core.macro11.Macro11Listing.ProblemKind;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MACRO-11 listing parser, against listings a real {@code macro11} produced.
 *
 * <p>Every fixture here was pasted out of the assembler's own output rather than written by
 * hand, because the whole difficulty of this parser is the column layout and a hand-written
 * example would agree with whatever the parser happens to do. {@code Macro11IT} assembles the
 * same sources for real when the tool is installed, and asserts the same words.</p>
 */
class Macro11ListingParserTest {
	private MemoryCellGroup group() {
		return new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
	}

	private static int wordAt(MemoryCellGroup group, long addr) {
		MemoryCell mc = group.findByAddress(Address.of(MemoryAddressType.VIRTUAL, addr));
		assertNotNull(mc, "no cell at 0" + Long.toOctalString(addr));
		return mc.getEditValue().word();
	}

	/**
	 * The ordinary case: line number, address, one or two words, then source text.
	 *
	 * <p>{@code sum.mac} out of the project's own corpus.</p>
	 */
	@Test
	void wordsAreReadOutOfTheCodeColumn() {
		List<String> listing = List.of(
			"      13 177570                         io\t=\t177570\t; combined switch and display register",
			"      15                                \t.asect",
			"      16 001000                         \t.=1000\t\t; program loads at 1000",
			"      18                                start:",
			"      19 001000 012706  000400          \tmov\t#400,sp ; no stack is used",
			"      22 001004 013703  177570          \tmov\t@#io,r3 ; r3 = n",
			"      23 001010 005000                  \tclr\tr0\t; r0 = 0");
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(listing, g);

		assertTrue(r.isOk(), "nothing to complain about");
		assertEquals(5, g.size(), "two two-word instructions and one one-word one");
		assertEquals(0012706, wordAt(g, 001000));
		assertEquals(0000400, wordAt(g, 001002));
		assertEquals(0013703, wordAt(g, 001004));
		assertEquals(0177570, wordAt(g, 001006));
		assertEquals(0005000, wordAt(g, 001010));
		assertEquals("001000", r.getStartAddress().toOctal());
	}

	/**
	 * A symbol definition has an address column but no code, and must not become a cell.
	 *
	 * <p>{@code io = 177570} lists {@code 177570} in the <i>address</i> column, which is the
	 * value rather than a location. Reading the code column - which holds the source text and
	 * nothing else - is what keeps it out.
	 */
	@Test
	void aSymbolDefinitionIsNotCode() {
		MemoryCellGroup g = group();
		Macro11ListingParser.parse(List.of(
			"      13 177570                         io\t=\t177570\t; switch register",
			"      39 165564                         diags        =165564                         ; entry"), g);
		assertTrue(g.isEmpty(), "a value in the address column is not a word of code");
	}

	/**
	 * Three digits is a byte; two bytes make one word, the odd address being the high half.
	 *
	 * <p>A five-character {@code .ascii} spills onto a second listing line with no line number
	 * of its own, and the last word is half filled - which is exactly the case the Pascal's own
	 * comment picks out ({@code FormMacro11ListingU.pas:437-441}).</p>
	 */
	@Test
	void bytesArePackedIntoWordsHighByteAtTheOddAddress() {
		List<String> listing = List.of(
			"       3 173000    104     104          start:\t.ascii\t\"DD\"",
			"       5 173004    110     145     154  tst0:\t.ascii\t\"Hello\"",
			"         173007    154     157          ",
			"       6 173011    054     040          tst1:\t.ascii\t\", \"");
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(listing, g);

		assertEquals(('D' << 8) | 'D', wordAt(g, 0173000));
		assertEquals(('e' << 8) | 'H', wordAt(g, 0173004));
		assertEquals(('l' << 8) | 'l', wordAt(g, 0173006));
		//-- "Hello" is five bytes and ends on an even address, so the word at 173010 is finished
		//-- by the *next* source line's comma. Two strings, one word, and no second cell at
		//-- 173011 - which is the reason for the odd-address lookup.
		assertEquals((',' << 8) | 'o', wordAt(g, 0173010));
		assertEquals(' ', wordAt(g, 0173012));
		assertEquals(5, g.size(), "no duplicate cell for the odd halves");

		//-- The continuation line belongs to the source line it continues, so highlighting the
		//-- source line highlights both.
		assertEquals(5, r.sourceLineOfListingLine(1));
		assertEquals(5, r.sourceLineOfListingLine(2));
		assertEquals(List.of(1, 2), r.listingLinesForSourceLine(5));
	}

	/**
	 * A diagnostic is the line that is not indented, and it names a source line.
	 *
	 * <p>Note the assembler still exits 0, so this line is the only report there is.</p>
	 */
	@Test
	void anErrorLineIsFoundAndPointsAtItsSourceLine() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"       6 173011    054     040          tst1:\t.ascii\t\", \"",
			"t1.mac:7: ***ERROR Instruction on odd address",
			"       7 173014 012700  000001          \tmov\t#1,r0"), g);

		assertFalse(r.isOk());
		Problem p = r.getFirstProblem();
		assertEquals(ProblemKind.ERROR, p.kind());
		assertEquals("t1.mac", p.file());
		assertEquals(7, p.sourceLine());
		assertEquals("***ERROR Instruction on odd address", p.message());
		assertEquals(1, p.listingLine());
		//-- And the code around it is still parsed: an error in one line does not lose the rest.
		assertEquals(0012700, wordAt(g, 0173014));
	}

	/**
	 * A Windows drive letter is a colon inside the file name, and the split has to skip it.
	 *
	 * <p>Listings from the original are still around and this is what they look like.</p>
	 */
	@Test
	void aDriveLetterIsNotTheSeparator() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"D:\\pdp11\\pdp 11-44\\progs\\memoryaddress.mac:11: ***ERROR Illegal addressing mode"), g);
		Problem p = r.getFirstProblem();
		assertEquals("D:\\pdp11\\pdp 11-44\\progs\\memoryaddress.mac", p.file());
		assertEquals(11, p.sourceLine());
		assertEquals("***ERROR Illegal addressing mode", p.message());
	}

	/**
	 * {@code G} means the assembler emitted a hole where an address should be.
	 *
	 * <p>It is not an error to MACRO-11 - the exit code and the listing are both clean - but the
	 * program will not run, and the usual cause is a misspelt symbol.</p>
	 */
	@Test
	void anUnresolvedGlobalIsReportedThoughTheAssemblerDidNot() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"       8 173020 004767  000000G         \tjsr\tpc,undefsym"), g);

		assertFalse(r.isOk());
		Problem p = r.getFirstProblem();
		assertEquals(ProblemKind.UNRESOLVED_GLOBAL, p.kind());
		assertEquals(8, p.sourceLine());
		//-- The word itself is still taken: it is what the assembler produced, hole and all.
		assertEquals(0004767, wordAt(g, 0173020));
		assertEquals(0, wordAt(g, 0173022));
	}

	/** One unresolved symbol used twice in a line is one complaint, not two. */
	@Test
	void anUnresolvedGlobalIsReportedOncePerSourceLine() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"       8 173020 004767  000000G  000000G       \tjsr\tpc,undefsym"), g);
		assertEquals(1, r.getProblems().size());
	}

	/** A relocatable value is marked and is otherwise ordinary. */
	@Test
	void aRelocatableValueIsNotAProblem() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"       3 000004 000000'                 \t.word\tstart"), g);
		assertTrue(r.isOk());
		assertEquals(0, wordAt(g, 4));
	}

	/**
	 * An unknown suffix loses one word, not the whole program.
	 *
	 * <p>The Pascal raises here ({@code :516-517}), which abandons the parse and throws away
	 * every cell already built - so a single unrecognised character costs the entire listing.</p>
	 */
	@Test
	void anUnknownSuffixIsRecordedAndTheRestSurvives() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"       1 001000 012700                  \tmov\t#1,r0",
			"       2 001002 000001Z                 \t.word\t1",
			"       3 001004 000000                  \thalt"), g);

		assertEquals(ProblemKind.UNKNOWN_SUFFIX, r.getFirstProblem().kind());
		assertEquals(3, g.size(), "the code before and after the oddity is still there");
		assertEquals(0000000, wordAt(g, 001004));
	}

	/** A label ends the code column, including one of the {@code 2$:} kind that starts with a digit. */
	@Test
	void aLocalLabelEndsTheCodeColumn() {
		MemoryCellGroup g = group();
		Macro11ListingParser.parse(List.of(
			"      48 000740 000200                  2$:\tsomething",
			"      49 000742                         3$:"), g);
		assertEquals(1, g.size());
		assertEquals(0000200, wordAt(g, 0740));
	}

	/**
	 * The hex listing is not an octal listing, and must produce nothing rather than nonsense.
	 *
	 * <p>{@code macro11 -e listhex} writes {@code 0200h} in the address column. Left to itself a
	 * digits-only scan would read {@code 15C6h} as the byte {@code 15}.</p>
	 */
	@Test
	void theHexListingIsIgnoredRatherThanMisread() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"      19  0200h  15C6h   0100h          \tmov\t#400,sp",
			"      23  0208h  0A00h                  \tclr\tr0"), g);
		assertTrue(g.isEmpty(), "no octal address, so no code");
		assertTrue(r.isOk());
	}

	/** Every cell knows which listing line made it, and the PC lookup is that key backwards. */
	@Test
	void aCellKnowsItsListingLineAndTheAddressLookupUsesIt() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(
			"       1 001000 012706  000400          \tmov\t#400,sp",
			"       2 001004 005000                  \tclr\tr0"), g);

		assertEquals(0, r.listingLineOfAddress(Address.of(MemoryAddressType.VIRTUAL, 001000)));
		assertEquals(0, r.listingLineOfAddress(Address.of(MemoryAddressType.VIRTUAL, 001002)));
		assertEquals(1, r.listingLineOfAddress(Address.of(MemoryAddressType.VIRTUAL, 001004)));
		assertEquals(-1, r.listingLineOfAddress(Address.of(MemoryAddressType.VIRTUAL, 002000)));
		assertEquals(-1, r.listingLineOfAddress(null));
	}

	/**
	 * A word out of a listing is an edit value with the machine value unknown.
	 *
	 * <p>The file says what memory should hold; the machine has not been told. That is what makes
	 * every word show as changed until it has been deposited, and it is the rule the Memory
	 * Loader follows too.</p>
	 */
	@Test
	void everyAssembledWordShowsAsChangedUntilItIsDeposited() {
		MemoryCellGroup g = group();
		Macro11ListingParser.parse(List.of(
			"       1 001000 012706                  \tmov\t#400,sp"), g);
		MemoryCell mc = g.cell(0);
		assertTrue(mc.isEdited());
		assertFalse(mc.getPdpValue().isKnown());
	}

	/** Parsing twice into the same group replaces it rather than appending to it. */
	@Test
	void reparsingClearsWhatWasThereBefore() {
		MemoryCellGroup g = group();
		Macro11ListingParser.parse(List.of("       1 001000 012706                  \tmov"), g);
		Macro11ListingParser.parse(List.of("       1 002000 005000                  \tclr"), g);
		assertEquals(1, g.size());
		assertNull(g.findByAddress(Address.of(MemoryAddressType.VIRTUAL, 001000)));
	}

	/** An empty listing is a listing, and says so rather than throwing. */
	@Test
	void anEmptyListingIsHarmless() {
		MemoryCellGroup g = group();
		Macro11Listing r = Macro11ListingParser.parse(List.of(), g);
		assertTrue(r.isOk());
		assertEquals(0, r.getWordCount());
		assertNull(r.getStartAddress());
	}
}
