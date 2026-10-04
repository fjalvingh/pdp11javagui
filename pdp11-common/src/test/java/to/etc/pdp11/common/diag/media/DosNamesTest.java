package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DosNamesTest {
	@Test
	void aDirectoryEntryNameDecodes() {
		//-- Z=26 R=18 L=12: 26*1600 + 18*40 + 12 = 42332 = 0122534; G=7 E=5 0=30: 026246; BIC: 06753.
		assertEquals("ZRLGE0.BIC", DosNames.fileName(0122534, 026246, 06753));
	}

	@Test
	void paddingIsTakenOffBothParts() {
		assertEquals("DD.SYS", DosNames.fileName(TestMedia.rad50("DD"), 0, TestMedia.rad50("SYS")));
	}

	@Test
	void code29IsAPercentSignAsDosHasIt() {
		assertEquals("%", DosNames.decode(29 * 1600).substring(0, 1));
	}

	@Test
	void aWordAboveTheRadix50RangeDoesNotPassForANameCharacter() {
		String s = DosNames.fileName(0175000, 0, 0);
		assertFalse(DosNames.isPlausibleName(s), "0175000 is 64000, beyond 40^3");
	}

	@Test
	void plausibleNamesAreLettersAndDigitsPaddedOnTheRight() {
		assertTrue(DosNames.isPlausibleName("ZRLGE0.BIC"));
		assertTrue(DosNames.isPlausibleName("DD.SYS"));
		assertTrue(DosNames.isPlausibleName("TEST."));
		assertFalse(DosNames.isPlausibleName(".SYS"), "no name");
		assertFalse(DosNames.isPlausibleName("A BZ.H6"), "an embedded blank is code read as a name");
		assertFalse(DosNames.isPlausibleName("K%JKC0.KBP"));
		assertFalse(DosNames.isPlausibleName("FB2.U6$"));
	}

	@Test
	void datesAreYearsSince1970TimesAThousandPlusTheDay() {
		assertEquals(LocalDate.of(1989, 3, 1), DosNames.date(19 * 1000 + 60));
		assertEquals(LocalDate.of(1999, 12, 31), DosNames.date(29 * 1000 + 365), "the 31-DEC-99 XXDP stamps chain files with");
	}

	@Test
	void theContiguousFlagIsNotPartOfTheDate() {
		assertEquals(LocalDate.of(1977, 3, 29), DosNames.date(0100000 | 7 * 1000 + 88));
	}

	@Test
	void zeroAndImpossibleDaysAreNoDate() {
		assertNull(DosNames.date(0));
		assertNull(DosNames.date(19 * 1000 + 366), "1989 was not a leap year");
		assertNull(DosNames.date(19 * 1000 + 999));
	}
}
