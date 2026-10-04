package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.macro11.Macro11ListingParser;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real programs, assembled here and by the C {@code macro11}, must come out the same.
 *
 * <p>The sources are the project's own: the programs of the Pascal PDP11GUI ({@code mac/}),
 * including the CXCPAG processor test, and the examples this application ships. Beside each is
 * {@code name.c.lst}, the listing the C assembler (with the fixes from its review) made of it.
 * The memory image is read out of that listing by {@link Macro11ListingParser}, which is what the
 * application did with it before the assembler was part of it, and compared word for word.</p>
 *
 * <p>The listings are committed rather than made on the fly, because CI has no C assembler;
 * this is the same arrangement as the disassembler's SimH corpus. Before they were committed,
 * the same comparison ran over the fourteen thousand MACRO-11 sources of the trailing-edge
 * archive: every difference left was either a bug in the C assembler - its floating point, and
 * assembling past {@code .END} - or the placement of relocatable sections, which differs on
 * purpose. None of those is in this corpus; the programs here are absolute.</p>
 */
class CorpusTest {
	@ParameterizedTest
	@ValueSource(strings = {
		"AC-E664G-MC_CXCPAG_Processor_Test_Sep78",
		"consoleserialport",
		"linetimeclocktest",
		"memoryaddress",
		"memsizing",
		"memtester",
		"sum",
		"tictac",
		"trapcatcher"
	})
	void theProgramIsTheOneTheCAssemblerMakes(String name) throws IOException {
		String source = resource(name + ".mac");
		List<String> cListing = Arrays.asList(resource(name + ".c.lst").split("\n"));

		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, name);
		Macro11ListingParser.parse(cListing, g);
		Map<Integer, Integer> expected = new TreeMap<>();
		for(MemoryCell mc : g.getCells())
			expected.putIfAbsent((int) mc.getAddr().val(), mc.getEditValue().wordOr(0));
		assertFalse(expected.isEmpty(), name + ": the C listing has no code");

		AssemblyResult r = new Macro11Assembler().assemble(name + ".mac", source);
		Map<Integer, Integer> actual = new TreeMap<>();
		for(AssemblyResult.Word w : r.getWords())
			actual.putIfAbsent(w.address(), w.value());

		assertEquals(octal(expected), octal(actual), name);
	}

	private static String resource(String name) throws IOException {
		try(InputStream in = CorpusTest.class.getResourceAsStream("corpus/" + name)) {
			assertNotNull(in, "No corpus file " + name);
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}

	/** One line per word, so that a difference shows where it is. */
	private static String octal(Map<Integer, Integer> m) {
		StringBuilder sb = new StringBuilder();
		m.forEach((a, v) -> sb.append(String.format("%06o: %06o%n", a, v)));
		return sb.toString();
	}
}
