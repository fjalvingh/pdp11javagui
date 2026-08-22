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
 * The simulated PDP-11/44 console, both firmwares.
 *
 * <p>Unlike ODT, nobody here has an 11/44 to check these against, so the reference is the Pascal
 * unit and the transcripts in it. What the tests are for is the console driver that comes next:
 * every string this prints is something that driver's scanner will have to take apart, so the
 * expectations are written out in full rather than being matched loosely.</p>
 */
class FakePdp1144Test {
	private FakePdp1144 m_fake;

	private Scheduler.Manual m_clock;

	@BeforeEach
	void setUp() {
		m_clock = new Scheduler.Manual();
		m_fake = new FakePdp1144(m_clock, new Random(1));
		m_fake.powerOn();
	}

	private static Address phys(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	/**
	 * Type a line and press return, returning what the console printed <i>in answer</i>.
	 *
	 * <p>The echo of what was typed is discarded before the return is sent, so a test says what
	 * the console answered rather than restating what it was asked. That the echo happens at all
	 * is its own test.</p>
	 */
	private String send(String s) {
		for(int i = 0; i < s.length(); i++) {
			m_fake.serialWriteByte(s.charAt(i));
		}
		m_fake.takeOutput();
		m_fake.serialWriteByte('\r');
		return m_fake.takeOutput();
	}

	@Test
	void powerOnAnnouncesItselfAndHaltsAtThePrompt() {
		//-- "CONSOLE", then where the machine is, then the prompt. The halt report is a bare
		//-- examine of R7 - the console does not label it.
		String out = m_fake.takeOutput();
		assertEquals("\r\nCONSOLE\r\n17777707 000000\r\n>>>", out);
	}

	@Test
	void everyTypedCharacterIsEchoed() {
		m_fake.takeOutput();
		m_fake.serialWriteByte('E');
		m_fake.serialWriteByte(' ');
		assertEquals("E ", m_fake.takeOutput());
	}

	@Test
	void examinePrintsTheAddressAndTheValue() {
		m_fake.setMem(phys(01000), 0123456);
		assertEquals("\r\n00001000 123456\r\n>>>", send("E 1000"));
	}

	@Test
	void depositThenExamineRoundTrips() {
		send("D 1000 123456");
		assertEquals(0123456, m_fake.getMem(phys(01000)));
		assertEquals("\r\n00001000 123456\r\n>>>", send("E 1000"));
	}

	@Test
	void examineWithNoAddressCarriesOnFromTheLastOne() {
		m_fake.setMem(phys(01000), 01);
		m_fake.setMem(phys(01002), 02);
		send("E 1000");
		assertEquals("\r\n00001002 000002\r\n>>>", send("E"));
	}

	@Test
	void depositWithAPlusCarriesOnFromTheLastOne() {
		send("D 1000 111");
		send("D + 222");
		assertEquals(0111, m_fake.getMem(phys(01000)));
		assertEquals(0222, m_fake.getMem(phys(01002)));
	}

	@Test
	void theCountModifierRepeatsTheOperation() {
		send("D 2000 777 ");
		//-- "/N:4" on a deposit fills four consecutive words with the same value.
		send("D/N:4 3000 555");
		for(int i = 0; i < 4; i++) {
			assertEquals(0555, m_fake.getMem(phys(03000 + 2L * i)), "word " + i);
		}
		//-- And on an examine it reads four of them back, one line each.
		assertEquals("\r\n00003000 000555\r\n00003002 000555\r\n00003004 000555\r\n00003006 000555\r\n>>>",
			send("E/N:4 3000"));
	}

	@Test
	void theGlobalRegisterModifierIsByteSpacedWhichIsWhyItExists() {
		//-- R0..R7 and the second register set live at sixteen consecutive byte addresses from
		//-- 17777700, so "/G n" steps by 1 and not by 2.
		send("D/G 0 111");
		send("D/G 1 222");
		assertEquals(0111, m_fake.getMem(phys(017777700L)));
		assertEquals(0222, m_fake.getMem(phys(017777701L)));
		assertEquals("\r\n17777701 000222\r\n>>>", send("E/G 1"));
	}

	@Test
	void modifiersCombine() {
		send("D/G 0 100");
		send("D/G 1 101");
		assertEquals("\r\n17777700 000100\r\n17777701 000101\r\n>>>", send("E/G/N:2 0"));
	}

	@Test
	void anAddressThatDoesNotExistDrawsABusTimeout() {
		//-- Above the fitted memory and below the I/O page. The error appears with the next
		//-- prompt, and no value line is printed for it.
		assertEquals("\r\n?20 TRAN ERR\r\n>>>", send("E 10000000"));
	}

	@Test
	void anAddressOutsideTheAddressSpaceIsASyntaxError() {
		//-- 2^22 is the whole physical space; anything above it is not an address at all.
		assertEquals("\r\n?01 SYN?\r\n>>>", send("E 40000000"));
	}

	@Test
	void gibberishIsASyntaxError() {
		assertEquals("\r\n?01 SYN?\r\n>>>", send("X"));
		assertEquals("\r\n?01 SYN?\r\n>>>", send("E 99"));
	}

	@Test
	void oddAddressesAreDeliberatelyNotChecked() {
		//-- The Pascal says so twice, in two different methods. An 11/44 console will examine
		//-- an odd address without complaint.
		send("D 1000 123456");
		assertEquals("\r\n00001001 123456\r\n>>>", send("E 1001"));
	}

	@Test
	void controlCAbandonsTheLine() {
		m_fake.takeOutput();
		m_fake.serialWriteByte('E');
		m_fake.serialWriteByte(3);
		assertEquals("E^C\r\n>>>", m_fake.takeOutput());
	}

	@Test
	void ruboutEchoesWhatItDeletesAndKeepsEchoingOnceItRunsOut() {
		//-- The Pascal calls this behaviour "etwas hohl" - a bit daft - and it is: the run of
		//-- erases opens with a backslash, echoes each deleted character, and once there is
		//-- nothing left it goes on echoing the last one deleted forever.
		m_fake.takeOutput();
		for(char c : "AB".toCharArray()) {
			m_fake.serialWriteByte(c);
		}
		assertEquals("AB", m_fake.takeOutput());
		m_fake.serialWriteByte(0x7F);
		m_fake.serialWriteByte(0x7F);
		m_fake.serialWriteByte(0x7F);
		assertEquals("\\BAA", m_fake.takeOutput());
		//-- Typing something else closes the run with a second backslash.
		m_fake.serialWriteByte('C');
		assertEquals("\\C", m_fake.takeOutput());
	}

	@Test
	void startRunsAndTheHaltReportsWhereItStopped() {
		m_fake.takeOutput();
		send("S 1000");
		assertTrue(m_fake.isRunning());
		//-- This firmware goes on listening while a program has the machine - the Pascal has no
		//-- gate on it, and that is the difference from V3.40C, which stops dead and takes only
		//-- ^P. Here a keystroke is echoed like any other.
		m_fake.serialWriteByte('E');
		assertEquals("E", m_fake.takeOutput());
		m_fake.serialWriteByte(3);                          // ^C, to put the line back
		m_fake.takeOutput();

		m_clock.fireAll();
		assertFalse(m_fake.isRunning());
		String out = m_fake.takeOutput();
		assertTrue(out.startsWith("\r\n17777707 "), out);
		assertTrue(out.endsWith(">>>"), out);
	}

	@Test
	void haltAndSingleStepAnswerWithTheSameStopReport() {
		//-- H and N are what the shipped console driver sends, and it parses both answers as
		//-- "17777707 <pc>" - which is why the fake has them even though the Pascal's does not.
		m_fake.setMem(m_fake.getProgramCounterAddr(), 01000);
		assertEquals("\r\n17777707 001000\r\n>>>", send("H"));
		assertEquals("\r\n17777707 001002\r\n>>>", send("N 1"));
		assertEquals("\r\n17777707 001006\r\n>>>", send("N 2"));
	}

	@Test
	void controlPIsATerminalModeSwitchAndNotAConsoleCommand() {
		//-- The driver sends it before H. It must not land in the line being typed, or the
		//-- command after it is gibberish.
		m_fake.takeOutput();
		m_fake.serialWriteByte(0x10);
		assertEquals("", m_fake.takeOutput(), "^P is not echoed and not collected");
		m_fake.setMem(m_fake.getProgramCounterAddr(), 04000);
		assertEquals("\r\n17777707 004000\r\n>>>", send("H"));
	}

	@Test
	void initPrintsNothingButItsPrompt() {
		//-- The real command takes a second and prints nothing; the Pascal spends that second
		//-- pumping a message loop that does not exist here.
		assertEquals("\r\n>>>", send("I"));
	}
}
