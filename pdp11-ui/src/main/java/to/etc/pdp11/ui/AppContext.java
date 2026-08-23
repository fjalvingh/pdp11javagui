package to.etc.pdp11.ui;

import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleConnection;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.machine.MachineDescription;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.OperationCancelledException;
import to.etc.pdp11.core.util.Scheduler;
import to.etc.pdp11.ui.macro11.AssemblerModel;
import to.etc.pdp11.ui.settings.ConfigDir;
import to.etc.pdp11.ui.settings.Settings;
import to.etc.pdp11.ui.settings.SettingsStore;
import to.etc.pdp11.ui.window.WindowManager;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * The services every window needs, in one place that is handed to each of them.
 *
 * <p><b>PLAN.md §5 says to do this now rather than later, and gives the reason:</b> lazy window
 * creation is what forces it. Today {@code FormMain.FormExecute.StartPCEdit.Text := …} works
 * because every one of the ~26 windows is created at startup and never destroyed, so any window
 * can reach into any other by name - about 120 reads of {@code FormMain.X}, plus direct sibling
 * reach-ins like {@code FormDiscImageU.pas:389, 1038, 1043} and {@code SerialXferU.pas:653-749}.
 * With create-on-demand those references simply have no target. Retrofitting this after a dozen
 * windows exist means reworking every one of them.</p>
 *
 * <p>So a window is given what it needs and reaches for nothing. There is no static instance and
 * no way to get one from nowhere; that is deliberate, and it is what keeps a window testable.</p>
 */
public final class AppContext {
	/**
	 * Runs {@link #onFile}, created on first use. Guarded by this context's monitor.
	 */
	private java.util.concurrent.ExecutorService m_fileExecutor;

	private final SettingsStore m_settingsStore;

	private final Logger m_logger;

	private final MemoryCellGroups m_memoryCellGroups;

	private final BitfieldsDefs m_bitfieldDefs;

	private final ConnectionManager m_connectionManager;

	private final WindowManager m_windowManager;

	private final Scheduler m_scheduler;

	private final MachineState m_machineState = new MachineState();

	private final CellSelection m_cellSelection = new CellSelection();

	/**
	 * The MACRO-11 program being written, shared because two windows assemble it.
	 *
	 * <p>Built at the end of the constructor rather than inline: it needs this context, and a
	 * field initialiser would hand it a half-built one.</p>
	 */
	private final AssemblerModel m_assembler;

	private final Path m_dataDir;

	/** Set once a machine description has been loaded. Null before that, and after unloading. */
	private MachineDescription m_machineDescription;

	/**
	 * Where a failure that nobody else handled goes.
	 *
	 * <p>One handler for the whole application, so that "the machine did not answer" looks the
	 * same whichever window asked. The Pascal has the opposite: {@code ShowMessage} calls
	 * scattered through every form, and a modal dialog raised from inside the protocol layer
	 * itself ({@code ConsoleGenericU.pas:480}).</p>
	 */
	private BiConsumer<String, Throwable> m_failureHandler = (message, x) -> {
	};

	/**
	 * Who asks before unsaved work is destroyed.
	 *
	 * <p>Defaults to saying yes, for the same reason {@link #m_failureHandler} defaults to
	 * silence: a context with no window over it - a test, a headless run - must not block on an
	 * answer nobody can give. {@link MainWindow} installs the one that actually asks.</p>
	 */
	private DiscardConfirmer m_discardConfirmer = question -> true;

	public AppContext(SettingsStore settingsStore, Logger logger, MemoryCellGroups memoryCellGroups,
		BitfieldsDefs bitfieldDefs, ConnectionManager connectionManager, WindowManager windowManager,
		Scheduler scheduler, Path dataDir) {
		m_settingsStore = settingsStore;
		m_logger = logger;
		m_memoryCellGroups = memoryCellGroups;
		m_bitfieldDefs = bitfieldDefs;
		m_connectionManager = connectionManager;
		m_windowManager = windowManager;
		m_scheduler = scheduler;
		m_dataDir = dataDir;
		m_machineState.bind(connectionManager);
		m_assembler = new AssemblerModel(this);
	}

	/**
	 * Everything wired together, with each part told about the others.
	 *
	 * <p>The order matters exactly once: the window manager needs the settings to restore
	 * geometry from, and the context needs the window manager, so the manager is built first and
	 * given the context afterwards.</p>
	 */
	public static AppContext create(Logger logger) {
		SettingsStore store = SettingsStore.forThisMachine();
		MemoryCellGroups groups = new MemoryCellGroups();
		BitfieldsDefs bitfields = new BitfieldsDefs();
		Scheduler scheduler = Scheduler.systemScheduler();
		Path dataDir = ConfigDir.data();
		ConnectionManager connections = new ConnectionManager(groups, logger, scheduler, dataDir);
		WindowManager windows = new WindowManager(store);
		AppContext ctx = new AppContext(store, logger, groups, bitfields, connections, windows, scheduler, dataDir);
		windows.setContext(ctx);
		String problem = store.getLastProblem();
		if(problem != null)
			logger.log(LogChannel.OTHER, problem);
		return ctx;
	}

	public SettingsStore getSettingsStore() {
		return m_settingsStore;
	}

	public Settings getSettings() {
		return m_settingsStore.get();
	}

	public Logger getLogger() {
		return m_logger;
	}

	public MemoryCellGroups getMemoryCellGroups() {
		return m_memoryCellGroups;
	}

	public BitfieldsDefs getBitfieldDefs() {
		return m_bitfieldDefs;
	}

	public ConnectionManager getConnectionManager() {
		return m_connectionManager;
	}

	public WindowManager getWindowManager() {
		return m_windowManager;
	}

	public Scheduler getScheduler() {
		return m_scheduler;
	}

	/** Where the machine is, and where its PC got to. */
	public MachineState getMachineState() {
		return m_machineState;
	}

	/** Which memory cell the user is looking at, for the windows that follow the selection. */
	public CellSelection getCellSelection() {
		return m_cellSelection;
	}

	/**
	 * The program being assembled: its source, its listing, and the code that came out.
	 *
	 * <p>Shared for the same reason {@link #getMachineState()} is - the Assembler window
	 * assembles it and the Execution window's "New program" assembles it too, and neither knows
	 * the other exists.</p>
	 */
	public AssemblerModel getAssembler() {
		return m_assembler;
	}

	/** Where working files go: SimH's generated configuration, temporary listings. */
	public Path getDataDir() {
		return m_dataDir;
	}

	public MachineDescription getMachineDescription() {
		return m_machineDescription;
	}

	public void setMachineDescription(MachineDescription machineDescription) {
		m_machineDescription = machineDescription;
	}

	public void setFailureHandler(BiConsumer<String, Throwable> failureHandler) {
		m_failureHandler = failureHandler == null ? (m, x) -> {
		} : failureHandler;
	}

	/** Asked before work the user has not saved would be thrown away. */
	@FunctionalInterface
	public interface DiscardConfirmer {
		/**
		 * @param question what would be lost and what would happen, already phrased for a dialog
		 * @return true to go ahead and lose it
		 */
		boolean confirmDiscard(String question);
	}

	public void setDiscardConfirmer(DiscardConfirmer confirmer) {
		m_discardConfirmer = confirmer == null ? q -> true : confirmer;
	}

	/**
	 * Ask before destroying something unsaved.
	 *
	 * <p>Event thread only - the answer is a modal dialog, and every caller is a menu item or a
	 * button. Same shape as {@link #reportFailure}: the window that can put a dialog on the
	 * screen installs the handler, and nothing below it has to know a dialog exists.</p>
	 *
	 * @return true when the caller may go ahead
	 */
	public boolean confirmDiscard(String question) {
		return m_discardConfirmer.confirmDiscard(question);
	}

	/**
	 * Something went wrong and there is nowhere better to say so.
	 *
	 * <p>Always logs; the handler decides whether to put it in front of the user as well. Safe
	 * from any thread - the handler marshals to the event thread itself, because the callers are
	 * mostly on the command thread.</p>
	 */
	public void reportFailure(String message, Throwable cause) {
		m_logger.log(LogChannel.OTHER, cause == null ? message : message + ": " + cause);
		m_failureHandler.accept(message, cause);
	}

	// -------------------------------------------------------------------------------------
	// Talking to the machine
	// -------------------------------------------------------------------------------------

	/** Work to do with the console, on the thread that is allowed to do it. */
	@FunctionalInterface
	public interface ConsoleJob {
		void run(Console console) throws ConsoleException;
	}

	/**
	 * Run console work on the command thread, and never on the event thread.
	 *
	 * <p>This is the one door between a button and a machine, and it exists so that no window
	 * has to get the threading right on its own. PLAN.md §1: every console call is serialized
	 * on one thread, and a call made from the EDT deadlocks the application. The job is queued
	 * rather than waited for - {@link ConsoleConnection#call} would block whoever asked, and
	 * the EDT is exactly who is asking.</p>
	 *
	 * <p>The job runs <b>on the command thread</b>, so it may call the console directly as often
	 * as it likes, and it must marshal anything it wants to show back with
	 * {@link #onUi(Runnable)}.</p>
	 *
	 * @return false if there is no machine to talk to, having said so
	 */
	public boolean onConsole(String what, ConsoleJob job) {
		ConsoleConnection connection = m_connectionManager.getConnection();
		Console console = m_connectionManager.getConsole();
		if(connection == null || console == null) {
			reportFailure("Not connected to a machine", null);
			return false;
		}
		boolean queued = connection.execute(() -> {
			try {
				job.run(console);
			} catch(OperationCancelledException x) {
				//-- The user pressed Cancel on the progress dialog. Not a failure, and not
				//-- something to put a second dialog in front of them about.
				m_logger.log(LogChannel.OTHER, what + ": cancelled");
			} catch(ConsoleException | RuntimeException x) {
				reportFailure(what + " failed", x);
			}
		});
		if(!queued) {
			//-- A disconnect landed between the check above and the submit, so the job was
			//-- refused and none of its callbacks will ever run. Saying nothing here is what
			//-- left the Memory Test window at "Testing ..." with every button disabled for the
			//-- rest of the session: it turns its buttons back on from inside the job.
			reportFailure(what + " failed: the machine was disconnected", null);
			return false;
		}
		return true;
	}

	/** File work: runs off the event thread, and may throw. */
	public interface FileJob<T> {
		T run() throws Exception;
	}

	/**
	 * Read or write a file without freezing the application while it happens.
	 *
	 * <p>The counterpart of {@link #onConsole} for the other slow thing a window does. On a
	 * local disk a load or a save is instant and this changes nothing; on a stale NFS mount or a
	 * USB stick somebody pulled out it is the difference between a window that says "Loading ..."
	 * and an application that has stopped responding with no clue why (FABLE-ISSUES #62).</p>
	 *
	 * <p>File jobs run one at a time, on one thread, so two saves cannot interleave. The job runs
	 * <b>off the event thread</b> and must therefore touch nothing that belongs to it - Swing
	 * components above all. Memory cell groups are safe: they are guarded by
	 * {@link MemoryCellGroups#lock()} and are already mutated from the command thread. Whatever
	 * the job answers is handed to {@code onDone} back on the event thread; a job that throws is
	 * reported through {@link #reportFailure} and {@code onFailed} runs instead, so a window can
	 * put its buttons back either way.</p>
	 *
	 * @param what     names the operation in a failure message
	 * @param job      the file work
	 * @param onDone   given the job's answer, on the event thread
	 * @param onFailed run on the event thread instead of {@code onDone}; may be null
	 */
	public <T> void onFile(String what, FileJob<T> job, java.util.function.Consumer<T> onDone, Runnable onFailed) {
		fileExecutor().execute(() -> {
			T result;
			try {
				result = job.run();
			} catch(Exception x) {
				onUi(() -> {
					reportFailure(what, x);
					if(onFailed != null)
						onFailed.run();
				});
				return;
			}
			onUi(() -> onDone.accept(result));
		});
	}

	private synchronized java.util.concurrent.ExecutorService fileExecutor() {
		java.util.concurrent.ExecutorService e = m_fileExecutor;
		if(e == null) {
			e = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "pdp11-file");
				//-- Daemon: a write blocked on a mount that has gone away must not keep the JVM
				//-- alive after the last window closes.
				t.setDaemon(true);
				return t;
			});
			m_fileExecutor = e;
		}
		return e;
	}

	/** Do this on the event thread, from wherever you are. */
	public static void onUi(Runnable work) {
		if(SwingUtilities.isEventDispatchThread())
			work.run();
		else
			SwingUtilities.invokeLater(work);
	}

	/** Save the settings, reporting a failure to save rather than throwing one. */
	public void saveSettings() {
		String problem = m_settingsStore.save();
		if(problem != null)
			m_logger.log(LogChannel.OTHER, problem);
	}
}
