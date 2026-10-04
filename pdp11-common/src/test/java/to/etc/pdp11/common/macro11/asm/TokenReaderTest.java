package to.etc.pdp11.common.macro11.asm;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token reader: lines, tokens, lookahead, and the text that is not tokens.
 */
class TokenReaderTest {
	private static TokenReader reader(String text) {
		TokenReader r = new TokenReader(new StringReader(text), "t.mac", null);
		assertTrue(r.nextLine());
		return r;
	}

	@Test
	void tokensOfAStatement() {
		TokenReader r = reader("L1:\tMOV\t#10.,-(SP) ; comment");
		assertEquals(TokenKind.SYMBOL, r.next().kind());
		assertEquals(TokenKind.COLON, r.next().kind());
		Token mov = r.next();
		assertEquals("MOV", mov.text());
		assertTrue(mov.spaceBefore());
		assertEquals(TokenKind.HASH, r.next().kind());
		assertEquals("10.", r.next().text());
		assertEquals(TokenKind.COMMA, r.next().kind());
		assertEquals(TokenKind.MINUS, r.next().kind());
		assertEquals(TokenKind.LEFT_PAREN, r.next().kind());
		assertEquals("SP", r.next().text());
		assertEquals(TokenKind.RIGHT_PAREN, r.next().kind());
		assertTrue(r.next().isEndOfLine());
		assertTrue(r.next().isEndOfLine(), "the end stays the end");
	}

	@Test
	void numbersLocalLabelsAndCharacters() {
		TokenReader r = reader("10$ 8.5 'A \"BC ^O");
		assertEquals(TokenKind.LOCAL_LABEL, r.next().kind());
		assertEquals("8.5", r.next().text());
		assertEquals('A', r.next().value());
		assertEquals('B' | ('C' << 8), r.next().value());
		Token c = r.next();
		assertEquals(TokenKind.CIRCUMFLEX, c.kind());
		assertEquals("O", c.text());
	}

	@Test
	void lookaheadAndPushBack() {
		TokenReader r = reader("A B C");
		assertEquals("C", r.peek(2).text());
		assertEquals("A", r.peek().text());
		Token a = r.next();
		r.pushBack(a);
		assertEquals("A", r.next().text());
		assertEquals("B", r.next().text());
	}

	@Test
	void rawTextStartsAtTheFirstTokenNotTaken() {
		//-- Peeking to decide must not lose what was peeked at.
		TokenReader r = reader("\t.ASCII\t/a;b/ <15>");
		assertEquals(".ASCII", r.next().text());
		r.peek(1);
		TokenReader.Delimited d = r.readDelimited();
		assertEquals("a;b", d.text());
		assertTrue(d.terminated());
		assertEquals(TokenKind.LEFT_ANGLE, r.next().kind());
	}

	@Test
	void macroArguments() {
		TokenReader r = reader("<a, b>, ^/c d/ e,,f");
		assertEquals("a, b", r.readArgument().text());
		r.skipArgumentSeparator();
		assertEquals("c d", r.readArgument().text());
		r.skipArgumentSeparator();
		assertEquals("e", r.readArgument().text());
		r.skipArgumentSeparator();
		assertEquals("", r.readArgument().text());
		r.skipArgumentSeparator();
		assertEquals("f", r.readArgument().text());
		assertEquals(null, r.readArgument());
	}

	@Test
	void nestedBrackets() {
		assertEquals("a<b>c", reader("<a<b>c>").readArgument().text());
	}

	@Test
	void linesCarriageReturnsAndFormFeeds() {
		TokenReader r = new TokenReader(new StringReader("a\r\nb\fc\nd"), "t.mac", null);
		assertTrue(r.nextLine());
		assertEquals("a", r.lineText());
		assertEquals(1, r.lineNumber());
		assertTrue(r.nextLine());
		assertEquals("b", r.lineText());
		assertEquals(2, r.lineNumber());
		assertTrue(r.nextLine());
		assertEquals("c", r.lineText());
		assertEquals(2, r.lineNumber(), "a form feed is a new page, not a new line");
		assertTrue(r.nextLine());
		assertEquals("d", r.lineText());
		assertEquals(3, r.lineNumber(), "the last line has no newline and still counts");
		assertFalse(r.nextLine());
	}
}
