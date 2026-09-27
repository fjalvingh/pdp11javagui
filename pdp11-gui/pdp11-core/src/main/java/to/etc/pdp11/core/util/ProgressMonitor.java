package to.etc.pdp11.core.util;

/**
 * Progress reporting and cancellation for long console operations.
 *
 * <p>Replaces the Pascal {@code BusyForm}, which {@code pdp11-core}'s ancestors called
 * directly: {@code TConsoleGeneric.Deposit} drives {@code BusyForm.Start}/{@code StepIt}/
 * {@code Aborted}/{@code Close} from inside the protocol layer
 * ({@code ConsoleGenericU.pas:539-554}). That is precisely the coupling the module split
 * exists to prevent, so the protocol layer gets this interface and the UI supplies a modal
 * dialog implementing it.</p>
 *
 * <p>Implementations are called from the command thread, never the EDT, and must marshal to
 * the EDT themselves.</p>
 */
public interface ProgressMonitor {
	/**
	 * Announce the total amount of work. Called once, before any {@link #step}.
	 *
	 * @param task  what is happening, for the dialog's label
	 * @param total the number the steps will add up to
	 */
	void begin(String task, int total);

	/**
	 * Report progress.
	 *
	 * @param amount how much work was just completed
	 * @param note   what is happening now, or {@code null} to leave the label alone
	 */
	void step(int amount, String note);

	default void step(int amount) {
		step(amount, null);
	}

	/**
	 * Whether the user has asked to stop. Callers poll this in their loop and throw
	 * {@link OperationCancelledException} - or call {@link #checkCancelled()} - when it is
	 * true.
	 *
	 * <p>The Pascal equivalent is {@code BusyForm.Aborted}, which could only become true
	 * because {@code StepIt} pumped {@code Application.ProcessMessages} and let the Abort
	 * button's click handler run. Here the flag is set on the EDT and read on the command
	 * thread, so implementations must make it visible across threads.</p>
	 */
	boolean isCancelled();

	/**
	 * @throws OperationCancelledException if the user has asked to stop.
	 */
	default void checkCancelled() {
		if(isCancelled())
			throw new OperationCancelledException();
	}

	/** Work finished, successfully or not. Idempotent. */
	void done();

	/**
	 * How long an operation must run before a progress dialog is worth showing.
	 *
	 * <p>1 s, matching {@code FormBusyU.pas:113-124}. Anything faster flashes a window at the
	 * user for no reason. This lives here rather than in the dialog so the threshold is part
	 * of the contract instead of one implementation's private opinion.</p>
	 */
	int DISPLAY_THRESHOLD_MS = 1000;

	/** Accepts progress and is never cancelled. For callers that have no UI. */
	ProgressMonitor NULL = new ProgressMonitor() {
		@Override
		public void begin(String task, int total) {
		}

		@Override
		public void step(int amount, String note) {
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public void done() {
		}

		@Override
		public String toString() {
			return "ProgressMonitor.NULL";
		}
	};
}
