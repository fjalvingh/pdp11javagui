package to.etc.pdp11.core.fake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.util.Scheduler;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ODT fake, tested against the behaviour Steve Maddison measured on a real PDP-11/23 in
 * September 2008 and recorded in {@code FakePDP11ODTU.pas:86-130}.
 *
 * <p>PLAN.md phase 3 is done when "a fake responds correctly to hand-written byte sequences in
 * tests", and those notes are the specification: there is no SimH to ask about ODT, so a
 * transcript from the hardware is the only oracle this console has. Each test below is named
 * after the case it covers.</p>
 */
class FakePdp11OdtTest {
	/** The escape the K1630 sends before a halt report. */
	private static final char ESC = 27;

	private Scheduler.Manual m_scheduler;

	private FakePdp11Odt m_odt;

	@BeforeEach
	void setUp() {
		m_scheduler = new Scheduler.Manual();
		m_odt = new FakePdp11Odt(MemoryAddressType.PHYSICAL22, m_scheduler, new Random(42));
		m_odt.powerOn();
		m_odt.takeOutput();                                 // discard the power-on banner
	}

	/** Type a string at the console and return everything it printed back. */
	private String type(String keys) {
		for(int i = 0; i < keys.length(); i++) {
			m_odt.serialWriteByte(keys.charAt(i));
		}
		return m_odt.takeOutput();
	}

	private static Address addr(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	private static FakePdp11Odt k1630(Scheduler scheduler) {
		FakePdp11Odt k = new FakePdp11Odt(MemoryAddressType.PHYSICAL22, scheduler, new Random(1),
			FakePdp11Odt.OdtDialect.K1630);
		k.powerOn();
		return k;
	}

	// ---------------------------------------------------------------------------------------
	// The power-on and halt reports
	// ---------------------------------------------------------------------------------------

	/**
	 * "Format is correct, but I get 173000 instead of 000000, although I don't think it really
	 * matters at start-up." - so 173000 it is.
	 */
	@Test
	void powerOnReportsThePcAndThenAPrompt() {
		FakePdp11Odt odt = new FakePdp11Odt(MemoryAddressType.PHYSICAL22, m_scheduler, new Random(1));
		odt.powerOn();
		assertEquals("\r\n173000\r\n@", odt.takeOutput());
	}

	// ---------------------------------------------------------------------------------------
	// Opening and closing locations
	// ---------------------------------------------------------------------------------------

	@Test
	void openingALocationEchoesTheAddressAndPrintsSixDigits() {
		m_odt.setMem(addr(01000), 0123);
		assertEquals("1000/000123 ", type("1000/"));
		assertEquals(FakePdp11Odt.OdtState.OPEN, m_odt.getState());
	}

	@Test
	void carriageReturnClosesWithoutChangingAnything() {
		m_odt.setMem(addr(01000), 0123);
		type("1000/");
		//-- The CR itself is not echoed.
		assertEquals("\r\n@", type("\r"));
		assertEquals(0123, m_odt.getMem(addr(01000)));
	}

	@Test
	void typingDigitsBeforeCarriageReturnDepositsThem() {
		type("1000/");
		assertEquals("777\r\n@", type("777\r"));
		assertEquals(0777, m_odt.getMem(addr(01000)));
	}

	/**
	 * Only the last six digits of a value count - and six octal digits reach 0777777, which
	 * does not fit in a 16-bit word, so the low 16 bits are what lands.
	 */
	@Test
	void aValueLongerThanSixDigitsKeepsTheLastSixAndThenTheLowSixteenBits() {
		type("1000/");
		type("1234567\r");
		assertEquals(0234567 & 0xFFFF, m_odt.getMem(addr(01000)));
		assertEquals(034567, m_odt.getMem(addr(01000)));
	}

	@Test
	void aBareSlashReopensTheLocationMostRecentlyOpened() {
		m_odt.setMem(addr(01000), 042);
		type("1000/\r");
		assertEquals("/000042 ", type("/"));
	}

	// ---------------------------------------------------------------------------------------
	// "odd addresses? They're rejected, and your response is correct too: @1001/? @"
	// ---------------------------------------------------------------------------------------

	@Test
	void anOddAddressIsRejectedWithAQuestionMarkOnTheSameLine() {
		assertEquals("1001/?\r\n@", type("1001/"));
	}

	// ---------------------------------------------------------------------------------------
	// "For non-existent addresses, you get zero back straight away. Writing also appears to
	//  succeed immediately but has no effect (i.e. you still get zero if you read it back)."
	// ---------------------------------------------------------------------------------------

	/** A 22-bit fake is fitted with 1 MB, so 04000000 is the first address that is not there. */
	private static final String MISSING = "4000000";

	@Test
	void nonexistentMemoryReadsBackAsZeroWithNoError() {
		assertFalse(m_odt.isImplemented(addr(04000000)));
		assertEquals(MISSING + "/000000 ", type(MISSING + "/"));
	}

	@Test
	void writingToNonexistentMemoryIsAcceptedAndDiscarded() {
		type(MISSING + "/");
		assertEquals("777\r\n@", type("777\r"));
		//-- Read it back: still zero, and no error was reported for the write.
		assertEquals(MISSING + "/000000 ", type(MISSING + "/"));
	}

	// ---------------------------------------------------------------------------------------
	// "@123x?  So the 'x' is echoed, then the ? on the same line. You then get a new prompt."
	// ---------------------------------------------------------------------------------------

	@Test
	void anIllegalCharacterInAnAddressIsEchoedThenQueriedImmediately() {
		//-- Note the "?" arrives at the "x", without waiting for a "/".
		assertEquals("123x?\r\n@", type("123x"));
	}

	@Test
	void eachFollowingIllegalCharacterGetsItsOwnQueryAndPrompt() {
		//-- "if you carried on you'd get: @y? @z? @"
		type("123x");
		assertEquals("y?\r\n@", type("y"));
		assertEquals("z?\r\n@", type("z"));
	}

	// ---------------------------------------------------------------------------------------
	// "@1000/000000 a?   The 'a' is echoed and the ? follows immediately after."
	// ---------------------------------------------------------------------------------------

	@Test
	void anIllegalCharacterInDataIsEchoedThenQueried() {
		assertEquals("1000/000000 ", type("1000/"));
		assertEquals("a?\r\n@", type("a"));
	}

	// ---------------------------------------------------------------------------------------
	// "On the PDP I get the lines one after another, and subsequent lines have no @ at all."
	// ---------------------------------------------------------------------------------------

	@Test
	void lineFeedOpensTheNextLocationWithNoPromptOnTheContinuationLines() {
		m_odt.setMem(addr(01000), 0123);
		m_odt.setMem(addr(01002), 0456);
		m_odt.setMem(addr(01004), 0701);

		assertEquals("1000/000123 ", type("1000/"));
		//-- The LF is not echoed; the next line carries no "@". The address the console prints
		//-- for itself is zero-padded to the machine's full width - eight digits at 22 bits -
		//-- which is what Addr2OctalStr does (AddressU.pas:117-120). See the note on
		//-- theAutoAdvanceAddressWidthIsUnverified below.
		assertEquals("\r\n00001002/000456 ", type("\n"));
		assertEquals("\r\n00001004/000701 ", type("\n"));
	}

	/**
	 * <b>Open question, inherited rather than introduced.</b> The fake prints the auto-advanced
	 * address padded to the machine's full width, because {@code doOpenLocation} builds it with
	 * {@code Addr2OctalStr} - six digits at 16 or 18 bits, eight at 22. Steve Maddison's
	 * transcript from a real 11/23 shows a shorter form:
	 *
	 * <pre>
	 * &#64;1000/000123 &lt;LF&gt;
	 * 1002/000456 &lt;LF&gt;
	 * </pre>
	 *
	 * <p>which is four digits, not six. It may be that he trimmed it when typing the mail, or
	 * that ODT really does print a minimal-width address. Nothing here can settle it, and the
	 * Pascal has always done it this way, so the behaviour is kept and the question recorded:
	 * phase 4's ODT scanner has to accept whatever real hardware sends, and this is the place
	 * to check before relying on the padding.</p>
	 */
	@Test
	void theAutoAdvanceAddressWidthIsUnverified() {
		FakePdp11Odt narrow = new FakePdp11Odt(MemoryAddressType.PHYSICAL18, m_scheduler, new Random(1));
		narrow.powerOn();
		narrow.takeOutput();
		for(char c : "1000/".toCharArray()) {
			narrow.serialWriteByte(c);
		}
		narrow.takeOutput();
		narrow.serialWriteByte('\n');
		//-- Six digits on an 18-bit machine, which is what Steve's 11/23 was.
		assertEquals("\r\n001002/000000 ", narrow.takeOutput());
	}

	@Test
	void lineFeedAfterTypingAValueDepositsItAndMovesOn() {
		type("1000/");
		assertEquals("777\r\n00001002/000000 ", type("777\n"));
		assertEquals(0777, m_odt.getMem(addr(01000)));
	}

	/** The PSW has no next location, so LF there gives an ordinary prompt. */
	@Test
	void lineFeedOnTheProcessorStatusWordStopsTheAutoAdvance() {
		type("RS/");
		assertEquals("\r\n@", type("\n"));
	}

	// ---------------------------------------------------------------------------------------
	// Registers
	// ---------------------------------------------------------------------------------------

	@Test
	void registersAreReachedByNameAndAdvanceRoundToR0() {
		m_odt.setMem(addr(m_odt.getIopageBase() + 017700), 011);
		m_odt.setMem(addr(m_odt.getIopageBase() + 017701), 022);
		assertEquals("R0/000011 ", type("R0/"));
		assertEquals("\r\nR1/000022 ", type("\n"));

		type("\r");
		//-- R7 is the program counter, which reset left pointing at the boot ROM.
		assertEquals("R7/173000 ", type("R7/"));
		//-- R7 is followed by R0, not by nothing.
		assertEquals("\r\nR0/000011 ", type("\n"));
	}

	/**
	 * Registers cannot be opened by their octal address - chapter 3.5.1 - but the PSW,
	 * uniquely, can.
	 */
	@Test
	void registersRefuseTheirOctalAddressesButThePswAcceptsIts() {
		assertEquals("17777700/?\r\n@", type("17777700/"));
		m_odt.setMem(addr(m_odt.getIopageBase() + 017776), 0340);
		assertEquals("17777776/000340 ", type("17777776/"));
	}

	@Test
	void thePswAnswersToRsAndToDollarS() {
		m_odt.setMem(addr(m_odt.getIopageBase() + 017776), 0340);
		assertEquals("RS/000340 ", type("RS/"));
		type("\r");
		assertEquals("$S/000340 ", type("$S/"));
	}

	// ---------------------------------------------------------------------------------------
	// "@1000G   There's no more output, not even a CR of LF after the 'G'."
	// ---------------------------------------------------------------------------------------

	@Test
	void goWithTheRunSwitchClearLoadsThePcAndReportsAHalt() {
		//-- With RUN clear, G behaves as a reset-and-halt, so the halt report follows.
		assertEquals("1000G\r\n001000\r\n@", type("1000G"));
		assertEquals(01000, m_odt.getMem(m_odt.getProgramCounterAddr()));
	}

	@Test
	void goWithTheRunSwitchSetPrintsNothingFurtherUntilTheProgramHalts() {
		m_odt.setRunMode(true);
		//-- "There's no more output, not even a CR or LF after the G."
		assertEquals("1000G", type("1000G"));
		assertTrue(m_odt.isRunning());
		assertTrue(m_scheduler.hasPending());
		//-- One to five seconds, per FakePDP11GenericU.pas:196.
		assertTrue(m_scheduler.lastDelayMillis() >= 1000 && m_scheduler.lastDelayMillis() < 5000);

		m_scheduler.fireAll();
		assertFalse(m_odt.isRunning());
		String halt = m_odt.takeOutput();
		assertTrue(halt.startsWith("\r\n") && halt.endsWith("\r\n@"), halt);
		//-- The halt lands within 64 bytes of where it started, always on an even address.
		long pc = m_odt.getMem(m_odt.getProgramCounterAddr());
		assertTrue(pc > 01000 && pc <= 01000 + 0077 + 2, "pc was 0" + Long.toOctalString(pc));
		assertEquals(0, pc & 1);
	}

	/** With the CPU pretending to run there is no console at all. */
	@Test
	void keystrokesAreIgnoredWhileTheProgramRuns() {
		m_odt.setRunMode(true);
		type("1000G");
		assertEquals("", type("1000/"));
	}

	@Test
	void proceedWithTheRunSwitchClearSingleStepsAndReports() {
		type("1000G");                                      // load the PC, stay halted
		m_odt.takeOutput();
		assertEquals("P\r\n001002\r\n@", type("P"));
		assertEquals(01002, m_odt.getMem(m_odt.getProgramCounterAddr()));
	}

	@Test
	void goNeedsAnAddressInFrontOfIt() {
		assertEquals("G?\r\n@", type("G"));
	}

	// ---------------------------------------------------------------------------------------
	// The K1630 dialect
	// ---------------------------------------------------------------------------------------

	@Test
	void theK1630PromptsWithAtSpaceAndQueriesWithSpaceQuestion() {
		FakePdp11Odt k = k1630(m_scheduler);
		assertEquals(ESC + "S\r\n173000\n\r@ ", k.takeOutput());

		for(char c : "1001/".toCharArray()) {
			k.serialWriteByte(c);
		}
		assertEquals("1001/ ?\n\r@ ", k.takeOutput());
	}

	/** The K1630 allows an "A" suffix on an address, meaning absolute rather than virtual. */
	@Test
	void theK1630AcceptsAnASuffixOnAnAddress() {
		FakePdp11Odt k = k1630(m_scheduler);
		k.setMem(addr(01000), 042);
		k.takeOutput();
		for(char c : "1000A/".toCharArray()) {
			k.serialWriteByte(c);
		}
		assertEquals("1000A/000042 ", k.takeOutput());
	}

	@Test
	void theK1630SendsEscapeSBeforeAHaltReport() {
		FakePdp11Odt k = k1630(m_scheduler);
		assertTrue(k.takeOutput().startsWith(ESC + "S"));
	}

	// ---------------------------------------------------------------------------------------
	// Memory model
	// ---------------------------------------------------------------------------------------

	@Test
	void aWordIsTheSameWordWhicheverOfItsTwoByteAddressesNamesIt() {
		m_odt.setMem(addr(01000), 0123456);
		assertEquals(0123456, m_odt.getMem(addr(01000)));
		//-- The Pascal stores a separate word at every byte address; indexing by addr>>1
		//-- means 01000 and 01001 are one location, as they are on the hardware.
		assertEquals(0123456, m_odt.getMem(addr(01001)));
	}

	@Test
	void memoryIsHalfPopulatedSoThereIsSomethingMissingToAimAt() {
		assertEquals(0x100000L, m_odt.getPhysicalMemorySize());
		assertTrue(m_odt.isImplemented(addr(0x100000 - 2)));
		assertFalse(m_odt.isImplemented(addr(0x100000)));
		//-- ...but the I/O page above it is there.
		assertTrue(m_odt.isImplemented(addr(m_odt.getIopageBase() + 017776)));
	}

	@Test
	void registersAreEightDistinctLocationsNotFourAliasedPairs() {
		for(int i = 0; i < 8; i++) {
			m_odt.setMem(addr(m_odt.getIopageBase() + 017700 + i), 0100 + i);
		}
		for(int i = 0; i < 8; i++) {
			assertEquals(0100 + i, m_odt.getMem(addr(m_odt.getIopageBase() + 017700 + i)),
				"R" + i + " should not be aliased onto its neighbour");
		}
	}
}
