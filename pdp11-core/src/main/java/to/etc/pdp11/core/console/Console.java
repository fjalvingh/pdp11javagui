package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.ProgressMonitor;

import java.util.EnumSet;

/**
 * One PDP-11 console dialect: SimH's remote console, ODT, an M9301 or M9312 boot ROM, an
 * 11/44's console emulator, a K1630's.
 *
 * <p>Ported from {@code TConsoleGeneric} ({@code ConsoleGenericU.pas:166-263}). Everything
 * above this interface - the memory windows, the execution control, the disc image drivers -
 * is written against exactly these operations, which is why a dialect is a class and not a
 * pile of {@code if}s.</p>
 *
 * <h2>Never on the EDT</h2>
 *
 * <p><b>Every method here blocks on a machine.</b> They run on the connection's single command
 * thread and nowhere else: call {@link ConsoleConnection#call} to get onto it. A console call
 * made directly from the Swing event thread deadlocks the application, and one made from two
 * threads at once interleaves two command/answer exchanges on one wire. This is the single
 * most important rule in the port; see PLAN.md §1 and CLAUDE.md.</p>
 *
 * <h2>Differences from the Pascal</h2>
 *
 * <ul>
 * <li>{@code HaltCpu(var newpc_v)} becomes a return value.</li>
 * <li>{@code TConsoleFeatureSet}, a Pascal set, becomes an {@code EnumSet}.</li>
 * <li>{@code BusyForm} and {@code Application.ProcessMessages} become a {@link ProgressMonitor},
 *     which also carries cancellation.</li>
 * <li>{@link #examine(Address)} returns a {@link CellValue} rather than a {@code dword} whose
 *     {@code $ffffffff} means "no answer". PLAN.md §1 sketched it as an {@code int}; PLAN.md §2
 *     had already decided the sentinel does not survive the port, and a UNIBUS timeout is
 *     precisely the case it was used for.</li>
 * </ul>
 */
public interface Console {
	/** What this console can do, for button enablement. May depend on the ENABLE/HALT switch. */
	EnumSet<ConsoleFeature> features();

	/**
	 * The physical address width this console talks in: 16, 18 or 22 bits.
	 *
	 * <p>Note SimH always answers in 22-bit physical addresses, even when emulating a machine
	 * that could not have had them.</p>
	 */
	MemoryAddressType physicalAddressType();

	/** How to name this console in the UI. */
	String name();

	/**
	 * Get the console into a known state: talking, prompting, and configured the way this
	 * dialect needs.
	 *
	 * <p>Called after connecting, and again whenever the conversation has gone off the rails.</p>
	 */
	void resync() throws ConsoleException;

	/** Forget everything cached about the machine's state. Ported from {@code ClearState}. */
	void clearState();

	/**
	 * Read one word.
	 *
	 * @return the value, or {@link CellValue#UNKNOWN} for a UNIBUS timeout - a nonexistent
	 *         address is an answer, not a failure.
	 */
	CellValue examine(Address a) throws ConsoleException;

	/** Write one word. */
	void deposit(Address a, int value) throws ConsoleException;

	/**
	 * Read a whole group, as few round trips as the dialect allows.
	 *
	 * @param unknownOnly skip cells that already have a value
	 */
	void examine(MemoryCellGroup g, boolean unknownOnly, ProgressMonitor pm) throws ConsoleException;

	/**
	 * Write a whole group.
	 *
	 * @param optimize skip cells whose edited value already matches what the machine holds
	 */
	void deposit(MemoryCellGroup g, boolean optimize, ProgressMonitor pm) throws ConsoleException;

	/** Reset CPU and UNIBUS without starting the machine. */
	void resetMachine(Address newPc) throws ConsoleException;

	/** Reset, then start the CPU at {@code newPc}. The PC is a virtual address. */
	void resetAndStart(Address newPc) throws ConsoleException;

	/** Carry on from where the machine stopped, with no reset. */
	void continueCpu() throws ConsoleException;

	/**
	 * Stop a running program.
	 *
	 * @return where it stopped, as a virtual address, or {@code null} if the console could not
	 *         say - including the common case of a machine that had already stopped.
	 */
	Address haltCpu() throws ConsoleException;

	/** Execute one instruction. */
	void singleStep() throws ConsoleException;

	/**
	 * Where this console's own monitor lives, for the consoles that are boot ROMs.
	 *
	 * <p>Only the M9312 has one; every other console answers {@code null}. Ported from
	 * {@code getMonitorEntryAddress} ({@code ConsoleGenericU.pas:560-564}), which returns the
	 * illegal-value sentinel for "not applicable".</p>
	 */
	default Address monitorEntryAddress() {
		return null;
	}

	/** Told when the machine stops, on the command thread. At most one. */
	void setExecutionStopListener(ExecutionStopListener listener);
}
