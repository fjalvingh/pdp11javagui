package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Scheduler;
import to.etc.pdp11.ui.log.UiLogger;
import to.etc.pdp11.ui.settings.SettingsStore;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowManager;
import to.etc.pdp11.ui.window.WindowType;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring, without a screen.
 *
 * <p>Everything here is what happens between the application starting and a window being drawn:
 * services built and handed to each other, a connection made, the failure path taken, settings
 * written. No {@code JFrame} is constructed, so this runs on a build machine with no display -
 * which is the point of keeping the logic out of the windows in the first place.</p>
 */
class AppContextTest {
	private static AppContext context(Path dir, UiLogger logger) {
		SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
		MemoryCellGroups groups = new MemoryCellGroups();
		Scheduler scheduler = new Scheduler.Manual();
		ConnectionManager connections = new ConnectionManager(groups, logger, scheduler, dir);
		WindowManager windows = new WindowManager(store);
		AppContext ctx = new AppContext(store, logger, groups, new BitfieldsDefs(), connections, windows,
			scheduler, dir);
		windows.setContext(ctx);
		return ctx;
	}

	@Test
	void theContextHandsOutEverythingAWindowCouldNeed(@TempDir Path dir) {
		//-- The point of PLAN.md section 5's "do this now, not later": a window is given what it
		//-- needs rather than reaching for it. There is no static instance to reach for.
		AppContext ctx = context(dir, new UiLogger());
		assertNotNull(ctx.getSettings());
		assertNotNull(ctx.getLogger());
		assertNotNull(ctx.getMemoryCellGroups());
		assertNotNull(ctx.getBitfieldDefs());
		assertNotNull(ctx.getConnectionManager());
		assertNotNull(ctx.getWindowManager());
		assertNotNull(ctx.getScheduler());
		assertNotNull(ctx.getDataDir());
	}

	@Test
	void aFailureIsLoggedWhetherOrNotAnyoneIsShowingIt(@TempDir Path dir) {
		UiLogger logger = new UiLogger();
		AppContext ctx = context(dir, logger);
		//-- No handler set: the default does nothing, and the failure still reaches the log.
		ctx.reportFailure("something went wrong", new IllegalStateException("because"));
		assertTrue(logger.snapshot().stream().anyMatch(l -> l.text().contains("something went wrong")));

		AtomicReference<String> shown = new AtomicReference<>();
		ctx.setFailureHandler((message, cause) -> shown.set(message));
		ctx.reportFailure("and again", null);
		assertEquals("and again", shown.get());
	}

	@Test
	void theWholeStackConnectsToASimulatedMachineAndTalksToIt(@TempDir Path dir) throws Exception {
		//-- Settings say which profile, the manager builds the transport and console from it, the
		//-- connection carries the bytes, and the console protocol makes sense of them. That is
		//-- every layer of phase 5 below the windows.
		AppContext ctx = context(dir, new UiLogger());
		ConnectionProfile profile = ConnectionProfile.simulated(ConsoleProtocol.PDP1144);
		ctx.getSettings().putProfile(profile);
		assertEquals(profile, ctx.getSettings().currentProfile());

		ConnectionManager m = ctx.getConnectionManager();
		m.connect(ctx.getSettings().currentProfile());
		assertTrue(m.isConnected());
		Address a = Address.of(m.getConsole().physicalAddressType(), 01000);
		m.getConnection().run(() -> m.getConsole().deposit(a, 0123456));
		assertEquals(0123456, m.getConnection().call(() -> m.getConsole().examine(a)).word());
		//-- And the terminal is told how to read this console's line endings.
		assertTrue(m.getConsole().terminalProfile().crIsNewline(), "the 11/44 ends a line with a lone CR");
		m.disconnect();
	}

	/**
	 * A job refused because the connection closed under it is reported, not lost.
	 *
	 * <p>{@code onConsole} null-checks the connection and then submits; a disconnect landing in
	 * between makes the executor refuse the task. That used to be swallowed and answered with
	 * true, so a window that had already told the user something was happening - the Memory Test
	 * window sets "Testing ..." and disables its buttons before queueing, and turns them back on
	 * from inside the job - waited for callbacks that could never run, for the rest of the
	 * session.</p>
	 */
	@Test
	void aJobThatCannotBeQueuedIsReportedRatherThanVanishing(@TempDir Path dir) throws Exception {
		AppContext ctx = context(dir, new UiLogger());
		ConnectionManager m = ctx.getConnectionManager();
		m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
		try {
			//-- The disconnect that lands between the check and the submit: the manager still
			//-- hands out console and connection, but the command thread is already gone.
			m.getConnection().close();

			AtomicReference<String> shown = new AtomicReference<>();
			ctx.setFailureHandler((message, cause) -> shown.set(message));
			boolean[] ran = {false};
			assertFalse(ctx.onConsole("Memory test", console -> ran[0] = true),
				"the caller is told the job was not queued");
			assertFalse(ran[0]);
			assertNotNull(shown.get(), "and the user is told why");
			assertTrue(shown.get().contains("Memory test"), shown.get());
		} finally {
			m.close();
		}
	}

	@Test
	void aWindowTypeWithNoFactoryIsAnErrorRatherThanANullWindow(@TempDir Path dir) {
		AppContext ctx = context(dir, new UiLogger());
		assertFalse(ctx.getWindowManager().isRegistered(WindowType.LOG));
		assertThrows(IllegalStateException.class, () -> ctx.getWindowManager().open(WindowType.LOG));
	}

	@Test
	void windowGeometrySurvivesARestart(@TempDir Path dir) {
		//-- No window is constructed: what is checked is that the manager and the settings agree
		//-- on the key, which is the part that used to be a caption match.
		AppContext ctx = context(dir, new UiLogger());
		WindowKey key = WindowKey.of(WindowType.MEMORY, "3");
		assertEquals("MEMORY:3", key.toStorageKey());
		assertEquals("Memory - 3", key.title());
		ctx.getSettings().setWindowGeometry(key.toStorageKey(),
			new to.etc.pdp11.ui.settings.WindowGeometry(10, 20, 300, 400, true, false));
		ctx.saveSettings();

		AppContext restarted = context(dir, new UiLogger());
		assertNotNull(restarted.getSettings().getWindowGeometry(key.toStorageKey()));
		assertEquals(300, restarted.getSettings().getWindowGeometry(key.toStorageKey()).width());
	}

	@Test
	void theLogBuffersFromTheFirstLineSoOpeningItLateMissesNothing(@TempDir Path dir) {
		//-- The interesting lines all happen before anybody opens a log window.
		UiLogger logger = new UiLogger();
		logger.log(LogChannel.OTHER, "starting");
		logger.log(LogChannel.OTHER, "settings loaded");
		assertEquals(List.of("starting", "settings loaded"),
			logger.snapshot().stream().map(l -> l.text()).toList());
	}

	@Test
	void theByteLevelChannelsAreOffUntilAskedFor() {
		//-- One line per byte. Leaving them on costs a formatted string per character of every
		//-- transcript, which is why the Pascal has the same switch.
		UiLogger logger = new UiLogger();
		assertFalse(logger.isEnabled(LogChannel.TRANSPORT_READ));
		assertTrue(logger.isEnabled(LogChannel.PROTOCOL));
		logger.log(LogChannel.TRANSPORT_READ, "a byte");
		assertTrue(logger.snapshot().isEmpty(), "a disabled channel is not recorded at all");

		logger.setEnabled(LogChannel.TRANSPORT_READ, true);
		logger.log(LogChannel.TRANSPORT_READ, "a byte");
		assertEquals(1, logger.snapshot().size());
	}
}
