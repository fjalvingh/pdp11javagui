package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.bits.BitfieldsWindow;
import to.etc.pdp11.ui.disas.DisassemblerWindow;
import to.etc.pdp11.ui.dump.MemoryDumperWindow;
import to.etc.pdp11.ui.exec.ExecutionWindow;
import to.etc.pdp11.ui.load.MemoryLoaderWindow;
import to.etc.pdp11.ui.log.LogWindow;
import to.etc.pdp11.ui.macro11.AssemblerWindow;
import to.etc.pdp11.ui.mem.MemoryWindow;
import to.etc.pdp11.ui.mem.RegisterGroupWindow;
import to.etc.pdp11.ui.memtest.MemoryTestWindow;
import to.etc.pdp11.ui.microcode.MicrocodeWindow;
import to.etc.pdp11.ui.mmu.MmuWindow;
import to.etc.pdp11.ui.numbers.NumberConverterWindow;
import to.etc.pdp11.ui.scan.IoPageScannerWindow;
import to.etc.pdp11.ui.settings.SettingsDialog;
import to.etc.pdp11.ui.simh.SimhConsoleWindow;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowType;

import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
	 * The Settings dialog behaves like a dialog: Enter connects, Escape closes, and the
	 * title-bar X does what Close does.
	 *
	 * <p>FABLE-ISSUES #41: it had no default button and no Escape binding, and its X was wired
	 * to {@code DISPOSE_ON_CLOSE} while Close called {@code saveSettings()} - so which of two
	 * gestures that both mean "I am done here" the user chose silently decided whether their
	 * saved profiles reached disk.</p>
	 */
	@Test
	void theSettingsDialogHasADefaultButtonAnEscapeAndOneWayOut(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		SettingsDialog dialog = onEdt(() -> SettingsDialog.create(null, ctx, profile -> {
		}));
		try {
			assertEquals("Connect", onEdt(() -> dialog.getRootPane().getDefaultButton().getText()),
				"Enter connects");
			assertNotNull(onEdt(() -> dialog.getRootPane().getActionForKeyStroke(
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))), "Escape does something");
			assertEquals(JDialog.DO_NOTHING_ON_CLOSE, onEdt(dialog::getDefaultCloseOperation),
				"the X is handled rather than taken literally");

			//-- And handled the way Close is: the settings are written before it goes.
			onEdt(() -> {
				dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
				return null;
			});
			assertFalse(onEdt(dialog::isVisible), "it closed");
			assertTrue(Files.exists(dir.resolve("settings.json")), "and saved on the way out");
		} finally {
			onEdt(() -> {
				dialog.dispose();
				return null;
			});
		}
	}

	/**
	 * Connecting records which profile it was, on the event thread that owns the settings.
	 *
	 * <p>FABLE-ISSUES #44: the connect worker wrote {@code setLastProfileName} into the shared
	 * {@link to.etc.pdp11.ui.settings.Settings} directly, while the EDT could be editing profiles
	 * or handing the same object to Gson in {@code saveSettings()}. What is asserted here is the
	 * marshalling: pumping the event thread is what makes the write appear.</p>
	 */
	@Test
	void connectingRecordsTheProfileOnTheEventThread(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		MainWindow w = onEdt(() -> new MainWindow(ctx));
		try {
			onEdt(() -> {
				w.getConnectToSimulatedMenu().getItem(0).doClick();
				return null;
			});
			long deadline = System.currentTimeMillis() + 20_000;
			while(ctx.getConnectionManager().getState() != ConnectionManager.State.CONNECTED) {
				if(System.currentTimeMillis() > deadline)
					throw new AssertionError("the connect never finished");
				Thread.sleep(5);
			}
			//-- Everything the worker had to say reaches the settings by way of the event thread.
			onEdt(() -> null);
			onEdt(() -> null);
			assertFalse(ctx.getSettings().getLastProfileName() == null
				|| ctx.getSettings().getLastProfileName().isEmpty(), "the profile was recorded");
		} finally {
			ctx.getConnectionManager().close();
			onEdt(() -> {
				w.dispose();
				return null;
			});
		}
	}

	/**
	 * Disconnect is on offer when there is a connection, and not otherwise.
	 *
	 * <p>FABLE-ISSUES #42: it was enabled at all times, so choosing it with nothing connected
	 * started a worker, tore down nothing, and printed "[disconnected]" in the terminal - the
	 * application answering a question about a machine it does not have. Connect is the other
	 * way round and stays on: connecting while connected is how the user moves to another
	 * machine.</p>
	 */
	@Test
	void disconnectIsOfferedOnlyWhenThereIsSomethingToDisconnect(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		MainWindow w = onEdt(() -> new MainWindow(ctx));
		try {
			assertFalse(onEdt(() -> w.getDisconnectItem().isEnabled()), "nothing is connected yet");
			assertTrue(onEdt(() -> w.getConnectItem().isEnabled()), "and connecting is the thing to do");

			ctx.getConnectionManager().connect(
				to.etc.pdp11.core.conn.ConnectionProfile.simulated(
					to.etc.pdp11.core.conn.ConsoleProtocol.ODT_18));
			onEdt(() -> null);                              // let the state change reach the window
			assertTrue(onEdt(() -> w.getDisconnectItem().isEnabled()), "there is a machine to let go of");
			assertTrue(onEdt(() -> w.getConnectItem().isEnabled()), "and connecting again is a reconnect");

			ctx.getConnectionManager().disconnect();
			onEdt(() -> null);
			assertFalse(onEdt(() -> w.getDisconnectItem().isEnabled()), "and not once it is gone");
		} finally {
			ctx.getConnectionManager().close();
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

	/**
	 * A connection that dies under the user says so, in the window they are looking at.
	 *
	 * <p>The state change greys every window out at once, which on its own reads as the
	 * application having broken rather than as the machine having gone away. The status bar and
	 * one line in the terminal are the difference.</p>
	 */
	@Test
	void aDroppedConnectionSaysSoInTheTerminalAndTheStatusBar(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		MainWindow w = onEdt(() -> new MainWindow(ctx));
		try {
			ctx.getConnectionManager().connect(
				to.etc.pdp11.core.conn.ConnectionProfile.simulated(
					to.etc.pdp11.core.conn.ConsoleProtocol.ODT_18));
			onEdt(() -> null);
			assertEquals("Connected", onEdt(() -> w.getPanel().getStateText()));

			//-- The machine goes away: the transport is closed by something that is not the
			//-- manager, which is what SimH exiting looks like from in here.
			ctx.getConnectionManager().getConnection().getTransport().close();
			long deadline = System.currentTimeMillis() + 10_000;
			while(ctx.getConnectionManager().getState() != ConnectionManager.State.FAILED) {
				if(System.currentTimeMillis() > deadline)
					throw new AssertionError("the drop was never noticed");
				Thread.sleep(5);
			}
			onEdt(() -> null);

			assertEquals("Connection failed", onEdt(() -> w.getPanel().getStateText()));
			assertTrue(onEdt(() -> w.getPanel().getDetailText()).contains("closed at the other end"),
				onEdt(() -> w.getPanel().getDetailText()));
			String terminal = onEdt(() -> w.getPanel().getGlassTerminal().getText());
			assertTrue(terminal.contains("closed at the other end"),
				"the terminal should say the machine went away: " + terminal.replace("\n", " | "));
		} finally {
			ctx.getConnectionManager().close();
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				w.dispose();
				return null;
			});
		}
	}

	/**
	 * Disconnect tears the connection down on a worker, not on the event thread.
	 *
	 * <p>{@code close()} waits up to two seconds for the reader thread and then closes the
	 * transport - killing a child process and waiting for it, or closing a serial port. Run from
	 * the menu item's action listener, as this was, a wedged transport freezes the whole window
	 * for several seconds, which looks exactly like a crash. Connect was already careful about
	 * this and said so in its own comment; Disconnect was not.</p>
	 *
	 * <p>The listener fires on whichever thread reached DISCONNECTED, so asking it which thread
	 * that was is the property itself rather than a proxy for it.</p>
	 */
	@Test
	void disconnectDoesNotTearTheConnectionDownOnTheEventThread(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		MainWindow w = onEdt(() -> new MainWindow(ctx));
		try {
			ctx.getConnectionManager().connect(
				to.etc.pdp11.core.conn.ConnectionProfile.simulated(
					to.etc.pdp11.core.conn.ConsoleProtocol.ODT_18));
			onEdt(() -> null);
			assertEquals("Connected", onEdt(() -> w.getPanel().getStateText()));

			List<Boolean> onEventThread = new java.util.concurrent.CopyOnWriteArrayList<>();
			ctx.getConnectionManager().addListener((m, state) -> {
				if(state == ConnectionManager.State.DISCONNECTED)
					onEventThread.add(SwingUtilities.isEventDispatchThread());
			});

			onEdt(() -> {
				w.getDisconnectItem().doClick();
				return null;
			});
			long deadline = System.currentTimeMillis() + 10_000;
			while(ctx.getConnectionManager().getState() != ConnectionManager.State.DISCONNECTED) {
				if(System.currentTimeMillis() > deadline)
					throw new AssertionError("the disconnect never finished");
				Thread.sleep(5);
			}

			assertFalse(onEventThread.isEmpty(), "the disconnect did happen");
			assertEquals(List.of(Boolean.FALSE), onEventThread,
				"the teardown must not run on the event thread");
			//-- And the window catches up afterwards, saying so once it is a fact.
			onEdt(() -> null);
			assertEquals("Not connected", onEdt(() -> w.getPanel().getStateText()));
			String terminal = onEdt(() -> w.getPanel().getGlassTerminal().getText());
			assertTrue(terminal.contains("[disconnected]"), terminal.replace("\n", " | "));
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

	/**
	 * A closed memory window is still reachable, and can be told apart from an open one.
	 *
	 * <p>Closing a tool window hides it. For a singleton that is fine - its own entry in the
	 * Windows menu brings it back with its contents. For a memory window there is no such entry:
	 * the menu offers "New memory window", which builds a <i>different</i> one, because the hidden
	 * window still holds instance id 1 and {@code openNew} takes the next free id. Listing only
	 * the visible windows therefore left it unreachable for the rest of the session - still on the
	 * propagation bus, still holding its range and its edits.</p>
	 */
	@Test
	void aClosedMemoryWindowIsStillListedAndStillHoldsItsId(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		MemoryWindow.register(ctx);
		MainWindow w = onEdt(() -> new MainWindow(ctx));
		try {
			ToolWindow one = onEdt(() -> ctx.getWindowManager().openNew(WindowType.MEMORY));
			assertEquals("Memory - 1", one.getTitle());
			onEdt(() -> {
				one.hideWindow();
				return null;
			});
			assertFalse(one.isVisible());

			//-- The id is not freed by closing, and must not be: the window still exists, keeps
			//-- its contents, and its saved geometry is keyed on it.
			ToolWindow two = onEdt(() -> ctx.getWindowManager().openNew(WindowType.MEMORY));
			assertEquals("Memory - 2", two.getTitle());
			assertEquals(List.of(one), ctx.getWindowManager().hiddenWindows());

			//-- So the menu has to name it, or nothing ever gets it back.
			List<String> items = onEdt(() -> menuItemTexts(w));
			assertTrue(items.contains("Memory - 1 (closed)"), items.toString());
			assertTrue(items.contains("Memory - 2"), items.toString());
			assertTrue(items.contains("Show all"), "PLAN.md's other half of Hide all: " + items);

			//-- And choosing it brings back the one that was closed, not a third window.
			onEdt(() -> {
				ctx.getWindowManager().raise(one.key());
				return null;
			});
			assertTrue(one.isVisible());
			assertEquals(2, ctx.getWindowManager().windowsOfType(WindowType.MEMORY).size());
			assertEquals(List.of(), ctx.getWindowManager().hiddenWindows());
			assertFalse(onEdt(() -> menuItemTexts(w)).contains("Show all"),
				"nothing to show once nothing is hidden");
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				w.dispose();
				return null;
			});
		}
	}

	/** The Windows menu as the user would read it, rebuilt as opening it would rebuild it. */
	private static List<String> menuItemTexts(MainWindow w) {
		List<String> l = new ArrayList<>();
		javax.swing.JMenu menu = w.getWindowsMenuRebuilt();
		for(int i = 0; i < menu.getItemCount(); i++) {
			javax.swing.JMenuItem item = menu.getItem(i);
			if(item != null)                                // null is a separator
				l.add(item.getText());
		}
		return l;
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

	/**
	 * The framework's own rule is that {@code onShowing} and {@code onHiding} pair up, and a
	 * window can leave the screen two ways. Only {@code hideWindow()} ran {@code onHiding};
	 * {@code closeAll()} and {@code closeAll(WindowType)} called {@code dispose()} straight, so a
	 * window disposed of while visible stayed subscribed to whatever it had subscribed to as a
	 * dead frame.
	 */
	@Test
	void aWindowDisposedWhileVisibleStillUnsubscribes(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		List<String> events = new ArrayList<>();
		ctx.getWindowManager().register(WindowType.LOG,
			key -> new RecordingWindow(key, ctx, events));

		ToolWindow w = onEdt(() -> ctx.getWindowManager().open(WindowType.LOG));
		assertEquals(List.of("showing"), events);
		assertTrue(w.isSubscribed());

		onEdt(() -> {
			ctx.getWindowManager().closeAll();
			return null;
		});
		assertEquals(List.of("showing", "hiding"), events, "disposed while visible, so it unsubscribed");
		assertFalse(w.isSubscribed());
	}

	/** And it happens once, not twice, for a window that was hidden first. */
	@Test
	void hidingThenDisposingUnsubscribesOnlyOnce(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		List<String> events = new ArrayList<>();
		ctx.getWindowManager().register(WindowType.LOG,
			key -> new RecordingWindow(key, ctx, events));

		ToolWindow w = onEdt(() -> ctx.getWindowManager().open(WindowType.LOG));
		onEdt(() -> {
			w.hideWindow();
			ctx.getWindowManager().closeAll();
			return null;
		});
		assertEquals(List.of("showing", "hiding"), events);
	}

	/** A tool window that records the two hooks, so a test can say whether they paired up. */
	private static final class RecordingWindow extends ToolWindow {
		private final List<String> m_events;

		RecordingWindow(to.etc.pdp11.ui.window.WindowKey key, AppContext context, List<String> events) {
			super(key, context);
			m_events = events;
			setSize(300, 200);
		}

		@Override
		protected void onShowing() {
			m_events.add("showing");
		}

		@Override
		protected void onHiding() {
			m_events.add("hiding");
		}
	}

	// ---------------------------------------------------------------------------------------
	// Restoring the layout
	// ---------------------------------------------------------------------------------------

	/**
	 * The geometry record has carried a {@code visible} flag since the beginning and nothing read
	 * it: every launch opened the main window alone while the settings file described, in detail,
	 * a layout it was not restoring.
	 */
	@Test
	void theWindowsThatWereOpenLastTimeComeBack(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext first = context(dir);
		LogWindow.register(first);
		MemoryWindow.register(first);
		NumberConverterWindow.register(first);
		onEdt(() -> {
			first.getWindowManager().open(WindowType.LOG);
			first.getWindowManager().openNew(WindowType.MEMORY);
			ToolWindow converter = first.getWindowManager().open(WindowType.NUMBER_CONVERTER);
			//-- Closed by the user before quitting, so it should not come back.
			converter.hideWindow();
			return null;
		});
		onEdt(() -> {
			first.getWindowManager().rememberAllGeometry();
			first.getWindowManager().closeAll();
			return null;
		});
		first.saveSettings();

		//-- A second run, reading the same settings file.
		AppContext second = context(dir);
		LogWindow.register(second);
		MemoryWindow.register(second);
		NumberConverterWindow.register(second);
		try {
			int reopened = onEdt(() -> second.getWindowManager().restoreVisibleWindows());
			assertEquals(2, reopened);
			assertNotNull(second.getWindowManager().find(to.etc.pdp11.ui.window.WindowKey.of(WindowType.LOG)));
			assertNotNull(second.getWindowManager()
				.find(to.etc.pdp11.ui.window.WindowKey.of(WindowType.MEMORY, "1")),
				"a memory window comes back as the same instance id, so its geometry follows it");
			assertNull(second.getWindowManager().find(
				to.etc.pdp11.ui.window.WindowKey.of(WindowType.NUMBER_CONVERTER)),
				"it was closed before quitting");
		} finally {
			onEdt(() -> {
				second.getWindowManager().closeAll();
				return null;
			});
		}
	}

	/**
	 * A saved entry can name a register group the loaded machine description does not declare -
	 * the factory throws for that on purpose - and nothing in settings may stop the application
	 * starting.
	 */
	@Test
	void aWindowThatCannotBeReopenedIsOneWindowSkipped(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		LogWindow.register(ctx);
		RegisterGroupWindow.register(ctx);
		//-- A group that is not there, and a window that is.
		ctx.getSettings().setWindowGeometry("REGISTER_GROUP:no such device",
			new to.etc.pdp11.ui.settings.WindowGeometry(10, 10, 400, 300, true, false));
		ctx.getSettings().setWindowGeometry("LOG",
			new to.etc.pdp11.ui.settings.WindowGeometry(20, 20, 400, 300, true, false));
		try {
			assertEquals(1, onEdt(() -> ctx.getWindowManager().restoreVisibleWindows()),
				"the one that could be built");
			assertNotNull(ctx.getWindowManager().find(to.etc.pdp11.ui.window.WindowKey.of(WindowType.LOG)));
		} finally {
			onEdt(() -> {
				ctx.getWindowManager().closeAll();
				return null;
			});
		}
	}

	/** The window types this test can build without a machine description behind them. */
	private static final WindowType[] SELF_CONTAINED_WINDOWS = {
		WindowType.LOG, WindowType.MEMORY, WindowType.EXECUTION, WindowType.DISASSEMBLER,
		WindowType.ASSEMBLER, WindowType.SIMH_CONSOLE, WindowType.MMU, WindowType.MICROCODE,
		WindowType.NUMBER_CONVERTER, WindowType.BITFIELDS, WindowType.MEMORY_TEST,
		WindowType.MEMORY_DUMPER, WindowType.MEMORY_LOADER, WindowType.IO_PAGE_SCANNER};

	private static void registerSelfContainedWindows(AppContext ctx) {
		LogWindow.register(ctx);
		MemoryWindow.register(ctx);
		ExecutionWindow.register(ctx);
		DisassemblerWindow.register(ctx);
		AssemblerWindow.register(ctx);
		SimhConsoleWindow.register(ctx);
		MmuWindow.register(ctx);
		MicrocodeWindow.register(ctx);
		NumberConverterWindow.register(ctx);
		BitfieldsWindow.register(ctx);
		MemoryTestWindow.register(ctx);
		MemoryDumperWindow.register(ctx);
		MemoryLoaderWindow.register(ctx);
		IoPageScannerWindow.register(ctx);
	}

	@Test
	void everyToolWindowBuilds(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		registerSelfContainedWindows(ctx);
		try {
			for(WindowType type : SELF_CONTAINED_WINDOWS) {
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
	 * A window has a floor it cannot be dragged below.
	 *
	 * <p>FABLE-ISSUES #61: eight of the fourteen set one and six did not, so the MMU, Microcode,
	 * Log, SimH Console, Number Converter and Execution windows could be dragged into slivers
	 * showing nothing - the kind of state a window manager will happily save and restore. Every
	 * window is checked rather than the six, because "the ones that have one" is not a property
	 * anybody can keep track of by hand.</p>
	 */
	@Test
	void everyToolWindowHasAMinimumSizeItCannotBeSquashedBelow(@TempDir Path dir) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
		AppContext ctx = context(dir);
		registerSelfContainedWindows(ctx);
		try {
			for(WindowType type : SELF_CONTAINED_WINDOWS) {
				ToolWindow w = onEdt(() -> ctx.getWindowManager().open(type));
				assertTrue(w.isMinimumSizeSet(), type + " sets no minimum size");
				java.awt.Dimension min = w.getMinimumSize();
				assertTrue(min.width >= 200 && min.height >= 120,
					type + " minimum size is " + min.width + "x" + min.height);
				assertTrue(min.width <= w.getWidth() && min.height <= w.getHeight(),
					type + " cannot fit its own opening size: min " + min.width + "x" + min.height
						+ ", opens at " + w.getWidth() + "x" + w.getHeight());
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
