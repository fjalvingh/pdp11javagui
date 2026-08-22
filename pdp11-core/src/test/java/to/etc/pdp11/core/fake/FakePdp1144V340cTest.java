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
 * The PDP-11/44 running the undocumented V3.40C console firmware.
 *
 * <p>Same command language as {@link FakePdp1144Test}'s machine and almost nothing else in
 * common, which is what these tests are for: every one of them is a difference a console driver
 * has to be told about.</p>
 */
class FakePdp1144V340cTest {
	private FakePdp1144V340c m_fake;

	private Scheduler.Manual m_clock;

	@BeforeEach
	void setUp() {
		m_clock = new Scheduler.Manual();
		m_fake = new FakePdp1144V340c(m_clock, new Random(1));
		m_fake.powerOn();
	}

	private static Address phys(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	/** Type a line and press return, returning what the console printed in answer. */
	private String send(String s) {
		for(int i = 0; i < s.length(); i++) {
			m_fake.serialWriteByte(s.charAt(i));
		}
		m_fake.takeOutput();
		m_fake.serialWriteByte('\r');
		return m_fake.takeOutput();
	}

	@Test
	void powerOnSendsFillNulsAndNamesItsFirmware() {
		//-- The NULs are the interesting part: they are fill characters sent while the line
		//-- settles, they carry no meaning, and a scanner that does not drop them will try to
		//-- parse them. That is why every console scanner filters NUL everywhere.
		String out = m_fake.takeOutput();
		assertTrue(out.startsWith("\0\0\0\0\0\r\n(Console V3.40C)"), out.replace("\0", "<nul>"));
		assertTrue(out.contains("(Program)"), out);
		//-- This firmware powers up with the PC at its own restart address.
		assertEquals(0165714, m_fake.getMem(m_fake.getProgramCounterAddr()));
		assertTrue(out.endsWith("\r\n(Console)\r\n  Halted at 165714\r\n\r\n>>>"), out);
	}

	@Test
	void examineNamesTheAddressSpaceItRead() {
		m_fake.setMem(phys(01000), 0123456);
		//-- "  P  <address>  <value>" - P for physical memory. The base firmware prints the
		//-- address and the value and nothing else.
		assertEquals("\r\n  P  00001000  123456\r\n\r\n>>>", send("E 1000"));
	}

	@Test
	void aGlobalRegisterShowsTheNumberYouAskedForNotTheAddressItBecame() {
		send("D/G 1 222");
		assertEquals(0222, m_fake.getMem(phys(017777701L)));
		//-- "G" and the register number, not 17777701.
		assertEquals("\r\n  G  00000001  000222\r\n\r\n>>>", send("E/G 1"));
	}

	@Test
	void aRegisterNumberBeyondTheFileIsItsOwnComplaint() {
		//-- Sixteen global registers exist. Asking for a higher number is not a bus error, and
		//-- the firmware checks it before it reads anything - so this is the message, not
		//-- "?Bus timeout error?".
		assertEquals("\r\n  G  00000041\r\n\r\n?Too big\r\n\r\n>>>", send("E/G 41"));
	}

	@Test
	void anAddressThatDoesNotExistPrintsTheAddressAndThenTheError() {
		//-- The address line has already gone out by the time the read fails, which is what the
		//-- console really looks like, and it stops the rest of the count rather than carrying
		//-- on silently as the base firmware does.
		assertEquals("\r\n  P  10000000\r\n\r\n?Bus timeout error?\r\n\r\n>>>", send("E 10000000"));
	}

	@Test
	void anAddressAboveTheAddressSpaceWrapsIntoItRatherThanDrawingAnError() {
		//-- The base firmware rejects it; this one masks to 22 bits.
		send("D 40001000 777");
		assertEquals(0777, m_fake.getMem(phys(01000)));
	}

	@Test
	void theErrorsAreWordsRatherThanNumbers() {
		assertEquals("\r\n?What?\r\n\r\n>>>", send("X"));
		assertEquals("\r\n?Context?\r\n\r\n>>>", send("E 99"));
	}

	@Test
	void aBareReturnJustDrawsAnotherPrompt() {
		//-- The base firmware calls an empty line a syntax error; this one does not.
		assertEquals("\r\n>>>", send(""));
	}

	@Test
	void haltingSomethingAlreadyStoppedSaysSo() {
		assertEquals("\r\n?Already halted\r\n\r\n>>>", send("H"));
	}

	@Test
	void backspaceErasesWhereTheOtherFirmwareUsesRubout() {
		m_fake.takeOutput();
		m_fake.serialWriteByte('A');
		m_fake.serialWriteByte('B');
		assertEquals("AB", m_fake.takeOutput());
		m_fake.serialWriteByte(0x08);
		assertEquals("\\B", m_fake.takeOutput());
		//-- And RUBOUT is now just another character: it ends the erase run, which closes
		//-- with a second backslash, and is then echoed and collected like anything else.
		m_fake.takeOutput();
		m_fake.serialWriteByte(0x7F);
		assertEquals("\\" + (char) 0x7F, m_fake.takeOutput());
	}

	@Test
	void controlCharactersEchoAsMnemonics() {
		m_fake.takeOutput();
		m_fake.serialWriteByte(1);                          // ^A
		assertEquals("^A", m_fake.takeOutput());
	}

	@Test
	void aSpaceIsNotAControlCharacterEvenThoughTheHelperThinksSo() {
		//-- Controlcode2Mnemonic treats anything up to and including 0x20 as a control code,
		//-- which would turn every space in a command into "^`". It never gets the chance: the
		//-- caller only routes characters strictly below 0x20 to it.
		m_fake.takeOutput();
		m_fake.serialWriteByte(' ');
		assertEquals(" ", m_fake.takeOutput());
	}

	@Test
	void startingAProgramSaysWhichModeItIsIn() {
		m_fake.takeOutput();
		String out = send("S 1000");
		//-- "(Program)" and then nothing: the console belongs to the program now, and there is
		//-- no prompt until it comes back.
		assertEquals("\r\n\r\n(Program)\r\n", out);
		assertTrue(m_fake.isRunning());
	}

	@Test
	void controlPIsTheOnlyKeyAProgramLetsThrough() {
		send("S 1000");
		m_fake.takeOutput();
		m_fake.serialWriteByte('E');
		assertEquals("", m_fake.takeOutput(), "everything else is ignored while a program runs");

		m_fake.serialWriteByte(0x10);                       // ^P
		assertFalse(m_fake.isRunning());
		//-- And it says where it stopped. On this firmware ^P is where a halt actually happens,
		//-- so it is the only place the driver can learn the PC from.
		assertEquals("\r\n(Console)\r\n^P\r\n\r\n(Console)\r\n  Halted at 001000\r\n\r\n>>>",
			m_fake.takeOutput());
	}

	@Test
	void aProgramThatHaltsOnItsOwnSaysWhereItStopped() {
		send("S 1000");
		m_fake.takeOutput();
		m_clock.fireAll();
		String out = m_fake.takeOutput();
		assertTrue(out.startsWith("\r\n(Console)\r\n  Halted at "), out);
		assertTrue(out.endsWith("\r\n\r\n>>>"), out);
	}
}
