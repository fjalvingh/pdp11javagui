package to.etc.pdp11.ui.exec;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.Capability;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.ConsoleFeature;
import to.etc.pdp11.ui.AppContext;

/**
 * Starting a program and putting the PC somewhere: the two things both the Execution window and
 * the Assembler do, written once.
 *
 * <p>Each queues one job on the command thread and returns. What the machine is doing reaches
 * {@code MachineState} from inside the job, once the console has taken the command - never
 * optimistically from the event thread, where a refused command would leave it saying RUNNING with
 * nothing running.</p>
 */
public final class RunActions {
	/** R7's offset within the I/O page, which is where a console deposits a new PC. */
	private static final int REG_R7 = 017707;

	private final AppContext m_context;

	public RunActions(AppContext context) {
		m_context = context;
	}

	/**
	 * Reset the machine and start it running at {@code start}.
	 *
	 * <p>Nothing races the RUNNING. A stop the machine reports while this is in flight is posted to
	 * this same command thread by {@code AbstractConsole.signalExecutionStop}, so it queues behind
	 * this job and lands after it. Neither console call waits for a prompt: they write and return,
	 * so RUNNING appears as promptly as the click.</p>
	 *
	 * <p>Setting it here rather than before the job is also what keeps a refused start - a serial
	 * timeout, or an ODT whose switch has moved back to HALT - from leaving MachineState RUNNING
	 * with nothing running, every button that disables on running disabled, and only Halt left.</p>
	 */
	public void start(Address start) {
		if(!m_context.checkRunMode())
			return;
		m_context.onConsole("Start", console -> {
			console.resetAndStart(start);
			m_context.getMachineState().running();
		});
	}

	/**
	 * Reset the machine where the console can, and put {@code start} into R7. It does not run.
	 *
	 * <p>Ported from {@code doResetMachineAndSetPC} ({@code FormExecuteU.pas:385-414}). The deposit
	 * comes <i>after</i> the reset and happens whether or not the console claims
	 * {@link ConsoleFeature#RESET_CPU_SETS_PC} - a reset that sets the PC has just set it to the
	 * same value, and one that does not needs this. Only offered where the connection has
	 * {@link Capability#SET_PC}.</p>
	 */
	public void resetAndSetPc(Address start) {
		if(!m_context.checkRunMode())
			return;
		m_context.onConsole("Reset", console -> {
			if(console.features().contains(ConsoleFeature.ACTION_RESET_MACHINE))
				console.resetMachine(start);
			depositPc(console, start);
			m_context.getMachineState().stopped(start);
		});
	}

	/** Put {@code pc} into R7 and nothing else. */
	public void setPc(Address pc) {
		m_context.onConsole("Set PC", console -> {
			depositPc(console, pc);
			m_context.getMachineState().stopped(pc);
		});
	}

	/**
	 * Deposit a virtual PC into R7.
	 *
	 * <p>R7 lives at the top of the I/O page - {@code 0177707} on a 16-bit machine,
	 * {@code 017777707} on a 22-bit one - which is why the address is built from the console's
	 * own width rather than written down. On the command thread.</p>
	 */
	static void depositPc(Console console, Address pc) throws ConsoleException {
		MemoryAddressType type = console.physicalAddressType();
		console.deposit(Address.of(type, type.getIopageBase() + REG_R7), (int) (pc.val() & 0xFFFF));
	}
}
