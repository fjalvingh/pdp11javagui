package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ODT lexer on its own, which is where "not all of it has arrived" is decided.
 *
 * <p>Everything else about this scanner is exercised through {@code OdtConsoleTest} against a
 * whole conversation. What is worth testing here is the boundary: a symbol that could still
 * grow, and the two answers a caller can ask for when one turns up.</p>
 */
class OdtScannerTest {
	private static OdtScanner scannerOn(String text) {
		OdtScanner s = new OdtScanner();
		s.moreInput(text);
		return s;
	}

	@Test
	void aSymbolThatCouldStillGrowInterruptsTheDecoderByDefault() {
		//-- 123 may yet become 1234, and R may yet become R0. Neither is a symbol yet.
		assertThrows(ScannerInputIncompleteException.class, () -> scannerOn("123").nextSymbol());
		assertThrows(ScannerInputIncompleteException.class, () -> scannerOn("R").nextSymbol());
		assertThrows(ScannerInputIncompleteException.class, () -> scannerOn("").nextSymbol());
	}

	/**
	 * FABLE-ISSUES #56: {@code nextSymbol(false)} is documented to answer end-of-input rather
	 * than throw, and only the empty-buffer case honoured it. A run of digits reaching the end
	 * of the buffer and a trailing {@code R} or {@code $} threw whatever the caller asked for.
	 */
	@Test
	void askedToSaySoRatherThanThrow_everyWayOfRunningOutAnswersEndOfInput() {
		for(String partial : new String[]{"", "123", "R", "$"}) {
			OdtScanner s = scannerOn(partial);
			assertEquals("EOF", s.nextSymbol(false), "on \"" + partial + "\"");
			assertEquals(OdtScanner.Sym.EOF, s.getCurSymType(), "on \"" + partial + "\"");
			//-- And nothing was consumed: the rest of it is still to come.
			assertEquals(partial, s.getRemainingInput(), "on \"" + partial + "\"");
		}
	}

	@Test
	void theSameInputCompletedScansAsTheWholeSymbol() {
		OdtScanner s = scannerOn("123");
		assertEquals("EOF", s.nextSymbol(false));
		s.moreInput("4 ");
		assertEquals("1234", s.nextSymbol(false));
		assertEquals(OdtScanner.Sym.OCTAL, s.getCurSymType());
	}
}
