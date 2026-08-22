package to.etc.pdp11.ui.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.conn.TransportConfig;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings: where they live, what survives a round trip, and what happens when the file is
 * nonsense.
 *
 * <p>All headless - the only AWT type here is {@code Rectangle}, which is a data class. The
 * screen arrangements are passed in rather than asked for, which is what makes the multi-monitor
 * rule checkable at all on a build machine with one screen and no display.</p>
 */
class SettingsTest {
	// ---------------------------------------------------------------------------------------
	// Where things live
	// ---------------------------------------------------------------------------------------

	@Test
	void eachPlatformGetsItsOwnConventionalPlace() {
		//-- Two of these three cannot be verified by running here, which is exactly why the
		//-- environment is an argument rather than something read from the JVM.
		assertEquals(Path.of("/home/jal/.config/pdp11gui"),
			ConfigDir.config("Linux", Map.of(), "/home/jal"));
		assertEquals(Path.of("/home/jal/Library/Application Support/pdp11gui"),
			ConfigDir.config("Mac OS X", Map.of(), "/home/jal"));
		assertEquals(Path.of("C:/Users/jal/AppData/Roaming/pdp11gui"),
			ConfigDir.config("Windows 11", Map.of("APPDATA", "C:/Users/jal/AppData/Roaming"), "C:/Users/jal"));
	}

	@Test
	void theXdgVariablesAreHonouredWhenTheyAreSet() {
		assertEquals(Path.of("/tmp/cfg/pdp11gui"),
			ConfigDir.config("Linux", Map.of("XDG_CONFIG_HOME", "/tmp/cfg"), "/home/jal"));
		assertEquals(Path.of("/tmp/state/pdp11gui"),
			ConfigDir.data("Linux", Map.of("XDG_STATE_HOME", "/tmp/state"), "/home/jal"));
		//-- Blank is the same as unset; an exported-but-empty variable is a common accident.
		assertEquals(Path.of("/home/jal/.config/pdp11gui"),
			ConfigDir.config("Linux", Map.of("XDG_CONFIG_HOME", ""), "/home/jal"));
	}

	@Test
	void configurationAndWorkingFilesAreKeptApart() {
		//-- One of them is worth backing up and the other is worth deleting.
		Path config = ConfigDir.config("Linux", Map.of(), "/home/jal");
		Path data = ConfigDir.data("Linux", Map.of(), "/home/jal");
		assertFalse(config.equals(data));
	}

	// ---------------------------------------------------------------------------------------
	// Reading and writing
	// ---------------------------------------------------------------------------------------

	@Test
	void whatIsSavedComesBack(@TempDir Path dir) {
		Path file = dir.resolve("settings.json");
		SettingsStore store = new SettingsStore(file);
		store.get().setWindowGeometry("LOG", new WindowGeometry(10, 20, 300, 400, true, false));
		store.get().putProfile(new ConnectionProfile("my 11/44", ConsoleProtocol.PDP1144,
			TransportConfig.serial("/dev/ttyUSB0", 9600, null)));
		assertNull(store.save());

		SettingsStore reread = new SettingsStore(file);
		assertEquals(new WindowGeometry(10, 20, 300, 400, true, false), reread.get().getWindowGeometry("LOG"));
		assertEquals(1, reread.get().profiles().size());
		ConnectionProfile p = reread.get().profiles().get(0);
		assertEquals("my 11/44", p.name());
		assertEquals(ConsoleProtocol.PDP1144, p.protocol());
		assertEquals("/dev/ttyUSB0", p.transport().serialPort());
		assertEquals("my 11/44", reread.get().currentProfile().name(), "and it is offered again next time");
	}

	@Test
	void theFileIsMeantToBeReadableByAPerson(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("settings.json");
		SettingsStore store = new SettingsStore(file);
		store.get().setWindowGeometry("LOG", new WindowGeometry(1, 2, 300, 400, true, false));
		store.save();
		String json = Files.readString(file);
		assertTrue(json.contains("\n"), "pretty printed, so it can be diffed and hand-edited");
		assertTrue(json.contains("\"schemaVersion\""), "versioned from day one, per PLAN.md section 4");
	}

	@Test
	void aFirstRunIsNotAProblem(@TempDir Path dir) {
		SettingsStore store = new SettingsStore(dir.resolve("nothing-here.json"));
		assertNotNull(store.get());
		assertNull(store.getLastProblem(), "a missing file is what a first run looks like, not a fault");
		assertEquals(ConnectionProfile.defaultProfile().name(), store.get().currentProfile().name());
	}

	@Test
	void nonsenseInTheFileIsReportedAndSteppedOver(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("settings.json");
		Files.writeString(file, "this is not JSON at all {{{");
		SettingsStore store = new SettingsStore(file);
		assertNotNull(store.get(), "the application still starts");
		assertNotNull(store.getLastProblem(), "and says why it forgot everything");
	}

	@Test
	void anEmptyFileIsSteppedOverToo(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("settings.json");
		Files.writeString(file, "");
		SettingsStore store = new SettingsStore(file);
		assertNotNull(store.get());
		assertNotNull(store.getLastProblem());
	}

	@Test
	void aFileFromANewerVersionIsLeftAloneRatherThanTruncated(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("settings.json");
		String original = "{\"schemaVersion\": 99, \"somethingWeHaveNotInventedYet\": true}";
		Files.writeString(file, original);
		SettingsStore store = new SettingsStore(file);
		assertNotNull(store.getLastProblem(), "reading it would silently drop what it knows");
		assertTrue(store.getLastProblem().contains("99"), store.getLastProblem());
		//-- And nothing has been written over it just by starting up.
		assertEquals(original, Files.readString(file));
	}

	@Test
	void aFileMissingAWholeSectionStillWorks(@TempDir Path dir) throws IOException {
		//-- What a hand-edited file, or one from an older version, looks like: the key is simply
		//-- not there, and the binder leaves the field null.
		Path file = dir.resolve("settings.json");
		Files.writeString(file, "{\"schemaVersion\": 1}");
		SettingsStore store = new SettingsStore(file);
		assertNull(store.getLastProblem());
		assertTrue(store.get().windows().isEmpty());
		assertTrue(store.get().profiles().isEmpty());
		assertNotNull(store.get().currentProfile());
	}

	@Test
	void anInterruptedWriteCannotDestroyTheOldSettings(@TempDir Path dir) throws IOException {
		//-- Writes go through a temporary file and a move, so a crash mid-write loses the new
		//-- settings rather than the ones already saved. What is checked here is that no
		//-- temporary file is left behind by a successful write.
		Path file = dir.resolve("settings.json");
		SettingsStore store = new SettingsStore(file);
		store.get().setWindowGeometry("LOG", new WindowGeometry(1, 2, 300, 400, true, false));
		store.save();
		try(var list = Files.list(dir)) {
			assertEquals(List.of("settings.json"), list.map(p -> p.getFileName().toString()).sorted().toList());
		}
	}

	@Test
	void savingWithoutHavingLoadedWritesNothing(@TempDir Path dir) {
		//-- Called on the way out by an application that never touched its settings.
		Path file = dir.resolve("settings.json");
		assertNull(new SettingsStore(file).save());
		assertFalse(Files.exists(file));
	}

	// ---------------------------------------------------------------------------------------
	// Window geometry and the monitor that went away
	// ---------------------------------------------------------------------------------------

	private static final Rectangle LAPTOP = new Rectangle(0, 0, 1920, 1080);

	private static final Rectangle SECOND = new Rectangle(1920, 0, 2560, 1440);

	@Test
	void aWindowStillOnAScreenIsLeftExactlyWhereItIs() {
		WindowGeometry g = new WindowGeometry(2000, 100, 800, 600, true, false);
		assertSame(g, g.clampTo(List.of(LAPTOP, SECOND)));
	}

	@Test
	void aWindowOnAMonitorThatIsGoneComesBackToThePrimary() {
		//-- The case that makes this worth writing: the window was on the second screen, the
		//-- second screen is not there any more, and without this the user cannot reach it or
		//-- close it - it is simply gone.
		WindowGeometry g = new WindowGeometry(2400, 300, 800, 600, true, false);
		WindowGeometry moved = g.clampTo(List.of(LAPTOP));
		assertTrue(LAPTOP.contains(moved.bounds()), "back on the primary: " + moved);
		assertEquals(800, moved.width(), "and the same size, since it fits");
		assertEquals(600, moved.height());
		assertTrue(moved.visible(), "whether it was showing is not a thing this changes");
	}

	@Test
	void aWindowLargerThanTheRemainingScreenIsShrunkToFit() {
		WindowGeometry g = new WindowGeometry(2400, 300, 2400, 1300, true, false);
		WindowGeometry moved = g.clampTo(List.of(LAPTOP));
		assertEquals(1920, moved.width());
		assertEquals(1080, moved.height());
		assertTrue(LAPTOP.contains(moved.bounds()));
	}

	@Test
	void aCornerOnScreenIsEnoughToCountAsReachable() {
		//-- Enough of the title bar to grab. A window hanging off the right edge is a thing
		//-- people do on purpose, and moving it back would be rude.
		WindowGeometry g = new WindowGeometry(1850, 100, 800, 600, true, false);
		assertSame(g, g.clampTo(List.of(LAPTOP)));
		//-- Ten pixels is not enough to grab.
		WindowGeometry sliver = new WindowGeometry(1910, 100, 800, 600, true, false);
		assertFalse(sliver == sliver.clampTo(List.of(LAPTOP)));
	}

	@Test
	void withNoScreensAtAllNothingIsMoved() {
		//-- A headless build machine, which is where this test itself runs.
		WindowGeometry g = new WindowGeometry(-5000, -5000, 800, 600, true, false);
		assertSame(g, g.clampTo(List.of()));
	}

	@Test
	void nonsenseGeometryIsNotWorthRestoring() {
		assertFalse(new WindowGeometry(0, 0, 0, 0, true, false).isUsable());
		assertFalse(new WindowGeometry(0, 0, 10, 10, true, false).isUsable());
		assertTrue(new WindowGeometry(0, 0, 300, 200, true, false).isUsable());
	}
}
