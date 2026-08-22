package to.etc.pdp11.ui;

import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Scheduler;
import to.etc.pdp11.ui.log.UiLogger;
import to.etc.pdp11.ui.settings.SettingsStore;
import to.etc.pdp11.ui.window.WindowManager;

import java.nio.file.Path;

/**
 * An {@link AppContext} wired up for a test, with its settings in a temporary directory.
 *
 * <p>Nothing here is a mock. The connection manager is the real one and connects to the real
 * simulated machines when a test asks it to; what the temporary directory buys is only that a
 * test cannot write over the settings of whoever is running it.</p>
 */
public final class TestContext {
	private TestContext() {
	}

	public static AppContext create(Path dir) {
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
}
