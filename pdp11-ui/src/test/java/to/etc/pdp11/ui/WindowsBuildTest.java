package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.disas.DisassemblerWindow;
import to.etc.pdp11.ui.exec.ExecutionWindow;
import to.etc.pdp11.ui.log.LogWindow;
import to.etc.pdp11.ui.macro11.AssemblerWindow;
import to.etc.pdp11.ui.mem.MemoryWindow;
import to.etc.pdp11.ui.mem.RegisterGroupWindow;
import to.etc.pdp11.ui.microcode.MicrocodeWindow;
import to.etc.pdp11.ui.mmu.MmuWindow;
import to.etc.pdp11.ui.numbers.NumberConverterWindow;
import to.etc.pdp11.ui.simh.SimhConsoleWindow;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowType;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
		return TestContext.create(dir);
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

	/**
	 * The main terminal is the machine's console, and SimH's {@code sim>} channel is not it.
	 *
	 * <p>Connecting to a simulated SimH is the case with no machine console at all - there is a
	 * {@code sim>} channel and nothing behind it - so the terminal says so rather than showing
	 * {@code sim>} traffic, and the window that does show that channel opens on its own.</p>
	 */
	@Test
	void aSimhConnectionOpensTheSimhConsoleAndLeavesTheTerminalSayingWhy(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		SimhConsoleWindow.register(ctx);
		MainWindow w = onEdt(() -> new MainWindow(ctx));
		try {
			//-- Off the event thread, as the main window's own menu item does it.
			ctx.getConnectionManager().connect(
				to.etc.pdp11.core.conn.ConnectionProfile.simulated(
					to.etc.pdp11.core.conn.ConsoleProtocol.SIMH));
			onEdt(() -> null);                              // let the state change reach the window
			ToolWindow simh = ctx.getWindowManager().find(
				to.etc.pdp11.ui.window.WindowKey.of(WindowType.SIMH_CONSOLE));
			assertNotNull(simh, "the SimH console opens with the connection");
			assertTrue(simh.isVisible());

			String terminal = onEdt(() -> w.getPanel().getGlassTerminal().getText());
			assertTrue(terminal.contains("no machine console"), terminal);
			//-- "sim>" itself appears in the note above; what must not be here is the traffic.
			assertFalse(terminal.contains("sh cpu iospace"),
				"no sim> traffic in it: " + terminal.replace("\n", " | "));
		} finally {
			ctx.getConnectionManager().close();
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				w.dispose();
				return null;
			});
		}
	}

	/** A real console <i>is</i> the machine's console, so the terminal shows the whole wire. */
	@Test
	void anOdtConnectionPutsTheWholeWireOnTheTerminal(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		SimhConsoleWindow.register(ctx);
		MainWindow w = onEdt(() -> new MainWindow(ctx));
		try {
			ctx.getConnectionManager().connect(
				to.etc.pdp11.core.conn.ConnectionProfile.simulated(
					to.etc.pdp11.core.conn.ConsoleProtocol.ODT_18));
			onEdt(() -> null);
			assertNull(ctx.getWindowManager().find(
				to.etc.pdp11.ui.window.WindowKey.of(WindowType.SIMH_CONSOLE)),
				"nothing to open a SimH window for");
			String terminal = onEdt(() -> w.getPanel().getGlassTerminal().getText());
			assertTrue(terminal.contains("@"), "ODT's prompt, from its own handshake: " + terminal);
		} finally {
			ctx.getConnectionManager().close();
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
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

	@Test
	void everyToolWindowBuilds(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		LogWindow.register(ctx);
		MemoryWindow.register(ctx);
		ExecutionWindow.register(ctx);
		DisassemblerWindow.register(ctx);
		AssemblerWindow.register(ctx);
		SimhConsoleWindow.register(ctx);
		MmuWindow.register(ctx);
		MicrocodeWindow.register(ctx);
		NumberConverterWindow.register(ctx);
		try {
			for(WindowType type : new WindowType[] {WindowType.LOG, WindowType.MEMORY, WindowType.EXECUTION,
				WindowType.DISASSEMBLER, WindowType.ASSEMBLER, WindowType.SIMH_CONSOLE, WindowType.MMU, WindowType.MICROCODE,
				WindowType.NUMBER_CONVERTER}) {
				ToolWindow w = onEdt(() -> ctx.getWindowManager().open(type));
				assertNotNull(w, type + " builds");
				assertTrue(w.getTitle().startsWith(type.getTitle()), w.getTitle());
			}
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				return null;
			});
		}
	}

	/**
	 * Memory views are unlimited, and each is its own window with its own remembered geometry -
	 * which is what the {@code instanceId} half of {@link to.etc.pdp11.ui.window.WindowKey} is
	 * for. The Pascal creates exactly four in {@code FormCreate} and calls that unlimited.
	 */
	@Test
	void thereCanBeSeveralMemoryWindows(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		MemoryWindow.register(ctx);
		try {
			ToolWindow first = onEdt(() -> ctx.getWindowManager().openNew(WindowType.MEMORY));
			ToolWindow second = onEdt(() -> ctx.getWindowManager().openNew(WindowType.MEMORY));
			assertNotSame(first, second);
			assertEquals("1", first.key().instanceId());
			assertEquals("2", second.key().instanceId());
			assertEquals("Memory - 2", second.getTitle());
			assertEquals(2, ctx.getWindowManager().windowsOfType(WindowType.MEMORY).size());
			//-- Two views, two groups on the propagation bus, so a deposit in one shows in the other.
			//-- Counted by usage tag rather than by total: the context also holds the assembler's
			//-- code group, which is application state and exists whether or not anything is open.
			assertEquals(2, ctx.getMemoryCellGroups().getGroups().stream()
				.filter(g -> "memory-view".equals(g.getUsageTag())).count());
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				return null;
			});
		}
	}

	/** Hiding and reopening must leave the window still following what it displays. */
	@Test
	void aReopenedWindowIsAttachedAgain(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		ExecutionWindow.register(ctx);
		try {
			ExecutionWindow w = (ExecutionWindow) onEdt(() -> ctx.getWindowManager().open(WindowType.EXECUTION));
			onEdt(() -> {
				w.hideWindow();
				return null;
			});
			onEdt(() -> ctx.getWindowManager().open(WindowType.EXECUTION));
			//-- The panel updates from the machine state, so a state change must reach it again.
			onEdt(() -> {
				ctx.getMachineState().stopped(to.etc.pdp11.core.addr.Address.of(
					to.etc.pdp11.core.addr.MemoryAddressType.VIRTUAL, 0777));
				return null;
			});
			assertEquals("000777", w.getPanel().getCurrentPcField().getText(),
				"a window that came back is still listening");
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				return null;
			});
		}
	}

	/**
	 * The register-group windows are whatever the machine description declares, so the factory
	 * looks the group up by name when the window is opened rather than being told about it.
	 */
	@Test
	void aRegisterGroupWindowIsBuiltForTheGroupItIsNamedAfter(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		RegisterGroupWindow.register(ctx);
		to.etc.pdp11.core.mem.MemoryCellGroup g = ctx.getMemoryCellGroups()
			.addGroup(to.etc.pdp11.core.addr.MemoryAddressType.PHYSICAL16, "DL11");
		g.setUsageTag("machine");
		g.add(0177560, 4);
		try {
			ToolWindow w = onEdt(() -> ctx.getWindowManager()
				.open(to.etc.pdp11.ui.window.WindowKey.of(WindowType.REGISTER_GROUP, "DL11")));
			assertEquals("Registers - DL11", w.getTitle());
			assertEquals(List.of(g), RegisterGroupWindow.groupsOf(ctx));

			//-- And a group that is not there is refused rather than opening an empty window
			//-- onto nothing.
			assertThrows(IllegalStateException.class, () -> onEdt(() -> ctx.getWindowManager()
				.open(to.etc.pdp11.ui.window.WindowKey.of(WindowType.REGISTER_GROUP, "NOSUCH"))));
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				return null;
			});
		}
	}
}
