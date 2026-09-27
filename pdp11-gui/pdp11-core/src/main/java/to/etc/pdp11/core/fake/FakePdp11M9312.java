package to.etc.pdp11.core.fake;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.Scheduler;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A PDP-11 whose only console is the M9312 boot ROM's console emulator.
 *
 * <p>Ported from {@code TFakePDP11M9312} ({@code FakePDP11M9312U.pas}). The behaviour comes from
 * <i>M9312 bootstrap-terminator module technical manual</i> (March 1981, EK-M9312-TM-003),
 * pages 3-1 onwards, with the error handling on 3-6 and 3-7.</p>
 *
 * <h2>This is not a console, it is a program pretending to be one</h2>
 *
 * <p>The console emulator is code the CPU executes out of the boot ROM, and the Pascal's own
 * summary of it is "unglaublich schwach" - unbelievably feeble. What that means in practice
 * shapes every console driver written against it:</p>
 *
 * <ul>
 * <li>Four commands: <b>L</b>oad address, <b>E</b>xamine, <b>D</b>eposit, <b>S</b>tart. There is
 *     no halt, no reset and no initialise.</li>
 * <li>There is no address argument on examine or deposit. You load an address and then examine
 *     it; a second examine in a row auto-advances by two.</li>
 * <li><b>An error stops the CPU dead.</b> An odd address, an address in the register space, or a
 *     bus timeout do not draw a message and a new prompt - they halt the emulator itself, which
 *     on real hardware can only be restarted from the front panel. This class prints a bracketed
 *     note and then accepts nothing but ESC, standing in for that.</li>
 * <li>Only a 16-bit virtual address space, and the registers at {@code 177700}..{@code 177707}
 *     cannot be reached at all.</li>
 * <li>The prompt carries a register dump: {@code 000000 173400 165212 <loaded address>}. Those
 *     are not the program's registers - they are the console emulator's own.</li>
 * </ul>
 *
 * <p>Two-letter device codes are boot commands rather than typing mistakes, which is why
 * {@code DL} is not a malformed deposit: it boots RL0. That check has to happen while the second
 * character is being typed, because the emulator validates as it goes.</p>
 */
public class FakePdp11M9312 extends FakePdp11 {
	protected static final char CR = '\r';

	protected static final char LF = '\n';

	protected static final char RUBOUT = 0x7F;

	protected static final char ESC = 0x1B;

	/**
	 * What the emulator leaves in its loaded-address register at reset, and after every error.
	 *
	 * <p>Not a round number and not a guess: {@code 165212} is where the ROM's own code happens
	 * to be, and it is what the display really shows ({@code :188-190}).</p>
	 */
	private static final int INITIAL_LOADED_ADDRESS = 0165212;

	private static final long GPR_BASE = 0177700;

	/** Where the emulator is. Ported from {@code TFakePDP11M9312State}. */
	public enum State {
		/** Powered on, nothing typed. */
		INIT,
		/** A prompt is showing and input is being collected. */
		PROMPT,
		/** The emulator itself ran into a HALT. Only ESC gets out of this. */
		HALTED
	}

	/**
	 * The devices this ROM set knows how to boot, from the technical manual page 3-4.
	 *
	 * <p>Which of them actually work depends on which ROMs are fitted, which nothing here
	 * models - what matters to a console driver is that these two-letter sequences are commands
	 * and not errors.</p>
	 */
	private static final List<String> BOOT_DEVICES = List.of(
		"DL", "DX", "DK", "DT", "DY", "DM", "MT", "MM", "CT", "PR", "TT", "DP", "DB", "DS", "MS", "DD");

	private State m_state = State.INIT;

	/** {@code @} on an M9312, {@code $} plus a NUL on an M9301. */
	private String m_prompt = "@";

	private Address m_loadedAddress;

	/**
	 * Why the loaded address was last used - {@code E}, {@code D}, or {@code X} for neither.
	 *
	 * <p>This is the whole of the auto-advance rule: two examines in a row advance, and so do two
	 * deposits, but an examine after a deposit does not, and neither does anything after a fresh
	 * {@code L} ({@code :360-374}).</p>
	 */
	private char m_lastUse = 'X';

	public FakePdp11M9312(Scheduler scheduler, Random random) {
		this("Fake PDP-11 M9312", scheduler, random);
	}

	protected FakePdp11M9312(String name, Scheduler scheduler, Random random) {
		//-- Only a 16-bit virtual address space; 76xxxx addresses map onto 16xxxx.
		super(name, MemoryAddressType.PHYSICAL16, scheduler, random);
	}

	public State getState() {
		return m_state;
	}

	public String getPrompt() {
		return m_prompt;
	}

	protected void setPrompt(String prompt) {
		m_prompt = prompt;
	}

	public Address getLoadedAddress() {
		return m_loadedAddress;
	}

	@Override
	public void powerOn() {
		clearMemory();
		clearInput();
		takeOutput();
		reset();
	}

	/** The same as rebooting from the front panel, without wiping memory. */
	@Override
	public void reset() {
		m_state = State.INIT;
		clearInput();
		m_loadedAddress = Address.of(getAddressType(), INITIAL_LOADED_ADDRESS);
		m_lastUse = 'X';
		doPrompt(true);
	}

	// -------------------------------------------------------------------------------------
	// Printing
	// -------------------------------------------------------------------------------------

	/**
	 * One line feed and thirteen carriage returns.
	 *
	 * <p>Sent before a register dump and before the prompt ({@code :253-255}). It is not a line
	 * ending in any normal sense - the ROM is winding the carriage of a hard-copy terminal back
	 * to the left margin and giving it time to get there - but it is what a scanner has to cope
	 * with, so it is reproduced exactly.</p>
	 */
	private static final String LONG_NEWLINE = "" + LF + CR + CR + CR + CR + CR + CR + CR + CR + CR + CR + CR + CR + CR;

	/**
	 * The prompt, optionally preceded by the emulator's four registers.
	 *
	 * <p>Prints nothing at all while the emulator is halted or a program is running - there is
	 * nobody to print it.</p>
	 */
	protected void doPrompt(boolean withRegisters) {
		clearInput();
		if(m_state == State.HALTED || isRunning())
			return;
		if(withRegisters) {
			print(LONG_NEWLINE);
			//-- The M9312's four are fixed except the last, which is the loaded address. Not
			//-- reproduced: the one-off "000000 000000 000000 000000" after a power-on.
			print("000000 173400 165212 ");
			print(Octal.format(m_loadedAddress.val(), 6) + " ");
		}
		print(LONG_NEWLINE + m_prompt);
		m_state = State.PROMPT;
	}

	/**
	 * A typing mistake: throw the line away and prompt again.
	 *
	 * <p>The loaded address is reset to {@code 165212} afterwards, so the dump shows what it was
	 * when the mistake was made and the emulator carries on from the ROM's own address
	 * ({@code :216-227}).</p>
	 */
	protected void doInputError(boolean withRegisters) {
		clearInput();
		m_lastUse = 'X';
		doPrompt(withRegisters);
		m_loadedAddress = Address.of(getAddressType(), INITIAL_LOADED_ADDRESS);
	}

	/**
	 * A program that was started ran into a HALT.
	 *
	 * <p>The emulator does not survive that either; the note about ESC stands in for the
	 * front-panel control-boot a real machine would need ({@code :229-238}).</p>
	 */
	@Override
	protected void doHalt() {
		print("" + CR + LF);
		print("[Started program halted. Reboot by pressing ESC]");
		m_state = State.HALTED;
	}

	/** The emulator itself hit an error it cannot continue past. */
	protected void doEmulatorErrorHalt(String message) {
		print("" + CR + LF);
		print("[Emulator error \"" + message + "\": HALT. Reboot by pressing ESC]");
		m_state = State.HALTED;
	}

	// -------------------------------------------------------------------------------------
	// Commands
	// -------------------------------------------------------------------------------------

	/** {@code L nnnnnn}. Only the last six digits count, and only sixteen bits of them. */
	protected void doLoadAddress(String addrExpr) {
		String digits = addrExpr.length() > 6 ? addrExpr.substring(addrExpr.length() - 6) : addrExpr;
		long value;
		try {
			value = Octal.parse(digits);
		} catch(RuntimeException x) {
			//-- No register dump for a bad number.
			doInputError(false);
			return;
		}
		m_loadedAddress = Address.of(getAddressType(), value & 0xFFFF);
		m_lastUse = 'L';                                    // do not advance on the next use
	}

	/**
	 * Whether the loaded address can be used at all - and halt the emulator if it cannot.
	 *
	 * <p>Three ways to lose the machine: an odd address, an address in the register space, and
	 * an address that does not answer ({@code :340-357}).</p>
	 */
	protected boolean isLoadedAddrValid() {
		long v = m_loadedAddress.val();
		if((v & 1) != 0) {
			doEmulatorErrorHalt("odd address");
			return false;
		}
		//-- Note the bound: R7 at 177707 is NOT caught, because the Pascal writes "+ 7" where
		//-- eight registers need "+ 8". Kept - the emulator's own behaviour is not known here,
		//-- and a driver that relies on examining R7 through this would break if it changed.
		if(v >= GPR_BASE && v < GPR_BASE + 7) {
			doEmulatorErrorHalt("GPR space");
			return false;
		}
		try {
			getMem(m_loadedAddress);
		} catch(FakePdp11Exception x) {
			doEmulatorErrorHalt("BUS error");
			return false;
		}
		return true;
	}

	/** Advance by two if this is the second use of the same kind in a row, rolling over at the top. */
	protected void incLoadedAddress(char use) {
		if(use == m_lastUse) {
			long v = m_loadedAddress.val();
			m_loadedAddress = Address.of(getAddressType(), v == 0177776 ? 0 : v + 2);
		}
		m_lastUse = use;
	}

	/** {@code E}. Prints {@code <addr> <val> } after the {@code E } already echoed. */
	protected void doExamine() {
		incLoadedAddress('E');
		if(!isLoadedAddrValid())
			return;                                         // the halt has already been printed
		int val = getMem(m_loadedAddress);
		print(m_loadedAddress.toOctal() + " " + Octal.format(val, 6) + " ");
	}

	/** {@code D nnnnnn}. Prints nothing at all when it works. */
	protected void doDeposit(String valueExpr) {
		incLoadedAddress('D');
		if(!isLoadedAddrValid())
			return;
		String digits = valueExpr.length() > 6 ? valueExpr.substring(valueExpr.length() - 6) : valueExpr;
		long value;
		try {
			value = Octal.parse(digits) & 0xFFFF;
		} catch(RuntimeException x) {
			doInputError(false);
			return;
		}
		setMem(m_loadedAddress, (int) value);
	}

	/** {@code S}. Starts at the loaded address, and stops the machine if it cannot. */
	protected void doStart() {
		if(!isLoadedAddrValid()) {
			doEmulatorErrorHalt("Start to invalid address");
			return;
		}
		runToHalt(m_loadedAddress.val());
	}

	/**
	 * Recognise, and optionally run, one command line.
	 *
	 * <p>The opcode is the first one or two characters and the argument is whatever follows;
	 * an empty argument counts as {@code 0} ({@code :464-492}).</p>
	 *
	 * @param checkOnly true while the line is still being typed, to find out whether it could
	 *                  still become something valid
	 * @return whether this is a command at all
	 */
	protected boolean doCommand(String cmd, boolean checkOnly) {
		String opcode = cmd.length() >= 2 ? cmd.substring(0, 2) : cmd;
		String args = cmd.length() > 2 ? cmd.substring(2).trim() : "";
		switch(opcode) {
			case "L ":
				if(!checkOnly)
					doLoadAddress(args.isEmpty() ? "0" : args);
				return true;
			case "D ":
				if(!checkOnly)
					doDeposit(args.isEmpty() ? "0" : args);
				return true;
			case "E ":
				if(!checkOnly)
					doExamine();
				return true;
			case "S":
				//-- Two characters were taken, so this matches only when the whole line is "S":
				//-- start takes no argument, and "SX" is a typing mistake rather than a start.
				if(!checkOnly)
					doStart();
				return true;
			default:
				return false;
		}
	}

	/**
	 * Whether this is a boot command: two upper-case letters naming a device, then an optional
	 * unit number.
	 */
	protected boolean isBootCommand(String s) {
		if(s.length() < 2)
			return false;
		String device = s.substring(0, 2);
		String unit = s.substring(2);
		if(unit.isEmpty())
			unit = "0";
		if(!device.equals(device.toUpperCase(Locale.ROOT)))
			return false;                                   // upper case only
		if(!BOOT_DEVICES.contains(device))
			return false;
		try {
			long n = Octal.parse(unit);
			return n <= 0xFFFF;
		} catch(RuntimeException x) {
			return false;
		}
	}

	// -------------------------------------------------------------------------------------
	// The keystroke handler
	// -------------------------------------------------------------------------------------

	/**
	 * Feed one typed character in.
	 *
	 * <p>Ported from {@code SerialWriteByte} ({@code :541-651}). The input processor is the
	 * ROM's, and its shape is unusual because it validates as it goes rather than on the
	 * carriage return:</p>
	 *
	 * <ul>
	 * <li>The <b>first</b> character after a prompt is always echoed and accepted, whatever it
	 *     is.</li>
	 * <li>The <b>second</b> decides: if the two together cannot become a command or a boot code,
	 *     the line is thrown away with a register dump there and then.</li>
	 * <li>After that only octal digits are allowed, and anything else throws the line away -
	 *     without a dump this time.</li>
	 * <li>{@code E } is executed on the space, with no carriage return needed.</li>
	 * <li>The <b>first</b> carriage return after a prompt is only remembered; the second runs
	 *     the line.</li>
	 * </ul>
	 */
	@Override
	public void serialWriteByte(int b) {
		//-- A running CPU means there is no console emulator: it is the program's machine now.
		if(isRunning())
			return;
		char c = (char) (b & 0x7F);

		if(m_state == State.HALTED) {
			//-- Standing in for a control-boot from the front panel.
			if(c == ESC)
				reset();
			return;
		}

		if(c == RUBOUT) {
			clearInput();
			doPrompt(false);
			return;
		}
		String input = getInputBuffer();
		if(c == CR) {
			if(input.isEmpty()) {
				//-- The first CR is only remembered, so that the next one has something to run.
				appendInput(c);
			} else if(doCommand(input, false)) {
				doPrompt(false);
			} else if(isBootCommand(input)) {
				//-- A boot is a program start like any other: it runs, and later halts.
				doStart();
				doPrompt(false);
			} else {
				doInputError(true);
			}
			return;
		}
		if(c == ' ' && "E".equals(input)) {
			//-- "E " examines at once; no carriage return is needed or expected.
			appendInput(c);
			print(c);
			doExamine();
			doPrompt(false);
			return;
		}
		switch(input.length()) {
			case 0 -> {
				//-- Always accepted, whatever it is.
				appendInput(c);
				print(c);
			}
			case 1 -> {
				appendInput(c);
				print(c);
				String now = getInputBuffer();
				if(!doCommand(now, true) && !isBootCommand(now))
					doInputError(true);
			}
			default -> {
				appendInput(c);
				print(c);
				if(c < '0' || c > '7')
					doInputError(false);                    // no register dump for a bad digit
			}
		}
	}
}
