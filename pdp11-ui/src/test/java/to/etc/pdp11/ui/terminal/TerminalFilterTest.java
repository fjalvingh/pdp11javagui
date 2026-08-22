package to.etc.pdp11.ui.terminal;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.console.TerminalProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pre-filter that sits in front of the terminal.
 *
 * <p>The case that makes it necessary is the last one here: the same bytes, read by two consoles'
 * rules, have to come out differently, and getting it wrong is not subtle - a whole transcript
 * collapses onto one line.</p>
 */
class TerminalFilterTest {
	private static final TerminalProfile ODT = TerminalProfile.of(false, true);

	private static final TerminalProfile PDP1144 = TerminalProfile.of(true, false);

	private static final TerminalProfile SIMH = new TerminalProfile(true, true, (char) 8, 8);

	@Test
	void twoConsolesReadTheSameBytesDifferentlyAndBothAreRight() {
		//-- ODT sends CR and LF and means the LF; the 11/44's console sends a lone CR and means
		//-- it. A terminal that assumes either one breaks the other.
		String odtStyle = "@1000/000000 \r\n@";
		assertEquals("@1000/000000 \n@", new TerminalFilter(ODT).filter(odtStyle));

		String pdp1144Style = ">>>\r00001000 123456\r>>>";
		assertEquals(">>>\n00001000 123456\n>>>", new TerminalFilter(PDP1144).filter(pdp1144Style));
	}

	@Test
	void aLoneCrThroughOdtsRulesWouldLoseEveryLine() {
		//-- What the bug looks like if the profile is ignored: the 11/44's stream read as ODT's
		//-- comes out as one line, because its CRs are dropped and it never sends an LF.
		assertEquals(">>>00001000 123456>>>", new TerminalFilter(ODT).filter(">>>\r00001000 123456\r>>>"));
	}

	@Test
	void chunkBoundariesDoNotMatter() {
		//-- The reader thread hands over whatever one read happened to return.
		TerminalFilter whole = new TerminalFilter(SIMH);
		TerminalFilter piecemeal = new TerminalFilter(SIMH);
		String text = "sim> E 1000\r\n1000:\t123456\r\nsim> ";
		StringBuilder byChar = new StringBuilder();
		for(int i = 0; i < text.length(); i++) {
			byChar.append(piecemeal.filter(text.substring(i, i + 1)));
		}
		assertEquals(whole.filter(text), byChar.toString());
	}

	@Test
	void aCarriageReturnAndLineFeedTogetherAreOneLineEnding() {
		//-- SimH sets both flags - its Enter sends CR and telnet breaks lines with LF - and sends
		//-- them together. Taking each at face value double-spaces the whole transcript, which is
		//-- what the Pascal does. A lone CR still ends a line, which is what the flag is for.
		assertEquals("one\ntwo", new TerminalFilter(SIMH).filter("one\r\ntwo"));
		assertEquals("one\ntwo", new TerminalFilter(SIMH).filter("one\rtwo"));
		assertEquals("one\ntwo", new TerminalFilter(SIMH).filter("one\ntwo"));
		//-- And two real blank lines are still two.
		assertEquals("one\n\ntwo", new TerminalFilter(SIMH).filter("one\r\n\r\ntwo"));
	}

	@Test
	void thePairIsStillCollapsedWhenItArrivesInTwoChunks() {
		TerminalFilter f = new TerminalFilter(SIMH);
		assertEquals("one\n", f.filter("one\r"));
		assertEquals("two", f.filter("\ntwo"));
	}

	@Test
	void tabsAreExpandedAgainstTheRunningColumn() {
		//-- SimH answers an examine as "1000:\t123456", so getting this wrong misaligns every
		//-- memory dump. Column 5 with stops of 8 means three spaces.
		assertEquals("1000:   123456", new TerminalFilter(SIMH).filter("1000:\t123456"));
		//-- And the column restarts on every line rather than running on.
		assertEquals("1000:   1\n2:      3", new TerminalFilter(SIMH).filter("1000:\t1\r\n2:\t3"));
	}

	@Test
	void aConsoleWithNoTabStopsGetsItsTabsLeftAlone() {
		assertEquals("a\tb", new TerminalFilter(ODT).filter("a\tb"));
	}

	@Test
	void backspaceBecomesAnEraseOnlyForConsolesThatHaveOne() {
		//-- SimH has a working backspace; the printing terminals do not, because ink does not
		//-- come off paper. There the character is simply not printable and goes nowhere.
		assertEquals("ab" + TerminalFilter.ERASE, new TerminalFilter(SIMH).filter("ab\b"));
		assertEquals("ab", new TerminalFilter(ODT).filter("ab\b"));
	}

	@Test
	void fillNulsAreDroppedBecauseTheyAreNotCharacters() {
		//-- The M9301's prompt ends with one and the 11/44 V3.40C sends five on power-up. They
		//-- mean "the line is settling".
		assertEquals("$", new TerminalFilter(ODT).filter("$\0"));
		assertEquals("(Console V3.40C)", new TerminalFilter(PDP1144).filter("\0\0\0\0\0(Console V3.40C)"));
	}

	@Test
	void otherControlCharactersAreDroppedRatherThanDrawnAsBoxes() {
		//-- The K1630 prefixes a halt report with ESC S; the ESC is not something to display.
		assertEquals("S\n001000", new TerminalFilter(ODT).filter("S\r\n001000"));
	}

	@Test
	void changingTheProfileStartsANewLine() {
		//-- Reconnecting to a different machine: the old column means nothing.
		TerminalFilter f = new TerminalFilter(SIMH);
		f.filter("12345");
		assertEquals(5, f.getColumn());
		f.setProfile(ODT);
		assertEquals(0, f.getColumn());
	}
}
