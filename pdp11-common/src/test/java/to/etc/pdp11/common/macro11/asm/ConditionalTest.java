package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import static to.etc.pdp11.common.macro11.asm.Asm.assertError;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWords;
import static to.etc.pdp11.common.macro11.asm.Asm.at1000;

/**
 * Conditional assembly.
 */
class ConditionalTest {
	private static AssemblyResult test(String condition) {
		return at1000("\t.IF\t" + condition, "\t.WORD\t1", "\t.IFF", "\t.WORD\t0", "\t.ENDC");
	}

	private static void assertTrueCondition(String condition) {
		assertWords(test(condition), "1");
	}

	private static void assertFalseCondition(String condition) {
		assertWords(test(condition), "0");
	}

	@Test
	void numericConditionsAreSigned() {
		assertTrueCondition("EQ 0");
		assertTrueCondition("NE 1");
		assertTrueCondition("LT -1");
		assertTrueCondition("LT 177777");
		assertTrueCondition("GT 1");
		assertTrueCondition("GE 0");
		assertTrueCondition("LE 0");
		assertFalseCondition("GT 100000");
		assertTrueCondition("Z 0");
		assertTrueCondition("NZ 5");
	}

	@Test
	void definedness() {
		assertWords(at1000("X=1", "\t.IF\tDF\tX", "\t.WORD\t1", "\t.ENDC", "\t.IF\tNDF\tY", "\t.WORD\t2", "\t.ENDC"), "1", "2");
	}

	@Test
	void definednessCombines() {
		assertWords(at1000("X=1", "\t.IF\tDF\tX&Y", "\t.WORD\t1", "\t.ENDC", "\t.IF\tDF\tX!Y", "\t.WORD\t2", "\t.ENDC"), "2");
	}

	@Test
	void blankAndIdentical() {
		assertTrueCondition("B <>");
		assertTrueCondition("NB <A>");
		assertTrueCondition("IDN <A>,<A>");
		assertTrueCondition("DIF <A>,<B>");
	}

	@Test
	void theOneWordForms() {
		assertWords(at1000("\t.IFEQ\t0", "\t.WORD\t1", "\t.ENDC", "\t.IFNDF\tQ", "\t.WORD\t2", "\t.ENDC"), "1", "2");
	}

	@Test
	void subconditions() {
		assertWords(at1000(
				"\t.IF\tEQ\t0",
				"\t.WORD\t1",
				"\t.IFF",
				"\t.WORD\t2",
				"\t.IFT",
				"\t.WORD\t3",
				"\t.IFTF",
				"\t.WORD\t4",
				"\t.ENDC"),
			"1", "3", "4");
	}

	@Test
	void nestedInsideAFalseBlockNothingIsAssembled() {
		assertWords(at1000("\t.IF\tEQ\t1", "\t.IF\tEQ\t0", "\t.WORD\t1", "\t.ENDC", "\t.WORD\t2", "\t.ENDC", "\t.WORD\t3"), "3");
	}

	@Test
	void immediateIf() {
		assertWords(at1000("\t.IIF\tNE 1, .WORD 5", "\t.IIF\tEQ 1, .WORD 6"), "5");
	}

	@Test
	void immediateIfCanHaveALabel() {
		assertWords(at1000("\t.IIF\tNE 1, L: .WORD L"), "1000");
	}

	// -------------------------------------------------------------------------------------
	// Errors
	// -------------------------------------------------------------------------------------

	@Test
	void anUnknownConditionIsAnError() {
		assertError(test("XX 1"), 3, "XX is not a condition");
	}

	@Test
	void aConditionNeedsAKnownValue() {
		assertError(at1000("\t.IF\tEQ\tLATER", "\t.ENDC", "LATER=0"), 3, "must be known");
	}

	@Test
	void anUnclosedConditionalIsAnError() {
		assertError(at1000("\t.IF\tEQ\t0"), 3, "not closed by .ENDC");
	}

	@Test
	void endcWithoutIfIsAnError() {
		assertError(at1000("\t.ENDC"), 3, ".ENDC without");
	}

	@Test
	void iffOutsideABlockIsAnError() {
		assertError(at1000("\t.IFF"), 3, "outside a conditional");
	}
}
