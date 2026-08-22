package to.etc.pdp11.core.fake;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.Scheduler;

import java.util.Random;

/**
 * A simulated PDP-11 ODT console, as found on an 11/73 (KDJ11).
 *
 * <p>Ported from {@code TFakePDP11ODT} ({@code FakePDP11ODTU.pas}). Reference:
 * <a href="http://www.bitsavers.org/pdf/dec/pdp11/1173/EK-KDJ1B-UG_KDJ11-B_Nov86.pdf">
 * EK-KDJ1B-UG</a>, chapter 3.</p>
 *
 * <h2>The state transition table</h2>
 *
 * <p>Carried over verbatim from {@code FakePDP11ODTU.pas:60-83}. PLAN.md says these comments
 * are test specifications, not commentary, and the tests below are named after their rows.</p>
 *
 * <pre>
 *   state            keys          action                                    new state
 *   prompt           0..7 $ a-Z    change last_addr                          addr_entering
 *   prompt           CR            clear last_addr                           prompt
 *   prompt           P                                                       go
 *
 *   addr_entering    0..7 $ a-Z    change last_addr                          addr_entering
 *   addr_entering    /             evaluate last_addr                        addr_open if the
 *                                                                            address is valid,
 *                                                                            error otherwise
 *   addr_entering    G             pc := last_addr                           go
 *   addr_open        CR                                                      prompt
 *   addr_open        LF            last_addr += 2, prompt + print last_addr  addr_open
 *   addr_open        0..7          curval := digit                           val_entering
 *
 *   val_entering     0..7          change curval                             val_entering
 *   val_entering     CR            last_addr := curval, save                 prompt
 *   val_entering     LF            last_addr := curval, last_addr += 2,
 *                                  prompt + print last_addr                  addr_open
 *
 *   error            print ?                                                 prompt
 *   go               read HALT                                               prompt
 * </pre>
 *
 * <h2>Behaviour verified on real hardware</h2>
 *
 * <p>The rest of {@code FakePDP11ODTU.pas:86-130} records an exchange between Joerg Hoppe and
 * Steve Maddison (www.cosam.org) in September 2008, in which Steve tried each case on an
 * actual PDP-11/23 and reported what it did. That is the closest thing to an oracle this
 * console has - there is no SimH to ask - so it is reproduced here in full and each item has a
 * test named after it.</p>
 *
 * <ul>
 *   <li><b>Odd addresses are rejected.</b> {@code @1001/?} then a new prompt.</li>
 *   <li><b>Nonexistent addresses read back as zero, straight away.</b> Writing appears to
 *       succeed and has no effect - read it back and you still get zero. No error.</li>
 *   <li><b>An illegal character in an address is echoed, then {@code ?} on the same line.</b>
 *       {@code @123x?} then a new prompt - so the {@code ?} does not wait for the {@code /}.</li>
 *   <li><b>An illegal character in data behaves the same:</b> {@code @1000/000000 a?}</li>
 *   <li><b>{@code /} then {@code CR} is as implemented.</b></li>
 *   <li><b>{@code /} then {@code LF}: the simulator printed one {@code @} too many.</b> On the
 *       real machine the lines follow one another with no {@code @} at all:
 *       <pre>
 *   &#64;1000/000123 &lt;LF&gt;
 *   1002/000456 &lt;LF&gt;
 *   1004/000701 &lt;LF&gt;
 *       </pre>
 *       DEC's documentation says there <i>is</i> a prompt; the hardware says there is not, and
 *       the hardware wins. ODT-11 agrees with the hardware.</li>
 *   <li><b>{@code nnnnG} prints nothing further</b> - not even a CR or LF after the {@code G}.</li>
 *   <li><b>The HALT report is as implemented.</b></li>
 *   <li><b>The power-on message format is right</b>, though a real 11/73 shows {@code 173000}
 *       rather than {@code 000000}, that being where it starts.</li>
 * </ul>
 *
 * <p>One question in that exchange was never answered and is still open: what a {@code LF}
 * immediately after a prompt does, and what {@code /} does immediately after an error.</p>
 */
public class FakePdp11Odt extends FakePdp11 {
	private static final char CR = '\r';

	private static final char LF = '\n';

	private static final char ESC = 27;

	/** Where the ODT command interpreter is. Ported from {@code TFakePDP11ODTState}. */
	public enum OdtState {
		/** Nothing has happened yet; the machine has not printed its first prompt. */
		INIT,
		/** {@code @} is showing and nothing has been typed. */
		PROMPT,
		/** Something has been typed after the prompt - an address is being entered. */
		ENTER_LOCATION_ADDR,
		/** {@code /} was typed and the location's contents have been printed. */
		OPEN,
		/** Digits are being typed after an open location - a new value. */
		ENTER_LOCATION_CONTENTS
	}

	/**
	 * Which machine's ODT this is.
	 *
	 * <p>Replaces the {@code IsK1630} boolean threaded through the Pascal
	 * ({@code FakePDP11ODTU.pas:218}) and the matching flags in the console scanner, per
	 * PLAN.md §2's "replace with a {@code ConsoleDialect} value object". The K1630 is a
	 * Robotron A6402's PDP-11-compatible CPU, reported by Ruediger Kurt in 2016.</p>
	 */
	public enum OdtDialect {
		/** DEC's own ODT, as on an 11/73. Prompt is {@code "@"}. */
		DEC,
		/** Robotron K1630: prompt is {@code "@ "}, errors are {@code " ?"}. */
		K1630
	}

	private final OdtDialect m_dialect;

	private OdtState m_state = OdtState.INIT;

	/** The last location opened, as the user spelled it: octal, or {@code R0}..{@code R7}, or {@code RS}. */
	private String m_lastLocationExpr = "";

	/** What {@code LF} would open next. {@code "???"} while undefined. */
	private String m_nextLocationExpr = "???";

	private Address m_lastLocationAddr;

	public FakePdp11Odt(MemoryAddressType type, Scheduler scheduler, Random random) {
		this(type, scheduler, random, OdtDialect.DEC);
	}

	public FakePdp11Odt(MemoryAddressType type, Scheduler scheduler, Random random, OdtDialect dialect) {
		super(dialect == OdtDialect.K1630 ? "Fake K1630 ODT" : "Fake PDP-11 ODT", type, scheduler, random);
		m_dialect = dialect;
	}

	public OdtState getState() {
		return m_state;
	}

	public OdtDialect getDialect() {
		return m_dialect;
	}

	@Override
	public void powerOn() {
		clearMemory();
		takeOutput();
		reset();
	}

	@Override
	public void reset() {
		m_state = OdtState.INIT;
		clearInput();
		m_lastLocationExpr = "";
		//-- An 11/73 powers up with its PC at the boot ROM, which is what Steve Maddison saw.
		setMem(getProgramCounterAddr(), 0173000);
		doHalt();
	}

	// -------------------------------------------------------------------------------------
	// Printing
	// -------------------------------------------------------------------------------------

	/**
	 * Print the prompt and get ready for a new line.
	 *
	 * @param printPrompt false suppresses the {@code @} itself, which is what {@code LF}
	 *                    needs: on real hardware the auto-advanced lines carry no prompt.
	 */
	private void doPrompt(boolean printPrompt) {
		if(printPrompt) {
			if(m_dialect == OdtDialect.K1630)
				print("" + LF + CR + "@ ");
			else
				print("" + CR + LF + "@");
		} else {
			print("" + CR + LF);
		}
		clearInput();
		m_state = OdtState.PROMPT;
	}

	private void doError() {
		//-- The "/" or the offending character has already been echoed; the "?" follows it on
		//-- the same line, which is what the 11/23 does.
		print(m_dialect == OdtDialect.K1630 ? " ?" : "?");
	}

	// -------------------------------------------------------------------------------------
	// Commands
	// -------------------------------------------------------------------------------------

	/**
	 * Evaluate an address expression, print the contents, and work out what {@code LF} would
	 * open next.
	 *
	 * <p>Ported from {@code doOpenLocation} ({@code :318-402}). An expression is octal digits,
	 * or {@code R0}..{@code R7}, or {@code RS}/{@code $S} for the PSW. Only the last eight
	 * octal digits count, and only the last digit of a register name - typing {@code R19}
	 * opens R1... no, {@code R9} is illegal, but {@code RRR3} opens R3, which is what the
	 * hardware does.</p>
	 */
	private void doOpenLocation(String addrExpr) {
		m_nextLocationExpr = "???";
		String expr = addrExpr.toUpperCase();
		m_lastLocationAddr = null;

		if(expr.isEmpty() || !isAddressStart(expr.charAt(0)))
			throw new FakePdp11Exception("'" + addrExpr + "' does not start an address");

		if(isOctalDigit(expr.charAt(0))) {
			openOctalLocation(expr);
		} else {
			openRegisterLocation(expr);
		}
		if(m_lastLocationAddr == null)
			throw new FakePdp11Exception("'" + addrExpr + "' is not a location");

		//-- Nonexistent memory reads back as zero, immediately and without complaint. That is
		//-- the hardware's answer, not a guess: see the class comment.
		int val = isImplemented(m_lastLocationAddr) ? getMem(m_lastLocationAddr) : 0;

		//-- The "/" has already been echoed by the caller.
		print(Octal.format(val, 6) + " ");
		clearInput();
		m_state = OdtState.OPEN;
	}

	private void openOctalLocation(String expr) {
		//-- Only the last eight digits count; ODT simply keeps the low bits of what you type.
		String digits = expr.length() > 8 ? expr.substring(expr.length() - 8) : expr;
		long value = Octal.parse(digits);
		long max = (1L << getAddressType().getBits()) - 1;
		if(value > max)
			throw new FakePdp11Exception("address 0" + Long.toOctalString(value) + " is too wide");
		Address addr = Address.of(getAddressType(), value);

		//-- R0..R7 can only be reached by name. The PSW, uniquely, also answers to its octal
		//-- address 17777776 (chapter 3.5.1).
		if(value >= getIopageBase() + 017700 && value <= getIopageBase() + 017707)
			throw new FakePdp11Exception("registers must be named R0..R7, not addressed octally");
		//-- Odd addresses are rejected: "@1001/?".
		if((value & 1) != 0)
			throw new FakePdp11Exception("odd address 0" + Long.toOctalString(value));

		m_lastLocationAddr = addr;
		m_lastLocationExpr = addr.toOctal();

		long next = value < getIopageBase() + 017776 ? value + 2 : 0;
		m_nextLocationExpr = Address.of(getAddressType(), next).toOctal();
	}

	/** {@code R0}..{@code R7}, or {@code RS}/{@code $S}/{@code $077}/{@code R477} for the PSW. */
	private void openRegisterLocation(String expr) {
		String rest = expr.substring(1);
		//-- Only the last three characters are looked at.
		if(rest.length() >= 3)
			rest = rest.substring(rest.length() - 3);

		if("077".equals(rest) || "477".equals(rest)) {
			m_lastLocationAddr = Address.of(getAddressType(), getIopageBase() + 017776);
			m_lastLocationExpr = "RS";
			m_nextLocationExpr = "RS";                      // the PSW has no next location
			return;
		}
		if(rest.isEmpty())
			throw new FakePdp11Exception("'" + expr + "' names no register");

		char c = rest.charAt(rest.length() - 1);
		if(c == 'S') {
			m_lastLocationAddr = Address.of(getAddressType(), getIopageBase() + 017776);
			m_lastLocationExpr = "RS";
			m_nextLocationExpr = "RS";
		} else if(c >= '0' && c <= '7') {
			int n = c - '0';
			m_lastLocationAddr = Address.of(getAddressType(), getIopageBase() + 017700 + n);
			m_lastLocationExpr = "R" + n;
			m_nextLocationExpr = "R" + ((n + 1) % 8);       // rolls around R7 -> R0
		} else {
			throw new FakePdp11Exception("'" + expr + "' names no register");
		}
	}

	/**
	 * Close the open location, writing {@code valExpr} if there is one.
	 *
	 * <p>Ported from {@code doCloseLocation} ({@code :406-427}). Note the comment there:
	 * <i>the ODT console on an 11/73 writes to illegal addresses without complaint</i> - which
	 * matches what Steve Maddison found, so a write to nonexistent memory is accepted and
	 * silently discarded.</p>
	 */
	private void doCloseLocation(String valExpr) {
		if(valExpr.isEmpty())
			return;
		//-- Only the last six digits count.
		String digits = valExpr.length() > 6 ? valExpr.substring(valExpr.length() - 6) : valExpr;
		long value = Octal.parse(digits);
		if(isImplemented(m_lastLocationAddr))
			setMem(m_lastLocationAddr, (int) value);
	}

	/** {@code nnnnG} with the RUN switch set: start pretending to execute. */
	private void doRun(String valExpr) {
		if(valExpr.isEmpty())
			return;
		runToHalt(parseValue(valExpr));
	}

	/** {@code nnnnG} with the RUN switch clear: load the PC and stay halted. */
	private void doReset(String valExpr) {
		if(valExpr.isEmpty())
			return;
		setMem(getProgramCounterAddr(), (int) parseValue(valExpr));
		doHalt();
	}

	/** {@code P} with the RUN switch clear: advance the PC by one word and report. */
	private void doSingleStep(String valExpr) {
		if(valExpr.isEmpty())
			return;
		setMem(getProgramCounterAddr(), (int) (parseValue(valExpr) + 2));
		doHalt();
	}

	private static long parseValue(String valExpr) {
		String digits = valExpr.length() > 6 ? valExpr.substring(valExpr.length() - 6) : valExpr;
		return Octal.parse(digits);
	}

	// -------------------------------------------------------------------------------------
	// The keystroke state machine
	// -------------------------------------------------------------------------------------

	/**
	 * Feed one typed character in.
	 *
	 * <p>Ported from {@code SerialWriteByte} ({@code :485-625}). Note the structure: the
	 * Pascal switches on the <b>key</b> first and the state second, "denn so ist die DEC-Doku"
	 * - because that is how DEC's manual is laid out ([3.4.1]) - and keeping that shape makes
	 * it possible to check this against the manual.</p>
	 */
	@Override
	public void serialWriteByte(int b) {
		//-- With the pretend CPU running there is no console at all.
		if(isRunning())
			return;

		char c = (char) (b & 0x7F);                         // the console is a 7-bit device
		char u = Character.toUpperCase(c);
		try {
			switch(u) {
				case 'R', '$', 'S' -> keyRegisterLetter(c);
				case '0', '1', '2', '3', '4', '5', '6', '7' -> keyOctalDigit(c);
				case '/' -> keySlash(c);
				case CR -> keyCarriageReturn();
				case LF -> keyLineFeed();
				case 'G' -> keyGo(c);
				case 'P' -> keyProceed(c);
				default -> keyOther(c);
			}
		} catch(FakePdp11Exception x) {
			doError();
			doPrompt(true);
		}
	}

	/** {@code R}, {@code $} and {@code S} name registers, so they only belong in an address. */
	private void keyRegisterLetter(char c) {
		print(c);
		switch(m_state) {
			case PROMPT -> {
				appendInput(c);
				m_state = OdtState.ENTER_LOCATION_ADDR;
			}
			case ENTER_LOCATION_ADDR -> appendInput(c);
			default -> throw new FakePdp11Exception("'" + c + "' is not valid here");
		}
	}

	/** Octal digits belong in both an address and a value. */
	private void keyOctalDigit(char c) {
		print(c);
		appendInput(c);
		switch(m_state) {
			case PROMPT -> m_state = OdtState.ENTER_LOCATION_ADDR;
			case OPEN -> m_state = OdtState.ENTER_LOCATION_CONTENTS;
			default -> {
				//-- Already entering an address or a value: stay where we are.
			}
		}
	}

	private void keySlash(char c) {
		print(c);
		switch(m_state) {
			//-- A bare "/" reopens the location most recently opened.
			case PROMPT -> {
				if(m_lastLocationExpr.isEmpty())
					throw new FakePdp11Exception("no location has been opened yet");
				doOpenLocation(m_lastLocationExpr);
			}
			case ENTER_LOCATION_ADDR -> doOpenLocation(getInputBuffer());
			default -> throw new FakePdp11Exception("'/' is not valid here");
		}
	}

	/** Close the location. The CR itself is never echoed. */
	private void keyCarriageReturn() {
		switch(m_state) {
			//-- CR at a bare prompt just gives another prompt. The Pascal marks this "my
			//-- guess!" - it is one of the cases the 11/23 was never asked about.
			case PROMPT -> doPrompt(true);
			case OPEN -> {
				doCloseLocation("");                        // leave the value alone
				doPrompt(true);
			}
			case ENTER_LOCATION_CONTENTS -> {
				doCloseLocation(getInputBuffer());
				doPrompt(true);
			}
			default -> throw new FakePdp11Exception("CR is not valid here");
		}
	}

	/**
	 * Close this location and open the next. The LF itself is never echoed.
	 *
	 * <p>The next location was already worked out by {@code doOpenLocation}. Two cases stop
	 * the auto-advance and print an ordinary prompt instead: rolling round from the top of
	 * memory to zero, and the PSW, which has no successor.</p>
	 */
	private void keyLineFeed() {
		switch(m_state) {
			case OPEN, ENTER_LOCATION_CONTENTS -> {
				doCloseLocation(m_state == OdtState.OPEN ? "" : getInputBuffer());
				boolean rolledOver = m_nextLocationExpr.equals(
					Address.of(getAddressType(), 0).toOctal());
				if(rolledOver || "RS".equals(m_lastLocationExpr)) {
					doPrompt(true);
				} else {
					//-- No "@" on the continuation lines. DEC's manual says there is one; a
					//-- real 11/73 does not print it, and ODT-11 agrees with the hardware.
					doPrompt(false);
					print(m_nextLocationExpr);
					print('/');
					doOpenLocation(m_nextLocationExpr);
				}
			}
			default -> throw new FakePdp11Exception("LF is not valid here");
		}
	}

	/** {@code nnnnG}: go. Prints nothing further, not even a CR. */
	private void keyGo(char c) {
		print(c);
		if(m_state != OdtState.ENTER_LOCATION_ADDR)
			throw new FakePdp11Exception("G needs an address in front of it");
		if(isRunMode())
			doRun(getInputBuffer());
		else
			doReset(getInputBuffer());
	}

	/** {@code P}: proceed from the current PC. */
	private void keyProceed(char c) {
		print(c);
		if(m_state != OdtState.PROMPT)
			throw new FakePdp11Exception("P is only valid at a prompt");
		String pc = Octal.format(getMem(getProgramCounterAddr()), 6);
		if(isRunMode())
			doRun(pc);
		else
			doSingleStep(pc);
	}

	/**
	 * Anything else is echoed and then draws a {@code ?}, on the same line - "@123x?" - which
	 * is what the 11/23 does.
	 */
	private void keyOther(char c) {
		//-- The K1630 allows an "A" suffix on an address, meaning absolute rather than
		//-- virtual. It changes nothing here, so every 'A' in an address is simply echoed.
		if(m_dialect == OdtDialect.K1630 && Character.toUpperCase(c) == 'A'
			&& m_state == OdtState.ENTER_LOCATION_ADDR) {
			print(c);
			return;
		}
		print(c);
		throw new FakePdp11Exception("'" + c + "' is not a valid ODT character");
	}

	/** Report a halt: the PC, then a prompt. */
	@Override
	protected void doHalt() {
		int pc = getMem(getProgramCounterAddr());
		if(m_dialect == OdtDialect.K1630) {
			//-- The K1630 sends "Esc S" before the CR/LF on a halt.
			print("" + ESC + 'S' + CR + LF + Octal.format(pc, 6));
		} else {
			print("" + CR + LF + Octal.format(pc, 6));
		}
		doPrompt(true);
	}

	private static boolean isOctalDigit(char c) {
		return c >= '0' && c <= '7';
	}

	private static boolean isAddressStart(char c) {
		return isOctalDigit(c) || c == 'R' || c == '$';
	}
}
