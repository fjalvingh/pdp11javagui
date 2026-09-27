package to.etc.pdp11.app;

import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Scheduler;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.settings.SettingsStore;
import to.etc.pdp11.ui.window.WindowManager;

import java.nio.file.Path;

/** An {@link AppContext} for a test, with its settings and data in a temporary directory. */
public record AppTestContext(AppContext context) {
	public static AppTestContext create(Path dir) {
		SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
		MemoryCellGroups groups = new MemoryCellGroups();
		Logger logger = Logger.NULL;
		Scheduler scheduler = new Scheduler.Manual();
		ConnectionManager connections = new ConnectionManager(groups, logger, scheduler, dir);
		WindowManager windows = new WindowManager(store);
		AppContext ctx = new AppContext(store, logger, groups, new BitfieldsDefs(), connections, windows,
			scheduler, dir);
		windows.setContext(ctx);
		return new AppTestContext(ctx);
	}
}
