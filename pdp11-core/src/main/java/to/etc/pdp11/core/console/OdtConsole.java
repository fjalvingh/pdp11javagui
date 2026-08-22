package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.mmu.Pdp11Mmu;
import to.etc.pdp11.core.mmu.TranslationResult;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.ProgressMonitor;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Drives a PDP-11 through its microcode ODT console: an 11/23, 11/73, 11/93, or a Robotron
 * K1630.
 *
 * <p>Ported from {@code TConsolePDP11ODT} ({@code ConsolePDP11ODTU.pas}). ODT is the console
 * emulator built into the CPU chip set itself, so this is what talks to a real machine over a
 * serial line rather than to an emulator. It handles the 16, 18 and 22-bit variants; which one
 * is a constructor argument, because ODT prints an address at whatever width the machine has
 * and there is no way to ask it.</p>
 *
 * <h2>ODT is a terminal, and that changes the parsing</h2>
 *
 * <p>Everything sent is echoed by the machine, and the reply is glued to the echo with no line
 * structure at all: opening location {@code 1000} means sending {@code 1000/} and reading back
 * {@code 1000/000000 }, of which the first five characters are our own echo. So there is
 * nothing here to anchor on the way the SimH console anchors on an echo - the echo <i>is</i> the
 * answer. What makes it work instead is that the phrase grammar is unambiguous about where a
 * reply starts: a prompt or a line end, then an address, then a slash.</p>
 *
 * <h2>Two operations, not one</h2>
 *
 * <p>A deposit is a conversation, not a command ({@code :700-733}). Send {@code addr/}, wait
 * for the machine to open the location and print its current contents, and only then send the
 * new value and a carriage return. Skipping the wait sends digits at a console that is not
 * listening for them yet.</p>
 */
public final class OdtConsole extends AbstractConsole {
	public static final char CR = '\r';

	/** 1 s, from {@code ODT_CMD_TIMEOUT} ({@code :51}). A real machine on a serial line is prompt. */
	public static final long CMD_TIMEOUT_MS = 1000;

	/** R0..R7 and the PSW, at their offsets within the I/O page. Byte-spaced, as PDP11GUI has them. */
	private static final int REG_R0 = 017700;

	private static final int REG_R7 = 017707;

	private static final int REG_PSW = 017776;

	private static final String[] REG_NAMES = {"R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7"};

	private final OdtScanner m_scanner = new OdtScanner();

	private final MemoryAddressType m_addressType;

	private final OdtDialect m_dialect;

	/**
	 * @param addressType 16, 18 or 22 bits - what this machine's ODT prints. There is no way to
	 *                    ask it, so the caller has to say.
	 */
	public OdtConsole(MemoryCellGroups groups, MemoryAddressType addressType, OdtDialect dialect,
		Logger logger) {
		super(logger);
		if(!addressType.isConcretePhysical())
			throw new IllegalArgumentException("An ODT console has a concrete physical width, not " + addressType);
		m_addressType = addressType;
		m_dialect = dialect;
		setCommandTimeoutMillis(CMD_TIMEOUT_MS);
		setMmu(new Pdp11Mmu(groups));
	}

	public OdtDialect getDialect() {
		return m_dialect;
	}

	@Override
	public String name() {
		return m_dialect == OdtDialect.K1630 ? "K1630 ODT console" : "PDP-11 ODT console";
	}

	@Override
	public MemoryAddressType physicalAddressType() {
		return m_addressType;
	}

	/**
	 * What ODT can do depends on where the ENABLE/HALT switch is, which is why
	 * {@link ConsoleFeature#SWITCH_ENABLE_OR_HALT} is in the set at all.
	 *
	 * <p>{@code nnnG} and {@code P} each mean two different things ({@code :330-352}): with the
	 * machine halted they are "reset to nnn" and "step one instruction"; with it enabled they
	 * are "reset and run from nnn" and "continue". Same characters, and only the switch says
	 * which.</p>
	 */
	@Override
	public EnumSet<ConsoleFeature> features() {
		EnumSet<ConsoleFeature> set = EnumSet.of(
			ConsoleFeature.NON_FATAL_HALT,
			ConsoleFeature.NON_FATAL_UNIBUS_TIMEOUT,
			ConsoleFeature.SWITCH_ENABLE_OR_HALT,
			//-- Unlike SimH's: "nnnG" sets the PC as part of resetting.
			ConsoleFeature.RESET_CPU_SETS_PC);
		if(getRunMode() == ConsoleRunMode.HALT) {
			set.add(ConsoleFeature.ACTION_RESET_MACHINE);
			set.add(ConsoleFeature.ACTION_SINGLE_STEP);
		} else {
			set.add(ConsoleFeature.ACTION_RESET_AND_START_CPU);
			set.add(ConsoleFeature.ACTION_CONTINUE_CPU);
		}
		//-- No ACTION_HALT_CPU: ODT is in the CPU, so a running CPU is not listening. Stopping
		//-- it is the operator's job, with the switch.
		return set;
	}

	/**
	 * Ported from {@code getTerminalSettings} ({@code ConsolePDP11ODTU.pas:312-321}).
	 *
	 * <p>ODT sends CR <i>and</i> LF and means the LF, so the CR is ignored. It never erases -
	 * the comment says so plainly, "PDP-11/ODT loescht nie" - which is what a printing terminal
	 * does, since ink does not come off paper.</p>
	 */
	@Override
	public TerminalProfile terminalProfile() {
		return TerminalProfile.of(false, true);
	}

	@Override
	protected ConsoleScanner<?> getScanner() {
		return m_scanner;
	}

	// -------------------------------------------------------------------------------------
	// Addresses
	// -------------------------------------------------------------------------------------

	/**
	 * ODT's name for an address, or {@code null} if it has none.
	 *
	 * <p>Ported from {@code addr2regname} ({@code :245-266}). The Pascal's own comment notes
	 * this list is model-dependent and ought to come out of the machine description; it does
	 * not, in either implementation.</p>
	 */
	String addrToRegName(Address addr) {
		if(addr.type() != m_addressType)
			return null;
		long base = m_addressType.getIopageBase();
		long off = addr.val() - base;
		if(off >= REG_R0 && off <= REG_R7)
			return REG_NAMES[(int) (off - REG_R0)];
		if(off == REG_PSW)
			return "RS";
		return null;
	}

	/**
	 * The address ODT means by a name, or {@code null}.
	 *
	 * <p>Ported from {@code regname2addr} ({@code :269-292}). Both spellings are accepted:
	 * {@code R0} and {@code $0} are the same register, and ODT prints whichever the operator
	 * typed.</p>
	 */
	Address regNameToAddr(String regname) {
		String n = regname.toUpperCase(Locale.ROOT);
		if(n.length() != 2)
			return null;
		char kind = n.charAt(0);
		if(kind != 'R' && kind != '$')
			return null;
		long base = m_addressType.getIopageBase();
		char which = n.charAt(1);
		if(which == 'S')
			return Address.of(m_addressType, base + REG_PSW);
		if(which >= '0' && which <= '7')
			return Address.of(m_addressType, base + REG_R0 + (which - '0'));
		return null;
	}

	/**
	 * How to spell an address at this console. Ported from {@code physicaladdr2text}
	 * ({@code :295-306}).
	 */
	String addressText(Address physical) {
		String regname = addrToRegName(physical);
		if(regname != null)
			return regname;
		String s = Octal.format(physical.val(), 1);
		//-- The K1630 writes and reads physical addresses as "nnnnnnA".
		return m_dialect.isPhysicalAddressSuffixA() ? s + "A" : s;
	}

	// -------------------------------------------------------------------------------------
	// Decoding - reader thread
	// -------------------------------------------------------------------------------------

	private OdtScanner.Sym sym() {
		return m_scanner.getCurSymType();
	}

	private String symText() {
		return m_scanner.getCurSymText();
	}

	private void next() {
		m_scanner.nextSymbol(true);
	}

	/** Skip a space where the dialect allows one - after the prompt, and after the slash. */
	private void gobbleSpace() {
		while(m_dialect.isGobbleSpaceAfterPrompt() && " ".equals(symText())) {
			next();
		}
	}

	/**
	 * Recognise one phrase of ODT's output.
	 *
	 * <p>Ported from {@code DecodeNextAnswerPhrase} ({@code :406-698}). The grammar, from the
	 * Pascal's own note at {@code :475-481} - and what is left in the buffer afterwards:</p>
	 *
	 * <pre>
	 * &lt;EOLN&gt;@                prompt   -&gt; @
	 * &lt;EOLN&gt;addr&lt;EOLN&gt;       halt     -&gt; EOLN
	 * @addr/val&lt;space&gt;       examine  -&gt; ""
	 * &lt;EOLN&gt;addr/val&lt;space&gt;  examine  -&gt; ""
	 * else: scan to eoln     otherline -&gt; EOLN
	 * </pre>
	 *
	 * <p>Note what "leave the {@code @} standing" means: the character itself is consumed from
	 * the buffer, but it stays as the scanner's <i>current symbol</i> across the call, so the
	 * next phrase is parsed as {@code @addr/val} without the prompt having to be re-read. The
	 * one-symbol lookahead surviving between calls is the whole reason this parser can be fed
	 * one byte at a time.</p>
	 */
	@Override
	protected boolean decodeNextAnswerPhrase() {
		m_scanner.markParsePosition();
		AnswerPhrase phrase;
		try {
			//-- Clear the end-of-input left by the previous scan; throws if there is still nothing.
			if(sym() == OdtScanner.Sym.EOF)
				next();
			phrase = parsePhrase();
		} catch(ScannerUnknownExpressionException x) {
			//-- A semantic error: throw the whole expression away and try again from scratch.
			//-- Something the machine said is not something we know how to read, which for a
			//-- console with a live operator typing at it is entirely ordinary.
			getLogger().log(LogChannel.PROTOCOL, "Discarding unrecognised console input: " + x.getMessage());
			m_scanner.cleanupInput();
			return true;
		} catch(ScannerInputIncompleteException x) {
			//-- Not everything has arrived. Rewind and wait to be called again.
			m_scanner.restoreParsePosition();
			return false;
		}
		//-- Whether this attempt got anywhere at all. A pass can consume input without
		//-- producing a phrase - the K1630 prefixes a halt report with ESC S, and the two
		//-- symbols before the address match no rule. The Pascal returns false there, which
		//-- stops the decode loop with input still in the buffer: nothing more is looked at
		//-- until the next byte arrives, and if that was the last of the reply, nothing more
		//-- ever is. Reporting progress instead keeps the loop going, and cannot spin, because
		//-- every true consumes at least one character.
		boolean consumed = m_scanner.getNextCharIndex() > 0;
		m_scanner.cleanupInput();
		if(phrase == null)
			return consumed;
		publish(phrase);
		return true;
	}

	private AnswerPhrase parsePhrase() {
		if(sym() == OdtScanner.Sym.EOLN)
			return parseAfterLineEnd();
		if("@".equals(symText()))
			return parseAfterPrompt();
		return parseOtherLine();
	}

	/** After a line end: a prompt, a blank line, a stray {@code ?}, a halt, or an examine. */
	private AnswerPhrase parseAfterLineEnd() {
		next();
		gobbleSpace();

		if("@".equals(symText())) {
			//-- Leave the '@' as the current symbol; see the method comment.
			return makePrompt();
		}
		if(sym() == OdtScanner.Sym.EOLN) {
			//-- An 11/23's M8186 sometimes prints an extra blank line. One CR is already eaten.
			return new AnswerPhrase.OtherLine("");
		}
		if("?".equals(symText())) {
			//-- And sometimes it answers a bare CR with nothing but a "?".
			next();
			return new AnswerPhrase.OtherLine("?");
		}
		if(sym() != OdtScanner.Sym.OCTAL && sym() != OdtScanner.Sym.REGISTER)
			return null;

		String addrText = symText();
		boolean isRegName = sym() == OdtScanner.Sym.REGISTER;
		next();
		if(m_dialect.isPhysicalAddressSuffixA() && "A".equals(symText()))
			next();

		if(!isRegName && sym() == OdtScanner.Sym.EOLN) {
			//-- An address alone on a line: the machine halted and printed where. Leave the
			//-- EOLN standing, because it also begins whatever comes next.
			return makeHalt(addrText);
		}
		if("/".equals(symText())) {
			next();
			AnswerPhrase r = makeExamine(addrText, symText());
			next();                                         // consume the value
			return r;
		}
		throw new ScannerUnknownExpressionException("<EOLN><addr> is not properly terminated");
	}

	/** After the prompt: {@code @addr/val}, which is what our own echo plus ODT's reply looks like. */
	private AnswerPhrase parseAfterPrompt() {
		next();
		gobbleSpace();
		if(sym() != OdtScanner.Sym.OCTAL && sym() != OdtScanner.Sym.REGISTER)
			throw new ScannerUnknownExpressionException("no address after @");

		String addrText = symText();
		next();
		if(m_dialect.isPhysicalAddressSuffixA() && "A".equals(symText()))
			next();
		if(!"/".equals(symText()))
			throw new ScannerUnknownExpressionException("no \"/\" after \"@<addr>\"");
		next();
		gobbleSpace();
		if(sym() != OdtScanner.Sym.OCTAL && !"?".equals(symText()))
			return null;
		AnswerPhrase r = makeExamine(addrText, symText());
		next();                                             // consume the value
		return r;
	}

	/** Anything else: take it to the end of the line and hand it over unread. */
	private AnswerPhrase parseOtherLine() {
		StringBuilder sb = new StringBuilder(symText());
		next();
		while(sym() != OdtScanner.Sym.EOLN) {
			sb.append(symText());
			next();                                         // may end the attempt with EOF
		}
		return new AnswerPhrase.OtherLine(sb.toString());
	}

	/**
	 * A prompt, and with it the news that a halt reported just before it is now safe to act on.
	 *
	 * <p>Deliberately not fired from the halt line itself ({@code :500-505}): a handler is
	 * expected to go straight on to issue console commands, which it can only do once ODT is
	 * prompting again.</p>
	 */
	private AnswerPhrase makePrompt() {
		AnswerPhrase previous = getAnswers().getLast();
		if(previous instanceof AnswerPhrase.Halt halt)
			signalExecutionStop(halt.haltAddr());
		else
			clearExecutionStop();
		return new AnswerPhrase.Prompt("@");
	}

	/** An address alone on a line. The PC it reports is virtual. */
	private AnswerPhrase makeHalt(String addrText) {
		try {
			return new AnswerPhrase.Halt(addrText, Address.parseOctal(addrText, MemoryAddressType.VIRTUAL));
		} catch(RuntimeException x) {
			throw new ScannerUnknownExpressionException("not an address: " + addrText);
		}
	}

	/**
	 * {@code addr/val}, where the address may be a register name and the value may be
	 * {@code ?}.
	 *
	 * <p>Ported from {@code makeExamine} ({@code :524-556}). {@code ?} is ODT's UNIBUS timeout,
	 * and it is an answer rather than an error - but it names no address, because ODT prints it
	 * where the value would go.</p>
	 */
	private AnswerPhrase makeExamine(String addrText, String valueText) {
		Address addr = regNameToAddr(addrText);
		if(addr == null) {
			try {
				addr = Address.parseOctal(addrText, m_addressType);
			} catch(RuntimeException x) {
				throw new ScannerUnknownExpressionException("not an address: " + addrText);
			}
		}
		if("?".equals(valueText))
			return new AnswerPhrase.ExamineResult(addrText + "/?", null, CellValue.UNKNOWN);
		long value;
		try {
			value = Octal.parse(valueText);
		} catch(RuntimeException x) {
			throw new ScannerUnknownExpressionException("not a value: " + valueText);
		}
		if(value > 0xFFFF)
			throw new ScannerUnknownExpressionException("wider than a word: " + valueText);
		return new AnswerPhrase.ExamineResult(addrText + "/" + valueText, addr, CellValue.of((int) value));
	}

	// -------------------------------------------------------------------------------------
	// Commands - command thread
	// -------------------------------------------------------------------------------------

	@Override
	public void init(ConsoleConnection connection) throws ConsoleException {
		super.init(connection);
		resync();
	}

	/**
	 * Hit return and make sure the {@code @} comes back.
	 *
	 * <p>Ported from {@code Resync} ({@code :573-590}). An LSI-11/03 may answer with a
	 * {@code ?} first and the {@code @} after it, which is why this waits for a prompt rather
	 * than for the next thing to arrive.</p>
	 */
	@Override
	public void resync() throws ConsoleException {
		resetScanner();
		writeToPdp(String.valueOf(CR));
		checkPrompt("Could not wake up PDP-11 ODT");
		getLogger().log(LogChannel.OTHER, "PDP-11 ODT ready and prompting \"@\"");
	}

	/** ODT only speaks physical addresses, so anything virtual has to go through the MMU. */
	private Address toPhysical(Address addr, boolean instructionSpace) throws ConsoleException {
		if(addr.type() == MemoryAddressType.VIRTUAL) {
			TranslationResult tr = instructionSpace
				? getMmu().translateInstruction(addr)
				: getMmu().translateData(addr);
			if(!tr.isValid())
				throw new ConsoleException("Cannot translate " + addr.toOctal() + ": " + tr.failure());
			return tr.address().withWidth(m_addressType);
		}
		if(addr.type() == m_addressType)
			return addr;
		if(addr.type().isConcretePhysical())
			return addr.withWidth(m_addressType);
		throw new ConsoleException("An ODT console cannot address " + addr);
	}

	@Override
	public CellValue examine(Address addr) throws ConsoleException {
		if(addr.type() == MemoryAddressType.SPECIAL_REGISTER) {
			//-- The only special register defined is the display register, and ODT cannot read
			//-- it: it is lamps on a panel, not a location ({@code :751-756}).
			return CellValue.UNKNOWN;
		}
		Address physical = toPhysical(addr, false);
		clearAnswers();
		writeToPdp(addressText(physical) + "/");

		int at = getAnswers().waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult, 0, CMD_TIMEOUT_MS);
		CellValue result;
		if(at < 0) {
			result = CellValue.UNKNOWN;                     // no answer at all
		} else {
			AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) getAnswers().get(at);
			if(r.value().isKnown() && r.examineAddr() != null && r.examineAddr().val() != physical.val())
				throw new ConsoleException("EXAMINE failure: asked for " + physical.toOctal()
					+ ", ODT answered \"" + r.rawText() + "\"");
			result = r.value();
			//-- A location that opened has to be closed again. A "?" answer never opened one,
			//-- and ODT has already printed its prompt ({@code :776-778}).
			if(r.value().isKnown())
				writeToPdp(String.valueOf(CR));
		}
		checkPromptAfter(at + 1, "EXAMINE failed, no prompt");
		return result;
	}

	/**
	 * Open a location, wait for its contents, then type the new value.
	 *
	 * <p>Ported from {@code Deposit} ({@code :700-733}). The address is translated through the
	 * <b>instruction</b> map rather than the data map, on the Pascal's reasoning that what gets
	 * written is nearly always code ({@code :714-715}).</p>
	 */
	@Override
	public void deposit(Address addr, int value) throws ConsoleException {
		Address physical = toPhysical(addr, true);
		clearAnswers();
		writeToPdp(addressText(physical) + "/");

		//-- CheckAddrOpen: the location has to be open before a value means anything.
		int at = getAnswers().waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult, 0, CMD_TIMEOUT_MS);
		if(at < 0)
			throw new NoConsolePromptException("DEPOSIT failed, the address did not open",
				m_scanner.getInput(), getAnswers().snapshot());
		writeToPdp(Octal.format(value & 0xFFFF, 1) + CR);
		checkPromptAfter(at + 1, "DEPOSIT failed, no prompt");
	}

	/**
	 * Read a whole group, one location at a time.
	 *
	 * <p>ODT has no way to ask for a range: {@code LF} advances to the next location, but only
	 * from an open one, and the Pascal does not use it ({@code :787-807}). So this is a loop,
	 * and on a 9600 baud line it is a slow one - which is what the progress monitor is for.</p>
	 */
	@Override
	public void examine(MemoryCellGroup g, boolean unknownOnly, ProgressMonitor pm) throws ConsoleException {
		List<MemoryCell> cells = List.copyOf(g.getCells());
		MemoryCellGroups owner = g.getOwner();
		pm.begin("Examining ...", cells.size());
		try {
			for(MemoryCell mc : cells) {
				pm.step(1);
				if(pm.isCancelled())
					break;
				if(unknownOnly && mc.getPdpValue().isKnown())
					continue;
				mc.setPdpValue(examine(mc.getAddr()));
				if(owner != null)
					owner.syncMemoryCells(mc);
			}
		} finally {
			pm.done();
		}
	}

	// -------------------------------------------------------------------------------------
	// Execution control
	// -------------------------------------------------------------------------------------

	/**
	 * {@code nnnG} with the machine halted: reset, set the PC, and stop again.
	 *
	 * <p>The same characters run the machine when the switch is at ENABLE, which is why this
	 * refuses rather than doing something else ({@code :809-830}).</p>
	 */
	@Override
	public void resetMachine(Address newPc) throws ConsoleException {
		requireRunMode(ConsoleRunMode.HALT, "RESET");
		requireVirtual(newPc);
		clearAnswers();
		writeToPdp(Octal.format(newPc.val(), 1) + "G");
		checkPrompt("RESET failed, no prompt");
	}

	/** {@code nnnG} with the machine enabled: reset and run. No prompt follows - it is running. */
	@Override
	public void resetAndStart(Address newPc) throws ConsoleException {
		requireRunMode(ConsoleRunMode.RUN, "START");
		requireVirtual(newPc);
		clearAnswers();
		clearExecutionStop();
		writeToPdp(Octal.format(newPc.val(), 1) + "G");
	}

	/** {@code P} with the machine enabled: proceed. No prompt follows. */
	@Override
	public void continueCpu() throws ConsoleException {
		requireRunMode(ConsoleRunMode.RUN, "CONTINUE");
		clearAnswers();
		clearExecutionStop();
		writeToPdp("P");
	}

	/** {@code P} with the machine halted: one instruction, then the address it stopped at. */
	@Override
	public void singleStep() throws ConsoleException {
		requireRunMode(ConsoleRunMode.HALT, "SINGLE STEP");
		clearAnswers();
		writeToPdp("P");
		checkPrompt("SINGLE STEP failed, no prompt");
	}

	/**
	 * ODT cannot stop a running machine, and neither can this.
	 *
	 * <p>The Pascal leaves {@code HaltCpu} abstract here, so calling it raises an
	 * abstract-method error rather than saying anything useful. ODT lives in the CPU's own
	 * microcode: while the CPU is executing a program it is not running ODT, so there is nobody
	 * on the other end to ask. The operator uses the HALT switch, and
	 * {@link ConsoleFeature#ACTION_HALT_CPU} is not among the features so the UI never offers
	 * the button.</p>
	 */
	@Override
	public Address haltCpu() throws ConsoleException {
		throw new ConsoleException("ODT cannot stop a running machine - use the HALT switch on the front panel");
	}

	private void requireRunMode(ConsoleRunMode required, String what) throws ConsoleException {
		if(getRunMode() != required)
			throw new ConsoleException(what + " needs the ENABLE/HALT switch at "
				+ (required == ConsoleRunMode.HALT ? "HALT" : "ENABLE"));
	}

	private static void requireVirtual(Address pc) {
		if(pc.type() != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("The start PC is a virtual address, not " + pc.type());
	}
}
