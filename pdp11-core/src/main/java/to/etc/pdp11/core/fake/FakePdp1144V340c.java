package to.etc.pdp11.core.fake;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.Scheduler;

import java.util.List;
import java.util.Random;

/**
 * A PDP-11/44 running the undocumented V3.40C console firmware.
 *
 * <p>Ported from {@code TFakePDP1144v340C} ({@code FakePDP1144v340cU.pas}), which is a copy of
 * the 11/44 unit with roughly a hundred lines changed. Here it is a subclass, so the changes are
 * the class: same command language, different everything else.</p>
 *
 * <h2>What this firmware does differently</h2>
 *
 * <ul>
 * <li><b>It has a console mode and a program mode</b>, and says which it is in. While the CPU is
 *     running the only key it listens to is {@code ^P}, which stops it - the base firmware
 *     simply ignores the console while a program runs.</li>
 * <li><b>Backspace erases</b>, not RUBOUT.</li>
 * <li><b>Errors are words, not numbers</b>: {@code ?Bus timeout error?} where the base firmware
 *     says {@code ?20 TRAN ERR}.</li>
 * <li><b>Examine prints a space class</b>: {@code   P  00001000  123456} for physical memory and
 *     {@code   G  0000000000  000000} for a global register - and under {@code /G} it shows the
 *     register <i>number</i> you asked for rather than the address it resolved to.</li>
 * <li><b>Addresses are masked, not rejected.</b> Depositing above the address space wraps into
 *     it rather than drawing an error.</li>
 * <li><b>{@code H} and a bare carriage return exist</b>, and anything unrecognised draws
 *     {@code ?What?}.</li>
 * <li><b>Control characters echo as mnemonics</b>: a typed {@code ^C} appears as {@code ^C}
 *     rather than as an invisible byte.</li>
 * </ul>
 */
public final class FakePdp1144V340c extends FakePdp1144 {
	private static final char BS = 0x08;

	private static final char CTRL_P = 0x10;

	/** Where this firmware leaves the PC after a reset. {@code :124}. */
	private static final int RESET_PC = 0165714;

	public FakePdp1144V340c(Scheduler scheduler, Random random) {
		super("Fake PDP-11/44 V3.40C", scheduler, random);
	}

	@Override
	protected char eraseKey() {
		return BS;
	}

	@Override
	protected String errorSyntax() {
		//-- This firmware calls a bad number a context error, not a syntax error.
		return "?Context?";
	}

	@Override
	protected String errorBusTimeout() {
		return "?Bus timeout error?";
	}

	/**
	 * Five NULs, then the firmware version, then the mode it starts in.
	 *
	 * <p>The NULs are real: the console sends fill characters while its line settles, and they
	 * are exactly the sort of thing a scanner has to drop rather than parse ({@code :116-118}).</p>
	 */
	@Override
	protected void printPowerOnBanner() {
		print("\0\0\0\0\0" + CR + LF + "(Console V3.40C)" + CR + LF + CR + LF + "(Program)");
	}

	@Override
	protected void printResetBanner() {
		setMem(getProgramCounterAddr(), RESET_PC);
		print("" + CR + LF + CR + LF + "(Console)" + CR + LF);
	}

	@Override
	protected void doPrompt() {
		//-- Same as the base, except the error line gets a blank line of its own after it.
		if(hasError()) {
			String error = getError();
			clearError();
			print("" + CR + LF + error + CR + LF);
		}
		print("" + CR + LF + ">>>");
		clearInput();
	}

	// -------------------------------------------------------------------------------------
	// Console mode and program mode
	// -------------------------------------------------------------------------------------

	/**
	 * While a program has the machine this console stops listening, unlike the other firmware.
	 *
	 * <p>The only key it takes is {@code ^P}, which stops the CPU - and, unlike the other
	 * firmware, {@code ^P} rather than {@code H} is where a halt actually happens here.</p>
	 */
	@Override
	public void serialWriteByte(int b) {
		char c = (char) (b & 0x7F);
		if(!isRunning()) {
			handleKey(c);
			return;
		}
		if(c != CTRL_P)
			return;
		haltSwitch();
		print("" + CR + LF + "(Console)" + CR + LF + "^P" + CR + LF);
		//-- And where it stopped. The Pascal fake prints the mode and the key and no PC
		//-- ({@code FakePDP1144v340cU.pas:203-211}), which leaves the console driver's halt with
		//-- nothing to report - and the driver, written against the real machine, requires a stop
		//-- report here and fails without one. So the fake was incomplete rather than the driver
		//-- wrong, and this is the report the same firmware prints everywhere else it stops.
		printHaltReport();
		doPrompt();
	}

	@Override
	protected void keyControlC() {
		print("^C" + CR + LF);
		doPrompt();
	}

	/** Control characters are echoed as {@code ^X} rather than sent back invisibly. */
	@Override
	protected void echo(char c) {
		if(c < 32)
			print("^" + (char) ('A' + c - 1));
		else
			print(c);
	}

	/** No prompt while the CPU is running - it is a program's console now, not ours. */
	@Override
	protected void promptAfterCommand() {
		if(!isRunning())
			doPrompt();
	}

	@Override
	protected void dispatch(String opcode, List<String> modifiers, String parm1, String parm2) {
		switch(opcode) {
			case "H" -> {
				//-- Halting something already stopped is the error; ^P is what stops a running
				//-- one, and it never reaches here because the console ignores everything else
				//-- while a program has the machine.
				if(!isRunning())
					setError("?Already halted");
			}
			case "" -> {
				//-- A bare carriage return just draws a new prompt.
			}
			case "D", "E", "S", "I", "N", "C" -> super.dispatch(opcode, modifiers, parm1, parm2);
			default -> setError("?What?");
		}
	}

	// -------------------------------------------------------------------------------------
	// Addresses and output format
	// -------------------------------------------------------------------------------------

	/** Masked into the address space rather than rejected. {@code :365-366}. */
	@Override
	protected long maskDepositAddress(long addrValue) {
		return addrValue & ADDRESS_SPACE_MASK;
	}

	/**
	 * {@code   P  00001000  123456}, or {@code   G  0000000000  000000} for a global register.
	 *
	 * <p>Two spaces, the space class, two spaces, the address as the user framed it, two spaces,
	 * the value. When the address turns out not to exist the value is simply missing and the
	 * error follows on its own line, which is why this stops the rest of the count
	 * ({@code :429-452}).</p>
	 */
	@Override
	protected boolean examineOne(Modifiers m, Address addr, long shown) {
		String space = m.global ? "G" : "P";
		//-- The address line goes out first, so a bus error arrives after an address that has
		//-- already been printed - which is what the console really looks like.
		print("" + CR + LF + "  " + space + "  " + Octal.format(shown, 8));
		int value = 0;
		if(m.global && shown > 32) {
			//-- Sixteen global registers exist; a higher number is a firmware complaint of its
			//-- own rather than a bus error, and it is checked before the read for that reason.
			setError("?Too big");
		} else {
			value = safeGetMem(addr);
			setLastExamineAddr(addr);
		}
		if(hasError())
			return false;
		print("  " + Octal.format(value, 6));
		return true;
	}

	@Override
	protected void finishExamine() {
		print("" + CR + LF);
	}

	@Override
	protected void printStarting() {
		print("" + CR + LF + CR + LF + "(Program)" + CR + LF);
	}

	/**
	 * {@code (Console)} and where it stopped, in words rather than as a bare examine of R7.
	 */
	@Override
	protected void printHaltReport() {
		int pc = getMem(getProgramCounterAddr());
		print("" + CR + LF + "(Console)" + CR + LF + "  Halted at " + Octal.format(pc, 6) + CR + LF);
	}
}
