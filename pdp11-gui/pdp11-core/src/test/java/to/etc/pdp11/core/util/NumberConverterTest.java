package to.etc.pdp11.core.util;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.util.NumberConverter.Base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number conversions behind the converter window.
 *
 * <p>All of this is loose functions inside a Delphi form in the original, where the only way to
 * check that the binary grouping lines up with the octal digits above it is to type into the
 * window and count the columns.</p>
 */
class NumberConverterTest {
	// ---------------------------------------------------------------------------------------
	// Digits
	// ---------------------------------------------------------------------------------------

	@Test
	void eachBaseKnowsItsOwnDigits() {
		assertTrue(NumberConverter.isValidDigit(Base.OCTAL, '7'));
		assertFalse(NumberConverter.isValidDigit(Base.OCTAL, '8'), "8 is not an octal digit");
		assertTrue(NumberConverter.isValidDigit(Base.DECIMAL, '9'));
		assertFalse(NumberConverter.isValidDigit(Base.DECIMAL, 'a'));
		assertTrue(NumberConverter.isValidDigit(Base.HEX, 'f'));
		assertTrue(NumberConverter.isValidDigit(Base.HEX, 'F'), "either case");
		assertFalse(NumberConverter.isValidDigit(Base.HEX, 'g'));
	}

	@Test
	void whatIsPastedInKeepsOnlyTheDigits() {
		//-- The Pascal has this function and calls it from nowhere: both call sites are commented
		//-- out, which is why pasting "1,234" into its decimal field raises inside StrToInt64.
		assertEquals("1234", NumberConverter.stripInvalidDigits(Base.DECIMAL, "1,234"));
		assertEquals("177", NumberConverter.stripInvalidDigits(Base.OCTAL, " 177 "));
		assertEquals("17", NumberConverter.stripInvalidDigits(Base.OCTAL, "1.8.7"), "8 is not octal");
		//-- Only what is not a digit goes: the leading zero of "0x1F" is a hex digit and stays.
		assertEquals("01F", NumberConverter.stripInvalidDigits(Base.HEX, "0x1F"));
		assertEquals("", NumberConverter.stripInvalidDigits(Base.OCTAL, null));
	}

	// ---------------------------------------------------------------------------------------
	// Parsing
	// ---------------------------------------------------------------------------------------

	@Test
	void aNumberIsReadInItsOwnBase() {
		assertEquals(0777, NumberConverter.parse(Base.OCTAL, "777", 16));
		assertEquals(511, NumberConverter.parse(Base.DECIMAL, "511", 16));
		assertEquals(0x1FF, NumberConverter.parse(Base.HEX, "1ff", 16));
		assertEquals(0x1FF, NumberConverter.parse(Base.HEX, "1FF", 16));
	}

	@Test
	void anEmptyFieldIsZeroRatherThanAComplaint() {
		//-- A field somebody has just cleared is on the way to being something else.
		assertEquals(0, NumberConverter.parse(Base.OCTAL, "", 16));
		assertEquals(0, NumberConverter.parse(Base.DECIMAL, "   ", 16));
	}

	@Test
	void aDigitFromTheWrongBaseIsRefused() {
		NumberFormatException x = assertThrows(NumberFormatException.class,
			() -> NumberConverter.parse(Base.OCTAL, "18", 16));
		assertTrue(x.getMessage().contains("'8'"), x.getMessage());
	}

	@Test
	void aValueTooWideForTheChosenWidthIsRefused() {
		assertEquals(0177777, NumberConverter.parse(Base.OCTAL, "177777", 16));
		NumberFormatException x = assertThrows(NumberFormatException.class,
			() -> NumberConverter.parse(Base.OCTAL, "200000", 16));
		assertTrue(x.getMessage().contains("16 bits"), x.getMessage());
		//-- The same number is fine at an address width.
		assertEquals(0200000, NumberConverter.parse(Base.OCTAL, "200000", 18));
	}

	@Test
	void somethingFarTooLongIsAnOverflowRatherThanACrash() {
		assertThrows(NumberFormatException.class,
			() -> NumberConverter.parse(Base.DECIMAL, "99999999999999999999999", 32));
	}

	@Test
	void everyWidthOfferedRoundTripsItsLargestValue() {
		for(int bits : NumberConverter.WIDTHS) {
			long max = NumberConverter.mask(bits);
			for(Base base : Base.values()) {
				assertEquals(max, NumberConverter.parse(base, NumberConverter.format(base, max), bits),
					base + " at " + bits + " bits");
			}
			assertFalse(NumberConverter.fits(max + 1, bits), bits + " bits should stop at " + max);
		}
	}

	// ---------------------------------------------------------------------------------------
	// Formatting
	// ---------------------------------------------------------------------------------------

	@Test
	void aNumberIsWrittenAsSomebodyWouldTypeIt() {
		assertEquals("777", NumberConverter.format(Base.OCTAL, 0777));
		assertEquals("1FF", NumberConverter.format(Base.HEX, 0x1FF), "upper case, as the original writes it");
		assertEquals("511", NumberConverter.format(Base.DECIMAL, 511));
	}

	@Test
	void paddedIsHowAPdp11PrintsIt() {
		assertEquals("000123", NumberConverter.formatPadded(Base.OCTAL, 0123, 16));
		assertEquals("00000123", NumberConverter.formatPadded(Base.OCTAL, 0123, 22));
		assertEquals("0053", NumberConverter.formatPadded(Base.HEX, 0x53, 16));
		assertEquals("00083", NumberConverter.formatPadded(Base.DECIMAL, 83, 16));
	}

	@Test
	void theDigitCountsAreTheOnesThePdp11Uses() {
		//-- Six octal digits for a 16-bit word and for an 18-bit address alike; eight for 22.
		assertEquals(6, NumberConverter.digitsFor(Base.OCTAL, 16));
		assertEquals(6, NumberConverter.digitsFor(Base.OCTAL, 18));
		assertEquals(8, NumberConverter.digitsFor(Base.OCTAL, 22));
		assertEquals(4, NumberConverter.digitsFor(Base.HEX, 16));
		assertEquals(5, NumberConverter.digitsFor(Base.DECIMAL, 16), "65535");
	}

	// ---------------------------------------------------------------------------------------
	// Binary
	// ---------------------------------------------------------------------------------------

	@Test
	void binaryIsPaddedToTheWidth() {
		assertEquals("0000000000000001", NumberConverter.binary(1, 16, 0));
		assertEquals("00000001", NumberConverter.binary(1, 8, 0));
	}

	@Test
	void groupsOfFourLineUpWithTheHexDigits() {
		//-- 0xBEEF: each group of four is one hex digit, left to right.
		assertEquals("1011 1110 1110 1111", NumberConverter.binary(0xBEEF, 16, 4));
		assertEquals("1011 1110 1110 1111", NumberConverter.binary(Base.HEX, 0xBEEF, 16));
	}

	@Test
	void groupsOfThreeLineUpWithTheOctalDigitsAndTheLeftmostGroupIsShort() {
		//-- 0177777 is six octal digits, and the first of them is a single bit: 1 777 77 in
		//-- octal is 1 111 111 111 111 111 in threes. Grouping from the right is what makes the
		//-- columns line up with the digits above them.
		assertEquals("1 111 111 111 111 111", NumberConverter.binary(0177777, 16, 3));
		assertEquals("0 000 000 000 000 001", NumberConverter.binary(1, 16, 3));
		//-- 22 bits is not a multiple of three either: 22 = 7*3 + 1.
		assertEquals(8, NumberConverter.binary(0, 22, 3).split(" ").length);
	}

	@Test
	void decimalGetsNoGroupingBecauseNoneWouldLineUp() {
		assertFalse(Base.DECIMAL.groupsBinary());
		assertEquals("0000000000000001", NumberConverter.binary(Base.DECIMAL, 1, 16));
	}

	@Test
	void bitsAboveTheWidthAreNotShown() {
		assertEquals("1111 1111", NumberConverter.binary(0xFFFF, 8, 4), "masked to the width");
	}

	// ---------------------------------------------------------------------------------------
	// Signed
	// ---------------------------------------------------------------------------------------

	@Test
	void theSameBitsReadAsSigned() {
		//-- The commonest thing on this machine: a word holding a small negative number.
		assertEquals(-1, NumberConverter.signed(0177777, 16));
		assertEquals(-2, NumberConverter.signed(0177776, 16));
		assertEquals(-32768, NumberConverter.signed(0100000, 16));
		assertEquals(32767, NumberConverter.signed(077777, 16));
		assertEquals(0, NumberConverter.signed(0, 16));
		assertEquals(-1, NumberConverter.signed(0377, 8));
		assertEquals(-1L, NumberConverter.signed(0xFFFFFFFFL, 32));
	}

	@Test
	void aWidthOutsideWhatANumberCanBeIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> NumberConverter.mask(0));
		assertThrows(IllegalArgumentException.class, () -> NumberConverter.mask(64));
		assertThrows(IllegalArgumentException.class, () -> NumberConverter.binary(1, 16, -1));
	}
}
