package to.etc.pdp11.ui.mem;

import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.SharedMemory;
import to.etc.pdp11.common.util.LogChannel;
import to.etc.pdp11.core.mem.SharedMemoryOperations;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.ProgressDialog;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Window;
import java.util.function.IntConsumer;

/**
 * The buttons that act on shared memory as a whole, wherever they are offered - the main
 * window's Memory menu and the Pending changes window both. What they do is
 * {@link SharedMemoryOperations}; this is the queueing, the progress and the saying so.
 * PLAN.md §1, "Shared memory".
 */
public final class SharedMemoryActions {
	private SharedMemoryActions() {
	}

	/** Deposit every pending word, whichever window made it. */
	public static void depositChanged(AppContext context, Window owner) {
		ProgressDialog progress = new ProgressDialog(owner);
		context.onConsole("Depositing changes", console -> {
			SharedMemoryOperations.DepositResult r =
				SharedMemoryOperations.depositPending(context.getMemoryCellGroups(), console, progress);
			context.getLogger().log(LogChannel.OTHER, "Deposited %d changed words", r.deposited());
			if(r.unreachable() > 0) {
				context.reportFailure(r.unreachable() + " changed words are above what this machine can address,"
					+ " and are still waiting to be deposited", null);
			}
		});
	}

	/** Read again every word of memory some window holds, each once. */
	public static void rereadShown(AppContext context, Window owner) {
		ProgressDialog progress = new ProgressDialog(owner);
		context.onConsole("Reading what is shown", console -> {
			int n = SharedMemoryOperations.rereadShown(context.getMemoryCellGroups(), console, progress);
			context.getLogger().log(LogChannel.OTHER, "Read %d words shown in windows", n);
		});
	}

	/**
	 * Read the MMU again and move every window looking through virtual addresses to the words
	 * the MMU now names.
	 *
	 * @param whenDone told on the event thread how many registers had changed; may be null
	 */
	public static void checkMmu(AppContext context, Window owner, IntConsumer whenDone) {
		ProgressDialog progress = new ProgressDialog(owner);
		context.onConsole("Checking the MMU", console -> {
			SharedMemoryOperations.MmuCheck check =
				SharedMemoryOperations.checkMmu(context.getMemoryCellGroups(), console, progress);
			if(check == null) {
				context.reportFailure("This console has no MMU to check", null);
				return;
			}
			context.getLogger().log(LogChannel.OTHER, "MMU: %d registers changed, %d windows now show other words",
				check.registersChanged(), check.moved().size());
			if(whenDone != null)
				AppContext.onUi(() -> whenDone.accept(check.registersChanged()));
		});
	}

	/**
	 * Start again: nothing read, nothing pending, nothing marked as data. Asks first if that throws anything away that
	 * has not been deposited.
	 */
	public static void forgetAll(AppContext context, Component parent) {
		SharedMemory memory = context.getMemoryCellGroups().getSharedMemory();
		int pending = memory.getPendingCount();
		if(pending > 0) {
			int answer = JOptionPane.showConfirmDialog(parent,
				pending + " changed words have not been deposited, and will be lost.\nForget everything anyway?",
				"Forget all memory", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if(answer != JOptionPane.OK_OPTION)
				return;
		}
		memory.forgetAll();
		context.getDataMarks().clear();
		context.getLogger().log(LogChannel.OTHER, "Forgot everything known about memory");
	}

	/**
	 * Whether a window that made edits may close, having asked what to do with any it made and
	 * did not deposit: undo them, keep them - still pending, and listed as such - or not close.
	 * A window with nothing pending is not asked anything.
	 */
	public static boolean mayClose(Component parent, MemoryCellGroup group) {
		if(group == null)
			return true;
		int pending = group.getPendingEditCount();
		if(pending == 0)
			return true;
		Object[] options = {"Undo them", "Keep them", "Cancel"};
		int answer = JOptionPane.showOptionDialog(parent,
			pending + (pending == 1 ? " word changed here has" : " words changed here have")
				+ " not been deposited to the machine.\n"
				+ "Undo them, or keep them in memory to deposit later?",
			"Changes not deposited", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
			options, options[1]);
		if(answer == 0) {
			group.discardOwnedEdits();
			return true;
		}
		return answer == 1;
	}
}
