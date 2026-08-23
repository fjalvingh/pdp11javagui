package to.etc.pdp11.core.fake;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A pretend SimH remote console: enough of the {@code sim>} dialect to drive the real
 * {@code SimhConsole} through every command it sends.
 *
 * <p><b>This one has no Pascal counterpart.</b> Every other console type in PDP11GUI has a
 * matching {@code Fake*} unit, but SimH does not - the Pascal simply tests that one against
 * SimH itself. That is not available here: PLAN.md records that CI has no SimH and is not
 * getting one, while phase 4's "done when" asks for headless tests that examine and deposit
 * against every console. Without this class the single most-used console in the application
 * would be the one console with no test that runs anywhere except this machine.</p>
 *
 * <h2>What it reproduces, and why those things and not others</h2>
 *
 * <p>Not SimH's behaviour in general - only the three properties the protocol layer has to cope
 * with, each of which cost phase 3 an afternoon to establish:</p>
 *
 * <ul>
 * <li><b>Nothing works before {@code ^E}.</b> Multiple-command mode is what produces a
 *     {@code sim>} prompt at all, and SimH answers the {@code ^E} itself with
 *     {@code Unknown command}.</li>
 * <li><b>Every command is echoed back</b>, character by character as it arrives, before it is
 *     acted on. That echo is what {@code SimhConsole} synchronises on.</li>
 * <li><b>The prompt comes before the echo</b> - because it was printed at the end of the
 *     previous command, which is exactly what happens here too. Reproducing that ordering is
 *     the whole point: a fake that printed the prompt after the reply would quietly validate a
 *     synchronisation that does not work against the real thing.</li>
 * </ul>
 *
 * <p>What it does not do is execute PDP-11 instructions; {@link FakePdp11} does not, and a
 * "run" here is a timer that reports a HALT a while later.</p>
 */
public final class FakeSimh extends FakePdp11 {
	public static final String PROMPT = "sim> ";

	private static final char CTRL_E = 5;

	/** R0..R7's offsets within the I/O page, byte-spaced - PDP11GUI's convention. */
	private static final int REG_BASE = 017700;

	private static final int REG_PSW = 017776;

	private static final String[] REG_NAMES = {"R0", "R1", "R2", "R3", "R4", "R5", "SP", "PC"};

	/** {@code psw_modes} ({@code pdp11_cpu.c:431}); "E" is the mode the hardware has no name for. */
	private static final String[] PSW_MODES = {"K", "S", "E", "U"};

	/** Until {@code ^E} arrives, this console says nothing to anybody. */
	private boolean m_masterMode;

	/** Every command line this console has been given, so a test can check what was sent. */
	private final List<String> m_commands = new ArrayList<>();

	public FakeSimh(Scheduler scheduler, Random random) {
		//-- SimH always speaks 22-bit physical addresses, whatever machine it is emulating.
		super("SimH", MemoryAddressType.PHYSICAL22, scheduler, random);
	}

	public boolean isMasterMode() {
		return m_masterMode;
	}

	@Override
	public void powerOn() {
		clearMemory();
		m_masterMode = false;
		clearInput();
		print("Connected to the PDP-11 simulator REM-CON device\r\n"
			+ "PDP-11 Remote Console\r\n"
			+ "Enter single commands or to enter multiple command mode enter the ^E character\r\n");
	}

	@Override
	public void reset() {
		clearInput();
	}

	@Override
	protected void doHalt() {
		//-- What SimH prints when a program runs into a HALT, followed by the prompt that is
		//-- the console layer's cue to fire its execution-stop event.
		print("\r\nHALT instruction, PC: " + Octal.format(getMem(getProgramCounterAddr()), 6) + " (HALT)\r\n");
		print(PROMPT);
	}

	@Override
	public void serialWriteByte(int b) {
		char c = (char) (b & 0x7F);
		if(!m_masterMode) {
			if(c != CTRL_E)
				return;                                     // silent until multiple-command mode
			m_masterMode = true;
			//-- SimH echoes the ^E back and answers it as though it were a command.
			print(c);
			print("\r\nUnknown command\r\n" + PROMPT);
			return;
		}
		if(c == CTRL_E) {
			if(isRunning()) {
				haltSwitch();
				print("\r\nSimulation stopped, PC: " + Octal.format(getMem(getProgramCounterAddr()), 6)
					+ " (HALT)\r\n" + PROMPT);
			} else {
				print(c);
				print("\r\nUnknown command\r\n" + PROMPT);
			}
			return;
		}
		if(c == '\r' || c == '\n') {
			String line = getInputBuffer();
			clearInput();
			print("\r\n");
			execute(line);
			return;
		}
		//-- Plain terminal echo, one character at a time, before anything is acted on.
		print(c);
		appendInput(c);
	}

	/** What has been sent, in order - a bulk examine is worth checking for its batching. */
	public synchronized List<String> getCommands() {
		return List.copyOf(m_commands);
	}

	private void execute(String line) {
		String cmd = line.trim();
		m_commands.add(cmd);
		if(cmd.isEmpty()) {
			print(PROMPT);
			return;
		}
		String[] parts = cmd.split("[ \t]+");
		String op = parts[0].toUpperCase(Locale.ROOT);
		switch(op) {
			case "E", "EX", "EXAM", "EXAMINE" -> doExamine(parts);
			case "D", "DEP", "DEPOSIT" -> doDeposit(parts);
			case "RUN" -> doRun(parts, true);
			case "GO", "C", "CONT", "CONTINUE" -> doRun(parts, false);
			case "STEP", "S" -> doStep();
			case "RESET" -> print(PROMPT);                  // silent on success
			case "SET" -> print(PROMPT);                    // ditto
			case "SH", "SHO", "SHOW" -> doShow(parts);
			default -> print("Unknown command\r\n" + PROMPT);
		}
	}

	private void doShow(String[] parts) {
		//-- Enough of "show cpu" to be recognisable; the console layer only checks that a
		//-- prompt comes back.
		if(parts.length >= 2 && parts[1].equalsIgnoreCase("cpu"))
			print("CPU\t11/70, FPP, RH70, autoconfiguration enabled, idle disabled\r\n\t256KB\r\n");
		print(PROMPT);
	}

	/** {@code E 0-100,230,R0} - a comma-separated list of ranges and single addresses. */
	private void doExamine(String[] parts) {
		if(parts.length < 2) {
			print("Missing argument\r\n" + PROMPT);
			return;
		}
		for(String item : parts[1].split(",")) {
			if(!examineItem(item))
				break;                                      // SimH stops at the first bad address
		}
		print(PROMPT);
	}

	/** @return false when the address did not exist, which ends the whole command */
	private boolean examineItem(String item) {
		String name = item.trim();
		if(name.isEmpty())
			return true;
		int dash = name.indexOf('-');
		if(dash > 0) {
			long from = parseAddressValue(name.substring(0, dash));
			long to = parseAddressValue(name.substring(dash + 1));
			if(from < 0 || to < 0) {
				print("Invalid argument\r\n");
				return false;
			}
			for(long a = from; a <= to; a += 2) {
				if(!printCell(Octal.format(a, 1), Address.of(getAddressType(), a)))
					return false;
			}
			return true;
		}
		Address addr = addressOfName(name);
		if(addr == null) {
			long v = parseAddressValue(name);
			if(v < 0) {
				print("Invalid argument\r\n");
				return false;
			}
			addr = Address.of(getAddressType(), v);
			return printCell(Octal.format(v, 1), addr);
		}
		return printCell(name.toUpperCase(Locale.ROOT), addr);
	}

	private boolean printCell(String label, Address addr) {
		if(!isImplemented(addr)) {
			print("Address space exceeded\r\n");
			return false;
		}
		int value = getMem(addr);
		String decode = "PSW".equals(label) ? "\t" + decodePsw(value) : "";
		print(label + ":\t" + Octal.format(value, 6) + decode + "\r\n");
		return true;
	}

	/**
	 * What SimH prints after the value of a register that has a {@code BITFIELD} table.
	 *
	 * <p>{@code psw_bits} ({@code pdp11_cpu.c:434-447}), printed most significant field first
	 * and with a trailing space, exactly as {@code fprint_bits} leaves it:</p>
	 * <pre>PSW:	000340	CM=K PM=K RS0 FPD0 IPL=7 TBIT0 N0 Z0 V0 C0 </pre>
	 * <p>The fake has this because leaving it out is what let the decoder go on rejecting the
	 * real thing: an {@code E PSW} that every test passed and no live machine did.</p>
	 */
	private static String decodePsw(int psw) {
		return "CM=" + PSW_MODES[(psw >> 14) & 3]
			+ " PM=" + PSW_MODES[(psw >> 12) & 3]
			+ " RS" + ((psw >> 11) & 1)
			+ " FPD" + ((psw >> 8) & 1)
			+ " IPL=" + ((psw >> 5) & 7)
			+ " TBIT" + ((psw >> 4) & 1)
			+ " N" + ((psw >> 3) & 1)
			+ " Z" + ((psw >> 2) & 1)
			+ " V" + ((psw >> 1) & 1)
			+ " C" + (psw & 1)
			+ " ";
	}

	private void doDeposit(String[] parts) {
		if(parts.length < 3) {
			print("Missing argument\r\n" + PROMPT);
			return;
		}
		Address addr = addressOfName(parts[1]);
		if(addr == null) {
			long v = parseAddressValue(parts[1]);
			if(v < 0) {
				//-- "deposit tti time 1300" and friends: a device register by name, which this
				//-- accepts and ignores, exactly as far as the console layer can tell.
				print(PROMPT);
				return;
			}
			addr = Address.of(getAddressType(), v);
		}
		long value = parseAddressValue(parts[2]);
		if(value < 0 || !isImplemented(addr)) {
			print("Non-existent device\r\n" + PROMPT);
			return;
		}
		if(isRunning() && addr.val() == getProgramCounterAddr().val()) {
			//-- SimH refuses to move a live PC.
			print("Invalid argument\r\n" + PROMPT);
			return;
		}
		setMem(addr, (int) value);
		print(PROMPT);
	}

	private void doRun(String[] parts, boolean withPc) {
		long pc = getMem(getProgramCounterAddr());
		if(withPc) {
			for(int i = 1; i < parts.length; i++) {
				if(parts[i].startsWith("-"))
					continue;                               // -q and friends
				long v = parseAddressValue(parts[i]);
				if(v >= 0)
					pc = v;
			}
		}
		print("Simulator Running...");
		//-- No prompt: SimH is busy now, and the next thing it prints is the halt report.
		runToHalt(pc);
	}

	private void doStep() {
		long pc = getMem(getProgramCounterAddr()) + 2;
		setMem(getProgramCounterAddr(), (int) pc);
		print("Step expired, PC: " + Octal.format(pc, 6) + " (SWAB -(R0))\r\n");
		print(PROMPT);
	}

	/** A register name's address, or {@code null} if it is not one. */
	private Address addressOfName(String name) {
		String n = name.trim().toUpperCase(Locale.ROOT);
		for(int i = 0; i < REG_NAMES.length; i++) {
			if(REG_NAMES[i].equals(n))
				return Address.of(getAddressType(), getIopageBase() + REG_BASE + i);
		}
		if(n.equals("PSW"))
			return Address.of(getAddressType(), getIopageBase() + REG_PSW);
		return null;
	}

	/** Octal, or -1 for anything that is not. */
	private static long parseAddressValue(String s) {
		try {
			return Octal.parse(s.trim());
		} catch(RuntimeException x) {
			return -1;
		}
	}

	/** Every address this fake would answer to, for tests that want to know. */
	public List<Address> registerAddresses() {
		List<Address> l = new ArrayList<>();
		for(int i = 0; i < REG_NAMES.length; i++) {
			l.add(Address.of(getAddressType(), getIopageBase() + REG_BASE + i));
		}
		return l;
	}
}
