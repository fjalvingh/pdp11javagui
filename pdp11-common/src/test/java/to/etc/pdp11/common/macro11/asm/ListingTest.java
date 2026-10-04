package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing, column for column the layout of the C {@code macro11}.
 *
 * <p>The expected lines are what the C assembler prints for the same source, except where this
 * assembler knows more: a PC-relative word is its final value, where the C one prints the target
 * with an apostrophe for a linker to finish.</p>
 */
class ListingTest {
	@Test
	void theLayoutOfTheCAssembler() {
		AssemblyResult r = Asm.assemble(
			"\t.ASECT",
			"\t.=1000",
			"A:\t.WORD\t1,2,3,4,5",
			"\t.BYTE\t1,2,3,4,5",
			"B=5",
			"\t.BLKW\t3",
			"\tJSR\tPC,A",
			"\t.IIF NE 1, .WORD 1,2,3,4",
			"\t.END");
		List<String> expected = List.of(
			"       1                                \t.ASECT",
			"       2 001000                         \t.=1000",
			"       3 001000 000001  000002  000003  A:\t.WORD\t1,2,3,4,5",
			"         001006 000004  000005          ",
			"       4 001012    001     002     003  \t.BYTE\t1,2,3,4,5",
			"         001015    004     005          ",
			"t.mac:5: ***WARNING The symbol B is not used",
			"       5 000005                         B=5",
			"t.mac:6: ***ERROR .BLKW at an odd address; it is moved to the next even one. Is a .EVEN missing?",
			"       6 001020                         \t.BLKW\t3",
			"       7 001026 004767  177746          \tJSR\tPC,A",
			"       8 000001                         \t.IIF NE 1, .WORD 1,2,3,4",
			"         001032 000001  000002  000003  ",
			"         001040 000004                  ",
			"       9                                \t.END");
		assertEquals(expected, r.getListing());
	}

	@Test
	void everyListingLineKnowsItsSourceLine() {
		AssemblyResult r = Asm.assemble("\t.ASECT", "\t.WORD\t1,2,3,4", "\t.END");
		List<String> l = r.getListing();
		assertEquals(4, l.size());
		assertEquals(1, r.sourceLineOf(0));
		assertEquals(2, r.sourceLineOf(1));
		assertEquals(2, r.sourceLineOf(2));
		assertEquals(3, r.sourceLineOf(3));
	}

	@Test
	void everyWordKnowsItsListingLine() {
		AssemblyResult r = Asm.assemble("\t.ASECT", "\t.=1000", "\t.WORD\t1,2,3,4", "\t.END");
		List<AssemblyResult.Word> w = r.getWords();
		assertEquals(2, w.get(0).listingLine());
		assertEquals(2, w.get(2).listingLine());
		assertEquals(3, w.get(3).listingLine());
	}

	@Test
	void aDiagnosticKnowsItsListingLine() {
		AssemblyResult r = Asm.assemble("\t.ASECT", "\t.WORD\t9", "\t.END");
		Diagnostic d = r.getErrors().get(0);
		int line = r.listingLineOf(d);
		assertTrue(r.getListing().get(line).startsWith("t.mac:2: ***ERROR"), r.getListing().get(line));
		assertEquals(2, r.sourceLineOf(line));
	}

	@Test
	void diagnosticsAreInSourceOrder() {
		AssemblyResult r = Asm.assemble("\t.ASECT", "\t.WORD\tUNDEF", "\t.WORD\t9", "\t.END");
		assertEquals(2, r.getDiagnostics().get(0).location().line());
		assertEquals(3, r.getDiagnostics().get(1).location().line());
	}

	@Test
	void aFormFeedStartsAPageNotALine() {
		AssemblyResult r = Asm.assemble("\t.ASECT\f\t.WORD\t1", "\t.END");
		assertEquals("       1                                \t.ASECT", r.getListing().get(0));
		assertTrue(r.getListing().get(1).startsWith("       1 000000 000001"), r.getListing().get(1));
		assertTrue(r.getListing().get(2).startsWith("       2 "), r.getListing().get(2));
	}
}
