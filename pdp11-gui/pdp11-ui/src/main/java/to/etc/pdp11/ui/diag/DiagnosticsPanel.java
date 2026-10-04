package to.etc.pdp11.ui.diag;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.diag.DiagnosticCatalog;
import to.etc.pdp11.common.diag.DiagnosticCollector;
import to.etc.pdp11.common.diag.DiagnosticLibrary;
import to.etc.pdp11.common.diag.DiagnosticSource;
import to.etc.pdp11.common.diag.HttpFetcher;
import to.etc.pdp11.common.diag.IndexPageScanner;
import to.etc.pdp11.common.diag.LibraryListing;
import to.etc.pdp11.common.diag.LibraryFile;
import to.etc.pdp11.common.diag.LibraryMedium;
import to.etc.pdp11.common.diag.ProgramCheck;
import to.etc.pdp11.common.diag.UrlFetcher;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.memfile.AbsoluteLoaderTape;
import to.etc.pdp11.common.util.LogChannel;
import to.etc.pdp11.core.run.ProgramLoader;
import to.etc.pdp11.common.util.OperationCancelledException;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Browser;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.ProgressDialog;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Collecting XXDP and MAINDEC diagnostics, and the library they end up in.
 *
 * <p>The application cannot ship DEC's diagnostics, but it can fetch them for the person running
 * it from the archives that have them. The Collect tab finds what the {@link DiagnosticSource}s
 * offer and downloads what is new; {@link DiagnosticCollector} takes every file off every disk,
 * tape and floppy, and the Library tab lists them by what DEC's own index says they are.</p>
 *
 * <h2>Threads</h2>
 *
 * <p>Finding and collecting run on this panel's own worker thread, not on the file thread
 * {@link AppContext#onFile} uses: a collection takes minutes on a slow line, and a save of the
 * assembler's source must not queue behind it. Not on the command thread either - there is no
 * machine in any of this. Progress comes back through {@link Progress}, which marshals; nothing
 * here blocks the event thread, and Cancel works at any point between two reads.</p>
 */
public final class DiagnosticsPanel extends JPanel {
	/** Under {@link AppContext#getLibraryDir()}. */
	public static final String DIRECTORY = "diagnostics";

	private final AppContext m_context;

	private final HttpFetcher m_fetcher;

	private final Path m_root;

	private final JTabbedPane m_tabs = new JTabbedPane();

	private final LibraryTab m_libraryTab;

	private final CollectTab m_collectTab;

	private final ExecutorService m_worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "pdp11-diagnostics");
		//-- A download in progress must not keep the application alive after its last window.
		t.setDaemon(true);
		return t;
	});

	/** Opened on the worker, then only ever used there. */
	private DiagnosticLibrary m_library;

	private DiagnosticCatalog m_catalog;

	/** The running job's monitor, or null. Written on the EDT, read by Cancel. */
	private volatile Progress m_running;

	/** What the last search found that is not in the library. Event thread. */
	private List<IndexPageScanner.Found> m_new = List.of();

	private List<IndexPageScanner.Found> m_found = List.of();

	/** Set when the library has been read and shown once. Event thread. */
	private boolean m_loaded;

	public DiagnosticsPanel(AppContext context) {
		this(context, new UrlFetcher());
	}

	DiagnosticsPanel(AppContext context, HttpFetcher fetcher) {
		super(new MigLayout("fill, insets 0", "[grow]", "[grow]"));
		m_context = context;
		m_fetcher = fetcher;
		m_root = context.getLibraryDir().resolve(DIRECTORY);

		List<DiagnosticSource> sources = DiagnosticSource.builtIn();
		Set<String> ticked = new HashSet<>();
		for(DiagnosticSource s : sources) {
			if(context.getSettings().isDiagnosticSourceChosen(s.id(), s.byDefault()))
				ticked.add(s.id());
		}
		m_collectTab = new CollectTab(sources, ticked, (s, on) -> {
			m_context.getSettings().setDiagnosticSourceChosen(s.id(), on);
			m_context.saveSettings();
		});
		m_libraryTab = new LibraryTab(this::openFolder, this::runProgram);
		m_libraryTab.setLocation(m_root.toString());

		m_tabs.addTab("Library", m_libraryTab);
		m_tabs.addTab("Collect", m_collectTab);
		add(m_tabs, "grow");

		m_collectTab.m_find.addActionListener(e -> find());
		m_collectTab.m_collect.addActionListener(e -> collect());
		m_collectTab.m_cancel.addActionListener(e -> cancel());
		m_collectTab.setStatus("Press Find diagnostics to see what the ticked sources have.", false);
		load();
	}

	// -------------------------------------------------------------------------------------
	// Jobs
	// -------------------------------------------------------------------------------------

	/** What the worker hands back for the library tab. */
	private record Snapshot(List<LibraryListing.Row> rows, Map<String, LibraryMedium> media, Set<String> urls, int unreadable) {
	}

	/** On the worker: the library, opened the first time it is wanted. */
	private DiagnosticLibrary library() throws Exception {
		if(m_library == null) {
			m_catalog = DiagnosticCatalog.builtIn();
			m_library = DiagnosticLibrary.open(m_root);
		}
		return m_library;
	}

	/** On the worker: the library read for showing. */
	private Snapshot snapshot() throws Exception {
		library();
		Map<String, LibraryMedium> media = new LinkedHashMap<>();
		Set<String> urls = new HashSet<>();
		for(LibraryMedium m : m_library.media()) {
			media.put(m.key(), m);
			urls.add(m.url());
		}
		DiagnosticLibrary lib = m_library;
		List<LibraryListing.Row> rows = LibraryListing.rows(lib.files(), m_catalog, (f, id) -> LibraryListing.check(lib, f, id));
		return new Snapshot(rows, media, urls, lib.getUnreadableLines());
	}

	private void show(Snapshot s) {
		m_libraryTab.show(s.rows(), s.media());
		m_loaded = true;
		if(s.unreadable() > 0)
			m_context.getLogger().log(LogChannel.OTHER, "Diagnostics library: %d lines of %s could not be read and were skipped",
				s.unreadable(), DiagnosticLibrary.INDEX_FILE);
	}

	private void load() {
		m_worker.execute(() -> {
			try {
				Snapshot s = snapshot();
				AppContext.onUi(() -> show(s));
			} catch(Exception x) {
				AppContext.onUi(() -> m_context.reportFailure("The diagnostics library in " + m_root + " cannot be read", x));
			}
		});
	}

	/** Read the sources' pages and say what they have. */
	void find() {
		List<DiagnosticSource> sources = m_collectTab.chosenSources();
		if(sources.isEmpty()) {
			m_collectTab.setStatus("Tick at least one source.", true);
			return;
		}
		Progress p = start("Looking ...");
		m_worker.execute(() -> {
			try {
				DiagnosticCollector c = new DiagnosticCollector(library(), m_fetcher);
				DiagnosticCollector.ScanResult r = c.scan(sources, p);
				List<IndexPageScanner.Found> fresh = c.notCollected(r.found());
				Set<String> have = new HashSet<>();
				for(LibraryMedium m : m_library.media())
					have.add(m.url());
				AppContext.onUi(() -> {
					m_found = r.found();
					m_new = fresh;
					m_collectTab.showFound(r.found(), have);
					m_collectTab.showProblems(r.problems());
					finish(p, fresh.isEmpty()
						? "Found " + r.found().size() + " files; the library has all of them."
						: "Found " + r.found().size() + " files, " + fresh.size() + " not in the library yet.", !r.problems().isEmpty() && r.found().isEmpty());
				});
			} catch(OperationCancelledException x) {
				AppContext.onUi(() -> finish(p, "Stopped.", false));
			} catch(Exception x) {
				AppContext.onUi(() -> {
					finish(p, "Looking failed: " + x.getMessage(), true);
					m_context.reportFailure("Finding diagnostics failed", x);
				});
			}
		});
	}

	/** Download what the last search found that is new, and add it to the library. */
	void collect() {
		List<IndexPageScanner.Found> todo = m_new;
		if(todo.isEmpty())
			return;
		Progress p = start("Collecting ...");
		m_worker.execute(() -> {
			DiagnosticCollector c = new DiagnosticCollector(m_library, m_fetcher);
			String outcome;
			boolean error = false;
			List<String> problems = List.of();
			try {
				DiagnosticCollector.CollectResult r = c.collect(todo, p);
				problems = r.problems();
				outcome = "Collected " + r.downloaded() + " of " + todo.size() + " downloads: " + r.newFiles() + " new programs.";
				error = r.downloaded() < todo.size();
			} catch(OperationCancelledException x) {
				outcome = "Stopped. Everything collected before that is kept.";
			} catch(RuntimeException x) {
				outcome = "Collecting failed: " + x.getMessage();
				error = true;
				m_context.reportFailure("Collecting diagnostics failed", x);
			}
			String said = outcome;
			boolean failed = error;
			List<String> shown = problems;
			try {
				Snapshot s = snapshot();
				List<IndexPageScanner.Found> fresh = c.notCollected(m_found);
				AppContext.onUi(() -> {
					show(s);
					m_new = fresh;
					m_collectTab.showFound(m_found, s.urls());
					m_collectTab.showProblems(shown);
					finish(p, said, failed);
					m_context.getLogger().log(LogChannel.OTHER, "Diagnostics: %s", said);
				});
			} catch(Exception x) {
				AppContext.onUi(() -> {
					finish(p, said, true);
					m_context.reportFailure("The diagnostics library cannot be read back", x);
				});
			}
		});
	}

	private Progress start(String status) {
		Progress p = new Progress();
		m_running = p;
		m_collectTab.setBusy(true, 0);
		m_collectTab.setStatus(status, false);
		return p;
	}

	private void finish(Progress p, String status, boolean error) {
		if(m_running == p)
			m_running = null;
		m_collectTab.setBusy(false, m_new.size());
		m_collectTab.setStatus(status, error);
	}

	void cancel() {
		Progress p = m_running;
		if(p != null) {
			p.m_cancelled = true;
			m_collectTab.setStatus("Stopping ...", false);
		}
	}

	// -------------------------------------------------------------------------------------
	// Running a standalone program
	// -------------------------------------------------------------------------------------

	/**
	 * Deposit a standalone program into the machine and, when asked, start it.
	 *
	 * <p>Only what {@link ProgramCheck} calls standalone gets here: a program that needs XXDP or
	 * its DRS supervisor cannot be run by depositing it, and is never offered. The file is read
	 * and checked again on the worker - the one in the list was checked when the library was
	 * read, and a file can change - and the deposits and the start then go to the command thread
	 * as one job, behind a {@link ProgressDialog}: modal, because while the machine is being
	 * loaded there is nothing else useful to ask of it.</p>
	 */
	void runProgram(LibraryListing.Row row, int start, Integer switches, boolean andStart) {
		if(m_context.getMachineState().getState() == MachineState.ExecutionState.RUNNING) {
			m_libraryTab.setRunStatus("The machine is running. Halt it first: a program cannot be deposited under a running one.", true);
			return;
		}
		if(m_context.getConnectionManager().getConsole() == null) {
			m_libraryTab.setRunStatus("Not connected to a machine.", true);
			return;
		}
		if(andStart && !m_context.checkRunMode())
			return;
		LibraryFile file = row.file();
		m_libraryTab.setRunStatus("Reading " + file.name() + " ...", false);
		m_worker.execute(() -> {
			ProgramCheck.Verdict v;
			try {
				byte[] data = Files.readAllBytes(library().resolve(file.path()));
				v = ProgramCheck.check(file.name(), data, file.damaged(), row.identification());
			} catch(Exception x) {
				AppContext.onUi(() -> m_libraryTab.setRunStatus("Cannot read " + file.name() + ": " + x.getMessage(), true));
				return;
			}
			if(!v.isRunnable()) {
				ProgramCheck.Verdict why = v;
				AppContext.onUi(() -> m_libraryTab.setRunStatus("Cannot be run here: " + why.reason(), true));
				return;
			}
			AbsoluteLoaderTape tape = v.tape();
			AppContext.onUi(() -> deposit(file.name(), tape, start, switches, andStart));
		});
	}

	/** On the event thread: queue the deposits, and the start, for the command thread. */
	private void deposit(String name, AbsoluteLoaderTape tape, int start, Integer switches, boolean andStart) {
		ProgressDialog progress = new ProgressDialog(SwingUtilities.getWindowAncestor(this));
		Address startPc = Address.of(MemoryAddressType.VIRTUAL, start);
		m_libraryTab.setRunStatus("Loading " + name + " ...", false);
		boolean queued = m_context.onConsole("Loading " + name, console -> {
			ProgramLoader.Loaded loaded = ProgramLoader.load(console, m_context.getMemoryCellGroups(), tape, switches, progress);
			//-- The Execution window offers this as its start PC from now on, as it does for a
			//-- program the Memory Loader or the assembler put there.
			m_context.getMachineState().setStartPc(startPc);
			String said = name + ": " + loaded.words() + " words deposited" + (switches == null ? "" : ", 176 set to "
				+ String.format(Locale.ROOT, "%06o", switches));
			if(andStart) {
				ProgramLoader.start(console, start);
				m_context.getMachineState().running();
				said += ", started at " + startPc.toOctal() + ". Its output is on the console terminal.";
			} else {
				said += ". Start it at " + startPc.toOctal() + " when ready.";
			}
			m_context.getLogger().log(LogChannel.OTHER, "Diagnostics: %s", said);
			String done = said;
			AppContext.onUi(() -> m_libraryTab.setRunStatus(done, false));
		});
		if(!queued)
			m_libraryTab.setRunStatus("Not connected to a machine.", true);
	}

	/** Show the library's directory in the platform's file manager. */
	private void openFolder() {
		m_worker.execute(() -> {
			try {
				Files.createDirectories(m_root);
			} catch(Exception x) {
				AppContext.onUi(() -> m_context.reportFailure("Cannot create " + m_root, x));
				return;
			}
			if(!Browser.open(m_root.toUri().toString(), m_context.getLogger()))
				AppContext.onUi(() -> m_context.reportFailure("Nothing on this machine would open " + m_root, null));
		});
	}

	/**
	 * The running job's progress, shown in the Collect tab rather than in a dialog.
	 *
	 * <p>Not a {@code ProgressDialog}: that one is modal, which is right for a console operation
	 * - the machine can do one thing at a time - and wrong here, where a collection can take a
	 * quarter of an hour and has no reason to stop anybody using the rest of the application.</p>
	 */
	private final class Progress implements ProgressMonitor {
		volatile boolean m_cancelled;

		private int m_total;

		private int m_done;

		@Override
		public void begin(String task, int total) {
			m_total = total;
			m_done = 0;
			AppContext.onUi(() -> {
				m_collectTab.progress(0, total);
				m_collectTab.setStatus(task + " ...", false);
			});
		}

		@Override
		public void step(int amount, String note) {
			m_done += amount;
			int done = m_done;
			int total = m_total;
			AppContext.onUi(() -> {
				if(m_running != this)
					return;
				m_collectTab.progress(done, total);
				if(note != null)
					m_collectTab.setStatus(note, false);
			});
		}

		@Override
		public boolean isCancelled() {
			return m_cancelled;
		}

		@Override
		public void done() {
		}
	}

	// -------------------------------------------------------------------------------------
	// For the window and the tests
	// -------------------------------------------------------------------------------------

	/** Stop whatever is running; the window calls this when it is disposed. */
	public void shutdown() {
		cancel();
		m_worker.shutdown();
	}

	public Path getLibraryRoot() {
		return m_root;
	}

	/** Whether the library has been read and shown. Event thread. */
	boolean isLoaded() {
		return m_loaded;
	}

	/** Whether a find or a collect is running. Event thread. */
	boolean isBusy() {
		return m_running != null;
	}

	JTabbedPane getTabs() {
		return m_tabs;
	}

	LibraryTab getLibraryTab() {
		return m_libraryTab;
	}

	CollectTab getCollectTab() {
		return m_collectTab;
	}
}
