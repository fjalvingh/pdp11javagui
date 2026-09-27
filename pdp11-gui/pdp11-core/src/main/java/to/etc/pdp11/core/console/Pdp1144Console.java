package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.CpuRegisters;
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
	private static final long GLOBAL_REGISTER_BASE =
		CpuRegisters.addressIn(MemoryAddressType.PHYSICAL22, CpuRegisters.R0_OFFSET);

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
	 * <p>It used to have to look <b>two</b> phrases back rather than one ({@code :275-277}),
	 * because this console's stop report is also an examine answer and the examine was published
	 * after the halt. The decoder keeps the halt itself now, so how many phrases separate the two
	 * no longer comes into it - see {@code AbstractConsole.takeHaltAwaitingPrompt}.</p>
	 */
	private AnswerPhrase makePrompt(String curline) {
		AnswerPhrase.Halt halt = takeHaltAwaitingPrompt();
		if(halt != null)
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
		int at = getAnswers().waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult, 0, getCommandTimeoutMillis());
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
	 *
	 * <p><b>The remembered address is committed only once the console has confirmed the
	 * command.</b> {@code D +} means "the word after the one you last deposited into", and the
	 * machine's own idea of that only advances when the deposit actually happens. Recording it
	 * before the prompt came back meant a failed deposit - which the caller may well carry on
	 * from, since the exception says only that the prompt did not arrive - left this console
	 * believing the machine had moved on when it had not, and the next sequential deposit went
	 * out as {@code D +} and landed one word short, silently.</p>
	 */
	@Override
	public void deposit(Address addr, int value) throws ConsoleException {
		Address physical = toPhysical(addr, true);
		String valueText = Octal.format(value & 0xFFFF, 1);
		boolean global = isGlobalRegister(physical.val());
		String cmd;
		if(global) {
			cmd = "D/G " + Octal.format(physical.val() - GLOBAL_REGISTER_BASE, 1) + " " + valueText;
		} else {
			//-- Consecutive deposits can say "+" instead of repeating the address.
			cmd = m_lastDepositAddr != null && physical.val() == m_lastDepositAddr.val() + 2
				? "D + " + valueText
				: "D " + Octal.format(physical.val(), 1) + " " + valueText;
		}
		//-- Whatever happens next, what the machine last deposited into is no longer what this
		//-- console remembers: clear first, and record the new one only on the way out.
		m_lastDepositAddr = null;                           // "+" after a register means the next memory word
		clearAnswers();
		writeToPdp(cmd + CR);
		checkPrompt("DEPOSIT failed, no prompt");
		if(!global)
			m_lastDepositAddr = physical;
	}

	// -------------------------------------------------------------------------------------
	// Bulk examine
	// -------------------------------------------------------------------------------------

	/**
	 * Read a whole group, a block per command.
	 *
	 * <p>Ported from {@code TConsolePDP1144.Examine(mcg, ...)} ({@code :540-742}). This is the
	 * function the Pascal's own comment calls the core of PDP11GUI - "schnell viele
	 * Memoryadressen auslesen" - and it is: {@code E/N:100 <addr>} is sixty-four words for one
	 * round trip, against sixty-four round trips without it.</p>
	 *
	 * <p>Memory and global registers go in separate lists because they step differently, 2 against
	 * 1, and because the registers need {@code /G} and an offset rather than an address. Which
	 * cell is which is all this decides; the passes over the lists are
	 * {@link AbstractConsole#bulkExamine}, shared with the SimH console.</p>
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
		bulkExamine(g, memory, registers, pm, BLOCKS);
	}

	/**
	 * One contiguous run of addresses per command: {@code E/N:count addr} for memory, and
	 * {@code E/G/N:count offset} for the byte-spaced global registers.
	 */
	private final BulkExamineProtocol BLOCKS = new BulkExamineProtocol() {
		@Override
		public ExamineBlock nextBlock(List<ExamineItem> list, int blockstart, int addrInc) {
			int blockend = blockstart + 1;
			while(blockend < list.size()
				&& !list.get(blockend - 1).isDone()
				&& list.get(blockend - 1).getPhysical().val() + addrInc == list.get(blockend).getPhysical().val()
				&& (blockend - blockstart) < MAX_EXAMINE_BLOCK_LEN) {
				blockend++;
			}
			long first = list.get(blockstart).getPhysical().val();
			String cmd = addrInc == 1
				? "E/G" + countSuffix(blockend - blockstart) + " " + Octal.format(first - GLOBAL_REGISTER_BASE, 1)
				: "E" + countSuffix(blockend - blockstart) + " " + Octal.format(first, 1);
			return new ExamineBlock(blockend, cmd);
		}

		@Override
		public int sendBlockCommand(String command) throws ConsoleException {
			//-- This console does not echo, so every answer that arrives is this command's.
			clearAnswers();
			writeToPdp(command + CR);
			return 0;
		}
	};

	/** {@code /N:count} for a block, and nothing at all for a single address. */
	private static String countSuffix(int count) {
		return count > 1 ? "/N:" + Octal.format(count, 1) : "";
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
		takeTheStartPrompt("S");
	}

	/** {@code C} - continue from where it stopped, with no initialise. */
	@Override
	public void continueCpu() throws ConsoleException {
		clearAnswers();
		clearExecutionStop();
		writeToPdp("C" + CR);
		takeTheStartPrompt("C");
	}

	/**
	 * Wait out the prompt a start draws, so it cannot be mistaken for the next command's answer.
	 *
	 * <p>{@code S} and {@code C} are the only commands here that ask for nothing: the machine is
	 * running afterwards and there is no result to collect. But the classic firmware prompts
	 * anyway - it prompts after every command - and a prompt nobody consumes is still coming down
	 * the wire when the next command starts. {@link #haltCpu()} then finds it, one line after its
	 * own {@code clearAnswers()}, and reads it as the prompt-with-no-stop-report that means "there
	 * was nothing to stop": Halt returns null for a machine it just halted, and says in the log
	 * that it was already halted. Clicking Run and then Halt is all it takes.</p>
	 *
	 * <p>So the answer is taken here, by the command that caused it. The wait is bounded and its
	 * absence is <b>not</b> an error: this is the only place that would learn a real 11/44 does
	 * not prompt after a start, and a start that has already been sent must not be reported as
	 * failed. V3.40C is not waited for at all - it prints nothing while a program has the
	 * terminal.</p>
	 */
	private void takeTheStartPrompt(String command) throws ConsoleException {
		if(!m_firmware.promptsAfterStart())
			return;
		if(getAnswers().waitFor(AnswerPhrase.Prompt.class, getCommandTimeoutMillis()) == null)
			getLogger().log(LogChannel.EXECUTION,
				command + ": the machine is running, but no prompt followed the command");
	}

	/**
	 * Stop a running program.
	 *
	 * <p>Ported from {@code HaltCpu} ({@code :806-834}). Two steps: {@code ^P} to get the
	 * terminal's attention - which answers nothing at all if the CPU was already stopped - and
	 * then {@code H}, which reports where it is. Since a stop report is also an examine answer,
	 * the PC comes back through the ordinary stop event rather than being read out of the reply
	 * here.</p>
	 *
	 * <p><b>Halting a machine that is already halted is not a failure.</b> The interface says so
	 * - null for "the console could not say, including the common case of a machine that had
	 * already stopped" - and the execution window calls this unconditionally, so a second click
	 * on Halt goes down this path every time. V3.40C answers {@code H} with
	 * {@code ?Already halted} and draws its prompt without a stop report; waiting for the report
	 * that is not coming used to sit out the whole command timeout and then throw "no answer" at
	 * the user. So this waits for <i>either</i> the stop report or the prompt, and a prompt
	 * arriving first means there was nothing to stop.</p>
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
		int at = getAnswers().waitForIndex(
			p -> p instanceof AnswerPhrase.Halt || p instanceof AnswerPhrase.Prompt, 0, getCommandTimeoutMillis());
		if(at < 0)
			throw new ConsoleException("Stopping the CPU failed: no answer");
		if(!(getAnswers().get(at) instanceof AnswerPhrase.Halt halt)) {
			getLogger().log(LogChannel.EXECUTION,
				"haltCpu: the console prompted with no stop report, so it was already halted");
			return null;
		}
		checkPromptAfter(at + 1, "Stopping CPU failed, no prompt");
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
		AnswerPhrase.Halt halt = getAnswers().waitFor(AnswerPhrase.Halt.class, getCommandTimeoutMillis());
		if(halt == null)
			throw new ConsoleException("Single step failed: no answer");
		checkPrompt("Single Step failed, no prompt");
	}
}
