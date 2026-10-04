package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static to.etc.pdp11.common.macro11.asm.Asm.assertNoWarning;
import static to.etc.pdp11.common.macro11.asm.Asm.assertWarning;

/**
 * Every warning, shown the mistake it exists to find, and shown that it can be switched off.
 */
class WarningTest {
	/**
	 * A source that causes the warning, and the line it is on.
	 *
	 * @param lines the whole source
	 * @param line  the 1-based line the warning is about
	 */
	private record Case(int line, String... lines) {
	}

	private static final Map<WarningKind, Case> CASES = new EnumMap<>(WarningKind.class);

	static {
		add(WarningKind.UNUSED_LABEL, 3, "\t.ASECT", "\tHALT", "L:\tHALT", "\t.END");
		add(WarningKind.UNUSED_SYMBOL, 2, "\t.ASECT", "X=5", "\t.END");
		add(WarningKind.UNUSED_MACRO, 2, "\t.ASECT", "\t.MACRO\tM", "\t.ENDM", "\t.END");
		add(WarningKind.UNREACHABLE_CODE, 3, "\t.ASECT", "L:\tBR\tL", "\tHALT", "\t.END");
		add(WarningKind.CONDITION_ON_LATER_DEFINITION, 2, "\t.ASECT", "\t.IF\tDF\tX", "\t.ENDC", "X=1", "\t.END");
		add(WarningKind.MISSING_END, 2, "\t.ASECT", "\tHALT");
		add(WarningKind.TEXT_AFTER_END, 3, "\t.ASECT", "\t.END", "\tHALT");
		add(WarningKind.OVERLAPPING_CODE, 5, "\t.ASECT", "\t.=1000", "\t.WORD\t1", "\t.=1000", "\t.WORD\t2", "\t.END");
		add(WarningKind.ODD_WORD_ADDRESS, 2, "\t.ASECT", "\tCLR\t@#1001", "\t.END");
		add(WarningKind.REGISTER_AS_VALUE, 2, "\t.ASECT", "\t.WORD\tR0", "\t.END");
		add(WarningKind.IMMEDIATE_DESTINATION, 2, "\t.ASECT", "\tCLR\t#5", "\t.END");
		add(WarningKind.BYTE_IMMEDIATE_TRUNCATED, 2, "\t.ASECT", "\tMOVB\t#400,R0", "\t.END");
		add(WarningKind.ARITHMETIC_OVERFLOW, 2, "\t.ASECT", "\t.WORD\t100000*2", "\t.END");
		add(WarningKind.LABEL_BEFORE_LOCATION_CHANGE, 2, "\t.ASECT", "L:\t.=1000", "\t.WORD\tL", "\t.END");
		add(WarningKind.MACRO_REDEFINED, 4, "\t.ASECT", "\t.MACRO\tM", "\t.ENDM", "\t.MACRO\tM", "\t.ENDM", "\tM", "\t.END");
		add(WarningKind.EXCESS_MACRO_ARGUMENTS, 4, "\t.ASECT", "\t.MACRO\tM", "\t.ENDM", "\tM\t1", "\t.END");
		add(WarningKind.ODD_REGISTER_PAIR, 2, "\t.ASECT", "\tDIV\tR2,R1", "\t.END");
		add(WarningKind.UNSUPPORTED_OPTION, 2, "\t.ASECT", "\t.ENABL\tCDR", "\t.END");
		add(WarningKind.IMPLICIT_WORD, 2, "\t.ASECT", "\t5", "\t.END");
		add(WarningKind.SYMBOL_NOT_UNIQUE_IN_SIX, 3, "\t.ASECT", "LONGNAME1=1", "LONGNAME2=2", "\t.WORD\tLONGNAME1,LONGNAME2", "\t.END");
		add(WarningKind.PSECT_ATTRIBUTE_CHANGE, 2, "\t.PSECT\tP,RO", "\t.PSECT\tP,RW", "\t.END");
		add(WarningKind.PC_AUTO_DECREMENT, 2, "\t.ASECT", "\tCLR\t-(PC)", "\t.END");
		add(WarningKind.ODD_TRANSFER_ADDRESS, 4, "\t.ASECT", "\t.=1000", "\tHALT", "\t.END\t1001");
		add(WarningKind.NO_EFFECT, 2, "\t.ASECT", "\tMOV\tR1,R1", "\t.END");
		add(WarningKind.NUMBER_AS_REGISTER, 2, "\t.ASECT", "\tCLR\t-(6)", "\t.END");
	}

	private static void add(WarningKind kind, int line, String... lines) {
		CASES.put(kind, new Case(line, lines));
	}

	@Test
	void everyWarningHasACase() {
		List<WarningKind> missing = new ArrayList<>();
		for(WarningKind k : WarningKind.values()) {
			if(!CASES.containsKey(k))
				missing.add(k);
		}
		assertTrue(missing.isEmpty(), "No test case for " + missing);
	}

	@ParameterizedTest
	@EnumSource(WarningKind.class)
	void theWarningIsGiven(WarningKind kind) {
		Case c = CASES.get(kind);
		AssemblyResult r = Asm.assemble(c.lines());
		Diagnostic d = assertWarning(r, kind, c.line());
		assertEquals(Severity.WARNING, d.severity());
		assertFalse(r.hasErrors(), () -> "A warning case must not have errors: " + r.getErrors());
	}

	@ParameterizedTest
	@EnumSource(WarningKind.class)
	void theWarningCanBeSwitchedOff(WarningKind kind) {
		Case c = CASES.get(kind);
		AssemblyResult r = Asm.assemble(AssemblerOptions.DEFAULT.withoutWarning(kind), SourceResolver.NONE, c.lines());
		assertNoWarning(r, kind);
	}

	@Test
	void aBranchToTheNextInstructionDoesNothing() {
		assertWarning(Asm.assemble("\t.ASECT", "\tBR\t.+2", "\t.END"), WarningKind.NO_EFFECT, 2);
	}

	@Test
	void aLabelMakesCodeAfterATransferReachable() {
		assertNoWarning(Asm.assemble("\t.ASECT", "L:\tBR\tL", "M:\tBR\tM", "\t.END"), WarningKind.UNREACHABLE_CODE);
	}

	@Test
	void haltDoesNotMakeTheNextInstructionUnreachable() {
		//-- Continue on the console goes on after a HALT, and programs rely on it.
		assertNoWarning(Asm.assemble("\t.ASECT", "\tHALT", "\tNOP", "\t.END"), WarningKind.UNREACHABLE_CODE);
	}

	@Test
	void labelsFromMacroExpansionsAreNotReportedUnused() {
		AssemblyResult r = Asm.assemble("\t.ASECT", "\t.MACRO\tM\t?L", "L:\tNOP", "\t.ENDM", "\tM", "\t.END");
		assertNoWarning(r, WarningKind.UNUSED_LABEL);
	}

	@Test
	void theStartAddressCountsAsAUse() {
		assertNoWarning(Asm.assemble("\t.ASECT", "S:\tHALT", "\t.END\tS"), WarningKind.UNUSED_LABEL);
	}
}
