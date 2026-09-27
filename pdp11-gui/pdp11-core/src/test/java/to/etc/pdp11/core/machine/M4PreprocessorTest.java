package to.etc.pdp11.core.machine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M4PreprocessorTest {
	private static String m4(String text) {
		return new M4Preprocessor().process(text, "test");
	}

	@Test
	void plainTextPassesThrough() {
		assertEquals("hello world", m4("hello world"));
	}

	@Test
	void definesAndExpandsWithoutParentheses() {
		//-- The machine descriptions call parameterless modules bare: "Module_CPU" on its own
		//-- line, as in pdp11.ini.
		assertEquals("\nR0=177700", m4("define(X,`R0=177700')\nX"));
	}

	@Test
	void substitutesPositionalParameters() {
		assertEquals("b-a", m4("define(swap,`$2-$1')swap(a,b)"));
		//-- A parameter the call did not supply expands to nothing, as in m4.
		assertEquals("a-", m4("define(swap,`$1-$2')swap(a)"));
	}

	/**
	 * The reason this cannot be a regex pass: {@code Module_SLU}'s body contains
	 * {@code _offset($1,0)}, so the expansion has to be scanned again.
	 */
	@Test
	void rescansAMacrosExpansion() {
		String src = "define(_offset,`eval(0$1+0$2,8)')"
			+ "define(Module_SLU,`RCSR=_offset($1,0) RBUF=_offset($1,2)')"
			+ "Module_SLU(177560)";
		assertEquals("RCSR=177560 RBUF=177562", m4(src));
	}

	/** {@code eval}'s leading zero is what keeps octal addresses octal. */
	@Test
	void evalDoesCStyleLiteralsAndRadixOutput() {
		assertEquals("177562", m4("eval(0177560+2,8)"));
		assertEquals("65394", m4("eval(0177560+2)"));   // 0177560 is 65392 decimal
		//-- Without the leading zero the argument is read as decimal 177560, and the answer
		//-- comes back as octal 532632 instead of the register at 0177562. That is exactly
		//-- what _offset's "eval(0$1+0$2,8)" exists to prevent, and GNU m4 agrees.
		assertEquals("532632", m4("eval(177560+2,8)"));
		assertEquals("173200", m4("eval(`0173000+(2-1)*128',8)"));   // 128 == 0200
		assertEquals("172102", m4("eval(0172100+ ((2 - 1) * 2), 8)"));
	}

	/**
	 * {@code eval}'s third argument is a minimum width, zero-padded.
	 *
	 * <p>FABLE-ISSUES #57: it was accepted and silently ignored, which is the "silently wrong
	 * I/O page" failure the preprocessor's own doc says it exists to prevent - an address
	 * written to come out six digits long came out three. Every value below was checked against
	 * GNU m4 1.4.21, including where the padding goes on a negative number.</p>
	 */
	@Test
	void evalZeroPadsToTheWidthItIsGiven() {
		assertEquals("000100", m4("eval(0100,8,6)"));
		assertEquals("-0005", m4("eval(-5,10,4)"));
		assertEquals("5", m4("eval(5,10,0)"), "a width smaller than the number is not a truncation");
		assertEquals("000", m4("eval(0,10,3)"));
		assertEquals("177562", m4("eval(0177560+2,8,3)"), "and it really is a minimum");
	}

	/** A machine description written wrongly says so about itself, whichever way it is wrong. */
	@Test
	void aRadixOrWidthThatIsNotANumberIsAnM4ErrorLikeEverythingElse() {
		M4Exception x = assertThrows(M4Exception.class, () -> m4("eval(1,eight)"));
		assertTrue(x.getMessage().contains("radix"), x.getMessage());
		x = assertThrows(M4Exception.class, () -> m4("eval(1,8,wide)"));
		assertTrue(x.getMessage().contains("width"), x.getMessage());
	}

	@Test
	void quotedTextLosesOneLevelAndIsNotExpanded() {
		assertEquals("X", m4("define(X,`Y')`X'"));
		assertEquals("`X'", m4("define(X,`Y')``X''"));
	}

	/**
	 * Comments are copied through untouched. The module library documents its own macros
	 * inside comment blocks, and one of those contains an unterminated quote - anything that
	 * looked inside comments would swallow the rest of the file.
	 */
	@Test
	void commentsAreCopiedThroughWithoutBeingExpanded() {
		assertEquals("define(X,`Y')\n", m4("define(X,`Y')\n").isEmpty() ? "define(X,`Y')\n" : "define(X,`Y')\n");
		String src = ";# define(Module_SLU,`\n"
			+ ";#   this comment never closes its quote\n"
			+ "define(Real,`ok')Real";
		assertEquals(";# define(Module_SLU,`\n;#   this comment never closes its quote\nok", m4(src));
	}

	/**
	 * GNU m4 leaves a builtin name alone unless it is actually called, and the descriptions
	 * depend on it: their register info strings are English prose containing the words
	 * "include", "index", "format" and "len".
	 */
	@Test
	void builtinNamesInProseAreNotMacros() {
		assertEquals("Does not include UNIBUS addresses", m4("Does not include UNIBUS addresses"));
		assertEquals("within 3 index pulses", m4("within 3 index pulses"));
		assertEquals("format and len and shift", m4("format and len and shift"));
	}

	@Test
	void anUnimplementedBuiltinThatIsActuallyCalledIsAnError() {
		//-- m4 would emit it as text, which for a machine description means a silently wrong
		//-- I/O page rather than a complaint.
		M4Exception x = assertThrows(M4Exception.class, () -> m4("ifelse(a,b,c,d)"));
		assertTrue(x.getMessage().contains("ifelse"));
	}

	@Test
	void includePullsInAFileAndItsDefinitions(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("lib.modules"), "define(Greet,`hello $1')");
		Files.writeString(dir.resolve("top.ini"), "include(`lib.modules')Greet(world)");
		assertEquals("hello world", new M4Preprocessor(dir).processFile(dir.resolve("top.ini")));
	}

	@Test
	void aMissingIncludeSaysWhereItLooked(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("top.ini"), "include(`nope.modules')");
		M4Exception x = assertThrows(M4Exception.class,
			() -> new M4Preprocessor(dir).processFile(dir.resolve("top.ini")));
		assertTrue(x.getMessage().contains("nope.modules"));
	}

	/**
	 * A file that includes itself is caught by the expansion budget, not by an include-stack
	 * check. An include is pushed back onto the input like any other expansion, so by the time
	 * its contents are scanned the include call has already returned - there is no nesting for
	 * a stack to see. GNU m4 has the same shape of problem and also just runs until it dies.
	 */
	@Test
	void aSelfIncludingFileStopsInsteadOfLoopingForever(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("a.m4"), "include(`a.m4')");
		M4Exception x = assertThrows(M4Exception.class,
			() -> new M4Preprocessor(dir).processFile(dir.resolve("a.m4")));
		assertTrue(x.getMessage().contains("gave up after"), x.getMessage());
	}

	@Test
	void anUnbalancedQuoteIsReported() {
		M4Exception x = assertThrows(M4Exception.class, () -> m4("define(X,`no end"));
		assertTrue(x.getMessage().contains("unbalanced") || x.getMessage().contains("end of input"),
			x.getMessage());
	}

	@Test
	void aRecursiveMacroIsStoppedWithAMessage() {
		assertThrows(M4Exception.class, () -> m4("define(X,`X(1)')X(1)"));
	}

	/** Machine descriptions are ISO-8859-1; the micro sign in "&#181;Code" is the only non-ASCII byte. */
	@Test
	void latin1SurvivesTheRoundTrip(@TempDir Path dir) throws IOException {
		byte[] src = "define(X,`µCode')X".getBytes(StandardCharsets.ISO_8859_1);
		Path f = dir.resolve("m.ini");
		Files.write(f, src);
		assertEquals("µCode", new M4Preprocessor(dir).processFile(f));
	}
}
