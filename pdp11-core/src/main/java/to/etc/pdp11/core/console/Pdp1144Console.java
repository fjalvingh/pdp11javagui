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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Drives a PDP-11/44 through its console processor.
 *
 * <p>Ported from {@code TConsolePDP1144} ({@code ConsolePDP1144U.pas}) and the four-line subclass
 * {@code ConsolePDP1144v340cU.pas}, which becomes {@link Pdp1144Firmware}. Unlike ODT, the 11/44's
 * console is a separate microprocessor with a real command language, and unlike SimH it is on the
 * other end of a serial line to a machine that is actually there.</p>
 *
 * <h2>Two things this console does that neither of the others do</h2>
 *
 * <p><b>It can read a block in one command.</b> {@code E/N:10 17772370} returns sixteen
 * consecutive words, one line each, which is what makes a memory window over a serial line
 * bearable. It stops at the first address that does not answer, and - since {@code ?20 TRAN ERR}
 * names no address - which one that was has to be worked out from the last one that did.</p>
 *
 * <p><b>A stop and an examine look identical.</b> The console reports a halt by examining R7 at
 * you: {@code 17777707 000114} means "the CPU stopped at 000114", and it is also exactly what
 * {@code E/G 7} answers. So one line has to become <i>two</i> phrases - a halt and an examine -
 * and in that order, because it is the prompt after them that fires the stop event and the prompt
 * looks one further back for it. The Pascal does this by parsing the line, keeping the halt, and
 * then deliberately pretending it found nothing so the same line gets parsed again
 * ({@code :318-321}).</p>
 */
public final class Pdp1144Console extends AbstractConsole {
	public static final char CR = '\r';

	public static final char LF = '\n';

	/** Gets the console's attention; on a running machine it switches the terminal to console mode. */
	public static final char CTRL_P = 0x10;

	/** Abandons whatever is being typed and prompts again. */
	public static final char CTRL_C = 3;

	public static final String PROMPT = ">>>";

	/** 1 s, from {@code PDP_CMD_TIMEOUT} ({@code :47}) - "long for slow telnet connections". */
	public static final long CMD_TIMEOUT_MS = 1000;

	/**
	 * How long to let the terminal settle after {@code ^P} before typing at it.
	 *
	 * <p>100 ms, from {@code HaltCpu} ({@code :812}). {@code ^P} is not a console command but a
	 * mode switch, and there is nothing it answers that could be waited for instead.</p>
	 */
	public static final long CONSOLE_MODE_SETTLE_MS = 100;

	/**
	 * R0..R7 and R10..R17, at sixteen <b>consecutive byte</b> addresses.
	 *
	 * <p>That is what {@code /G} exists for: {@code E/G 7} rather than an address, because the
	 * console will not take one. It is also why the address step inside a register block is 1 and
	 * not 2 ({@code :117-119}).</p>
	 */
	private static final long GLOBAL_REGISTER_BASE = 017777700L;

	private static final int GLOBAL_REGISTER_BLOCKSIZE = 16;

	/** The longest run of addresses to put in one {@code E/N:} command. {@code :551}. */
	private static final int MAX_BLOCK_LEN = 100;

	/**
	 * The scanner this console does not use.
	 *
	 * <p>{@code TConsolePDP1144Scanner.NxtSym} raises "not implemented" ({@code :130-134}), same
	 * as SimH's: the dialect is line-oriented, so the decoder works on the buffer directly. What
	 * the scanner still provides is the buffer, the NUL filtering and the consumption.
	 */
	public static final class Pdp1144Scanner extends ConsoleScanner<Pdp1144Scanner.Sym> {
		/** Never tokenised, so there is exactly one symbol and it never appears. */
		public enum Sym {
			NONE
		}

		@Override
		public String nextSymbol(boolean raiseIncompleteOnEof) {
			throw new UnsupportedOperationException("The PDP-11/44 decoder is line-oriented and does not tokenise");
		}
	}

	private final Pdp1144Scanner m_scanner = new Pdp1144Scanner();

	private final Pdp1144Firmware m_firmware;

	/**
	 * The last address deposited into, so the next one can be {@code D + value}.
	 *
	 * <p>Worth having: a bulk deposit is one command per word whatever happens, and {@code D + v}
	 * is half the characters of {@code D 17772370 v} on a line where characters are the cost.</p>
	 */
	private Address m_lastDepositAddr;

	public Pdp1144Console(MemoryCellGroups groups, Pdp1144Firmware firmware, Logger logger) {
		super(logger);
		m_firmware = firmware;
		setCommandTimeoutMillis(CMD_TIMEOUT_MS);
		setMmu(new Pdp11Mmu(groups));
	}

	public Pdp1144Firmware getFirmware() {
		return m_firmware;
	}

	@Override
	public String name() {
		return m_firmware == Pdp1144Firmware.V340C ? "PDP-11/44 V3.40C console" : "PDP-11/44 console";
	}

	@Override
	public MemoryAddressType physicalAddressType() {
		return MemoryAddressType.PHYSICAL22;
	}

	/**
	 * Everything except a switch.
	 *
	 * <p>An 11/44 does have a HALT/CONTINUE switch, and the Pascal's comment says plainly that it
	 * ignores it ({@code :161-162}). That is a reasonable thing to keep: unlike ODT, this console
	 * answers whatever the switch is doing, so nothing here depends on knowing.</p>
	 */
	@Override
	public EnumSet<ConsoleFeature> features() {
		//-- No RESET_CPU_SETS_PC: "I" initialises but leaves the PC alone.
		return EnumSet.of(
			ConsoleFeature.NON_FATAL_HALT,
			ConsoleFeature.NON_FATAL_UNIBUS_TIMEOUT,
			ConsoleFeature.ACTION_RESET_MACHINE,
			ConsoleFeature.ACTION_RESET_AND_START_CPU,
			ConsoleFeature.ACTION_CONTINUE_CPU,
			ConsoleFeature.ACTION_HALT_CPU,
			ConsoleFeature.ACTION_SINGLE_STEP);
	}

	/**
	 * Ported from {@code getTerminalSettings} ({@code ConsolePDP1144U.pas:158-165}).
	 *
	 * <p>The opposite of ODT, and the reason a profile exists at all: this console ends a line
	 * with a <b>lone CR</b> and its LFs are to be ignored. Handing that stream to a conforming
	 * VT100 emulator would overwrite every line with the next one.</p>
	 */
	@Override
	public TerminalProfile terminalProfile() {
		return TerminalProfile.of(true, false);
	}

	@Override
	protected ConsoleScanner<?> getScanner() {
		return m_scanner;
	}

	// -------------------------------------------------------------------------------------
	// Addresses
	// -------------------------------------------------------------------------------------

	private static boolean isGlobalRegister(long addrValue) {
		return addrValue >= GLOBAL_REGISTER_BASE
			&& addrValue < GLOBAL_REGISTER_BASE + GLOBAL_REGISTER_BLOCKSIZE;
	}

	/** This console only speaks physical addresses, so anything virtual goes through the MMU. */
	private Address toPhysical(Address addr, boolean instructionSpace) throws ConsoleException {
		if(addr.type() == MemoryAddressType.VIRTUAL) {
			TranslationResult tr = instructionSpace
				? getMmu().translateInstruction(addr)
				: getMmu().translateData(addr);
			if(!tr.isValid())
				throw new ConsoleException("Cannot translate " + addr.toOctal() + ": " + tr.failure());
			return tr.address().withWidth(MemoryAddressType.PHYSICAL22);
		}
		if(addr.type() == MemoryAddressType.PHYSICAL22)
			return addr;
		if(addr.type().isConcretePhysical())
			return addr.withWidth(MemoryAddressType.PHYSICAL22);
		throw new ConsoleException("A PDP-11/44 console cannot address " + addr);
	}

	// -------------------------------------------------------------------------------------
	// Decoding - reader thread
	// -------------------------------------------------------------------------------------

	private static boolean isEoln(char c) {
		return c == CR || c == LF;
	}

	/**
	 * Recognise one phrase, or two when a line is both.
	 *
	 * <p>Ported from {@code DecodeNextAnswerPhrase} ({@code :232-421}), indices converted from
	 * 1-based to 0-based.</p>
	 */
	@Override
	protected boolean decodeNextAnswerPhrase() {
		int i = 0;
		while(i < m_scanner.length() && isEoln(m_scanner.charAt(i))) {
			i++;
		}
		m_scanner.dropLeading(i);

		//-- Scan to the end of the line, or until exactly the prompt has been collected. Note
		//-- "equals", not "ends with" as SimH needs: this console always puts its prompt at the
		//-- start of a line, so there is no glued case to allow for.
		StringBuilder line = new StringBuilder();
		i = 0;
		while(i < m_scanner.length()
			&& !PROMPT.contentEquals(line)
			&& !isEoln(m_scanner.charAt(i))) {
			line.append(m_scanner.charAt(i));
			i++;
		}
		boolean eoln = i < m_scanner.length() && isEoln(m_scanner.charAt(i));
		String curline = line.toString();

		AnswerPhrase phrase = null;
		if(PROMPT.equals(curline)) {
			phrase = makePrompt(curline);
		} else if(eoln) {
			//-- A stop report is also an examine answer, so the same line becomes both. The halt
			//-- goes out first, because the prompt that follows looks past the examine for it.
			String pcText = haltPcText(curline);
			if(pcText != null) {
				try {
					publish(new AnswerPhrase.Halt(curline, Address.parseOctal(pcText.trim(),
						MemoryAddressType.VIRTUAL)));
				} catch(RuntimeException x) {
					getLogger().log(LogChannel.PROTOCOL, "Not a stop report after all: " + curline);
				}
			}
			phrase = decodeExamine(curline);
			if(phrase == null)
				phrase = new AnswerPhrase.OtherLine(curline);
		}

		if(phrase == null)
			return false;
		m_scanner.dropLeading(curline.length());
		publish(phrase);
		return true;
	}

	/**
	 * A prompt, and with it the stop event for a halt reported just before it.
	 *
	 * <p>Note it looks <b>two</b> phrases back, not one ({@code :275-277}). The line that reported
	 * the halt also produced an examine answer, which was published after it, so one back is the
	 * examine and two back is the halt.</p>
	 */
	private AnswerPhrase makePrompt(String curline) {
		int size = getAnswers().size();
		AnswerPhrase beforeLast = size >= 2 ? getAnswers().get(size - 2) : null;
		if(beforeLast instanceof AnswerPhrase.Halt halt)
			signalExecutionStop(halt.haltAddr());
		else
			clearExecutionStop();
		return new AnswerPhrase.Prompt(curline);
	}

	/**
	 * The PC out of a stop report, or {@code null} if this line is not one.
	 *
	 * <p>The classic firmware requires {@code 17777707} at the <i>start</i> of the line
	 * ({@code :331}), because that string appearing anywhere else is just an address.</p>
	 */
	private String haltPcText(String curline) {
		int at;
		if(m_firmware == Pdp1144Firmware.V340C) {
			at = curline.indexOf("Halted at");
			if(at < 0)
				return null;
		} else {
			if(!curline.startsWith("17777707"))
				return null;
			at = 0;
		}
		int from = at + 9;
		if(from >= curline.length())
			return null;
		return curline.substring(from, Math.min(from + 7, curline.length()));
	}

	/**
	 * An examine answer, or {@code null}.
	 *
	 * <p>A nonexistent address is an answer rather than an error - but the console names no
	 * address when it says so, so the caller has to attribute it to whatever it asked for
	 * next.</p>
	 */
	private AnswerPhrase decodeExamine(String curline) {
		if(curline.contains(m_firmware.getBusTimeoutMarker()))
			return new AnswerPhrase.ExamineResult(curline, null, CellValue.UNKNOWN);

		String[] w = curline.trim().split("[ \t]+");
		String addrText;
		String valueText;
		long offset = 0;
		if(m_firmware == Pdp1144Firmware.V340C) {
			//-- "  P  12345670 123456", or "  G  01 123456" for R1.
			if(w.length != 3)
				return null;
			if("G".equals(w[0]))
				offset = GLOBAL_REGISTER_BASE;
			else if(!"P".equals(w[0]))
				return null;
			addrText = w[1];
			valueText = w[2];
		} else {
			//-- "00000000 222222".
			if(w.length != 2)
				return null;
			addrText = w[0];
			valueText = w[1];
		}
		long addrValue;
		long value;
		try {
			addrValue = Octal.parse(addrText) + offset;
			value = Octal.parse(valueText);
		} catch(RuntimeException x) {
			return null;
		}
		if(value > 0xFFFF || addrValue > MemoryAddressType.PHYSICAL22.getMaxAddress() + 1)
			return null;
		return new AnswerPhrase.ExamineResult(curline,
			Address.of(MemoryAddressType.PHYSICAL22, addrValue), CellValue.of((int) value));
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
	 * Hit {@code ^C} and make sure the {@code >>>} comes back.
	 *
	 * <p>Ported from {@code Resync} ({@code :194-209}). {@code ^C} rather than a carriage return
	 * because it also abandons any half-typed line, which is the state a resync is usually
	 * getting out of.</p>
	 */
	@Override
	public void resync() throws ConsoleException {
		resetScanner();
		m_lastDepositAddr = null;
		writeToPdp(String.valueOf(CTRL_C));
		checkPrompt("Could not wake up PDP-11/44");
		getLogger().log(LogChannel.OTHER, "PDP-11/44 ready and prompting \">>>\"");
	}

	@Override
	public void clearState() {
		super.clearState();
		//-- Force the next deposit to name its address rather than trusting "+".
		m_lastDepositAddr = null;
	}

	@Override
	public CellValue examine(Address addr) throws ConsoleException {
		if(addr.type() == MemoryAddressType.SPECIAL_REGISTER) {
			//-- Only the display register is defined and an 11/44 cannot be asked for it.
			return CellValue.UNKNOWN;
		}
		Address physical = toPhysical(addr, false);
		String cmd = isGlobalRegister(physical.val())
			? "E/G " + Octal.format(physical.val() - GLOBAL_REGISTER_BASE, 1)
			: "E " + Octal.format(physical.val(), 1);

		clearAnswers();
		writeToPdp(cmd + CR);
		int at = getAnswers().waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult, 0, CMD_TIMEOUT_MS);
		CellValue result;
		if(at < 0) {
			result = CellValue.UNKNOWN;                     // no answer at all
		} else {
			AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) getAnswers().get(at);
			if(r.value().isKnown() && r.examineAddr() != null && r.examineAddr().val() != physical.val())
				throw new ConsoleException("EXAMINE failure: asked for " + physical.toOctal()
					+ ", the console answered \"" + r.rawText() + "\"");
			result = r.value();
		}
		checkPromptAfter(at + 1, "EXAMINE failed, no prompt");
		return result;
	}

	/**
	 * Write one word.
	 *
	 * <p>Ported from {@code Deposit} ({@code :420-455}). The address is translated through the
	 * <b>instruction</b> map, on the Pascal's reasoning that what gets written is nearly always
	 * code ({@code :428-429}).</p>
	 */
	@Override
	public void deposit(Address addr, int value) throws ConsoleException {
		Address physical = toPhysical(addr, true);
		String valueText = Octal.format(value & 0xFFFF, 1);
		String cmd;
		if(isGlobalRegister(physical.val())) {
			cmd = "D/G " + Octal.format(physical.val() - GLOBAL_REGISTER_BASE, 1) + " " + valueText;
			m_lastDepositAddr = null;                       // "+" means the next memory word, not the next register
		} else {
			//-- Consecutive deposits can say "+" instead of repeating the address.
			cmd = m_lastDepositAddr != null && physical.val() == m_lastDepositAddr.val() + 2
				? "D + " + valueText
				: "D " + Octal.format(physical.val(), 1) + " " + valueText;
			m_lastDepositAddr = physical;
		}
		clearAnswers();
		writeToPdp(cmd + CR);
		checkPrompt("DEPOSIT failed, no prompt");
	}

	// -------------------------------------------------------------------------------------
	// Bulk examine
	// -------------------------------------------------------------------------------------

	/** One cell, its physical address, and whether the console has answered about it yet. */
	private static final class ExamineItem {
		private final MemoryCell m_cell;

		private final Address m_physical;

		private boolean m_done;

		private ExamineItem(MemoryCell cell, Address physical) {
			m_cell = cell;
			m_physical = physical;
		}
	}

	/**
	 * Read a whole group, a block per command.
	 *
	 * <p>Ported from {@code TConsolePDP1144.Examine(mcg, ...)} ({@code :540-742}). This is the
	 * function the Pascal's own comment calls the core of PDP11GUI - "schnell viele
	 * Memoryadressen auslesen" - and it is: {@code E/N:100 <addr>} is sixty-four words for one
	 * round trip, against sixty-four round trips without it.</p>
	 *
	 * <p>Memory and global registers go in separate lists because they step differently, 2 against
	 * 1, and because the registers need {@code /G} and an offset rather than an address.</p>
	 */
	@Override
	public void examine(MemoryCellGroup g, boolean unknownOnly, ProgressMonitor pm) throws ConsoleException {
		List<ExamineItem> memory = new ArrayList<>();
		List<ExamineItem> registers = new ArrayList<>();
		for(MemoryCell mc : List.copyOf(g.getCells())) {
			if(unknownOnly && mc.getPdpValue().isKnown())
				continue;
			Address physical = toPhysical(mc.getAddr(), false);
			if(isGlobalRegister(physical.val()))
				registers.add(new ExamineItem(mc, physical));
			else
				memory.add(new ExamineItem(mc, physical));
		}
		memory.sort(Comparator.comparingLong(a -> a.m_physical.val()));
		registers.sort(Comparator.comparingLong(a -> a.m_physical.val()));

		pm.begin("Examining ...", memory.size() + registers.size());
		try {
			runExamineList(memory, 2, pm);
			runExamineList(registers, 1, pm);
		} finally {
			pm.done();
		}
		//-- One propagation pass at the end rather than one per word.
		MemoryCellGroups owner = g.getOwner();
		if(owner != null) {
			for(MemoryCell mc : List.copyOf(g.getCells())) {
				owner.syncMemoryCells(mc);
			}
		}
	}

	/**
	 * Keep passing over the list until nothing is left, or until a pass answers nothing new.
	 *
	 * <p>The Pascal's {@code while not examineAddrList(...) do ;} terminates because a comment
	 * says every call marks at least one more cell answered. Counting what is left makes that
	 * something the code enforces rather than something it hopes - the same hardening the SimH
	 * console got, and for the same reason.</p>
	 */
	private void runExamineList(List<ExamineItem> list, int addrInc, ProgressMonitor pm) throws ConsoleException {
		int outstanding = list.size();
		while(!examineAddrList(list, addrInc, pm)) {
			int now = 0;
			for(ExamineItem it : list) {
				if(!it.m_done)
					now++;
			}
			if(now >= outstanding) {
				getLogger().log(LogChannel.OTHER,
					"EXAMINE list: giving up with " + now + " cell(s) unanswered - no progress this pass");
				return;
			}
			outstanding = now;
		}
	}

	/**
	 * One pass over the not-yet-answered cells.
	 *
	 * @param addrInc 2 between memory words, 1 between the byte-spaced global registers
	 * @return true when there is nothing left to do
	 */
	private boolean examineAddrList(List<ExamineItem> list, int addrInc, ProgressMonitor pm) throws ConsoleException {
		int blockstart = -1;
		for(int i = 0; i < list.size(); i++) {
			if(!list.get(i).m_done) {
				blockstart = i;
				break;
			}
		}
		if(blockstart < 0)
			return true;

		boolean blockFailure = false;
		while(!pm.isCancelled() && !blockFailure && blockstart < list.size()) {
			//-- A block runs while the addresses step by exactly addrInc, capped so one command
			//-- cannot become unmanageable.
			int blockend = blockstart + 1;
			while(blockend < list.size()
				&& !list.get(blockend - 1).m_done
				&& list.get(blockend - 1).m_physical.val() + addrInc == list.get(blockend).m_physical.val()
				&& (blockend - blockstart) < MAX_BLOCK_LEN) {
				blockend++;
			}

			long first = list.get(blockstart).m_physical.val();
			String cmd = addrInc == 1
				? "E/G" + countSuffix(blockend - blockstart) + " " + Octal.format(first - GLOBAL_REGISTER_BASE, 1)
				: "E" + countSuffix(blockend - blockstart) + " " + Octal.format(first, 1);

			if(!collectBlock(list, blockstart, blockend, addrInc, cmd, pm))
				return true;                                // timed out; do not retry, ever
			blockFailure = anyUnanswered(list, blockstart, blockend);
			blockstart = blockend;
		}
		return pm.isCancelled() || !blockFailure;
	}

	/** {@code /N:count} for a block, and nothing at all for a single address. */
	private static String countSuffix(int count) {
		return count > 1 ? "/N:" + Octal.format(count, 1) : "";
	}

	/**
	 * Send one command and take in its replies.
	 *
	 * <p>The console answers in ascending order, one line per address, and simply stops at the
	 * first address that does not exist. The Pascal's own worked example ({@code :531-546}):</p>
	 *
	 * <pre>
	 * &gt;&gt;&gt;E/N:10 17772370
	 * 17772370 156735
	 * 17772372 156735
	 * 17772374 156735
	 * 17772376 156735
	 * ?20 TRAN ERR
	 * &gt;&gt;&gt;
	 * </pre>
	 *
	 * <p>The address the error was about has to be inferred from the last one that answered.</p>
	 *
	 * @return false if the console stopped answering altogether, which is not worth retrying
	 */
	private boolean collectBlock(List<ExamineItem> list, int blockstart, int blockend, int addrInc,
		String cmd, ProgressMonitor pm) throws ConsoleException {
		clearAnswers();
		writeToPdp(cmd + CR);
		int scanFrom = 0;
		long nextExpected = list.get(blockstart).m_physical.val();

		while(!pm.isCancelled()) {
			if(!anyUnanswered(list, blockstart, blockend))
				return true;
			int at = getAnswers().waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult,
				scanFrom, CMD_TIMEOUT_MS);
			if(at < 0) {
				getLogger().log(LogChannel.OTHER,
					"EXAMINE list failure: timeout waiting for " + Octal.format(nextExpected, 8));
				return false;
			}
			AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) getAnswers().get(at);
			scanFrom = at + 1;

			long answerAddr = r.value().isKnown() && r.examineAddr() != null
				? r.examineAddr().val()
				: nextExpected;
			boolean found = false;
			for(int j = blockstart; j < blockend; j++) {
				ExamineItem it = list.get(j);
				if(it.m_physical.val() == answerAddr) {
					nextExpected = answerAddr + addrInc;
					if(!it.m_done)
						pm.step(1);
					it.m_done = true;
					it.m_cell.setPdpValue(r.value());
					found = true;
				}
			}
			if(!found) {
				getLogger().log(LogChannel.OTHER,
					"No memory cell matches the console's answer \"" + r.rawText() + "\"");
				if(!r.value().isKnown()) {
					//-- An unattributable failure would leave the block outstanding forever and
					//-- the caller retries until something changes. Give up on the first cell
					//-- still waiting, so the retry can make progress.
					for(int j = blockstart; j < blockend; j++) {
						ExamineItem it = list.get(j);
						if(!it.m_done) {
							it.m_done = true;
							it.m_cell.setPdpValue(CellValue.UNKNOWN);
							pm.step(1);
							break;
						}
					}
				}
			}
			if(!r.value().isKnown())
				return true;                                // the block ended here
		}
		return true;
	}

	private static boolean anyUnanswered(List<ExamineItem> list, int from, int to) {
		for(int i = from; i < to; i++) {
			if(!list.get(i).m_done)
				return true;
		}
		return false;
	}

	// -------------------------------------------------------------------------------------
	// Execution control
	// -------------------------------------------------------------------------------------

	/**
	 * {@code I} - initialise. A UNIBUS reset, so every device with it.
	 *
	 * <p>{@code newPc} is ignored: this does not set the PC, which is why
	 * {@link ConsoleFeature#RESET_CPU_SETS_PC} is not among the features.</p>
	 */
	@Override
	public void resetMachine(Address newPc) throws ConsoleException {
		clearAnswers();
		writeToPdp("I" + CR);
		checkPrompt("Reset failed, no prompt");
	}

	/** {@code S <pc>} - start, with an initialise in front of it. No prompt: it is running now. */
	@Override
	public void resetAndStart(Address newPc) throws ConsoleException {
		if(newPc.type() != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("The start PC is a virtual address, not " + newPc.type());
		clearAnswers();
		clearExecutionStop();
		writeToPdp("S " + Octal.format(newPc.val(), 6) + CR);
	}

	/** {@code C} - continue from where it stopped, with no initialise. No prompt either. */
	@Override
	public void continueCpu() throws ConsoleException {
		clearAnswers();
		clearExecutionStop();
		writeToPdp("C" + CR);
	}

	/**
	 * Stop a running program.
	 *
	 * <p>Ported from {@code HaltCpu} ({@code :806-834}). Two steps: {@code ^P} to get the
	 * terminal's attention - which answers nothing at all if the CPU was already stopped - and
	 * then {@code H}, which reports where it is. Since a stop report is also an examine answer,
	 * the PC comes back through the ordinary stop event rather than being read out of the reply
	 * here.</p>
	 */
	@Override
	public Address haltCpu() throws ConsoleException {
		clearAnswers();
		writeToPdp(String.valueOf(CTRL_P));
		try {
			//-- Let the terminal finish switching to console mode. There is nothing ^P answers
			//-- that could be waited for instead.
			Thread.sleep(CONSOLE_MODE_SETTLE_MS);
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
			throw new ConsoleException("Interrupted while halting");
		}
		writeToPdp("H" + CR);
		AnswerPhrase.Halt halt = getAnswers().waitFor(AnswerPhrase.Halt.class, CMD_TIMEOUT_MS);
		if(halt == null)
			throw new ConsoleException("Stopping the CPU failed: no answer");
		checkPrompt("Stopping CPU failed, no prompt");
		return halt.haltAddr();
	}

	/**
	 * {@code N 1} - one instruction, then the same stop report a halt gives.
	 *
	 * <p>The PC cannot be given here; the console steps from wherever it is ({@code :845-847}).</p>
	 */
	@Override
	public void singleStep() throws ConsoleException {
		clearAnswers();
		writeToPdp("N 1" + CR);
		AnswerPhrase.Halt halt = getAnswers().waitFor(AnswerPhrase.Halt.class, CMD_TIMEOUT_MS);
		if(halt == null)
			throw new ConsoleException("Single step failed: no answer");
		checkPrompt("Single Step failed, no prompt");
	}
}
