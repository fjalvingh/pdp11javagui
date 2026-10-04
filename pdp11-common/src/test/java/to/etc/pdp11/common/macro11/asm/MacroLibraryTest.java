package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static to.etc.pdp11.common.macro11.asm.Asm.assertError;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWords;

/**
 * Macro libraries: reading both formats, and using them from the source.
 *
 * <p>The reader was also run over the 84 {@code .MLB} files in the trailing-edge archive: the 80
 * RSX and 2 RT-11 libraries among them read with every module holding its {@code .MACRO}, the
 * 310 RT-11 modules came out the same as the C {@code macro11} extracts them, and the other two
 * files are damaged and said to be. Those files are not committed; these tests write their own,
 * in both formats.</p>
 */
class MacroLibraryTest {
	private static final String SAVE = ".MACRO\tSAVE\tR\n\tMOV\tR,-(SP)\n.ENDM\n";

	private static final String REST = ".MACRO\tREST\tR\n\tMOV\t(SP)+,R\n.ENDM\n";

	private static LibraryWriter library() {
		return new LibraryWriter().add("SAVE", SAVE).add("REST", REST);
	}

	@Test
	void anRt11LibraryReadsBackAsWritten() throws IOException {
		MacroLibrary l = MacroLibrary.read("T.MLB", library().rt11());
		assertEquals(MacroLibrary.Format.RT11, l.getFormat());
		assertEquals(List.of("SAVE", "REST"), l.getMacroNames());
		assertEquals(SAVE, l.getText("SAVE").orElseThrow());
		assertEquals(REST, l.getText("REST").orElseThrow());
	}

	@Test
	void anRsxLibraryReadsBackAsWritten() throws IOException {
		MacroLibrary l = MacroLibrary.read("T.MLB", library().rsx());
		assertEquals(MacroLibrary.Format.RSX, l.getFormat());
		assertEquals(List.of("SAVE", "REST"), l.getMacroNames());
		assertEquals(SAVE, l.getText("SAVE").orElseThrow());
		assertEquals(REST, l.getText("REST").orElseThrow());
	}

	@Test
	void aLibraryCopiedAsTextIsReadAsItWas() throws IOException {
		MacroLibrary l = MacroLibrary.read("T.MLB", LibraryWriter.copiedAsText(library().rsx()));
		assertEquals(SAVE, l.getText("SAVE").orElseThrow());
	}

	@Test
	void aFileThatIsNotALibraryIsSaidToBe() {
		IOException x = assertThrows(IOException.class, () -> MacroLibrary.read("X.MLB", new byte[1024]));
		assertTrue(x.getMessage().contains("not a macro library"), x.getMessage());
	}

	@Test
	void aTruncatedLibraryNamesTheDamagedModule() {
		byte[] whole = library().rsx();
		byte[] cut = java.util.Arrays.copyOf(whole, 1024 + 20);
		IOException x = assertThrows(IOException.class, () -> MacroLibrary.read("T.MLB", cut));
		assertTrue(x.getMessage().contains("SAVE is damaged"), x.getMessage());
	}

	// -------------------------------------------------------------------------------------
	// Using one
	// -------------------------------------------------------------------------------------

	private static AssemblyResult assemble(List<MacroLibrary> libraries, Map<String, byte[]> files, String... lines) {
		SourceResolver r = Asm.resolver(Map.of(), files);
		String text = String.join("\n", lines) + "\n";
		return new Macro11Assembler(AssemblerOptions.DEFAULT, r, libraries).assemble("t.mac", text);
	}

	@Test
	void mcallFindsAMacroInAGivenLibrary() throws IOException {
		MacroLibrary l = MacroLibrary.read("T.MLB", library().rt11());
		assertWords(assemble(List.of(l), Map.of(), "\t.ASECT", "\t.=1000", "\t.MCALL\tSAVE,REST", "\tSAVE\tR1", "\tREST\tR1", "\t.END"),
			"10146", "12601");
	}

	@Test
	void libraryNamesALibraryForTheRestOfTheSource() {
		AssemblyResult r = assemble(List.of(), Map.of("MY.MLB", library().rsx()),
			"\t.ASECT", "\t.=1000", "\t.LIBRARY\t/MY.MLB/", "\t.MCALL\tSAVE", "\tSAVE\tR2", "\t.END");
		assertWords(r, "10246");
	}

	@Test
	void theLastLibraryNamedIsSearchedFirst() throws IOException {
		MacroLibrary system = MacroLibrary.read("SYS.MLB", new LibraryWriter().add("PUT", ".MACRO PUT\n\t.WORD 1\n.ENDM\n").rt11());
		byte[] mine = new LibraryWriter().add("PUT", ".MACRO PUT\n\t.WORD 2\n.ENDM\n").rsx();
		AssemblyResult r = assemble(List.of(system), Map.of("MINE.MLB", mine),
			"\t.ASECT", "\t.=1000", "\t.LIBRARY\t/MINE.MLB/", "\t.MCALL\tPUT", "\tPUT", "\t.END");
		assertWords(r, "2");
	}

	@Test
	void mclLooksInTheLibrariesToo() throws IOException {
		MacroLibrary l = MacroLibrary.read("T.MLB", library().rt11());
		assertWords(assemble(List.of(l), Map.of(), "\t.ASECT", "\t.=1000", "\t.ENABL\tMCL", "\tSAVE\tR0", "\t.END"), "10046");
	}

	@Test
	void anErrorInALibraryMacroSaysWhichLibrary() throws IOException {
		MacroLibrary l = MacroLibrary.read("T.MLB", new LibraryWriter().add("BAD", ".MACRO BAD\n\t.WORD 9\n.ENDM\n").rt11());
		AssemblyResult r = assemble(List.of(l), Map.of(), "\t.ASECT", "\t.MCALL\tBAD", "\tBAD", "\t.END");
		Diagnostic d = assertError(r, 3, "not a valid octal number");
		assertTrue(d.describe().contains("BAD"), d.describe());
	}

	@Test
	void aMissingLibraryIsAnError() {
		assertError(assemble(List.of(), Map.of(), "\t.LIBRARY\t/NONE.MLB/", "\t.END"), 1, "no macro library NONE.MLB");
	}

	@Test
	void aDamagedLibraryIsAnError() {
		assertError(assemble(List.of(), Map.of("BAD.MLB", new byte[600]), "\t.LIBRARY\t/BAD.MLB/", "\t.END"), 1, "not a macro library");
	}
}
