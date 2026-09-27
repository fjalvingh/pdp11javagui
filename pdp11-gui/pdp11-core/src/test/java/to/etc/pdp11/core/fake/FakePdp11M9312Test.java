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
 * The M9312 boot ROM's console emulator, and the M9301's.
 *
 * <p>This is the feeblest console PDP11GUI supports and the one that punishes a driver hardest:
 * there is no error recovery at all - an odd address or a bus timeout stops the emulator, and on
 * a real machine only the front panel gets it back. Everything here is written out in full
 * because the driver that comes next has to match it exactly.</p>
 */
class FakePdp11M9312Test {
	/** One line feed and thirteen carriage returns: the ROM winding a hard-copy carriage back. */
	private static final String LN = "\n" + "\r".repeat(13);

	private FakePdp11M9312 m_fake;

	private Scheduler.Manual m_clock;

	@BeforeEach
	void setUp() {
		m_clock = new Scheduler.Manual();
		m_fake = new FakePdp11M9312(m_clock, new Random(1));
		m_fake.powerOn();
	}

	private static Address addr(long v) {
		return Address.of(MemoryAddressType.PHYSICAL16, v);
	}

	private static final String ESC = String.valueOf((char) 0x1B);

	private static final String RUBOUT = String.valueOf((char) 0x7F);

	/** Type characters, returning everything printed - echo included, since it matters here. */
	private String type(String s) {
		m_fake.takeOutput();
		for(int i = 0; i < s.length(); i++) {
			m_fake.serialWriteByte(s.charAt(i));
		}
		return m_fake.takeOutput();
	}

	/** The prompt, with the four registers in front of it. */
	private static String dump(String loadedAddress) {
		return LN + "000000 173400 165212 " + loadedAddress + " " + LN + "@";
	}

	@Test
	void powerOnDumpsFourRegistersAndPrompts() {
		//-- The four are the console emulator's own, not the program's, and only the last one
		//-- means anything: it is the address last loaded, which after a reset is where the ROM
		//-- itself lives.
		assertEquals(dump("165212"), m_fake.takeOutput());
		assertEquals(0165212, m_fake.getLoadedAddress().val());
		assertEquals(FakePdp11M9312.State.PROMPT, m_fake.getState());
	}

	@Test
	void theFirstCharacterAfterAPromptIsAlwaysAccepted() {
		//-- Whatever it is. The ROM validates on the second character, not the first.
		assertEquals("Z", type("Z"));
	}

	@Test
	void theSecondCharacterDecidesWhetherTheLineCanStillBecomeSomething() {
		//-- "ZZ" can never become a command or a boot code, so the line goes there and then -
		//-- with a register dump, which is how the operator is told.
		assertEquals("Z" + "Z" + dump("165212"), type("ZZ"));
	}

	@Test
	void loadThenExamineReadsTheLocation() {
		m_fake.setMem(addr(01000), 0123456);
		assertEquals("L 1000" + LN + "@", type("L 1000\r"));
		//-- "E " examines on the space; no carriage return is wanted or needed.
		assertEquals("E " + "001000 123456 " + LN + "@", type("E "));
	}

	@Test
	void twoExaminesInARowAdvanceButAnExamineAfterADepositDoesNot() {
		m_fake.setMem(addr(01000), 011);
		m_fake.setMem(addr(01002), 022);
		type("L 1000\r");
		assertEquals("E " + "001000 000011 " + LN + "@", type("E "));
		//-- A second examine advances by two...
		assertEquals("E " + "001002 000022 " + LN + "@", type("E "));

		type("L 1000\r");
		type("D 777\r");
		//-- ...but this is the first examine after a deposit, so it stays put. Only two uses of
		//-- the same kind in a row advance.
		assertEquals("E " + "001000 000777 " + LN + "@", type("E "));
	}

	@Test
	void depositWritesWhereTheAddressWasLoaded() {
		type("L 1000\r");
		assertEquals("D 123456" + LN + "@", type("D 123456\r"));
		assertEquals(0123456, m_fake.getMem(addr(01000)));
	}

	@Test
	void anOddAddressStopsTheEmulatorDead() {
		type("L 1001\r");
		//-- Not an error message and a new prompt: the emulator is code the CPU runs, and this
		//-- halts the CPU. The bracketed note stands in for the front-panel control-boot a real
		//-- machine would need.
		assertEquals("E " + "\r\n[Emulator error \"odd address\": HALT. Reboot by pressing ESC]", type("E "));
		assertEquals(FakePdp11M9312.State.HALTED, m_fake.getState());
	}

	@Test
	void anAddressThatDoesNotAnswerStopsItToo() {
		//-- 100000 is above the 32 KB this machine has and below its I/O page at 160000.
		type("L 100000\r");
		assertTrue(type("E ").contains("BUS error"));
		assertEquals(FakePdp11M9312.State.HALTED, m_fake.getState());
	}

	@Test
	void theRegisterSpaceCannotBeReachedAtAll() {
		type("L 177700\r");
		assertTrue(type("E ").contains("GPR space"));
	}

	@Test
	void onlyEscapeGetsOutOfAHaltedEmulator() {
		type("L 1001\r");
		type("E ");
		assertEquals(FakePdp11M9312.State.HALTED, m_fake.getState());
		//-- Everything else is ignored, silently and completely.
		assertEquals("", type("L 1000\rE "));
		//-- ESC is the control-boot.
		assertEquals(dump("165212"), type(ESC));
		assertEquals(FakePdp11M9312.State.PROMPT, m_fake.getState());
	}

	@Test
	void aTwoLetterDeviceCodeIsABootCommandAndNotAMalformedDeposit() {
		//-- "DL" is boot RL0, not a deposit that lost its argument. The check has to happen on
		//-- the second character, because that is when the ROM decides.
		type("L 1000\r");
		assertEquals("DL", type("DL"), "no complaint: it could still be a boot command");
		assertEquals("", type("\r"), "a boot runs, and a running machine has no console");
		assertTrue(m_fake.isRunning());
	}

	@Test
	void aDeviceCodeThatIsNotInTheRomSetIsATypingMistake() {
		assertEquals("DC" + dump("165212"), type("DC"));
	}

	@Test
	void aBadDigitLaterInTheLineThrowsItAwayWithoutADump() {
		//-- Past the second character only octal digits are allowed, and the complaint is
		//-- quieter: a new prompt, no registers.
		assertEquals("L 19" + LN + "@", type("L 19"));
	}

	@Test
	void anErrorSendsTheLoadedAddressBackToTheRom() {
		type("L 1000\r");
		//-- The dump still shows what was loaded when the mistake was made...
		assertEquals("DC" + dump("001000"), type("DC"));
		//-- ...and only afterwards does it go back to the ROM's own address.
		assertEquals(0165212, m_fake.getLoadedAddress().val());
	}

	@Test
	void theFirstReturnIsRememberedAndTheSecondRunsTheLine() {
		//-- A bare carriage return at a prompt is collected rather than executed; it is the
		//-- second one that gets the line looked at, and an empty line is a mistake.
		assertEquals("", type("\r"));
		assertEquals(dump("165212"), type("\r"));
	}

	@Test
	void aStartedProgramThatHaltsTakesTheEmulatorWithIt() {
		type("L 1000\r");
		assertEquals("S", type("S"));
		type("\r");
		assertTrue(m_fake.isRunning());
		m_fake.takeOutput();
		m_clock.fireAll();
		assertFalse(m_fake.isRunning());
		assertEquals("\r\n[Started program halted. Reboot by pressing ESC]", m_fake.takeOutput());
		assertEquals(FakePdp11M9312.State.HALTED, m_fake.getState());
	}

	@Test
	void ruboutThrowsTheLineAwayAndPromptsWithoutADump() {
		type("L 10");
		assertEquals(LN + "@", type(RUBOUT));
	}

	// ---------------------------------------------------------------------------------------
	// The M9301
	// ---------------------------------------------------------------------------------------

	@Test
	void theM9301IsTheSameConsoleWithAPromptThatCarriesANul() {
		FakePdp11M9301 m9301 = new FakePdp11M9301(new Scheduler.Manual(), new Random(1));
		m9301.powerOn();
		//-- "$" and then a NUL fill character. An M9301-YA sends it and an M9301-YF does not, so
		//-- a driver has to cope either way - which is why the scanners filter NUL everywhere
		//-- rather than only where one is expected.
		String expected = LN + "000000 173400 165212 165212 " + LN + "$" + (char) 0;
		assertEquals(expected, m9301.takeOutput());
		assertEquals("$" + (char) 0, m9301.getPrompt());
	}
}
