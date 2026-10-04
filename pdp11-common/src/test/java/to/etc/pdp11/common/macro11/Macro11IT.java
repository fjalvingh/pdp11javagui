package to.etc.pdp11.common.macro11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.macro11.Macro11Listing.ProblemKind;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.util.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real {@code macro11}, end to end: source in, memory cells out.
 *
 * <p><b>Skips itself when the assembler is not installed</b>, which includes CI. What it buys
 * over {@link Macro11ListingParserTest} is that the fixtures there are still what the tool
 * prints - a listing format that drifts would otherwise go unnoticed until somebody assembled
 * something and got no code.</p>
 */
class Macro11IT {
	/** Two instructions and a halt, with the encodings written out beside them. */
	private static final String SUM = """
		\t.asect
		\t.=1000
		start:\tmov\t#400,sp
		\tclr\tr0
		\thalt
		\t.end
		""";

	private static Path source(Path dir, String name, String text) throws Exception {
		Path f = dir.resolve(name);
		Files.writeString(f, text, StandardCharsets.US_ASCII);
		return f;
	}

	private static int wordAt(MemoryCellGroup group, long addr) {
		MemoryCell mc = group.findByAddress(Address.of(MemoryAddressType.VIRTUAL, addr));
		assertNotNull(mc, "no cell at 0" + Long.toOctalString(addr));
		return mc.getEditValue().word();
	}

	@Test
	void assemblingASourceProducesTheWordsItSays(@TempDir Path dir) throws Exception {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		Path src = source(dir, "sum.mac", SUM);

		Macro11.Result result = Macro11.assemble(src, Logger.NULL);
		assertEquals(dir.resolve("sum.lst"), result.listing());
		assertTrue(Files.isRegularFile(result.listing()));
		//-- The second run, in hex, for reading logic-analyser captures against.
		assertNotNull(result.hexListing(), "the hex listing is written too");
		assertTrue(Files.isRegularFile(result.hexListing()));

		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
		Macro11Listing listing = Macro11ListingParser.parse(result.listing(), g);

		assertTrue(listing.isOk(), () -> "unexpected: " + listing.getProblems());
		assertEquals(4, listing.getWordCount());
		assertEquals(0012706, wordAt(g, 01000));                // mov #400,sp
		assertEquals(0000400, wordAt(g, 01002));
		assertEquals(0005000, wordAt(g, 01004));                // clr r0
		assertEquals(0000000, wordAt(g, 01006));                // halt
		assertEquals("001000", listing.getStartAddress().toOctal());
	}

	/** References forwards and backwards, by call, by jump and by data reference. */
	private static final String CALLS = """
		start:\tjsr\tpc,sub
		\tmov\tdata,r0
		\tjmp\tstart
		sub:\tjsr\tpc,start
		\trts\tpc
		data:\t.word\t123
		\t.end\tstart
		""";

	/**
	 * A program in an {@code .ASECT} loads as the same program in a relocatable section.
	 *
	 * <p>In a relocatable section {@code macro11} computes every PC-relative offset itself and the
	 * listing holds the real word. In an absolute one it lists the target with a {@code '} and
	 * leaves the subtraction to a linker, which this application does not have - so the parser
	 * does it, and this is the check that it does the same arithmetic the assembler would.</p>
	 */
	@Test
	void anAbsoluteSectionLoadsTheSameWordsAsARelocatableOne(@TempDir Path dir) throws Exception {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		Path abs = source(dir, "abs.mac", "\t.asect\n\t.=1000\n" + CALLS);
		Path rel = source(dir, "rel.mac", "\t.=.+1000\n" + CALLS);

		MemoryCellGroup a = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "abs");
		MemoryCellGroup r = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "rel");
		Macro11Listing la = Macro11ListingParser.parse(Macro11.assemble(abs, Logger.NULL).listing(), a);
		Macro11Listing lr = Macro11ListingParser.parse(Macro11.assemble(rel, Logger.NULL).listing(), r);
		assertTrue(la.isOk(), () -> "unexpected: " + la.getProblems());
		assertTrue(lr.isOk(), () -> "unexpected: " + lr.getProblems());

		//-- The listing really does mark them, or this test proves nothing.
		assertTrue(Files.readString(Macro11.assemble(abs, Logger.NULL).listing()).contains("'"),
			"the absolute listing should have PC-relative words marked with '");

		assertEquals(la.getWordCount(), lr.getWordCount());
		for(MemoryCell mc : r.getCells()) {
			long addr = mc.getAddr().val();
			assertEquals(mc.getEditValue().word(), wordAt(a, addr), "word at 0" + Long.toOctalString(addr));
		}
		//-- And one of them by hand: JSR PC,SUB at 1000, SUB at 1014, so 1014 - 1004.
		assertEquals(0000010, wordAt(a, 01002));
	}

	/**
	 * A source with an error still exits 0 - which is the whole reason the listing is parsed
	 * for errors rather than the status being checked.
	 */
	@Test
	void anErrorIsFoundInTheListingBecauseTheExitCodeSaysNothing(@TempDir Path dir) throws Exception {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		Path src = source(dir, "bad.mac", """
			\t.asect
			\t.=1000
			\t.byte\t1
			\tmov\t#1,r0
			\t.end
			""");

		Macro11.Result result = Macro11.assemble(src, Logger.NULL);
		assertEquals(0, result.runs().get(0).exitCode(), "macro11 reports success for a broken source");

		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
		Macro11Listing listing = Macro11ListingParser.parse(result.listing(), g);
		assertFalse(listing.isOk(), "the listing is where the error is");
		assertEquals(ProblemKind.ERROR, listing.getFirstProblem().kind());
		assertEquals(4, listing.getFirstProblem().sourceLine(), "the mov on an odd address");
	}

	/** An unresolved symbol is not an error to MACRO-11 at all, and is one here. */
	@Test
	void anUnresolvedGlobalIsCaughtThoughTheAssemblerIsHappy(@TempDir Path dir) throws Exception {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		Path src = source(dir, "undef.mac", """
			\t.asect
			\t.=1000
			\tjsr\tpc,undefsym
			\t.end
			""");

		Macro11.Result result = Macro11.assemble(src, Logger.NULL);
		assertTrue(result.runs().get(0).output().isBlank(), "the assembler says nothing at all");

		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
		Macro11Listing listing = Macro11ListingParser.parse(result.listing(), g);
		assertEquals(ProblemKind.UNRESOLVED_GLOBAL, listing.getFirstProblem().kind());
	}

	/** An old listing must not be read as though it belonged to the run that just failed. */
	@Test
	void aStaleListingIsRemovedBeforeTheRun(@TempDir Path dir) throws Exception {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		Path src = source(dir, "sum.mac", SUM);
		Path listing = Macro11.listingFileFor(src);
		Files.writeString(listing, "       1 001000 177777                  yesterday\n");

		Macro11.assemble(src, Logger.NULL);
		assertFalse(Files.readString(listing).contains("yesterday"));
	}

	@Test
	void aMissingSourceIsASentenceRatherThanACrash(@TempDir Path dir) {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		Macro11Exception x = assertThrows(Macro11Exception.class,
			() -> Macro11.assemble(dir.resolve("nothing.mac"), Logger.NULL));
		assertTrue(x.getMessage().contains("no source file"), x.getMessage());
	}
}
