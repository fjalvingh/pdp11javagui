package to.etc.pdp11.app;

import com.formdev.flatlaf.FlatDarculaLaf;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.MainWindow;
import to.etc.pdp11.ui.disas.DisassemblerWindow;
import to.etc.pdp11.ui.exec.ExecutionWindow;
import to.etc.pdp11.ui.log.LogWindow;
import to.etc.pdp11.ui.mem.MemoryWindow;
import to.etc.pdp11.ui.bits.BitfieldsWindow;
import to.etc.pdp11.ui.mem.RegisterGroupWindow;
import to.etc.pdp11.ui.dump.MemoryDumperWindow;
import to.etc.pdp11.ui.load.MemoryLoaderWindow;
import to.etc.pdp11.ui.memtest.MemoryTestWindow;
import to.etc.pdp11.ui.scan.IoPageScannerWindow;
import to.etc.pdp11.ui.log.UiLogger;
import to.etc.pdp11.ui.macro11.AssemblerWindow;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.EventQueue;

/**
 * Application entry point.
 *
 * <p>Everything platform-specific that has to happen before Swing wakes up lives here, in
 * {@link #configurePlatform()}, and nowhere else. Note the Lazarus version needs a launcher
 * script ({@code Pdp11gui/run.sh}) to compute {@code QT_SCALE_FACTOR} and pin
 * {@code QT_FONT_DPI} around a Qt5/X11 double-scaling bug; Swing needs no such thing, which
 * is one of the reasons for the rewrite.</p>
 *
 * <p>Wiring lives here too, and only here: this builds the {@link AppContext}, registers the
 * window types with it and opens the main window. No window reaches for a service it was not
 * given, which is the rule PLAN.md §5 asks for and the reason lazy window creation works at
 * all.</p>
 */
public final class Pdp11Gui {
	private Pdp11Gui() {
	}

	public static void main(String[] args) {
		configurePlatform();

		UiLogger logger = new UiLogger();
		//-- Covers the reader and command threads of PLAN.md section 1, which is where an
		//-- escaping exception would otherwise vanish silently.
		Thread.setDefaultUncaughtExceptionHandler((t, x) -> reportFatal(logger, t, x));

		EventQueue.invokeLater(() -> {
			installLookAndFeel();
			AppContext context = AppContext.create(logger);
			registerWindows(context);
			//-- Before the main window, so its Windows menu has the device groups in it the
			//-- first time it is opened.
			MachineDescriptionStore.installAndLoad(context);
			logger.log(LogChannel.OTHER, "PDP11GUI starting on Java " + Runtime.version());
			logger.log(LogChannel.OTHER, "Settings: " + context.getSettingsStore().getFile());
			new MainWindow(context).setVisible(true);
		});
	}

	/**
	 * Say how to build each kind of window.
	 *
	 * <p>The one place that knows the whole set. {@code WindowManager} knows about no window in
	 * particular, which is what lets every window depend on it rather than the other way round,
	 * and none of them are built until something asks for one.</p>
	 */
	private static void registerWindows(AppContext context) {
		LogWindow.register(context);
		MemoryWindow.register(context);
		ExecutionWindow.register(context);
		DisassemblerWindow.register(context);
		RegisterGroupWindow.register(context);
		BitfieldsWindow.register(context);
		IoPageScannerWindow.register(context);
		MemoryTestWindow.register(context);
		MemoryDumperWindow.register(context);
		MemoryLoaderWindow.register(context);
		AssemblerWindow.register(context);
	}

	/**
	 * System properties that must be set before the first AWT class loads.
	 */
	private static void configurePlatform() {
		//-- Fractional HiDPI scaling. Swing does this natively; see PLAN.md "Why Swing".
		System.setProperty("sun.java2d.uiScale.enabled", "true");

		if(isMacOs()) {
			//-- On macOS the menu bar belongs to the screen and follows the focused window,
			//-- which is the platform-correct behaviour for a multi-window app like this one.
			System.setProperty("apple.laf.useScreenMenuBar", "true");
			System.setProperty("apple.awt.application.name", "PDP11GUI");
		}
	}

	/**
	 * Darcula, FlatLaf's dark theme.
	 *
	 * <p>The terminal was always a dark glass TTY - {@code GlassTerminalView} paints itself
	 * {@code 0x121214} whatever is around it - so a light frame around it was the odd part. The
	 * colours that carry meaning are in {@link to.etc.pdp11.ui.UiColors}, tuned for this
	 * background; nothing else in the UI names a colour.</p>
	 */
	private static void installLookAndFeel() {
		try {
			UIManager.setLookAndFeel(new FlatDarculaLaf());
		} catch(Exception x) {
			//-- Not fatal: the cross-platform L&F is ugly but works, and a missing theme is no
			//-- reason to refuse to start.
			System.err.println("Could not install FlatLaf, falling back to the default: " + x);
		}
	}

	private static boolean isMacOs() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	private static void reportFatal(UiLogger logger, Thread thread, Throwable x) {
		logger.log(LogChannel.OTHER, "Uncaught exception on thread '" + thread.getName() + "': " + x);
		System.err.println("Uncaught exception on thread '" + thread.getName() + "':");
		x.printStackTrace();
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
			x.getClass().getSimpleName() + ": " + x.getMessage(),
			"Unexpected error", JOptionPane.ERROR_MESSAGE));
	}
}
