package to.etc.pdp11.app;

import com.formdev.flatlaf.FlatLightLaf;
import to.etc.pdp11.ui.MainWindow;

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
 */
public final class Pdp11Gui {
	private Pdp11Gui() {
	}

	public static void main(String[] args) {
		configurePlatform();

		//-- Covers the reader and command threads of PLAN.md section 1, which is where an
		//-- escaping exception would otherwise vanish silently. It does NOT cover the EDT:
		//-- EventDispatchThread catches Throwable itself and just prints it. Catching EDT
		//-- exceptions needs a custom EventQueue, which arrives with the Log window in phase 5.
		Thread.setDefaultUncaughtExceptionHandler((t, x) -> reportFatal(t, x));

		EventQueue.invokeLater(() -> {
			installLookAndFeel();
			new MainWindow().setVisible(true);
		});
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

	private static void installLookAndFeel() {
		try {
			UIManager.setLookAndFeel(new FlatLightLaf());
		} catch(Exception x) {
			//-- Not fatal: the cross-platform L&F is ugly but works, and a missing theme is no
			//-- reason to refuse to start.
			System.err.println("Could not install FlatLaf, falling back to the default: " + x);
		}
	}

	private static boolean isMacOs() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	private static void reportFatal(Thread thread, Throwable x) {
		System.err.println("Uncaught exception on thread '" + thread.getName() + "':");
		x.printStackTrace();
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
			x.getClass().getSimpleName() + ": " + x.getMessage(),
			"Unexpected error", JOptionPane.ERROR_MESSAGE));
	}
}
