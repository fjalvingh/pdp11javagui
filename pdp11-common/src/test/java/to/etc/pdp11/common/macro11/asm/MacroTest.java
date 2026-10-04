package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static to.etc.pdp11.common.macro11.asm.Asm.assertError;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWords;
import static to.etc.pdp11.common.macro11.asm.Asm.at1000;

/**
 * Macros, repeat blocks, and the substitution that makes them work.
 */
class MacroTest {
	@Test
	void argumentsAreSubstituted() {
		assertWords(at1000(
				"\t.MACRO\tSAVE\tR",
				"\tMOV\tR,-(SP)",
				"\t.ENDM",
				"\tSAVE\tR3"),
			"10346");
	}

	@Test
	void defaultsKeywordsAndBrackets() {
		assertWords(at1000(
				"\t.MACRO\tW\tA,B=7",
				"\t.WORD\tA,B",
				"\t.ENDM",
				"\tW\t1",
				"\tW\tB=3,A=2",
				"\tW\t<1+1>"),
			"1", "7", "2", "3", "2", "7");
	}

	@Test
	void anApostropheConcatenates() {
		assertWords(at1000(
				"\t.MACRO\tDEF\tN",
				"X'N=5",
				"\t.ENDM",
				"\tDEF\t1",
				"\t.WORD\tX1"),
			"5");
	}

	@Test
	void aBackslashArgumentIsTheValue() {
		assertWords(at1000(
				"\t.MACRO\tLBL\tN",
				"L'N:\t.WORD\tN",
				"\t.ENDM",
				"C=4",
				"\tLBL\t\\C+1",
				"\t.WORD\tL5"),
			"5", "1000");
	}

	@Test
	void aQuestionMarkParameterMakesALocalLabel() {
		AssemblyResult r = at1000(
			"\t.MACRO\tWAIT\t?L",
			"L:\tBR\tL",
			"\t.ENDM",
			"\tWAIT",
			"\tWAIT");
		assertWords(r, "777", "777");
	}

	@Test
	void nargNchrAndNtype() {
		assertWords(at1000(
				"\t.MACRO\tM\tA,B",
				"\t.NARG\tN",
				"\t.NCHR\tC,A",
				"\t.NTYPE\tT,B",
				"\t.WORD\tN,C,T",
				"\t.ENDM",
				"\tM\tHELLO,(R3)+"),
			"2", "5", "23");
	}

	@Test
	void mexitLeavesTheExpansionAndItsConditionals() {
		assertWords(at1000(
				"\t.MACRO\tM",
				"\t.WORD\t1",
				"\t.IF\tEQ\t0",
				"\t.MEXIT",
				"\t.ENDC",
				"\t.WORD\t2",
				"\t.ENDM",
				"\tM",
				"\t.WORD\t3"),
			"1", "3");
	}

	@Test
	void aMacroCanDefineAMacro() {
		assertWords(at1000(
				"\t.MACRO\tOUTER",
				"\t.MACRO\tINNER",
				"\t.WORD\t7",
				"\t.ENDM",
				"\t.ENDM",
				"\tOUTER",
				"\tINNER"),
			"7");
	}

	@Test
	void repeatBlocks() {
		assertWords(at1000(
				"\t.REPT\t3",
				"\t.WORD\t1",
				"\t.ENDR",
				"\t.IRP\tX,<4,5>",
				"\t.WORD\tX",
				"\t.ENDR",
				"\t.IRPC\tX,ab",
				"\t.ASCII\t/X/",
				"\t.ENDR",
				"\t.REPT\t0",
				"\t.WORD\t9.",
				"\t.ENDR"),
			"1", "1", "1", "4", "5", "61141");
	}

	@Test
	void endmCanEndARepeatBlock() {
		//-- DEC's assembler allows it, and RSX sources use it.
		assertWords(at1000("\t.REPT\t2", "\t.WORD\t1", "\t.ENDM"), "1", "1");
	}

	@Test
	void irpEvaluatesABackslashItem() {
		assertWords(at1000("N=3", "\t.IRP\tX,<\\N>", "\t.WORD\tX", "\t.ENDR"), "3");
	}

	@Test
	void mcallFindsTheMacroThroughTheResolver() {
		SourceResolver lib = Asm.resolver(Map.of("SAVE.MAC", "; library\n.MACRO SAVE R\n\tMOV R,-(SP)\n.ENDM\n"));
		AssemblyResult r = Asm.assemble(AssemblerOptions.DEFAULT, lib, "\t.ASECT", "\t.=1000", "\t.MCALL\tSAVE", "\tSAVE\tR1", "\t.END");
		assertWords(r, "10146");
	}

	@Test
	void mclFindsAMacroWithoutMcall() {
		SourceResolver lib = Asm.resolver(Map.of("SAVE.MAC", ".MACRO SAVE R\n\tMOV R,-(SP)\n.ENDM\n"));
		AssemblyResult r = Asm.assemble(AssemblerOptions.DEFAULT, lib, "\t.ASECT", "\t.=1000", "\t.ENABL\tMCL", "\tSAVE\tR1", "\t.END");
		assertWords(r, "10146");
	}

	@Test
	void anErrorInAnExpansionIsReportedAtTheCallAndSaysWhere() {
		AssemblyResult r = at1000("\t.MACRO\tBAD", "\t.WORD\t9", "\t.ENDM", "\tBAD");
		Diagnostic d = assertError(r, 6, "not a valid octal number");
		assertTrue(d.describe().contains("in BAD, line 1"), d.describe());
	}

	@Test
	void theExpansionIsListedUnderTheCall() {
		AssemblyResult r = at1000("\t.MACRO\tM", "\t.WORD\t1", "\t.ENDM", "\tM");
		List<String> l = r.getListing();
		int call = indexOf(l, "\\s+6\\s+\tM");
		assertTrue(l.get(call + 1).matches("\\s+1 001000 000001\\s+\t\\.WORD\t1"), l.get(call + 1));
		assertEquals(6, r.sourceLineOf(call + 1));
	}

	private static int indexOf(List<String> lines, String regex) {
		for(int i = 0; i < lines.size(); i++) {
			if(lines.get(i).matches(regex))
				return i;
		}
		throw new AssertionError("No line matching " + regex + " in " + lines);
	}

	// -------------------------------------------------------------------------------------
	// Errors
	// -------------------------------------------------------------------------------------

	@Test
	void aMacroWithoutEndmIsAnError() {
		assertError(Asm.assemble("\t.MACRO\tM", "\tHALT"), 1, "has no .ENDM");
	}

	@Test
	void aRepeatWithoutEndrIsAnError() {
		assertError(Asm.assemble("\t.REPT\t2", "\tHALT"), 1, "has no .ENDR");
	}

	@Test
	void aRepeatCountMustBeKnown() {
		assertError(at1000("\t.REPT\tN", "\t.WORD\t1", "\t.ENDR", "N=2"), 3, "must be known");
	}

	@Test
	void anUnknownKeywordArgumentIsAnError() {
		assertError(at1000("\t.MACRO\tM\tA", "\t.ENDM", "\tM\tB=1"), 5, "has no parameter B");
	}

	@Test
	void anUnclosedConditionalInAMacroIsAnError() {
		assertError(at1000("\t.MACRO\tM", "\t.IF\tEQ\t0", "\t.ENDM", "\tM"), 6, "not closed before the end of the macro");
	}

	@Test
	void mexitOutsideAMacroIsAnError() {
		assertError(at1000("\t.MEXIT"), 3, "outside a macro");
	}

	@Test
	void narOutsideAMacroIsAnError() {
		assertError(at1000("\t.NARG\tN"), 3, "outside a macro");
	}

	@Test
	void aMissingLibraryMacroIsAnError() {
		assertError(at1000("\t.MCALL\tNONE"), 3, "no macro NONE");
	}

	@Test
	void aMacroCallingItselfForeverStops() {
		AssemblyResult r = at1000("\t.MACRO\tM", "\tM", "\t.ENDM", "\tM");
		assertTrue(r.getErrors().stream().anyMatch(d -> d.message().contains("nested more than")), r.getDiagnostics().toString());
	}
}
