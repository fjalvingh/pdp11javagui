package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.addr.SpecialRegister;
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
import to.etc.pdp11.core.util.Scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Drives the SimH emulator over its remote console.
 *
 * <p>Ported from {@code TConsolePDP11SimH} ({@code ConsolePDP11SimHU.pas}). The application
 * only ever calls examine and deposit; this turns them into {@code E}/{@code D} commands and
 * turns SimH's replies back into values.</p>
 *
 * <p>Two facts about SimH shape everything here. It maps the CPU registers <b>outside</b> the
 * address space, so {@code R0..R7} have to be reached by name or it answers "illegal address
 * space" ({@code :28-31}). And it always speaks 22-bit physical addresses, even when emulating
 * a machine that never had them ({@code :33}).</p>
 *
 * <h2>Synchronising with the prompt, which is the hard part</h2>
 *
 * <p>SimH prints its prompt <i>before</i> echoing the command it is about to run, and echoes
 * every command back before acting on it. So "send, then wait for {@code sim>}" can be
 * satisfied by the <i>previous</i> command's prompt, and an accepted command differs from a
 * rejected one only in the text that follows the echo. Phase 3 reached a working exchange live
 * several times and could not make it into a test that passed twice running; PLAN.md records
 * that as this phase's problem.</p>
 *
 * <p>{@link #sendCommand} is the answer. It clears the collected answers, sends, and then waits
 * for SimH's echo of the command - which cannot possibly predate the command - and returns the
 * position of that echo. Everything afterwards is read strictly after that position: the
 * prompt, the examine replies, and the "did SimH reject this" check. Nothing in flight when the
 * command was sent can be mistaken for an answer to it.</p>
 */
public final class SimhConsole extends AbstractConsole {
	public static final char CR = '\r';

	public static final char LF = '\n';

	/** {@code ^E}: halts a running simulation, and is what gets a prompt out of a fresh remote console. */
	public static final char HALT_CHAR = 5;

	public static final String PROMPT = "sim> ";

	/**
	 * How long a command has to be answered.
	 *
	 * <p>8 s, from {@code SIMH_CMD_TIMEOUT} ({@code :73}), which grew from 1 s to 3 s to this
	 * as bulk operations hit it. The Pascal's own diagnosis is that {@code WaitForAnswer}
	 * depended on {@code Application.ProcessMessages} to service the network poll, so it
	 * competed with any other pending UI work - a freshly opened listing window's layout pass
	 * was enough to starve it past 3 s. None of that applies to a reader thread, so the real
	 * requirement here is far smaller; it is kept because a genuinely loaded machine can still
	 * be slow and nothing is lost by waiting.</p>
	 */
	public static final long CMD_TIMEOUT_MS = 8000;

	/**
	 * How long {@code ^E} has to produce a halt report.
	 *
	 * <p>Short, from {@code SIMH_HALT_TIMEOUT} ({@code :80}): halting a machine that has
	 * already stopped is a common, expected no-op, and waiting the full command timeout for the
	 * reply that will never come would freeze the caller for eight seconds. {@link #haltCpu}
	 * mostly avoids sending anything at all in that case - see {@link CpuState}.</p>
	 */
	public static final long HALT_TIMEOUT_MS = 1000;

	/** How long to wait before chasing down a stop that SimH never announced. {@code :121}. */
	public static final long SILENT_HALT_DELAY_MS = 200;

	/** How many times to say {@code ^E} before giving up on a console that will not wake. */
	public static final int WAKEUP_ATTEMPTS = 4;

	/** The longest run of addresses to put in one {@code E} command. {@code :779}. */
	private static final int MAX_BLOCK_LEN = 100;

	/** R0..R7 as SimH-invisible pseudo-addresses; PDP11GUI's own convention, byte-spaced. */
	private static final long REG_R0 = 017777700L;

	private static final long REG_PC = 017777707L;

	private static final long REG_UNKNOWN_LOW = 017777710L;

	private static final long REG_UNKNOWN_HIGH = 017777717L;

	private static final long REG_CPUERR = 017777766L;

	private static final long REG_PIRQ = 017777772L;

	private static final long REG_PSW = 017777776L;

	private static final String[] REG_NAMES = {"R0", "R1", "R2", "R3", "R4", "R5", "SP", "PC"};

	/**
	 * The scanner SimH does not use.
	 *
	 * <p>{@code TConsoleSimHScanner.NxtSym} raises "not implemented" ({@code :179-183}): this
	 * dialect is line-oriented, so its decoder works on the buffer directly rather than on
	 * symbols. The scanner is still what holds the buffer, the NUL filtering and the
	 * consumption, which is the part every console needs.</p>
	 */
	public static final class SimhScanner extends ConsoleScanner<SimhScanner.Sym> {
		/** SimH's decoder never tokenises, so there is exactly one symbol and it never appears. */
		public enum Sym {
			NONE
		}

		@Override
		public String nextSymbol(boolean raiseIncompleteOnEof) {
			throw new UnsupportedOperationException("The SimH decoder is line-oriented and does not tokenise");
		}
	}

	/**
	 * What the CPU is doing, as far as the protocol has actually confirmed - never as far as a
	 * button click was supposed to cause.
	 *
	 * <p>Ported from {@code TSimhCpuState} ({@code :98}). PLAN.md phase 4 says to preserve this
	 * and it is worth saying why: the version it replaced decided from a timeout, and deciding
	 * "it must have stopped by now" is how you send {@code ^E} into a program that is running
	 * perfectly well and stop it. SimH is the only console type whose protocol can state this,
	 * so the tracking is local to this class.</p>
	 */
	public enum CpuState {
		UNKNOWN,
		HALTED,
		RUNNING
	}

	private final SimhScanner m_scanner = new SimhScanner();

	private final Scheduler m_scheduler;

	/** Written by the decoder on the reader thread, read by commands on the command thread. */
	private volatile CpuState m_cpuState = CpuState.UNKNOWN;

	/**
	 * The decoder saw a prompt while the CPU was believed to be running, so the machine stopped
	 * without saying so. Resolved later, off the back of a scheduled task.
	 */
	private volatile boolean m_silentHaltPending;

	private volatile Scheduler.Handle m_silentHaltHandle;

	public SimhConsole(MemoryCellGroups groups, Logger logger, Scheduler scheduler) {
		super(logger);
		m_scheduler = scheduler;
		setCommandTimeoutMillis(CMD_TIMEOUT_MS);
		//-- The MMU builds its own register group, which is why it needs the whole collection.
		setMmu(new Pdp11Mmu(groups));
	}

	@Override
	public String name() {
		return "SimH PDP-11 console";
	}

	@Override
	public MemoryAddressType physicalAddressType() {
		//-- Always 22 bits, whatever machine SimH is pretending to be ({@code :33}).
		return MemoryAddressType.PHYSICAL22;
	}

	@Override
	public EnumSet<ConsoleFeature> features() {
		//-- No RESET_CPU_SETS_PC: SimH's reset leaves the PC alone ({@code :351}).
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
	 * Ported from {@code getTerminalSettings} ({@code ConsolePDP11SimHU.pas:325-334}).
	 *
	 * <p>SimH is the one console here that behaves like a modern stream: our Enter sends CR,
	 * telnet wraps lines with LF, and both end a line. It is also the only one with a working
	 * backspace and tab stops, because it is a program on a host rather than a printing
	 * terminal.</p>
	 */
	@Override
	public TerminalProfile terminalProfile() {
		return new TerminalProfile(true, true, (char) 8, 8);
	}

	public CpuState getCpuState() {
		return m_cpuState;
	}

	@Override
	protected ConsoleScanner<?> getScanner() {
		return m_scanner;
	}

	// -------------------------------------------------------------------------------------
	// Register names
	// -------------------------------------------------------------------------------------

	/**
	 * SimH's name for an address, or {@code null} if it has none and plain octal will do.
	 *
	 * <p>Ported from {@code addr2regname} ({@code :195-233}). {@code "?"} means the opposite of
	 * {@code null}: SimH knows of no such thing and asking would be an error, so the caller must
	 * not ask at all.</p>
	 *
	 * <p>The Pascal's own comment says this list is model-dependent and really ought to come out
	 * of the machine description; it does not, in either implementation.</p>
	 */
	static String addrToRegName(Address addr) {
		if(addr.type() == MemoryAddressType.SPECIAL_REGISTER) {
			SpecialRegister sr = SpecialRegister.of(addr);
			if(sr == null)
				return null;
			//-- "e -d dr" is answered with "DR: nnnnnn".
			return sr == SpecialRegister.DISPLAY_REGISTER ? "DR" : "SR";
		}
		if(addr.type() != MemoryAddressType.PHYSICAL22)
			return null;
		long v = addr.val();
		if(v >= REG_R0 && v <= REG_PC)
			return REG_NAMES[(int) (v - REG_R0)];
		if(v >= REG_UNKNOWN_LOW && v <= REG_UNKNOWN_HIGH)
			return "?";
		if(v == REG_CPUERR)
			return "CPUERR";
		if(v == REG_PIRQ)
			return "PIRQ";
		if(v == REG_PSW)
			return "PSW";
		return null;
	}

	/**
	 * The address SimH means by a name, or {@code null} for a name it does not use.
	 *
	 * <p>Ported from {@code regname2addr} ({@code :236-266}), with one correction: the Pascal
	 * gives {@code DR} and {@code SR} the type {@code matVirtual} while
	 * {@link #addrToRegName} only recognises them as {@code matSpecialRegister}, so the two are
	 * not inverses. Nothing depends on the difference there, because the comparison that uses
	 * them looks at the value alone.</p>
	 */
	static Address regNameToAddr(String regname) {
		String n = regname.toUpperCase(Locale.ROOT);
		for(int i = 0; i < REG_NAMES.length; i++) {
			if(REG_NAMES[i].equals(n))
				return Address.of(MemoryAddressType.PHYSICAL22, REG_R0 + i);
		}
		switch(n) {
			case "CPUERR":
				return Address.of(MemoryAddressType.PHYSICAL22, REG_CPUERR);
			case "PIRQ":
				return Address.of(MemoryAddressType.PHYSICAL22, REG_PIRQ);
			case "PSW":
				return Address.of(MemoryAddressType.PHYSICAL22, REG_PSW);
			case "DR":
				return SpecialRegister.DISPLAY_REGISTER.toAddress();
			case "SR":
				return SpecialRegister.SWITCH_REGISTER.toAddress();
			default:
				return null;
		}
	}

	// -------------------------------------------------------------------------------------
	// Decoding - reader thread
	// -------------------------------------------------------------------------------------

	private static boolean isEoln(char c) {
		return c == CR || c == LF;
	}

	/**
	 * Pull the next phrase out of SimH's output.
	 *
	 * <p>Ported from {@code DecodeNextAnswerPhrase} ({@code :374-563}), indices converted from
	 * 1-based to 0-based throughout.</p>
	 */
	@Override
	protected boolean decodeNextAnswerPhrase() {
		//-- Leading line ends belong to whatever came before; drop them.
		int i = 0;
		while(i < m_scanner.length() && isEoln(m_scanner.charAt(i))) {
			i++;
		}
		m_scanner.dropLeading(i);

		//-- Scan to the end of the line, or to a prompt. SimH sometimes prints text with no
		//-- trailing CRLF before the next "sim> " (eg. "Simulator Running..." - see
		//-- _sim_rem_message() in scp.c), so the prompt can arrive glued to the end of an
		//-- unterminated line rather than starting one. Stopping as soon as the line *ends
		//-- with* the prompt is what catches that; testing for equality would scan past it
		//-- forever, since no CR or LF is ever coming.
		StringBuilder line = new StringBuilder();
		boolean promptFound = false;
		i = 0;
		while(i < m_scanner.length() && !promptFound && !isEoln(m_scanner.charAt(i))) {
			line.append(m_scanner.charAt(i));
			i++;
			promptFound = line.length() >= PROMPT.length()
				&& line.lastIndexOf(PROMPT) == line.length() - PROMPT.length();
		}
		//-- Whether the scan stopped on a line end rather than by running out of input.
		boolean eoln = i < m_scanner.length() && isEoln(m_scanner.charAt(i));
		String curline = line.toString();

		AnswerPhrase phrase = null;
		if(promptFound && !curline.equals(PROMPT)) {
			//-- Prompt glued onto preceding text. Split the text off as its own phrase now and
			//-- leave the prompt in the buffer, so the next call sees it standing alone.
			String leading = curline.substring(0, curline.length() - PROMPT.length());
			phrase = new AnswerPhrase.OtherLine(leading);
			curline = leading;
		} else if(curline.equals(PROMPT)) {
			phrase = decodePrompt(curline);
		}

		if(phrase == null && eoln) {
			phrase = decodeHalt(curline);
			if(phrase == null)
				phrase = decodeExamine(curline);
			if(phrase == null)
				phrase = new AnswerPhrase.OtherLine(curline);
		}

		if(phrase == null)
			return false;

		updateCpuState(phrase);
		m_scanner.dropLeading(curline.length());
		publish(phrase);
		return true;
	}

	/**
	 * A standalone prompt, which is also the only reliable news that a run ended.
	 *
	 * <p>The stop event is deliberately not fired from the halt line itself but from the prompt
	 * that follows it ({@code :425-429}): a handler is expected to go straight on to issue
	 * console commands, and it can only do that once SimH is listening again.</p>
	 */
	private AnswerPhrase decodePrompt(String curline) {
		AnswerPhrase previous = getAnswers().getLast();
		if(previous instanceof AnswerPhrase.Halt halt) {
			signalExecutionStop(halt.haltAddr());
		} else {
			clearExecutionStop();
			//-- No halt message preceded this prompt. If the CPU was confirmed running right up
			//-- until now, this prompt is the only sign we will ever get that it stopped -
			//-- typically it started on zeroed memory, executed the implicit HALT at address 0
			//-- and that was that. Flag it and resolve the real PC later, off the back of a
			//-- scheduled task; never guess from a timeout, because a program that really is
			//-- still running sends no prompt at all, so this flag simply never gets set for it.
			if(m_cpuState == CpuState.RUNNING)
				scheduleSilentHaltResolution();
		}
		return new AnswerPhrase.Prompt(curline);
	}

	/**
	 * A CPU stop, in any of the several shapes SimH announces one:
	 * <pre>
	 * Simulation stopped, PC: 002502 (MOV (SP)+,177776)
	 * HALT instruction, PC: 000114 (SWAB (R0)+)
	 * Step expired, PC: 000006 (SWAB -(R0))
	 * </pre>
	 * The PC it reports is virtual.
	 */
	private AnswerPhrase decodeHalt(String curline) {
		String up = curline.toUpperCase(Locale.ROOT);
		if(!up.contains("SIMULATION STOPPED") && !up.contains("HALT") && !up.contains("STEP EXPIRED")
			&& !up.contains("TRAP") && !up.contains("BREAKPOINT"))
			return null;
		int p = curline.indexOf("PC:");
		if(p < 0)
			return null;                                    // an error we do not recognise
		String s = curline.substring(p + 3, Math.min(p + 3 + 7, curline.length())).trim();
		try {
			return new AnswerPhrase.Halt(curline, Address.parseOctal(s, MemoryAddressType.VIRTUAL));
		} catch(RuntimeException x) {
			return null;
		}
	}

	/**
	 * An examine reply: {@code <addr>: <value>}, where the address may be a register name.
	 *
	 * <p>"Address space exceeded" is a UNIBUS timeout, and a perfectly valid answer - but SimH
	 * does not say which address it was about, so the caller has to attribute it to whatever it
	 * asked for next ({@code :505-510}).</p>
	 *
	 * <p><b>Anything after the value is ignored</b>, which is where the Pascal's "and there is
	 * no third word" test ({@code :524-525}) has to be left behind. A SimH register declared
	 * with a {@code BITFIELD} table is shown decoded, so {@code E PSW} on an 11/70 answers</p>
	 * <pre>PSW:	000000	CM=K PM=K RS0 FPD0 IPL=0 TBIT0 N0 Z0 V0 C0</pre>
	 * <p>and an exact-two-words test throws that away as an unrecognised line. The examine then
	 * waits out {@link #CMD_TIMEOUT_MS} for an answer that already arrived, and the cell stays
	 * unknown - which for the PSW means the MMU never learns the CPU mode. Halt lines cannot be
	 * caught by the looser rule because {@link #decodeHalt} has already had them, and the
	 * word-size check below still rejects a second word that is not a machine word.</p>
	 */
	private AnswerPhrase decodeExamine(String curline) {
		if(curline.contains("Address space exceeded"))
			return new AnswerPhrase.ExamineResult(curline, null, CellValue.UNKNOWN);

		String[] words = curline.trim().split("[ \t]+");
		if(words.length < 2 || !words[0].endsWith(":"))
			return null;
		String name = words[0].substring(0, words[0].length() - 1);
		Address addr = regNameToAddr(name);
		if(addr == null) {
			try {
				addr = Address.parseOctal(name, MemoryAddressType.PHYSICAL22);
			} catch(RuntimeException x) {
				return null;
			}
		}
		long value;
		try {
			value = Octal.parse(words[1]);
		} catch(RuntimeException x) {
			return null;
		}
		//-- OctalStr2Dword(s, 16) turns anything wider than a word into the illegal-value
		//-- sentinel, which the Pascal then treats as "not an examine answer" ({@code :528}).
		if(value > 0xFFFF)
			return null;
		return new AnswerPhrase.ExamineResult(curline, addr, CellValue.of((int) value));
	}

	/**
	 * Keep the run state current from confirmed events, continuously - not just right after a
	 * command was sent. A running program can stop on its own at any moment, and the decoder is
	 * the only thing watching when nothing is waiting for anything ({@code :541-551}).
	 */
	private void updateCpuState(AnswerPhrase phrase) {
		if(phrase instanceof AnswerPhrase.Halt) {
			m_cpuState = CpuState.HALTED;
		} else if(phrase instanceof AnswerPhrase.Prompt) {
			//-- A prompt is only ever sent when SimH is not actively running.
			m_cpuState = CpuState.HALTED;
		} else if(phrase instanceof AnswerPhrase.OtherLine other) {
			if("Simulator Running...".equals(other.text().trim()))
				m_cpuState = CpuState.RUNNING;
		}
	}

	// -------------------------------------------------------------------------------------
	// The silent halt
	// -------------------------------------------------------------------------------------

	/**
	 * Arrange to find out where a machine that stopped without saying so actually stopped.
	 *
	 * <p>Ported from {@code SilentHaltTimer}/{@code SilentHaltTimerTimer} ({@code :110-118,
	 * 300-318}). It has to be asynchronous: {@link #resetAndStart} is fire-and-forget and must
	 * stay that way - blocking it on a synchronous confirmation froze the Pascal's UI for
	 * however long that took, sometimes seconds.</p>
	 *
	 * <p>PLAN.md §1 calls this out as the one concrete deadlock trap in the port, because the
	 * Pascal resolves it by issuing a console command from inside a timer callback, guarded
	 * only by a nesting counter. Here the scheduler's thread does nothing except post the work
	 * to the command executor, where it queues behind whatever is running - so it can never
	 * compete with an in-flight examine, deposit or halt, and needs no guard at all.</p>
	 */
	private void scheduleSilentHaltResolution() {
		m_silentHaltPending = true;
		ConsoleConnection c = getConnection();
		if(c == null)
			return;
		Scheduler.Handle old = m_silentHaltHandle;
		if(old != null)
			old.cancel();
		m_silentHaltHandle = m_scheduler.schedule(() -> c.execute(this::resolveSilentHalt), SILENT_HALT_DELAY_MS);
	}

	/** On the command thread. */
	private void resolveSilentHalt() {
		if(!m_silentHaltPending)
			return;
		m_silentHaltPending = false;
		getLogger().log(LogChannel.EXECUTION,
			"CPU halted with no explicit stop message (eg. no program loaded) - resolving PC");
		try {
			CellValue pc = examine(Address.of(MemoryAddressType.PHYSICAL22, REG_PC));
			if(pc.isKnown())
				signalExecutionStop(Address.of(MemoryAddressType.VIRTUAL, pc.word()));
		} catch(ConsoleException x) {
			getLogger().log(LogChannel.EXECUTION, "Could not resolve the PC after a silent halt: " + x.getMessage());
		}
	}

	// -------------------------------------------------------------------------------------
	// Commands - command thread
	// -------------------------------------------------------------------------------------

	/**
	 * Send a command and wait for SimH to echo it back.
	 *
	 * <p>See the class comment: the echo is the only thing in SimH's output stream that cannot
	 * possibly have been produced before the command was sent, which makes it the only sound
	 * place to synchronise. Everything the caller then reads is read from the returned position
	 * onwards.</p>
	 *
	 * <p>The echo is matched on the <b>end</b> of a line rather than on the whole of it, for the
	 * same reason the decoder tests the prompt that way: SimH prints "Simulator Running..." with
	 * no line ending, so anything sent while that fragment is still unconsumed arrives glued to
	 * it and comes back as {@code "Simulator Running...E PC"}. Requiring equality there loses the
	 * anchor completely and the command waits out its whole timeout for an echo that already
	 * arrived. Matching the end cannot mistake something else for the echo: the answers were
	 * cleared immediately before the write, so only lines decoded after it are considered at
	 * all.</p>
	 *
	 * @return where the echo landed in the collected answers, or the position just before the
	 *         first real reply if the echo never arrived - a console with echo turned off is
	 *         degraded, not broken, and is left to the prompt check to sort out.
	 */
	private int sendCommand(String command) throws ConsoleException {
		clearAnswers();
		writeToPdp(command + CR);
		String echoed = command.trim();
		int at = getAnswers().waitForIndex(
			p -> p instanceof AnswerPhrase.OtherLine ol && ol.text().trim().endsWith(echoed),
			0, getCommandTimeoutMillis());
		if(at >= 0)
			return at;
		getLogger().log(LogChannel.PROTOCOL, "SimH did not echo \"" + echoed + "\"; falling back to prompt-only sync");
		return -1;
	}

	/**
	 * Like {@link #checkPromptAfter}, but also notices a command SimH silently rejected.
	 *
	 * <p>Ported from {@code CheckPromptNoOutput} ({@code :637-666}). SimH's remote console only
	 * accepts a whitelist of commands ({@code allowed_remote_cmds}/
	 * {@code allowed_master_remote_cmds} in {@code sim_console.c}); a rejected one still returns
	 * to the prompt, so a plain prompt check does not notice. {@code DEPOSIT} and {@code RESET}
	 * produce no output at all when they work, so any line at all after the echo means
	 * rejection.</p>
	 */
	private void checkPromptNoOutput(int echoIndex, String errinfo) throws ConsoleException {
		checkPromptAfter(echoIndex + 1, errinfo);
		StringBuilder err = new StringBuilder();
		for(AnswerPhrase p : getAnswers().snapshotFrom(echoIndex + 1)) {
			if(p instanceof AnswerPhrase.OtherLine ol && !ol.text().isBlank())
				err.append(ol.text().trim()).append(' ');
		}
		String text = err.toString().trim();
		if(!text.isEmpty())
			throw new ConsoleException(errinfo + ": SimH rejected the command: \"" + text + "\"");
	}

	@Override
	public void init(ConsoleConnection connection) throws ConsoleException {
		super.init(connection);
		resync();
	}

	/**
	 * Get SimH prompting and configured.
	 *
	 * <p>Ported from {@code Resync} ({@code :567-611}), with the {@code ^E} that phase 3
	 * established added in front of it. Without that, a freshly connected remote console
	 * answers nothing at all: multiple-command mode is what produces a {@code sim>} prompt,
	 * whatever {@code set remote master} says in the configuration. SimH echoes the {@code ^E}
	 * back as though it were a command and answers it {@code Unknown command}, which is
	 * expected and ignored here - and which, misread, is what made an earlier reading of this
	 * transcript conclude that {@code show} was not whitelisted.</p>
	 *
	 * <p>The Pascal's setup commands are sent with a prompt check each. Note those checks are
	 * very nearly free there: {@code CheckPrompt} looks for <i>any</i> prompt and the previous
	 * command's is still in the list, so only the first one actually waits. Here each is
	 * anchored on its own echo and so genuinely confirms its own command.</p>
	 */
	@Override
	public void resync() throws ConsoleException {
		//-- A bare RETURN would repeat the last command, with unforeseeable consequences
		//-- ({@code :576-578}); everything below sends something explicit.
		resetScanner();
		m_cpuState = CpuState.UNKNOWN;                      // a reconnect knows nothing about the run state
		m_silentHaltPending = false;

		enterMultipleCommandMode();

		//-- Reportedly the only way to get EXAMINE at the I/O page to work ({@code :585-586}).
		runSetupCommand("sh cpu iospace", "Could not wake up SimH");
		//-- Aim for something like 38400 baud on the emulated console: at 5 MIPS a character
		//-- every 1300 instructions is about right ({@code :590-597}).
		runSetupCommand("set throttle 5M", "\"set throttle\" failed");
		runSetupCommand("deposit tti time 1300", "\"deposit tti time\" failed");
		runSetupCommand("SET CPU HISTORY=100", "\"SET CPU HISTORY\" failed");
		getLogger().log(LogChannel.OTHER, "SimH ready and prompting \"sim>\"");
	}

	/**
	 * Send {@code ^E} until SimH answers with a prompt.
	 *
	 * <p>Once is not reliable, and the reason is not a missing delay so much as a missing
	 * acknowledgement: a connected socket does not mean the session at the other end is ready,
	 * and a keystroke that arrives before it is simply gone. The Pascal covers that by sleeping
	 * a flat second in {@code Init} before doing anything at all
	 * ({@code ConsolePDP11SimHU.pas:620-625}) - a guess, and one that costs a second on every
	 * connection whether it is needed or not.</p>
	 *
	 * <p>Asking again is better than waiting longer. A repeated {@code ^E} is harmless: outside
	 * a run it is an inert byte that draws an {@code Unknown command}, and if it does reach a
	 * running simulation the first one stops it and the prompt that follows ends the loop.</p>
	 *
	 * <p><b>Note the {@code ^E} is not usually what produces the prompt at all</b> when SimH was
	 * launched by us: {@code set remote master} plus both channels connected gets one on its own,
	 * measured at 15 launches out of 15. It is sent because a plain telnet connection to a SimH
	 * somebody else started has no such configuration. See PLAN.md phase 4 for the open item on
	 * the case where neither happens.</p>
	 */
	private void enterMultipleCommandMode() throws ConsoleException {
		long perAttempt = Math.max(500, getCommandTimeoutMillis() / WAKEUP_ATTEMPTS);
		for(int attempt = 1; attempt <= WAKEUP_ATTEMPTS; attempt++) {
			writeToPdp(String.valueOf(HALT_CHAR));
			if(getAnswers().waitFor(AnswerPhrase.Prompt.class, perAttempt) != null)
				return;
			getLogger().log(LogChannel.PROTOCOL,
				"No prompt after ^E (attempt " + attempt + " of " + WAKEUP_ATTEMPTS + "); asking again");
		}
		throw new NoConsolePromptException("SimH did not answer ^E with a prompt",
			m_scanner.getInput(), getAnswers().snapshot());
	}

	private void runSetupCommand(String command, String errinfo) throws ConsoleException {
		int echo = sendCommand(command);
		checkPromptAfter(echo + 1, errinfo);
	}

	@Override
	public void clearState() {
		super.clearState();
		m_cpuState = CpuState.UNKNOWN;
		m_silentHaltPending = false;
	}

	// -------------------------------------------------------------------------------------
	// Commands typed by a person
	// -------------------------------------------------------------------------------------

	/**
	 * What one hand-typed command produced.
	 *
	 * @param command  what was sent, trimmed.
	 * @param lines    what SimH said before prompting again, blank lines and its echo of the
	 *                 command removed. Empty for the commands that succeed silently, which
	 *                 {@code DEPOSIT}, {@code RESET} and {@code SET} all do.
	 * @param prompted whether {@code sim>} came back within the command timeout. <b>False is
	 *                 not necessarily an error</b>: {@code go}, {@code run} and {@code cont}
	 *                 hand the machine control and produce no prompt until it stops again.
	 */
	public record CommandResult(String command, List<String> lines, boolean prompted) {
	}

	/**
	 * Send a command somebody typed at the SimH Console window, and collect the answer.
	 *
	 * <p>The window shows the raw channel rather than this, so what comes back here is for
	 * deciding what happened rather than for display. It goes through {@link #sendCommand} like
	 * every other command, which means it is serialized on the command thread and anchored on
	 * its own echo - a typed command therefore lands <i>between</i> the console layer's own
	 * commands and can never be mistaken for a reply to one of them. That is the hazard the
	 * Pascal avoided by refusing to let anybody type here at all
	 * ({@code FormSimhConsoleU.pas:5-11}: the parser "was built and tested only against a clean
	 * administrative channel").</p>
	 *
	 * <p>What this cannot prevent is a command that changes the machine behind the application's
	 * back - {@code go} and {@code dep} do exactly that. The transcript is what makes it obvious
	 * afterwards, which is the trade the window is for.</p>
	 *
	 * <p>Not thrown for: a command SimH rejects (its complaint is a line like any other, and the
	 * window shows it), and a command that never prompts again (the machine is running). Both
	 * are ordinary things to type.</p>
	 *
	 * @throws ConsoleException if the command could not be sent at all, or is empty - a bare
	 *                          RETURN repeats SimH's last command, with consequences nobody
	 *                          asked for ({@code ConsolePDP11SimHU.pas:576-578}).
	 */
	public CommandResult command(String command) throws ConsoleException {
		String cmd = command == null ? "" : command.trim();
		if(cmd.isEmpty())
			throw new ConsoleException("Empty command: SimH would repeat the last one");
		getLogger().log(LogChannel.OTHER, "SimH console: " + cmd);
		int echo = sendCommand(cmd);
		int from = echo < 0 ? 0 : echo + 1;
		boolean prompted = getAnswers().waitFor(AnswerPhrase.Prompt.class, from, getCommandTimeoutMillis()) != null;
		List<String> lines = new ArrayList<>();
		for(AnswerPhrase p : getAnswers().snapshotFrom(from)) {
			if(p instanceof AnswerPhrase.Prompt)
				break;
			String text = p.rawText().trim();
			//-- The echo, when sendCommand could not find it and started collecting from zero.
			if(text.isEmpty() || text.equals(cmd))
				continue;
			lines.add(text);
		}
		if(!prompted)
			getLogger().log(LogChannel.PROTOCOL, "No prompt after \"" + cmd + "\"; the machine may be running");
		return new CommandResult(cmd, List.copyOf(lines), prompted);
	}

	/**
	 * The address to name in a command, and the name to use if SimH has one.
	 *
	 * <p>Both examine and deposit start the same way ({@code :670-694, 727-747}): a special
	 * register keeps its own address, anything else is translated to physical if it was virtual
	 * and then checked for a register name.</p>
	 */
	private Address toPhysical(Address addr) throws ConsoleException {
		if(addr.type() == MemoryAddressType.SPECIAL_REGISTER)
			return addr;
		if(addr.type() == MemoryAddressType.VIRTUAL) {
			TranslationResult tr = getMmu().translateData(addr);
			if(!tr.isValid())
				throw new ConsoleException("Cannot translate " + addr.toOctal() + ": " + tr.failure());
			return tr.address();
		}
		if(addr.type() != MemoryAddressType.PHYSICAL22)
			return addr.withWidth(MemoryAddressType.PHYSICAL22);
		return addr;
	}

	/** How to name an address in an {@code E} or {@code D} command. */
	private static String commandOperand(Address physical) {
		String regname = addrToRegName(physical);
		return regname != null ? regname : Octal.format(physical.val(), 1);
	}

	@Override
	public CellValue examine(Address addr) throws ConsoleException {
		Address physical = toPhysical(addr);
		String operand = commandOperand(physical);
		if("?".equals(operand)) {
			//-- SimH knows of nothing at this address - 017777710..017777717, the second register
			//-- set some machines have and SimH does not model. From a reader's point of view
			//-- that is exactly a nonexistent address, so it is answered the same way rather
			//-- than thrown: the I/O page scanner walks all 4096 words of the page and a throw
			//-- here would abort the scan eight addresses in. (The Pascal sends "E ?" and lets
			//-- SimH reject it, which arrives at the same answer by a worse route.)
			return CellValue.UNKNOWN;
		}

		int echo = sendCommand("E " + operand);
		//-- The reply is "<addr>: <val>" followed by the prompt.
		AnswerPhrase.ExamineResult r = getAnswers().waitFor(AnswerPhrase.ExamineResult.class,
			echo + 1, CMD_TIMEOUT_MS);
		CellValue result;
		if(r == null) {
			result = CellValue.UNKNOWN;                     // no answer, or an error
		} else if(r.value().isKnown() && r.examineAddr() != null && r.examineAddr().val() != physical.val()) {
			throw new ConsoleException("EXAMINE failure: asked for \"E " + operand
				+ "\", SimH answered \"" + r.rawText() + "\"");
		} else {
			result = r.value();
		}
		checkPromptAfter(echo + 1, "EXAMINE failed, no prompt");
		return result;
	}

	@Override
	public void deposit(Address addr, int value) throws ConsoleException {
		Address physical = toPhysical(addr);
		String operand = commandOperand(physical);
		//-- A deposit that cannot be made must be reported: silently dropping a write is how a
		//-- user ends up debugging a machine that never received what they typed.
		if("?".equals(operand))
			throw new ConsoleException("SimH has no register at " + physical.toOctal());

		//-- SimH refuses to deposit into a live PC while the CPU is running - it answers
		//-- "Invalid argument". Knowing the run state lets that be said clearly and at once,
		//-- instead of round-tripping to SimH to find out ({@code :700-705}).
		if("PC".equals(operand) && m_cpuState == CpuState.RUNNING)
			throw new ConsoleException("DEPOSIT failed: cannot set PC while the CPU is running");

		String cmd = "D " + operand + " " + Octal.format(value & 0xFFFF, 1);
		int echo = sendCommand(cmd);
		checkPromptNoOutput(echo, "DEPOSIT failed, no prompt");
	}

	// -------------------------------------------------------------------------------------
	// Bulk examine
	// -------------------------------------------------------------------------------------

	/** One cell, its physical address, and whether SimH has answered about it yet. */
	private static final class ExamineItem {
		private final MemoryCell m_cell;

		private final Address m_physical;

		/** The Pascal's {@code TMemoryCell.tag}, which it borrows for exactly this ({@code :984}). */
		private boolean m_done;

		private ExamineItem(MemoryCell cell, Address physical) {
			m_cell = cell;
			m_physical = physical;
		}
	}

	/**
	 * Read a whole group, batching consecutive addresses into as few commands as possible.
	 *
	 * <p>Ported from {@code TConsolePDP11SimH.Examine(mcg, ...)} ({@code :767-1017}). SimH takes
	 * an address list - {@code E 0-100,230,234,R0,PC,1000-1006} - so a screenful of memory is
	 * one round trip rather than a hundred. Memory and CPU registers go in separate lists
	 * because their addresses step differently: 2 for memory, 1 for PDP11GUI's byte-spaced
	 * pseudo-registers.</p>
	 *
	 * <p>Where the Pascal stores the physical address in the cell's {@code addr.tmpval} scratch
	 * field and its answered-yet flag in {@code addr.tag}, this keeps both beside the cell for
	 * the duration of the call. A shared mutable scratch field on a model object is exactly the
	 * kind of thing that stops being safe the moment two things run at once.</p>
	 */
	@Override
	public void examine(MemoryCellGroup g, boolean unknownOnly, ProgressMonitor pm) throws ConsoleException {
		List<ExamineItem> memory = new ArrayList<>();
		List<ExamineItem> registers = new ArrayList<>();
		for(MemoryCell mc : List.copyOf(g.getCells())) {
			if(unknownOnly && mc.getPdpValue().isKnown())
				continue;
			Address physical = toPhysical(mc.getAddr());
			String regname = addrToRegName(physical);
			if("?".equals(regname))
				continue;                                   // SimH knows of no such register
			if(regname != null)
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
		//-- One propagation pass at the end rather than one per word; see MemoryCell.setPdpValue.
		MemoryCellGroups owner = g.getOwner();
		if(owner != null) {
			for(MemoryCell mc : List.copyOf(g.getCells())) {
				owner.syncMemoryCells(mc);
			}
		}
	}

	/**
	 * Keep passing over the list until nothing is left - or until a pass answers nothing new.
	 *
	 * <p>The Pascal's {@code while not examineAddrList(...) do ;} ({@code :1013-1014}) relies on
	 * a stated invariant: every call marks at least one more cell answered. That holds as long
	 * as a failure can be attributed to a cell, and {@link #collectBlock} works hard to keep it
	 * true - but "the loop terminates because a comment says it must" is not a property, it is a
	 * hope, and this one spins forever against a SimH that answers about an address nobody
	 * asked for. Counting what is left makes termination something the code enforces.</p>
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
	 * @param addrInc 2 between memory words, 1 between the byte-spaced pseudo-registers
	 * @return true when there is nothing left to do - every cell answered, cancelled, or given
	 *         up on. False means "call me again", and the invariant that makes that terminate is
	 *         that every call marks at least one more cell answered.
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
			return true;                                    // all answered, or the list is empty

		boolean blockFailure = false;
		while(!pm.isCancelled() && !blockFailure && blockstart < list.size()) {
			StringBuilder cmd = new StringBuilder("E");
			String sep = " ";
			int blockend = blockstart;
			//-- Build a comma-separated list of ranges and single addresses. A range runs while
			//-- the addresses step by exactly 2 and no register name interrupts them, and is cut
			//-- off at MAX_BLOCK_LEN so one command cannot become unmanageable.
			do {
				int rangestart = blockend;
				blockend = rangestart + 1;
				while(blockend < list.size()
					&& !list.get(blockend - 1).m_done
					&& list.get(blockend - 1).m_physical.val() + 2 == list.get(blockend).m_physical.val()
					&& addrToRegName(list.get(blockend).m_physical) == null
					&& (blockend - blockstart) < MAX_BLOCK_LEN) {
					blockend++;
				}
				if(blockend - rangestart > 1) {
					cmd.append(sep)
						.append(Octal.format(list.get(rangestart).m_physical.val(), 1))
						.append('-')
						.append(Octal.format(list.get(blockend - 1).m_physical.val(), 1));
				} else {
					cmd.append(sep).append(commandOperand(list.get(rangestart).m_physical));
				}
				sep = ",";
			} while(blockend < list.size() && (blockend - blockstart) < MAX_BLOCK_LEN);

			if(!collectBlock(list, blockstart, blockend, addrInc, cmd.toString(), pm))
				return true;                                // timed out; do not retry, ever
			blockFailure = anyUnanswered(list, blockstart, blockend);
			blockstart = blockend;
		}
		return pm.isCancelled() || !blockFailure;
	}

	private static boolean anyUnanswered(List<ExamineItem> list, int from, int to) {
		for(int i = from; i < to; i++) {
			if(!list.get(i).m_done)
				return true;
		}
		return false;
	}

	/**
	 * Send one {@code E} command and take in its replies.
	 *
	 * <p>SimH answers strictly in ascending address order, one line per address, and simply
	 * stops when it hits a nonexistent one - so a UNIBUS timeout ends the block early and the
	 * address it happened at has to be inferred from the last good answer ({@code :906-940}).</p>
	 *
	 * @return false if SimH stopped answering altogether, which is not worth retrying
	 */
	private boolean collectBlock(List<ExamineItem> list, int blockstart, int blockend, int addrInc,
		String cmd, ProgressMonitor pm) throws ConsoleException {
		int echo = sendCommand(cmd);
		int scanFrom = echo + 1;
		long nextExpected = list.get(blockstart).m_physical.val();

		while(!pm.isCancelled()) {
			if(!anyUnanswered(list, blockstart, blockend))
				return true;
			int at = getAnswers().waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult,
				scanFrom, CMD_TIMEOUT_MS);
			if(at < 0) {
				getLogger().log(LogChannel.OTHER, "EXAMINE list failure: timeout waiting for "
					+ Octal.format(nextExpected, 8));
				return false;
			}
			AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) getAnswers().get(at);
			scanFrom = at + 1;

			//-- A timeout answer names no address, so it belongs to whatever was next in line.
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
				getLogger().log(LogChannel.OTHER, "No memory cell matches SimH's answer \"" + r.rawText() + "\"");
				if(!r.value().isKnown()) {
					//-- A failure that cannot be attributed would otherwise leave the block
					//-- unanswered forever, and the caller retries until something changes.
					//-- Give up on the first outstanding cell so the retry can make progress.
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

	// -------------------------------------------------------------------------------------
	// Execution control
	// -------------------------------------------------------------------------------------

	/**
	 * Reset the machine without starting it.
	 *
	 * <p>Ported from {@code ResetMachine} ({@code :1032-1049}), whose comment is worth keeping:
	 * there is no whitelisted command that resets <i>without</i> also starting the CPU.
	 * {@code RUN} resets, but then runs from the current PC for an unknown number of
	 * instructions before anything could stop it again. So this sends {@code reset all} and, if
	 * SimH rejects it, says so - rather than silently doing nothing, which is what a plain
	 * prompt check gives you, or taking the unsafe run-then-halt route.</p>
	 *
	 * <p>{@code newPc} is ignored: SimH's reset does not set the PC, which is why
	 * {@link ConsoleFeature#RESET_CPU_SETS_PC} is not among the features.</p>
	 */
	@Override
	public void resetMachine(Address newPc) throws ConsoleException {
		int echo = sendCommand("reset all");
		checkPromptNoOutput(echo, "Reset failed, no prompt");
		m_cpuState = CpuState.HALTED;                       // a reset always halts
	}

	/**
	 * Reset and start the CPU at {@code newPc}, which is a virtual address.
	 *
	 * <p>Ported from {@code ResetMachineAndStartCpu} ({@code :1053-1105}). A separate
	 * {@code reset cpu} + {@code go} does not work over the remote console: the reset is
	 * rejected and only the {@code go} runs. {@code RUN} is whitelisted and internally resets
	 * every device before starting ({@code sim_run_boot_prep()} in {@code scp.c}), so one
	 * {@code run} replaces both and is the only way left to get a real reset over this channel.
	 * {@code -q} suppresses the "Resetting all devices..." notice.</p>
	 *
	 * <p>Fire and forget: it does not wait for confirmation, because waiting used to freeze the
	 * UI for however long that took. The run state is set optimistically and at once, which
	 * closes a real race - {@link #haltCpu} decides purely from it, and a halt clicked before
	 * the decoder had seen "Simulator Running..." would otherwise conclude there was nothing to
	 * halt while the CPU was genuinely running. If the machine turns out to have stopped
	 * immediately instead, the decoder corrects the state and
	 * {@link #scheduleSilentHaltResolution} finds the real PC.</p>
	 */
	@Override
	public void resetAndStart(Address newPc) throws ConsoleException {
		if(newPc.type() != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("The start PC is a virtual address, not " + newPc.type());
		clearAnswers();
		m_silentHaltPending = false;
		clearExecutionStop();                               // drop any stale flag from an earlier action
		writeToPdp("run -q " + Octal.format(newPc.val(), 6) + CR);
		m_cpuState = CpuState.RUNNING;
	}

	/** Carry on from where it stopped. No reset, no prompt to wait for. ({@code :1108-1116}) */
	@Override
	public void continueCpu() throws ConsoleException {
		clearAnswers();
		writeToPdp("cont" + CR);
		//-- Optimistic, like resetAndStart; the decoder corrects it if this turns out wrong.
		m_cpuState = CpuState.RUNNING;
	}

	/**
	 * Stop a running program with {@code ^E}.
	 *
	 * <p>Ported from {@code HaltCpu} ({@code :1119-1163}). {@code ^E} only does anything while
	 * SimH considers itself mid-{@code RUN} - verified live against a real running loop.
	 * Outside that it is an inert stray byte that eventually surfaces as {@code Unknown
	 * command}. The execution-control window calls this unconditionally, whatever it believes
	 * the machine is doing, which is exactly why {@link CpuState} is tracked continuously: this
	 * can check a protocol-confirmed state instead of guessing from a timeout.</p>
	 */
	@Override
	public Address haltCpu() throws ConsoleException {
		if(m_cpuState != CpuState.RUNNING) {
			getLogger().log(LogChannel.EXECUTION, "haltCpu: CPU state is " + m_cpuState + ", nothing to halt");
			return null;
		}
		clearAnswers();
		writeToPdp(String.valueOf(HALT_CHAR));
		AnswerPhrase.Halt halt = getAnswers().waitFor(AnswerPhrase.Halt.class, HALT_TIMEOUT_MS);
		writeToPdp(String.valueOf(CR));                     // tidy up whatever ^E left behind
		if(halt == null) {
			//-- The state said Running, so unlike the "already halted" case above - which never
			//-- sent anything - this is a genuine surprise.
			getLogger().log(LogChannel.EXECUTION, "haltCpu: the CPU was running but ^E got no reply");
			return null;
		}
		checkPrompt("Stopping CPU failed, no prompt");
		return halt.haltAddr();
	}

	/**
	 * Execute one instruction.
	 *
	 * <p>Ported from {@code SingleStep} ({@code :1168-1198}). SimH answers {@code STEP 1} with a
	 * "Step expired" line carrying the new PC, which the decoder turns into a halt phrase and
	 * the prompt after it turns into a stop event - so the execution-control window learns the
	 * new PC the same way it learns any other stop.</p>
	 */
	@Override
	public void singleStep() throws ConsoleException {
		int echo = sendCommand("STEP 1");
		AnswerPhrase.Halt halt = getAnswers().waitFor(AnswerPhrase.Halt.class, echo + 1, CMD_TIMEOUT_MS);
		if(halt == null)
			throw new ConsoleException("Single step failed, no answer");
		checkPromptAfter(echo + 1, "Single Step failed, no prompt");
		m_cpuState = CpuState.HALTED;
	}
}
