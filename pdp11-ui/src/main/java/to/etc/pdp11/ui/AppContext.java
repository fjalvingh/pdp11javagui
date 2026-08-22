package to.etc.pdp11.ui;

import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.machine.MachineDescription;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Scheduler;
import to.etc.pdp11.ui.settings.ConfigDir;
import to.etc.pdp11.ui.settings.Settings;
import to.etc.pdp11.ui.settings.SettingsStore;
import to.etc.pdp11.ui.window.WindowManager;

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
	private final SettingsStore m_settingsStore;

	private final Logger m_logger;

	private final MemoryCellGroups m_memoryCellGroups;

	private final BitfieldsDefs m_bitfieldDefs;

	private final ConnectionManager m_connectionManager;

	private final WindowManager m_windowManager;

	private final Scheduler m_scheduler;

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

	/** Save the settings, reporting a failure to save rather than throwing one. */
	public void saveSettings() {
		String problem = m_settingsStore.save();
		if(problem != null)
			m_logger.log(LogChannel.OTHER, problem);
	}
}
