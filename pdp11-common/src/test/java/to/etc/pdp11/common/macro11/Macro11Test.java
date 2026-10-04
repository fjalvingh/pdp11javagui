package to.etc.pdp11.common.macro11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.macro11.Macro11Listing.Problem;
import to.etc.pdp11.common.macro11.Macro11Listing.ProblemKind;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assembler as the application uses it: source in, memory cells and problems out.
 *
 * <p>This replaces the integration test that ran the external {@code macro11}, which skipped
 * itself wherever the tool was not installed - CI included. Nothing here can skip.</p>
 */
class Macro11Test {
	private static final String SUM = """
		\t.asect
		\t.=1000
		start:\tmov\t#400,sp
		\tclr\tr0
		\thalt
		\t.end\tstart
		""";

	private static MemoryCellGroup group() {
		return new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
	}

	private static int wordAt(MemoryCellGroup group, long addr) {
		MemoryCell mc = group.findByAddress(Address.of(MemoryAddressType.VIRTUAL, addr));
		assertNotNull(mc, "no cell at 0" + Long.toOctalString(addr));
		return mc.getEditValue().word();
	}

	@Test
	void assemblingASourceProducesTheWordsItSays(@TempDir Path dir) {
		Path src = dir.resolve("sum.mac");
		MemoryCellGroup g = group();
		Macro11Listing listing = Macro11.assemble(src, SUM, MemoryAddressType.VIRTUAL).parsed().installInto(g);

		assertTrue(listing.isOk(), () -> listing.getProblems().toString());
		assertEquals(012706, wordAt(g, 01000));
		assertEquals(0400, wordAt(g, 01002));
		assertEquals(05000, wordAt(g, 01004));
		assertEquals(0, wordAt(g, 01006));
		assertEquals("001000", listing.getStartAddress().toOctal());
	}

	@Test
	void eachWordKnowsTheListingLineItIsOn(@TempDir Path dir) {
		MemoryCellGroup g = group();
		Macro11Listing listing = Macro11.assemble(dir.resolve("sum.mac"), SUM, MemoryAddressType.VIRTUAL).parsed().installInto(g);
		int line = listing.listingLineOfAddress(Address.of(MemoryAddressType.VIRTUAL, 01004));
		assertTrue(listing.getLines().get(line).contains("clr"), listing.getLines().get(line));
		assertEquals(4, listing.sourceLineOfListingLine(line));
	}

	@Test
	void theStartAddressIsTheOneEndGives(@TempDir Path dir) {
		String text = "\t.asect\n\t.=1000\ndata:\t.word\t0\nstart:\thalt\n\t.end\tstart\n";
		Macro11Listing listing = Macro11.assemble(dir.resolve("s.mac"), text, MemoryAddressType.VIRTUAL).parsed().installInto(group());
		assertEquals("001002", listing.getStartAddress().toOctal());
	}

	@Test
	void anErrorIsAProblemOnItsSourceLine(@TempDir Path dir) {
		String text = "\t.asect\n\t.=1000\n\tmov\t#9,r0\n\t.end\n";
		Macro11Listing listing = Macro11.assemble(dir.resolve("e.mac"), text, MemoryAddressType.VIRTUAL).parsed().installInto(group());
		assertFalse(listing.isOk());
		Problem p = listing.getFirstProblem();
		assertEquals(ProblemKind.ERROR, p.kind());
		assertEquals(3, p.sourceLine());
		assertTrue(listing.getLines().get(p.listingLine()).contains("***ERROR"), listing.getLines().get(p.listingLine()));
	}

	@Test
	void aWarningDoesNotStopTheProgram(@TempDir Path dir) {
		String text = "\t.asect\n\t.=1000\nunused:\thalt\n\t.end\n";
		Macro11Listing listing = Macro11.assemble(dir.resolve("w.mac"), text, MemoryAddressType.VIRTUAL).parsed().installInto(group());
		assertTrue(listing.isOk());
		assertEquals(1, listing.getWarningCount());
		assertEquals(ProblemKind.WARNING, listing.getFirstProblem().kind());
	}

	@Test
	void includeAndMcallLookBesideTheSource(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("defs.mac"), "VAL=1234\n", StandardCharsets.ISO_8859_1);
		Files.writeString(dir.resolve("PUT.MAC"), ".MACRO PUT X\n\t.WORD X\n.ENDM\n", StandardCharsets.ISO_8859_1);
		String text = "\t.asect\n\t.=1000\n\t.include\t/defs.mac/\n\t.mcall\tPUT\n\tPUT\tVAL\n\t.end\n";
		MemoryCellGroup g = group();
		Macro11Listing listing = Macro11.assemble(dir.resolve("i.mac"), text, MemoryAddressType.VIRTUAL).parsed().installInto(g);
		assertTrue(listing.isOk(), () -> listing.getProblems().toString());
		assertEquals(01234, wordAt(g, 01000));
	}

	@Test
	void aSavedListingReadsBackAsTheSameProgram(@TempDir Path dir) throws Exception {
		//-- Writer and reader in one test: the listing the assembler writes is what Open listing
		//-- reads, and the two must agree on every word.
		Macro11.Result r = Macro11.assemble(dir.resolve("sum.mac"), SUM, MemoryAddressType.VIRTUAL);
		MemoryCellGroup assembled = group();
		r.parsed().installInto(assembled);

		Path lst = dir.resolve("sum.lst");
		Files.write(lst, r.listing(), StandardCharsets.ISO_8859_1);
		MemoryCellGroup read = group();
		Macro11ListingParser.parse(lst, read);

		assertEquals(assembled.size(), read.size());
		for(MemoryCell mc : assembled.getCells())
			assertEquals(mc.getEditValue().word(), wordAt(read, mc.getAddr().val()), "at " + mc.getAddr().toOctal());
	}
}
