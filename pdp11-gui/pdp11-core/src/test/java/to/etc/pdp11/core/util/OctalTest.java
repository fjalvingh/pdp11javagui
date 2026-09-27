package to.etc.pdp11.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OctalTest {
	@Test
	void formatsPaddedToWidth() {
		assertEquals("000000", Octal.format(0, 6));
		assertEquals("177570", Octal.format(0177570L, 6));
		assertEquals("17777570", Octal.format(017777570L, 8));
	}

	@Test
	void doesNotTruncateValuesWiderThanTheRequestedWidth() {
		assertEquals("17777570", Octal.format(017777570L, 6));
	}

	@Test
	void wordMasksToSixteenBits() {
		assertEquals("177777", Octal.word(-1));
		assertEquals("000001", Octal.word(0x10001));
	}

	@Test
	void parsesOctalIgnoringSurroundingSpace() {
		assertEquals(0177570L, Octal.parse("177570"));
		assertEquals(0177570L, Octal.parse("  177570 "));
		assertEquals(0L, Octal.parse("0"));
	}

	@Test
	void rejectsNonOctalText() {
		assertThrows(NumberFormatException.class, () -> Octal.parse("178"));
		assertThrows(NumberFormatException.class, () -> Octal.parse("17 570"));
		assertThrows(NumberFormatException.class, () -> Octal.parse(""));
		assertThrows(NumberFormatException.class, () -> Octal.parse("  "));
		assertThrows(NumberFormatException.class, () -> Octal.parse(null));
		assertThrows(NumberFormatException.class, () -> Octal.parse("-10"));
	}

	@Test
	void parseOrFallsBackInsteadOfThrowing() {
		assertEquals(8L, Octal.parseOr("10", -1));
		assertEquals(-1L, Octal.parseOr("nonsense", -1));
	}
}
