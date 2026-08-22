package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Scheduler;
import to.etc.pdp11.ui.log.LogWindow;
import to.etc.pdp11.ui.log.UiLogger;
import to.etc.pdp11.ui.settings.SettingsStore;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowManager;
import to.etc.pdp11.ui.window.WindowType;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The windows are built, but never shown.
 *
 * <p>What this catches is everything that goes wrong between writing a layout and seeing it: a
 * MigLayout constraint that does not parse, a table model whose column count disagrees with its
 * header, a menu built against an enum that has grown. All of that throws on construction, and
 * none of it needs a window on anybody's screen - so nothing here calls {@code setVisible}.</p>
 *
 * <p><b>Skipped on a headless machine</b>, which includes CI: constructing a {@code JFrame} needs
 * a display even when nothing is shown on it. Everything worth testing that does <i>not</i> need
 * one lives outside the windows, in {@link AppContextTest} and the settings and terminal tests,
 * and those run everywhere.</p>
 */
class WindowsBuildTest {
	private static AppContext context(Path dir) {
		SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
		MemoryCellGroups groups = new MemoryCellGroups();
		UiLogger logger = new UiLogger();
		Scheduler scheduler = new Scheduler.Manual();
		ConnectionManager connections = new ConnectionManager(groups, logger, scheduler, dir);
		WindowManager windows = new WindowManager(store);
		AppContext ctx = new AppContext(store, logger, groups, new BitfieldsDefs(), connections, windows,
			scheduler, dir);
		windows.setContext(ctx);
		return ctx;
	}

	/** Build on the event thread, as Swing requires, and hand back whatever went wrong. */
	private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Exception> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				result.set(work.call());
			} catch(Exception x) {
				failure.set(x);
			}
		});
		if(failure.get() != null)
			throw failure.get();
		return result.get();
	}

	@Test
	void theMainWindowBuilds(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		MainWindow w = onEdt(() -> new MainWindow(context(dir)));
		try {
			assertEquals("PDP11GUI", w.getTitle());
			assertNotNull(w.getJMenuBar());
			assertEquals(3, w.getJMenuBar().getMenuCount(), "File, Windows, Help");
			assertFalse(w.isVisible(), "built, not shown");
		} finally {
			onEdt(() -> {
				w.dispose();
				return null;
			});
		}
	}

	@Test
	void theLogWindowBuildsAndTakesTheHistoryItMissed(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		//-- Logged before the window exists, which is when everything interesting happens.
		ctx.getLogger().log(LogChannel.OTHER, "before the window existed");
		LogWindow.register(ctx);
		assertTrue(ctx.getWindowManager().isRegistered(WindowType.LOG));

		ToolWindow w = onEdt(() -> ctx.getWindowManager().open(WindowType.LOG));
		try {
			assertEquals("Log", w.getTitle());
			//-- Opened once, so asking again is the same window rather than a second one.
			assertSame(w, onEdt(() -> ctx.getWindowManager().open(WindowType.LOG)));
			assertEquals(1, ctx.getWindowManager().allWindows().size());
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				return null;
			});
		}
	}

	@Test
	void closingAToolWindowHidesItAndRemembersWhereItWas(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		LogWindow.register(ctx);
		ToolWindow w = onEdt(() -> ctx.getWindowManager().open(WindowType.LOG));
		try {
			onEdt(() -> {
				w.hideWindow();
				return null;
			});
			assertFalse(w.isVisible());
			//-- Hidden, not disposed: reopening is the same window with its contents intact,
			//-- which is the behaviour change the MDI FormStyle flip used to prevent.
			assertSame(w, onEdt(() -> ctx.getWindowManager().open(WindowType.LOG)));
			assertNotNull(ctx.getSettings().getWindowGeometry(w.key().toStorageKey()),
				"where it was is remembered on the way out");
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				return null;
			});
		}
	}
}
