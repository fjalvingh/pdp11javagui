package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static to.etc.pdp11.common.macro11.asm.Asm.assertError;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWarning;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWords;
import static to.etc.pdp11.common.macro11.asm.Asm.at1000;

/**
 * Numbers, operators and the special terms.
 */
class ExpressionTest {
	@Test
	void numbersAreOctalUnlessTheyEndInAPoint() {
		assertWords(at1000("\t.WORD\t10,10.,177777,65535."), "10", "12", "177777", "177777");
	}

	@Test
	void theRadixCanBeChangedForTheWholeSourceOrOneTerm() {
		assertWords(at1000(
				"\t.WORD\t^B101,^D10,^O10,^X1F,^XFF",
				"\t.RADIX\t10",
				"\t.WORD\t10,^O10",
				"\t.RADIX\t16",
				"\t.WORD\t1F"),
			"5", "12", "10", "37", "377", "12", "10", "37");
	}

	@Test
	void operatorsAreEvaluatedLeftToRightWithoutPrecedence() {
		assertWords(at1000("\t.WORD\t1+2*3,1+<2*3>,7&3!10,-1,^C0,100/7"), "11", "7", "13", "177777", "177777", "11");
	}

	@Test
	void characterConstants() {
		assertWords(at1000("\t.WORD\t'A,\"AB"), "101", "41101");
	}

	@Test
	void rad50AndFloatingPointTerms() {
		//-- ^RABC: A=1, B=2, C=3 -> 1*1600+2*50+3; ^F1.0 is the first word of 1.0.
		assertWords(at1000("\t.WORD\t^RABC,^R<A>,^F1.0"), "3223", "3100", "40200");
	}

	@Test
	void rad50StopsAtTheFirstCharacterItCannotEncode() {
		assertWords(at1000("\tCMP\t#^RDU,R4"), "22704", "16110");
	}

	@Test
	void aRelocatableDifferenceIsAbsolute() {
		AssemblyResult r = Asm.assemble("\t.PSECT\tP", "A:\t.WORD\t0", "B:\t.WORD\tB-A", "\t.END");
		Asm.assertNoErrors(r);
		assertEquals(2, Asm.at(r, 2));
	}

	@Test
	void aForwardReferenceIsResolvedAtTheEnd() {
		assertWords(at1000("\t.WORD\tX+1", "X=5"), "6");
	}

	@Test
	void aSymbolMeansWhatItWasWhereItIsUsed() {
		//-- Reassigning a symbol does not change what an earlier statement stored.
		assertWords(at1000("X=1", "\t.WORD\tX", "X=2", "\t.WORD\tX"), "1", "2");
	}

	@Test
	void aSymbolDefinedInTermsOfALaterOneIsResolved() {
		assertWords(at1000("A=B+1", "\t.WORD\tA", "B=4"), "5");
	}

	// -------------------------------------------------------------------------------------
	// Errors
	// -------------------------------------------------------------------------------------

	@Test
	void aDigitThatIsNotInTheRadixIsAnError() {
		AssemblyResult r = at1000("\t.WORD\t9", "\t.WORD\t^B102", "\t.WORD\t8.5");
		assertError(r, 3, "write 9. for a decimal number");
		assertError(r, 4, "not a valid binary number");
		assertError(r, 5, "not an integer");
	}

	@Test
	void aNumberThatDoesNotFitIsAnError() {
		assertError(at1000("\t.WORD\t200000"), 3, "does not fit in 16 bits");
	}

	@Test
	void divisionByZeroIsAnError() {
		assertError(at1000("\t.WORD\t1/0"), 3, "Division by zero");
	}

	@Test
	void anUndefinedSymbolIsAnError() {
		assertError(at1000("\t.WORD\tNOWHERE"), 3, "Undefined symbol NOWHERE");
	}

	@Test
	void aCircularDefinitionIsAnError() {
		assertError(at1000("A=B", "B=A", "\t.WORD\tA"), 4, "refers to itself");
	}

	@Test
	void aLocalLabelInAnotherBlockIsExplained() {
		AssemblyResult r = at1000("1$:\tHALT", "L:\tBR\t1$");
		assertError(r, 4, "another block");
	}

	@Test
	void overflowIsAWarningNotAnError() {
		AssemblyResult r = at1000("\t.WORD\t100000*2,-1+1");
		assertWarning(r, WarningKind.ARITHMETIC_OVERFLOW, 3);
		assertEquals(1, r.getWarnings().stream().filter(d -> d.warning() == WarningKind.ARITHMETIC_OVERFLOW).count());
	}
}
