package to.etc.pdp11.ui;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where the machine is: running or stopped, and where its program counter got to.
 *
 * <p>This is the replacement for a reach-in the Pascal makes constantly.
 * {@code TFormExecute.SetAndShowPc} ({@code FormExecuteU.pas:198-235}) tells the disassembler,
 * the assembler listing, the memory cell bus and the 11/70 panel about a new PC by naming each
 * of them - {@code FormMain.FormDisas.ShowNewPcAddr}, {@code FormMain.FormMacro11Listing.setPcMark},
 * and so on. With windows created on demand those names have no target, and with more windows
 * every new one means another line in a method that has nothing to do with it.</p>
 *
 * <p>So the PC is a small piece of application state that windows watch. A window that is not
 * open hears nothing and needs to hear nothing; a window that opens later asks what the state
 * is. Nothing here knows what a disassembler is.</p>
 *
 * <h2>Which thread</h2>
 *
 * <p>{@link #bind} attaches to the live console's stop event, which arrives <b>on the command
 * thread</b> - so the setters accept any thread and every listener is called on the event
 * thread. Reading {@link #getPc()} from the EDT is safe, which is the only place it is read.</p>
 */
public final class MachineState {
	/** Ported from {@code TExecuteState} ({@code FormExecuteU.pas:42}), minus {@code esCompiling}. */
	public enum ExecutionState {
		/** Nothing has told us yet - which is where every connection starts. */
		UNKNOWN,
		STOPPED,
		RUNNING
	}

	@FunctionalInterface
	public interface Listener {
		void machineStateChanged(MachineState state);
	}

	private final List<Listener> m_listeners = new CopyOnWriteArrayList<>();

	private volatile ExecutionState m_state = ExecutionState.UNKNOWN;

	/** Where the machine stopped, as a virtual address, or null if nothing has said. */
	private volatile Address m_pc;

	public ExecutionState getState() {
		return m_state;
	}

	public Address getPc() {
		return m_pc;
	}

	public void addListener(Listener l) {
		m_listeners.add(l);
	}

	public void removeListener(Listener l) {
		m_listeners.remove(l);
	}

	/**
	 * Follow this connection: hear about every stop, and forget everything on disconnect.
	 *
	 * <p>Done here rather than in the execution-control window, because the machine stops
	 * whether or not anybody has that window open, and a PC learned while it was shut is still
	 * the PC.</p>
	 */
	public void bind(ConnectionManager manager) {
		manager.addListener((m, state) -> {
			Console console = m.getConsole();
			if(state == ConnectionManager.State.CONNECTED && console != null) {
				//-- A fresh connection knows nothing about the machine, whatever we thought we
				//-- knew about the last one.
				set(ExecutionState.UNKNOWN, null);
				console.setExecutionStopListener((c, pc) -> stopped(pc));
			} else if(state != ConnectionManager.State.CONNECTING) {
				set(ExecutionState.UNKNOWN, null);
			}
		});
	}

	/** The machine has stopped at {@code pc}, which may be null when the console cannot say. */
	public void stopped(Address pc) {
		set(ExecutionState.STOPPED, pc == null ? m_pc : pc);
	}

	/** Something was started; the PC we knew is now stale but is the last thing we saw. */
	public void running() {
		set(ExecutionState.RUNNING, m_pc);
	}

	/** The user typed a PC and deposited it. */
	public void setPc(Address pc) {
		set(m_state, pc);
	}

	private void set(ExecutionState state, Address pc) {
		m_state = state;
		m_pc = pc;
		fire();
	}

	private void fire() {
		if(SwingUtilities.isEventDispatchThread()) {
			notifyListeners();
		} else {
			SwingUtilities.invokeLater(this::notifyListeners);
		}
	}

	private void notifyListeners() {
		for(Listener l : m_listeners) {
			l.machineStateChanged(this);
		}
	}

	@Override
	public String toString() {
		return m_state + (m_pc == null ? "" : " at " + m_pc.toOctal());
	}
}
