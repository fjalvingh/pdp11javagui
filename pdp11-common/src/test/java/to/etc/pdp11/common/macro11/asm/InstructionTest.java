package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static to.etc.pdp11.common.macro11.asm.Asm.assertError;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWords;
import static to.etc.pdp11.common.macro11.asm.Asm.at;
import static to.etc.pdp11.common.macro11.asm.Asm.at1000;

/**
 * Instruction encoding: every operand format and every addressing mode, with the octal written
 * out from the PDP-11 processor handbook rather than from what the assembler happens to do.
 */
class InstructionTest {
	@Test
	void theEightAddressingModes() {
		assertWords(at1000(
				"\tMOV\tR0,R1",                  // 0 0
				"\tMOV\t(R0)+,-(R1)",            // 2 4
				"\tMOV\t@(R2)+,@-(R3)",          // 3 5
				"\tMOV\t4(R5),@6(R4)",           // 6 7
				"\tCLR\t(R3)",                   // 1
				"\tCLR\t@R3"),                   // @R is (R)
			"10001", "12041", "13253", "16574", "4", "6", "5013", "5013");
	}

	@Test
	void deferredRegisterWithoutAnOffsetIsIndexZero() {
		assertWords(at1000("\tMOV\t@(R1),R0"), "17100", "0");
	}

	@Test
	void immediateAndAbsoluteUseThePc() {
		assertWords(at1000("\tMOV\t#400,SP", "\tMOV\t@#177570,R0"), "12706", "400", "13700", "177570");
	}

	@Test
	void aPlainAddressIsPcRelativeToTheWordAfterTheOffset() {
		AssemblyResult r = at1000(
			"\tMOV\tX,R0",          // 1000, offset word at 1002
			"\tHALT",               // 1004
			"X:\t.WORD\t0");        // 1006
		assertWords(r, "16700", "2", "0", "0");
	}

	@Test
	void absoluteAddressingWhenAmaIsEnabled() {
		assertWords(at1000("\t.ENABL\tAMA", "\tCLR\tX", "X:\t.WORD\t0"), "5037", "1004", "0");
	}

	@Test
	void deferredPcRelativeIsMode77() {
		assertWords(at1000("\tJMP\t@V", "V:\t.WORD\t1000"), "177", "0", "1000");
	}

	@Test
	void branchesCountWordsFromTheNextInstruction() {
		assertWords(at1000(
				"L:\tBR\tL",                 // to itself: -1
				"\tBNE\tF",                  // 1002 -> 1006: +1
				"\tNOP",
				"F:\tBCS\tL"),               // 1006 -> 1000: -4
			"777", "1001", "240", "103774");
	}

	@Test
	void sobGoesBackwards() {
		assertWords(at1000("L:\tNOP", "\tSOB\tR1,L"), "240", "77102");
	}

	@Test
	void jsrRtsAndTheirShorthands() {
		AssemblyResult r = at1000(
			"\tJSR\tPC,S",       // 1000
			"\tCALL\tS",         // 1004
			"\tHALT",            // 1010
			"S:\tRTS\tPC",       // 1012
			"\tRETURN");
		assertWords(r, "4767", "6", "4767", "2", "0", "207", "207");
	}

	@Test
	void eisInstructionsTakeTheRegisterSecond() {
		assertWords(at1000("\tASH\t#3,R2", "\tMUL\tR3,R2", "\tDIV\t(R1),R0", "\tASHC\t#-1,R2", "\tXOR\tR1,R2"),
			"72227", "3", "70203", "71011", "73227", "177777", "74102");
	}

	@Test
	void trapsAndTheirNumbers() {
		assertWords(at1000("\tEMT\t30", "\tTRAP", "\tTRAP\t377", "\tMARK\t5", "\tSPL\t7"),
			"104030", "104400", "104777", "6405", "237");
	}

	@Test
	void floatingPointAccumulatorsAndImmediates() {
		assertWords(at1000(
				"\tLDF\t(R0),%1",      // 172400 | 1<<6 | 10
				"\tSTF\tR0,@R2",       // 174000 | 0<<6 | 12
				"\tLDF\t#1.0,R0",      // the immediate is one word of floating point
				"\tFADD\tR2",
				"\tSETD"),
			"172510", "174012", "172427", "40200", "75002", "170011");
	}

	@Test
	void anAccumulatorCanBeAPlainNumber() {
		//-- Sources write AC0=0 and use AC0 where an accumulator goes.
		AssemblyResult r = at1000("AC1=1", "\tLDF\t(R0),AC1");
		assertWords(r, "172510");
	}

	@Test
	void anInstructionNameAsAValueIsItsOpcode() {
		assertWords(at1000("\t.WORD\tMOV,HALT,BR"), "10000", "0", "400");
	}

	@Test
	void butALabelOfTheSameNameIsTheLabel() {
		AssemblyResult r = at1000("\tBR\tRETURN", "RETURN:\tHALT");
		assertWords(r, "400", "0");
	}

	@Test
	void registerExpressions() {
		assertWords(at1000("\tCLR\t%2", "\tCLR\tR2+1", "X=%4", "\tCLR\tX"), "5002", "5003", "5004");
	}

	@Test
	void dotIsTheStartOfTheInstruction() {
		assertWords(at1000("\tMOV\t#.,R0", "\tBR\t."), "12700", "1000", "777");
	}

	// -------------------------------------------------------------------------------------
	// Errors
	// -------------------------------------------------------------------------------------

	@Test
	void jumpingToARegisterIsAnError() {
		assertError(at1000("\tJMP\tR0"), 3, "JMP to a register is illegal");
		assertError(at1000("\tJSR\tPC,R1"), 3, "JSR to a register is illegal");
	}

	@Test
	void aBranchTooFarIsAnError() {
		assertError(at1000("L:\t.BLKW\t200", "\tBR\tL"), 4, "a branch reaches");
	}

	@Test
	void aBranchToAnOddAddressIsAnError() {
		assertError(at1000("\tBR\t1001"), 3, "odd address");
	}

	@Test
	void sobCannotGoForwards() {
		assertError(at1000("\tSOB\tR0,L", "\tNOP", "L:\tHALT"), 3, "SOB can only go backwards");
	}

	@Test
	void anOperandIsMissing() {
		assertError(at1000("\tMOV\tR0"), 3, "needs a comma");
		assertError(at1000("\tCLR"), 3, "needs a destination");
	}

	@Test
	void junkAfterTheOperandsIsAnError() {
		assertError(at1000("\tMOV\tR0,R1 junk"), 3, "Unexpected 'JUNK'");
		assertError(at1000("\tBLE\tL:", "L:\tHALT"), 3, "Unexpected ':'");
	}

	@Test
	void anOperandOutOfItsFieldIsAnError() {
		AssemblyResult r = at1000("\tEMT\t400", "\tMARK\t100", "\tSPL\t10");
		assertError(r, 3, "out of range");
		assertError(r, 4, "out of range");
		assertError(r, 5, "out of range");
	}

	@Test
	void anInstructionAtAnOddAddressIsMovedAndReported() {
		AssemblyResult r = at1000("\t.BYTE\t1", "\tHALT");
		assertError(r, 4, "odd address");
		assertEquals(0, at(r, 01002));
	}

	@Test
	void aFailingInstructionStillTakesItsPlace() {
		//-- So that the branch after it is not thrown off by one word.
		AssemblyResult r = at1000("\tJMP\tR0", "L:\tBR\tL");
		assertError(r, 3, "illegal");
		assertEquals(0777, at(r, 01002));
	}
}
