package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static to.etc.pdp11.common.macro11.asm.Asm.assertError;
import static to.etc.pdp11.common.macro11.asm.Asm.assertNoErrors;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWords;
import static to.etc.pdp11.common.macro11.asm.Asm.at;
import static to.etc.pdp11.common.macro11.asm.Asm.at1000;

/**
 * The data, space, section and housekeeping directives.
 */
class DirectiveTest {
	@Test
	void wordsAndBytes() {
		assertWords(at1000("\t.WORD\t1,2", "\t.WORD", "\t.BYTE\t1,-1", "\t.WORD\t,5"), "1", "2", "0", "177401", "0", "5");
	}

	@Test
	void valuesMaySeparateByBlanks() {
		assertWords(at1000("\t.WORD\t1 2"), "1", "2");
	}

	@Test
	void asciiAsciz() {
		assertWords(at1000("\t.ASCIZ\t/AB/", "\t.EVEN", "\t.ASCII\t<15><12>/x/<0>"), "41101", "0", "5015", "170");
	}

	@Test
	void asciiTermsCanBeAnyTerm() {
		assertWords(at1000("\t.ASCII\t^O15/y/"), "74415");
	}

	@Test
	void rad50PacksThreeCharactersInAWord() {
		assertWords(at1000("\t.RAD50\t/ABCDEF/", "\t.RAD50\t/A/<3>"), "3223", "14716", "3270");
	}

	@Test
	void spaceIsReservedNotFilled() {
		AssemblyResult r = at1000("\t.BLKW\t2", "\t.BLKB", "\t.EVEN", "\t.WORD\t7");
		assertNoErrors(r);
		assertEquals(7, at(r, 01006));
	}

	@Test
	void evenAndOddPad() {
		AssemblyResult r = at1000("\t.BYTE\t1", "\t.EVEN", "\t.ODD", "\t.BYTE\t2");
		assertNoErrors(r);
		assertEquals(01, at(r, 01000));
		assertEquals(02 << 8, at(r, 01002));
	}

	@Test
	void floatingPoint() {
		//-- 1.0 is 040200 0; -2.5 is 140440 0; 920. is 042546 0; .1 rounds up in the last bit.
		assertWords(at1000("\t.FLT2\t1.0,-2.5,920.", "\t.FLT4\t0.1"),
			"40200", "0", "140440", "0", "42546", "0", "37314", "146314", "146314", "146315");
	}

	@Test
	void packedDecimal() {
		//-- -123: 12 3D
		assertWords(at1000("\t.PACKED\t-123,N", "\t.EVEN", "\t.WORD\tN"), "36422", "3");
	}

	@Test
	void limitGivesTheProgramsBounds() {
		AssemblyResult r = at1000("\t.LIMIT", "\t.WORD\t0");
		assertWords(r, "1000", "1006", "0");
	}

	@Test
	void theEndGivesTheStartAddress() {
		AssemblyResult r = Asm.assemble("\t.ASECT", "\t.=1000", "S:\tHALT", "\t.END\tS");
		assertNoErrors(r);
		assertEquals(01000, r.getTransferAddress().orElseThrow());
	}

	@Test
	void relocatableSectionsAreLaidOutOneAfterTheOther() {
		AssemblyResult r = new Macro11Assembler(AssemblerOptions.DEFAULT.withRelocationBase(02000), SourceResolver.NONE)
			.assemble("t.mac", String.join("\n",
				"\t.PSECT\tA",
				"X:\t.WORD\t1",
				"\t.PSECT\tB",
				"Y:\t.WORD\tX",
				"\t.PSECT\tA",
				"\t.WORD\tY",
				"\t.END", ""));
		assertNoErrors(r);
		Map<Integer, Integer> m = Asm.memory(r);
		assertEquals(Map.of(02000, 1, 02002, 02004, 02004, 02000), m);
	}

	@Test
	void saveAndRestoreTheSection() {
		AssemblyResult r = Asm.assemble("\t.ASECT", "\t.=1000", "\t.SAVE", "\t.PSECT\tP", "\t.WORD\t1", "\t.RESTORE",
			"\t.WORD\t2", "\t.END");
		assertNoErrors(r);
		assertEquals(2, at(r, 01000));
	}

	@Test
	void directiveNamesCountSixCharacters() {
		assertWords(at1000("\t.ENABLE\tAMA", "\tCLR\tX", "X:\t.WORD\t0"), "5037", "1004", "0");
	}

	@Test
	void includeReadsAnotherSource() {
		AssemblyResult r = Asm.assemble(AssemblerOptions.DEFAULT, Asm.resolver(Map.of("DEFS.MAC", "X=5\n")),
			"\t.ASECT", "\t.=1000", "\t.INCLUDE\t/DEFS.MAC/", "\t.WORD\tX", "\t.END");
		assertWords(r, "5");
	}

	@Test
	void anErrorInAnIncludedFileIsReportedAtTheInclude() {
		AssemblyResult r = Asm.assemble(AssemblerOptions.DEFAULT, Asm.resolver(Map.of("BAD.MAC", "\t.WORD\t9\n")),
			"\t.ASECT", "\t.INCLUDE\t/BAD.MAC/", "\t.END");
		Diagnostic d = assertError(r, 2, "not a valid octal number");
		assertTrue(d.describe().contains("BAD.MAC"), d.describe());
	}

	@Test
	void textAfterEndIsNotAssembled() {
		AssemblyResult r = at1000("\t.WORD\t1");
		assertNoErrors(r);
		AssemblyResult after = Asm.assemble("\t.ASECT", "\t.=1000", "\t.END", "\t.WORD\t1");
		assertTrue(after.getWords().isEmpty());
	}

	// -------------------------------------------------------------------------------------
	// Errors
	// -------------------------------------------------------------------------------------

	@Test
	void aByteThatDoesNotFitIsAnError() {
		assertError(at1000("\t.BYTE\t400"), 3, "does not fit in a byte");
	}

	@Test
	void anUnterminatedStringIsAnError() {
		assertError(at1000("\t.ASCII\t/abc"), 3, "not closed");
	}

	@Test
	void aCharacterThatIsNotRad50IsAnError() {
		assertError(at1000("\t.RAD50\t/A-B/"), 3, "cannot be written in RAD50");
	}

	@Test
	void anUnknownDirectiveIsAnError() {
		assertError(at1000("\t.FOO"), 3, ".FOO is not a directive");
	}

	@Test
	void spaceThatDependsOnALaterSymbolIsAnError() {
		assertError(at1000("\t.BLKW\tN", "N=4"), 3, "must be known where it is used");
	}

	@Test
	void aStrayEndmOrEndrIsAnError() {
		AssemblyResult r = at1000("\t.ENDM", "\t.ENDR");
		assertError(r, 3, ".ENDM without");
		assertError(r, 4, ".ENDR without");
	}

	@Test
	void restoreWithoutSaveIsAnError() {
		assertError(at1000("\t.RESTORE"), 3, "without a .SAVE");
	}

	@Test
	void aMissingIncludeIsAnError() {
		assertError(at1000("\t.INCLUDE\t/NONE.MAC/"), 3, "no file NONE.MAC");
	}

	@Test
	void anErrorDirectiveIsAnError() {
		assertError(at1000("\t.ERROR\tno good"), 3, "no good");
	}

	@Test
	void aGlobalThatIsNeverDefinedIsAnError() {
		assertError(at1000("\t.GLOBL\tEXT", "\tJMP\tEXT"), 3, "no linker");
	}

	@Test
	void aLabelDefinedTwiceIsAnError() {
		assertError(at1000("A:\tHALT", "A:\tHALT"), 4, "already a label");
	}

	@Test
	void aLabelCannotBeReassigned() {
		assertError(at1000("C:\t.WORD\t0", "C=5"), 4, "is a label");
	}

	@Test
	void registersCanOnlyBeDefinedAsThemselves() {
		assertWords(at1000("R0=%0", "\tCLR\tR0"), "5000");
		assertError(at1000("R0=%1"), 3, "is a register");
	}
}
