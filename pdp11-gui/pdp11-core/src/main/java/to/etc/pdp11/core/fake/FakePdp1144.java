package to.etc.pdp11.core.fake;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.CpuRegisters;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A simulated PDP-11/44 console.
 *
 * <p>Ported from {@code TFakePDP1144} ({@code FakePDP1144U.pas}). The 11/44's console is a
 * separate microprocessor with a line-oriented command language, so unlike ODT it takes a whole
 * command and a carriage return: {@code E 1000}, {@code D 1000 123456}, {@code S 1000}.</p>
 *
 * <h2>The command language, as far as PDP11GUI uses it</h2>
 *
 * <pre>
 * E &lt;addr&gt;        examine
 * E                examine the next word after the last one examined
 * E/G &lt;n&gt;          examine global register n
 * E/N:&lt;count&gt; ...  examine count consecutive locations
 * D &lt;addr&gt; &lt;val&gt;   deposit
 * D + &lt;val&gt;        deposit into the word after the last one deposited
 * D/G &lt;n&gt; &lt;val&gt;    deposit into global register n
 * S &lt;addr&gt;         start
 * I                initialise
 * </pre>
 *
 * <p>Modifiers stack on the opcode and are combinable: {@code E/G/N:10 0} reads the whole
 * register file. The prompt is {@code &gt;&gt;&gt;} and errors are numbered, {@code ?01 SYN?} being
 * the one for anything it could not parse.</p>
 *
 * <p>The <b>global registers</b> are the reason {@code /G} exists at all: R0..R7 and their
 * duplicates for the second register set live at sixteen <i>consecutive byte</i> addresses from
 * {@code 17777700}, and {@code /G n} is shorthand for that base plus n - which is also why the
 * address step under {@code /G} is 1 rather than 2.</p>
 *
 * <p>This class is subclassed by {@link FakePdp1144V340c}, which is the same console running
 * different firmware. The Pascal duplicates the whole unit for that; the differences are
 * gathered here as overridable pieces instead, so that what actually differs between the two
 * firmwares is a readable list rather than a diff of two 450-line files.</p>
 */
public class FakePdp1144 extends FakePdp11 {
	protected static final char CR = '\r';

	protected static final char LF = '\n';

	protected static final char CTRL_C = 3;

	protected static final char RUBOUT = 0x7F;

	/** Gets the console's attention. On a running machine it switches the terminal to console mode. */
	protected static final char CTRL_P = 0x10;

	/** R0..R7 and R10..R17 at sixteen consecutive byte addresses. {@code :92-93}. */
	protected static final long GLOBAL_REGISTER_BASE =
		CpuRegisters.addressIn(MemoryAddressType.PHYSICAL22, CpuRegisters.R0_OFFSET);

	/**
	 * Every address a 22-bit machine has, odd ones included.
	 *
	 * <p>Not {@code MemoryAddressType.getMaxAddress()}, which is the highest <i>word</i> address
	 * and so two lower. This console examines odd addresses quite happily, and using the word
	 * bound here would both reject {@code 17777777} and, as a mask, clear the low bit that tells
	 * one global register from the next.</p>
	 */
	protected static final long ADDRESS_SPACE_MASK = 017777777L;

	private Address m_lastExamineAddr;

	private Address m_lastDepositAddr;

	/**
	 * The error the command being processed ran into, printed by the next prompt.
	 *
	 * <p>The Pascal also prints it from {@code SerialReadByte} ({@code :152-157}), and
	 * destructively - it <i>replaces</i> the output buffer rather than appending to it, so
	 * anything already printed and not yet read would be lost. That path is unreachable:
	 * {@code setMem} and {@code getMem} are the only things that set this, both are only
	 * reached from inside the carriage-return handler, and the prompt at the end of that
	 * handler always clears it first. Not reproduced.
	 */
	private String m_lastError = "";

	/** RUBOUT echoes the character it deleted, and brackets the run with backslashes. */
	private boolean m_eraseActive;

	private char m_eraseEchoChar;

	public FakePdp1144(Scheduler scheduler, Random random) {
		this("Fake PDP-11/44", scheduler, random);
	}

	protected FakePdp1144(String name, Scheduler scheduler, Random random) {
		super(name, MemoryAddressType.PHYSICAL22, scheduler, random);
	}

	// -------------------------------------------------------------------------------------
	// What the firmware prints
	// -------------------------------------------------------------------------------------

	/** Printed on power-up, before the reset banner. Nothing, on this firmware. */
	protected void printPowerOnBanner() {
	}

	/** Printed by a reset, before the halt report. */
	protected void printResetBanner() {
		print("" + CR + LF + "CONSOLE");
	}

	protected String errorSyntax() {
		return "?01 SYN?";
	}

	protected String errorBusTimeout() {
		return "?20 TRAN ERR";
	}

	/** The key that erases the last character typed. */
	protected char eraseKey() {
		return RUBOUT;
	}

	@Override
	public void powerOn() {
		clearMemory();
		clearInput();
		takeOutput();
		printPowerOnBanner();
		reset();
	}

	@Override
	public void reset() {
		clearInput();
		printResetBanner();
		doHalt();
	}

	/**
	 * Where a halted machine says it is: the PC's own address, then its contents.
	 *
	 * <p>{@code 17777707 001000} - the console does not label it, it simply examines R7 at you
	 * ({@code :433-441}).</p>
	 */
	@Override
	protected void doHalt() {
		printHaltReport();
		doPrompt();
	}

	/**
	 * The stop report on its own, without the prompt after it.
	 *
	 * <p>Separate because a stop reached through a <i>command</i> gets its prompt from the
	 * command handler; printing one here as well would give two, and a second prompt with no
	 * halt in front of it is exactly the shape that cancels a pending stop event.</p>
	 */
	protected void printHaltReport() {
		int pc = getMem(getProgramCounterAddr());
		print("" + CR + LF + "17777707 " + Octal.format(pc, 6));
	}

	protected void doPrompt() {
		if(!m_lastError.isEmpty()) {
			print("" + CR + LF + m_lastError);
			m_lastError = "";
		}
		print("" + CR + LF + ">>>");
		m_eraseActive = false;
		clearInput();
	}

	protected void setError(String error) {
		m_lastError = error;
	}

	protected boolean hasError() {
		return !m_lastError.isEmpty();
	}

	/** What the current command went wrong with, or {@code ""}. */
	protected String getError() {
		return m_lastError;
	}

	protected void clearError() {
		m_lastError = "";
	}

	// -------------------------------------------------------------------------------------
	// Memory, with the console's own error reporting
	// -------------------------------------------------------------------------------------

	/**
	 * Read, turning a nonexistent address into the console's bus-timeout message.
	 *
	 * <p>The Pascal overrides {@code getMem}/{@code setMem} themselves for this ({@code :126-146}).
	 * Here they stay as they are - "nonexistent memory throws" is a contract other things rely
	 * on - and the swallowing happens where the console needs it.</p>
	 *
	 * <p>Note what it does <b>not</b> check: odd addresses. The comment is explicit about it,
	 * twice. An 11/44 console will happily examine an odd address.</p>
	 */
	protected int safeGetMem(Address addr) {
		try {
			return getMem(addr);
		} catch(FakePdp11Exception x) {
			setError(errorBusTimeout());
			return 0;
		}
	}

	protected void safeSetMem(Address addr, int value) {
		try {
			setMem(addr, value);
		} catch(FakePdp11Exception x) {
			setError(errorBusTimeout());
		}
	}

	// -------------------------------------------------------------------------------------
	// The keystroke handler
	// -------------------------------------------------------------------------------------

	@Override
	public void serialWriteByte(int b) {
		//-- No gate on isRunning here, deliberately: the Pascal has none either ({@code :171-256}),
		//-- and this console goes on answering while a program has the machine. The V3.40C
		//-- firmware is the one that stops listening, and it says so by overriding this.
		handleKey((char) (b & 0x7F));                       // a 7-bit line, as always
	}

	protected void handleKey(char c) {
		if(c == CTRL_P) {
			//-- Not a console command but a terminal mode switch: this console is already in
			//-- console mode, so there is nothing to do and nothing to echo.
			return;
		}
		if(c != eraseKey()) {
			//-- A run of erases is closed with a backslash when something else is typed.
			if(m_eraseActive)
				print('\\');
			m_eraseActive = false;
		}
		if(c == CTRL_C) {
			keyControlC();
		} else if(c == CR) {
			executeLine(getInputBuffer());
			promptAfterCommand();
		} else if(c == eraseKey()) {
			keyErase();
		} else {
			appendInput(c);
			echo(c);
		}
	}

	protected void keyControlC() {
		print("^C");
		doPrompt();
	}

	/** Echo one typed character. */
	protected void echo(char c) {
		print(c);
	}

	/** Whether a prompt follows every command. It does here; the V3.40C firmware differs. */
	protected void promptAfterCommand() {
		doPrompt();
	}

	/**
	 * The erase key, whose behaviour the Pascal's own comment calls "etwas hohl" - a bit daft -
	 * and which is reproduced daftness and all ({@code :228-250}).
	 *
	 * <p>A run of erases opens with a backslash and echoes each character as it deletes it. Once
	 * there is nothing left to delete it keeps echoing the <i>last</i> character deleted,
	 * forever, even though it is already gone.</p>
	 */
	protected void keyErase() {
		boolean empty = getInputBuffer().isEmpty();
		if(!m_eraseActive) {
			if(empty)
				m_eraseEchoChar = 0;                        // erasing an empty line echoes nothing
			print('\\');
			m_eraseActive = true;
		}
		if(empty) {
			if(m_eraseEchoChar != 0)
				print(m_eraseEchoChar);
		} else {
			m_eraseEchoChar = removeLastInput();
			print(m_eraseEchoChar);
		}
	}

	// -------------------------------------------------------------------------------------
	// Commands
	// -------------------------------------------------------------------------------------

	/** Split on a delimiter, dropping empty pieces - the Pascal's {@code ExtractWord}. */
	protected static List<String> words(String s, char delimiter) {
		List<String> l = new ArrayList<>();
		for(String part : s.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)))) {
			if(!part.isEmpty())
				l.add(part);
		}
		return l;
	}

	private static String word(List<String> words, int index) {
		return index < words.size() ? words.get(index) : "";
	}

	/**
	 * Take a whole typed line apart and run it.
	 *
	 * <p>An opcode may carry several modifiers - {@code E/G/N:66} - so the first space-separated
	 * word is split again on slashes ({@code :208-215}).</p>
	 */
	protected void executeLine(String line) {
		List<String> parts = words(line, ' ');
		String head = word(parts, 0).toUpperCase(Locale.ROOT);
		String parm1 = word(parts, 1).toUpperCase(Locale.ROOT);
		String parm2 = word(parts, 2).toUpperCase(Locale.ROOT);

		List<String> modifiers = words(head, '/');
		String opcode = modifiers.isEmpty() ? "" : modifiers.remove(0);
		dispatch(opcode, modifiers, parm1, parm2);
	}

	/**
	 * Which commands this firmware has.
	 *
	 * <p><b>{@code H}, {@code N} and {@code C} are an extension</b>, and it is worth saying where
	 * they come from. The Pascal fake stops at {@code E}, {@code D}, {@code S}, {@code I} and
	 * {@code ^C} - its own header says so - and never grew the three the shipped console driver
	 * sends for halt, single step and continue. The driver is the evidence: it sends {@code H},
	 * {@code N 1} and {@code C}, and parses what comes back from the first two as
	 * {@code 17777707 <pc>}, so the machine it was written against accepts all three and answers
	 * a stop with that report. The <i>format</i> is therefore the Pascal's and came from real
	 * hardware; only the fact that the fake answers at all is inferred.</p>
	 */
	protected void dispatch(String opcode, List<String> modifiers, String parm1, String parm2) {
		switch(opcode) {
			case "D" -> doDeposit(modifiers, parm1, parm2);
			case "E" -> doExamine(modifiers, parm1);
			case "S" -> doStart(parm1);
			case "I" -> doInit();
			case "H" -> doHaltCommand();
			case "N" -> doSingleStep(parm1);
			case "C" -> doContinue();
			default -> setError(errorSyntax());
		}
	}

	/** {@code H} - halt, and say where. Answers whether or not anything was running. */
	protected void doHaltCommand() {
		haltSwitch();
		printHaltReport();
	}

	/** {@code C} - carry on from where the machine is, with no initialise. */
	protected void doContinue() {
		printStarting();
		runToHalt(getMem(getProgramCounterAddr()));
	}

	/** {@code N <count>} - step that many instructions, then report like any other stop. */
	protected void doSingleStep(String countText) {
		long count = countText.isEmpty() ? 1 : parseOctalOr(countText, -1);
		if(count < 0) {
			setError(errorSyntax());
			return;
		}
		int pc = getMem(getProgramCounterAddr());
		setMem(getProgramCounterAddr(), (int) (pc + 2 * count));
		printHaltReport();
	}

	/** How a modifier list changes the address and the repeat count. */
	protected static final class Modifiers {
		long base;

		int step = 2;

		int count = 1;

		boolean global;
	}

	/**
	 * Apply {@code /G} and {@code /N:count}.
	 *
	 * <p>{@code /G} is why the step becomes 1: the global registers are byte-spaced, not
	 * word-spaced.</p>
	 */
	protected Modifiers applyModifiers(List<String> modifiers, long addrValue) {
		Modifiers m = new Modifiers();
		m.base = addrValue;
		for(String mod : modifiers) {
			String u = mod.toUpperCase(Locale.ROOT);
			if(u.equals("G")) {
				m.global = true;
				m.step = 1;
				m.base = GLOBAL_REGISTER_BASE + addrValue;
			} else if(u.startsWith("N")) {
				List<String> parts = words(u, ':');
				long n = parseOctalOr(parts.size() > 1 ? parts.get(1) : "", -1);
				if(n < 0 || n > 0xFFFF)
					setError(errorSyntax());
				else
					m.count = (int) n;
			}
		}
		return m;
	}

	protected static long parseOctalOr(String s, long fallback) {
		try {
			return Octal.parse(s);
		} catch(RuntimeException x) {
			return fallback;
		}
	}

	protected void doDeposit(List<String> modifiers, String addrText, String valueText) {
		clearError();
		long addrValue;
		if("+".equals(addrText)) {
			//-- Carry on from the last deposit.
			addrValue = (m_lastDepositAddr == null ? 0 : m_lastDepositAddr.val()) + 2;
		} else {
			addrValue = parseOctalOr(addrText, -1);
			if(addrValue < 0) {
				setError(errorSyntax());
				addrValue = 0;
			}
		}
		Modifiers m = applyModifiers(modifiers, addrValue);
		long value = parseOctalOr(valueText, -1);
		if(value < 0 || value > 0xFFFF) {
			setError(errorSyntax());
			value = 0;
		}
		long addr = maskDepositAddress(m.base);
		if(hasError())
			return;
		for(int i = 0; i < m.count; i++) {
			Address a = Address.of(getAddressType(), addr);
			safeSetMem(a, (int) value);
			m_lastDepositAddr = a;
			addr += m.step;
		}
	}

	/**
	 * Reject an address that does not fit the machine.
	 *
	 * <p>{@code FakePDP11_max_addr} is 2^22, the whole physical address space, so this is "does
	 * it fit in 22 bits" and nothing to do with how much memory is fitted ({@code :323-324}).
	 * The V3.40C firmware masks instead of rejecting.</p>
	 */
	protected long maskDepositAddress(long addrValue) {
		if(addrValue > ADDRESS_SPACE_MASK)
			setError(errorSyntax());
		return addrValue;
	}

	protected void doExamine(List<String> modifiers, String addrText) {
		clearError();
		long addrValue;
		if(addrText.isEmpty()) {
			//-- No address: the word after the last one examined.
			addrValue = (m_lastExamineAddr == null ? 0 : m_lastExamineAddr.val()) + 2;
		} else {
			addrValue = parseOctalOr(addrText, -1);
			if(addrValue < 0) {
				setError(errorSyntax());
				addrValue = 0;
			}
		}
		Modifiers m = applyModifiers(modifiers, addrValue);
		long addr = maskExamineAddress(m.base);
		if(hasError())
			return;
		//-- What the user asked for, which under /G is the register number rather than the
		//-- address it resolves to. Only the V3.40C firmware prints it.
		long shown = addrValue;
		for(int i = 0; i < m.count; i++) {
			if(!examineOne(m, Address.of(getAddressType(), addr), shown))
				break;
			addr += m.step;
			shown += m.step;
		}
		finishExamine();
	}

	protected void setLastExamineAddr(Address addr) {
		m_lastExamineAddr = addr;
	}

	protected long maskExamineAddress(long addrValue) {
		return maskDepositAddress(addrValue);
	}

	/**
	 * Read one location and print the answer.
	 *
	 * <p>A bus error prints nothing for that address but does <b>not</b> stop the rest of the
	 * count - the loop carries on and, since the error is still set, prints nothing for those
	 * either ({@code :388-398}). The V3.40C firmware stops instead.</p>
	 *
	 * @param shown what the user asked for, which under {@code /G} is the register number
	 *              rather than its address - only the V3.40C firmware prints that
	 * @return whether to carry on with the rest of the count
	 */
	protected boolean examineOne(Modifiers m, Address addr, long shown) {
		int value = safeGetMem(addr);
		setLastExamineAddr(addr);
		if(!hasError())
			print("" + CR + LF + addr.toOctal() + " " + Octal.format(value, 6));
		return true;
	}

	/** Anything printed after the last line of an examine. Nothing, on this firmware. */
	protected void finishExamine() {
	}

	/**
	 * {@code I} - initialise.
	 *
	 * <p>The Pascal spends a second here pumping {@code Application.ProcessMessages}
	 * ({@code :404-415}) to make the delay feel real. There is no message loop to pump and a
	 * fake that sleeps for a second is a test that takes a second, so this does nothing
	 * visible - which is also what the real command does, since it prints nothing either.</p>
	 */
	protected void doInit() {
	}

	/** {@code S <addr>} - start. Only the last six digits count. */
	protected void doStart(String addrText) {
		if(addrText.isEmpty())
			return;
		String digits = addrText.length() > 6 ? addrText.substring(addrText.length() - 6) : addrText;
		long value = parseOctalOr(digits, -1);
		if(value < 0) {
			setError(errorSyntax());
			return;
		}
		printStarting();
		runToHalt(value);
	}

	/** Printed just before the pretend program starts. Nothing, on this firmware. */
	protected void printStarting() {
	}
}
