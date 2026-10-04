package to.etc.pdp11.core.console;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.util.LogChannel;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.Octal;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.core.mmu.Pdp11Mmu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Drives the console emulator in an M9312 or M9301 boot ROM: the console of an 11/04 or 11/34
 * that has no ODT.
 *
 * <p>Ported from {@code TConsolePDP11M9312} ({@code ConsolePDP11M9312U.pas}) and its M9301
 * subclass. The Pascal's summary of what it is driving, translated:</p>
 *
 * <blockquote>The M9312 console emulator runs out of the boot ROM on the CPU and is unbelievably
 * feeble:
 * <ul>
 * <li>only EXAMINE/DEPOSIT, in a 16-bit address space</li>
 * <li>only START - no stop, no single step</li>
 * <li>no reset, no bus initialise</li>
 * <li>once a program runs into a HALT, the machine can only be restarted from the front panel
 *     (CTRL-BOOT)</li>
 * <li>a BUS ERROR - an address that is not implemented - also ends in a HALT, so no I/O page
 *     scan or memory scan is possible</li>
 * <li>no access to the CPU registers R0..R7 at 177700..177707</li>
 * </ul>
 * The 11/34 has 18-bit addresses, but the console emulator only 16.</blockquote>
 *
 * <h2>A program, not a console</h2>
 *
 * <p>Every consequence of that list follows from one fact: the console emulator is code the CPU
 * executes, so anything that stops the CPU stops the console. The driver cannot recover from that
 * and does not try. What it does instead is refuse, before sending anything, the two mistakes it
 * can see coming - an odd address, and the register space - and say plainly, when the machine
 * stops answering, that this is what stopping answering means here. The third mistake, an address
 * with nothing behind it, cannot be seen coming, which is why this console does not claim
 * {@link ConsoleFeature#NON_FATAL_UNIBUS_TIMEOUT} and why the windows that would examine a range
 * on their own ask first.</p>
 *
 * <h2>The loaded address</h2>
 *
 * <p>Neither examine nor deposit takes an address. {@code L nnnnnn} loads one, and then {@code E }
 * and {@code D nnnnnn} use it - advancing it by two on the <i>second</i> examine or deposit in a
 * row, not the first. So a run of consecutive words costs one {@code L} and then one command per
 * word, which is what both bulk operations here arrange.</p>
 */
public final class M9312Console extends AbstractConsole {
	public static final char CR = '\r';

	/** 1 s, from {@code PDP11M9312_CMD_TIMEOUT} ({@code :66}), "long for slow telnet connections". */
	public static final long CMD_TIMEOUT_MS = 1000;

	/**
	 * The CPU registers in the I/O page, which the emulator cannot reach: R0..R7 and R10..R17,
	 * sixteen bytes from {@code 177700} ({@code :139-141}).
	 */
	private static final long GLOBAL_REGISTER_BASE = 0177700;

	private static final long GLOBAL_REGISTER_BLOCKSIZE = 16;

	private static final String DEAD_HINT = "a nonexistent address, or a program that halted, stops the "
		+ "console emulator, and only a reboot from the front panel brings it back";

	private final M9312Scanner m_scanner = new M9312Scanner();

	private final BootRom m_rom;

	private final Address m_monitorEntry;

	/**
	 * @param monitorEntry where a program can jump to get back into the console emulator, or
	 *                     {@code null}; see {@link BootRom#getDefaultMonitorEntry()}
	 */
	public M9312Console(MemoryCellGroups groups, BootRom rom, Address monitorEntry, Logger logger) {
		super(logger);
		m_rom = rom;
		m_monitorEntry = monitorEntry;
		setCommandTimeoutMillis(CMD_TIMEOUT_MS);
		setMmu(new Pdp11Mmu(groups));
	}

	public BootRom getRom() {
		return m_rom;
	}

	@Override
	public String name() {
		return m_rom.getName();
	}

	@Override
	public MemoryAddressType physicalAddressType() {
		return MemoryAddressType.PHYSICAL16;
	}

	/**
	 * Start, and nothing else ({@code :277-292}).
	 *
	 * <p>Neither {@link ConsoleFeature#NON_FATAL_HALT} nor
	 * {@link ConsoleFeature#NON_FATAL_UNIBUS_TIMEOUT}: both stop the CPU, and with it this
	 * console. And not {@link ConsoleFeature#RESET_CPU_SETS_PC} - there is no reset at all.</p>
	 */
	@Override
	public EnumSet<ConsoleFeature> features() {
		return EnumSet.of(ConsoleFeature.ACTION_RESET_AND_START_CPU);
	}

	/**
	 * Ported from {@code getTerminalSettings} ({@code :268-274}): the CRs are fill characters and
	 * the LF is the line end. The emulator never erases.
	 */
	@Override
	public TerminalProfile terminalProfile() {
		return TerminalProfile.of(false, true);
	}

	@Override
	public Address monitorEntryAddress() {
		return m_monitorEntry;
	}

	@Override
	protected ConsoleScanner<?> getScanner() {
		return m_scanner;
	}

	// -------------------------------------------------------------------------------------
	// Decoding - reader thread
	// -------------------------------------------------------------------------------------

	private M9312Scanner.Sym sym() {
		return m_scanner.getCurSymType();
	}

	private String symText() {
		return m_scanner.getCurSymText();
	}

	private void next() {
		m_scanner.nextSymbol(true);
	}

	/**
	 * Recognise one phrase of the emulator's output.
	 *
	 * <p>Ported from {@code DecodeNextAnswerPhrase} ({@code :357-566}). The grammar:</p>
	 *
	 * <pre>
	 * &lt;EOLN&gt;prompt                          prompt
	 * &lt;EOLN&gt;octal octal octal octal&lt;space&gt;  halt: the register dump
	 * E addr value                            examine - our "E " echoed, then the answer
	 * else: scan to EOLN                      otherline
	 * </pre>
	 *
	 * <p>The register dump is read as a halt because it is what the emulator prints when it
	 * starts - after a reboot, which is the only way back after a program halts. It also comes
	 * after a typing mistake, and after the second carriage return of {@link #resync()}; the
	 * Pascal accepts those as stops too, and so does this, because in each case the machine is
	 * indeed stopped at a prompt. The four numbers are the emulator's own registers, not the
	 * program's, so the stop carries no PC.</p>
	 *
	 * <p>As in {@link OdtConsole}, a pass that consumes input without producing a phrase reports
	 * progress, where the Pascal returns false and leaves what follows undecoded until the next
	 * byte arrives.</p>
	 */
	@Override
	protected boolean decodeNextAnswerPhrase() {
		m_scanner.markParsePosition();
		AnswerPhrase phrase;
		try {
			//-- Clear the end-of-input left by the previous scan; throws if there is still nothing.
			if(sym() == M9312Scanner.Sym.EOF)
				next();
			phrase = parsePhrase();
		} catch(ScannerUnknownExpressionException x) {
			getLogger().log(LogChannel.PROTOCOL, "Discarding unrecognised console input: " + x.getMessage());
			m_scanner.cleanupInput();
			return true;
		} catch(ScannerInputIncompleteException x) {
			m_scanner.restoreParsePosition();
			return false;
		}
		boolean consumed = m_scanner.getNextCharIndex() > 0;
		m_scanner.cleanupInput();
		if(phrase == null)
			return consumed;
		publish(phrase);
		return true;
	}

	private AnswerPhrase parsePhrase() {
		if(sym() == M9312Scanner.Sym.EOLN)
			return parseAfterLineEnd();
		if(sym() == M9312Scanner.Sym.OPCODE && "E".equals(symText()))
			return parseExamine();
		return parseOtherLine();
	}

	/** After a line end: the prompt, or the register dump. Anything else is left for the next pass. */
	private AnswerPhrase parseAfterLineEnd() {
		next();
		if(m_rom.getPrompt().equals(symText())) {
			//-- Read past the prompt without waiting for more: the prompt is complete, and
			//-- nothing may follow it for a long time.
			m_scanner.nextSymbol(false);
			return makePrompt();
		}
		if(sym() != M9312Scanner.Sym.OCTAL)
			return null;

		StringBuilder raw = new StringBuilder();
		for(int n = 1; n <= 4; n++) {
			if(sym() != M9312Scanner.Sym.OCTAL)
				throw new ScannerUnknownExpressionException("only " + (n - 1) + " octal const, 4 expected for HALT");
			//-- The Pascal throws "incomplete" for a short fourth number (:497-498). A number
			//-- followed by its terminator is not incomplete, and a decoder told it is rewinds
			//-- and fails on the same text forever. Every one of the four is six digits.
			if(symText().length() != 6)
				throw new ScannerUnknownExpressionException("octal const " + n + " is not 6 digits for HALT");
			raw.append(symText()).append(' ');
			next();
			if(!" ".equals(symText()))
				throw new ScannerUnknownExpressionException("Space after octal const " + n + " expected for HALT");
			if(n < 4)
				next();
		}
		next();                                             // past the space; the EOLN stays current
		return new AnswerPhrase.Halt(raw.toString().trim(), null);
	}

	/** {@code E addr value}: our own {@code E } echoed, then the emulator's answer. */
	private AnswerPhrase parseExamine() {
		next();
		if(!" ".equals(symText()))
			throw new ScannerUnknownExpressionException("Space after \"E\" expected for EXAMINE");
		next();
		if(sym() != M9312Scanner.Sym.OCTAL)
			throw new ScannerUnknownExpressionException("octal addr expected after \"E\"");
		String addrText = symText();
		next();
		if(!" ".equals(symText()))
			throw new ScannerUnknownExpressionException("Space after <addr> expected for EXAMINE");
		next();
		if(sym() != M9312Scanner.Sym.OCTAL)
			throw new ScannerUnknownExpressionException("octal val expected after \"E\"");
		String valueText = symText();
		AnswerPhrase r = makeExamine(addrText, valueText);
		next();                                             // consume the value
		return r;
	}

	/** Anything else: take it to the end of the line and hand it over unread. The EOLN stays. */
	private AnswerPhrase parseOtherLine() {
		StringBuilder sb = new StringBuilder(symText());
		next();
		while(sym() != M9312Scanner.Sym.EOLN) {
			sb.append(symText());
			next();
		}
		return new AnswerPhrase.OtherLine(sb.toString());
	}

	/** A prompt, and with it the news that a register dump just before it was a stop. */
	private AnswerPhrase makePrompt() {
		AnswerPhrase.Halt halt = takeHaltAwaitingPrompt();
		if(halt != null)
			signalExecutionStop(halt.haltAddr());
		else
			clearExecutionStop();
		return new AnswerPhrase.Prompt(m_rom.getPrompt());
	}

	/**
	 * Ported from {@code makeExamine} ({@code :405-432}). There is no timeout answer to decode:
	 * a UNIBUS timeout prints nothing, because it stops the emulator ({@code :422}).
	 */
	private AnswerPhrase makeExamine(String addrText, String valueText) {
		Address addr;
		long value;
		try {
			addr = Address.parseOctal(addrText, MemoryAddressType.PHYSICAL16);
			value = Octal.parse(valueText);
		} catch(RuntimeException x) {
			throw new ScannerUnknownExpressionException("not an examine answer: E " + addrText + " " + valueText);
		}
		if(value > 0xFFFF)
			throw new ScannerUnknownExpressionException("wider than a word: " + valueText);
		return new AnswerPhrase.ExamineResult("E " + addrText + " " + valueText, addr, CellValue.of((int) value));
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
	 * Wait for a prompt; failing that hit return, wait, and hit return again.
	 *
	 * <p>Ported from {@code Resync} ({@code :302-332}), in its order, which matters. Straight
	 * after a boot the prompt is already on its way, and the first wait catches it without
	 * sending anything. The emulator only <i>remembers</i> the first carriage return after a
	 * prompt; it is the second that does something, and what it does with a line holding nothing
	 * else is call it a mistake and print a register dump and a fresh prompt. A CR sent when none
	 * was needed is therefore not harmless - it stays remembered, and the next command arrives
	 * behind it as nonsense. So never two at once, and none at all if a prompt turns up.</p>
	 */
	@Override
	public void resync() throws ConsoleException {
		resetScanner();
		if(!promptWithin()) {
			writeToPdp(String.valueOf(CR));
			if(!promptWithin()) {
				writeToPdp(String.valueOf(CR));
				checkPrompt("Could not wake up " + name() + " - " + DEAD_HINT);
			}
		}
		getLogger().log(LogChannel.OTHER, name() + " ready and prompting \"" + m_rom.getPrompt() + "\"");
	}

	private boolean promptWithin() {
		return getAnswers().waitFor(AnswerPhrase.Prompt.class, 0, getCommandTimeoutMillis()) != null;
	}

	/**
	 * Load an address, then {@code E }.
	 *
	 * <p>Ported from {@code Examine} ({@code :737-788}). The registers are answered as unknown
	 * without asking, as the Pascal does. A nonexistent address is <b>not</b> answered as
	 * unknown, unlike every other console: the Pascal returns its sentinel and then fails on the
	 * missing prompt, but the honest answer is that the machine has just stopped, and that is
	 * what the exception says.</p>
	 */
	@Override
	public CellValue examine(Address addr) throws ConsoleException {
		if(addr.type() == MemoryAddressType.SPECIAL_REGISTER)
			return CellValue.UNKNOWN;                       // the display register is lamps, not a location
		Address physical = toPhysical(addr, false);
		if(isRegisterSpace(physical))
			return CellValue.UNKNOWN;
		requireEven(physical, "EXAMINE");
		loadAddress(physical);
		return examineLoaded(physical);
	}

	/**
	 * Load an address, then {@code D value}.
	 *
	 * <p>Ported from {@code Deposit} ({@code :693-731}), through the instruction map as every
	 * console here deposits. Two differences. The register space is refused out loud where the
	 * Pascal skips it silently - a group deposit would otherwise mark the cell deposited. And a
	 * single deposit always loads its address: the Pascal remembers the last one across calls to
	 * save the {@code L}, but somebody typing at the terminal in between moves the emulator's
	 * address and not the remembered one. A group deposit, which nothing can interrupt, does the
	 * saving instead.</p>
	 */
	@Override
	public void deposit(Address addr, int value) throws ConsoleException {
		Address physical = toPhysical(addr, true);
		requireDepositable(physical);
		loadAddress(physical);
		depositLoaded(value);
	}

	/**
	 * Write a group, with one {@code L} per run of consecutive words.
	 *
	 * <p>The rules for which cells are written are {@link AbstractConsole#deposit(MemoryCellGroup,
	 * boolean, ProgressMonitor)}'s, unchanged: unknown edit values are never sent, and with
	 * {@code optimize} neither is a value the machine already holds. Only the address loading
	 * differs.</p>
	 */
	@Override
	public void deposit(MemoryCellGroup g, boolean optimize, ProgressMonitor pm) throws ConsoleException {
		List<MemoryCell> cells = List.copyOf(g.getCells());
		MemoryCellGroups owner = g.getOwner();
		Address last = null;                                // what the emulator's address now is, after a D
		pm.begin("Depositing ...", cells.size());
		try {
			for(MemoryCell mc : cells) {
				pm.step(1);
				if(pm.isCancelled())
					break;
				if(!mc.getEditValue().isKnown())
					continue;
				if(optimize && !mc.isEdited())
					continue;
				Address physical = toPhysical(mc.getAddr(), true);
				requireDepositable(physical);
				//-- After an L the next D does not advance; after a D it does. So a D straight
				//-- after a D lands two further on, and needs no L if that is where it should go.
				if(last == null || physical.val() != last.val() + 2)
					loadAddress(physical);
				depositLoaded(mc.getEditValue().word());
				last = physical;
				mc.setDeposited();
				if(owner != null)
					owner.syncMemoryCells(mc);
			}
		} finally {
			pm.done();
		}
	}

	/**
	 * Read a group, with one {@code L} per run of consecutive words and then one {@code E } each.
	 *
	 * <p>Ported from {@code Examine(mcg, ...)} ({@code :798-964}). The emulator cannot be asked for
	 * a block, and it must not be sent a string of commands to answer in turn: it validates as it
	 * reads and prints with the CPU, so it is never listening while it talks. One examine, one
	 * answer, one prompt.</p>
	 *
	 * <p>Registers are left unknown, as in the Pascal. A nonexistent address stops the machine, and
	 * what was read before it is kept and propagated before the exception goes up.</p>
	 */
	@Override
	public void examine(MemoryCellGroup g, boolean unknownOnly, ProgressMonitor pm) throws ConsoleException {
		List<MemoryCell> cells = List.copyOf(g.getCells());
		List<ExamineItem> memory = new ArrayList<>();
		for(MemoryCell mc : cells) {
			if(unknownOnly && mc.isMachineValueCurrent())
				continue;
			if(mc.getAddr().type() == MemoryAddressType.SPECIAL_REGISTER) {
				mc.setPdpValue(CellValue.UNKNOWN);
				continue;
			}
			Address physical = toPhysical(mc.getAddr(), false);
			if(isRegisterSpace(physical)) {
				mc.setPdpValue(CellValue.UNKNOWN);
				continue;
			}
			requireEven(physical, "EXAMINE");
			memory.add(new ExamineItem(mc, physical));
		}
		memory.sort(Comparator.comparingLong(it -> it.getPhysical().val()));

		pm.begin("Examining ...", memory.size());
		try {
			Address loaded = null;                          // the last address examined since an L
			for(ExamineItem it : memory) {
				pm.step(1);
				if(pm.isCancelled())
					break;
				Address physical = it.getPhysical();
				//-- After an L the next E does not advance; after an E it does.
				if(loaded == null || physical.val() != loaded.val() + 2)
					loadAddress(physical);
				it.getCell().setPdpValue(examineLoaded(physical));
				loaded = physical;
			}
		} finally {
			pm.done();
			//-- One propagation pass at the end rather than one per word, even when the machine
			//-- stopped halfway: what was read before it is still true.
			MemoryCellGroups owner = g.getOwner();
			if(owner != null) {
				for(MemoryCell mc : cells) {
					owner.syncMemoryCells(mc);
				}
			}
		}
	}

	/** {@code L nnnnnn}, and its prompt. */
	private void loadAddress(Address physical) throws ConsoleException {
		clearAnswers();
		writeToPdp("L " + Octal.format(physical.val(), 6) + CR);
		checkPrompt("LOAD ADDRESS " + physical.toOctal() + " failed - " + DEAD_HINT);
	}

	/** {@code E } at whatever is loaded, which the caller says it expects to be {@code expected}. */
	private CellValue examineLoaded(Address expected) throws ConsoleException {
		clearAnswers();
		writeToPdp("E ");
		int at = getAnswers().waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult, 0, getCommandTimeoutMillis());
		if(at < 0) {
			NoConsolePromptException x = new NoConsolePromptException("No answer to EXAMINE " + expected.toOctal()
				+ " from the " + name() + ": " + DEAD_HINT, getUnconsumedInput(), getAnswers().snapshot());
			x.logDiagnostics(getLogger());
			throw x;
		}
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) getAnswers().get(at);
		if(r.examineAddr().val() != expected.val())
			throw new ConsoleException("EXAMINE failure: asked for " + expected.toOctal()
				+ ", the " + name() + " answered \"" + r.rawText() + "\"");
		checkPromptAfter(at + 1, "EXAMINE " + expected.toOctal() + " failed, no prompt");
		return r.value();
	}

	/** {@code D nnnnnn} at whatever is loaded. Prints nothing but the prompt. */
	private void depositLoaded(int value) throws ConsoleException {
		clearAnswers();
		writeToPdp("D " + Octal.format(value & 0xFFFF, 6) + CR);
		checkPrompt("DEPOSIT failed - " + DEAD_HINT);
	}

	private static boolean isRegisterSpace(Address physical) {
		long v = physical.val();
		return v >= GLOBAL_REGISTER_BASE && v < GLOBAL_REGISTER_BASE + GLOBAL_REGISTER_BLOCKSIZE;
	}

	private void requireDepositable(Address physical) throws ConsoleException {
		if(isRegisterSpace(physical))
			throw new ConsoleException("The " + name() + " cannot reach the CPU registers at " + physical.toOctal());
		requireEven(physical, "DEPOSIT");
	}

	/**
	 * Refuse an odd address before the emulator sees it: it would halt on it, and the only way
	 * back is the front panel.
	 */
	private void requireEven(Address physical, String what) throws ConsoleException {
		if((physical.val() & 1) != 0)
			throw new ConsoleException(what + " at odd address " + physical.toOctal()
				+ " refused: it would halt the " + name());
	}

	// -------------------------------------------------------------------------------------
	// Execution control
	// -------------------------------------------------------------------------------------

	/**
	 * {@code L pc}, then {@code S}. No prompt follows: the program has the CPU now.
	 *
	 * <p>Ported from {@code ResetMachineAndStartCpu} ({@code :984-1008}). The PC is a 16-bit
	 * virtual address and goes to the emulator untranslated, as there - {@code S} initialises the
	 * bus, and with it the MMU. An odd PC is refused here, because the emulator would halt on it.</p>
	 */
	@Override
	public void resetAndStart(Address newPc) throws ConsoleException {
		if(newPc.type() != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("The start PC is a virtual address, not " + newPc.type());
		Address pc = Address.of(MemoryAddressType.PHYSICAL16, newPc.val() & 0xFFFF);
		requireEven(pc, "START");
		loadAddress(pc);
		clearAnswers();
		clearExecutionStop();
		writeToPdp("S" + CR);
	}

	@Override
	public void resetMachine(Address newPc) throws ConsoleException {
		throw new ConsoleException("The " + name() + " has no reset - START is the only way to run anything");
	}

	@Override
	public void continueCpu() throws ConsoleException {
		throw new ConsoleException("The " + name() + " cannot continue a stopped program - it can only START one");
	}

	/**
	 * Nothing to talk to: while a program runs, the console emulator is not running.
	 *
	 * <p>Use the HALT switch, then boot the M9312 from the front panel to get a prompt back.</p>
	 */
	@Override
	public Address haltCpu() throws ConsoleException {
		throw new ConsoleException("The " + name() + " cannot stop a running program - use the HALT switch,"
			+ " then reboot from the front panel");
	}

	@Override
	public void singleStep() throws ConsoleException {
		throw new ConsoleException("The " + name() + " cannot single step");
	}
}
