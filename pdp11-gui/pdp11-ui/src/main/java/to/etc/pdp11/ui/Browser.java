package to.etc.pdp11.ui;

import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Opening a web page in whatever browser this machine has.
 *
 * <h2>Why this is not one call to {@code Desktop.browse}</h2>
 *
 * <p>Because on Linux that call is frequently unavailable, and says so before it is even tried:
 * {@code Desktop.isSupported(BROWSE)} answers <b>false</b> on a perfectly ordinary KDE desktop
 * with {@code xdg-open}, {@code gio} and a default browser all present and working. The JDK's X11
 * desktop peer reports BROWSE only when it can bind the GNOME URL-showing entry point, so on a
 * machine that is not GNOME the answer is no - and the honest-looking "no browser could be opened
 * here" that follows is simply wrong.</p>
 *
 * <p>So {@code Desktop} is the first thing tried and not the only one. After it comes the
 * platform's own "open this" program, which is what actually works: {@code xdg-open} on Linux,
 * {@code open} on macOS, {@code url.dll} on Windows. Only when every one of those has failed is
 * there really nothing here that opens a web page.</p>
 *
 * <p>Every attempt and every failure goes to the log, because "the manual did not open" is
 * otherwise a report with nothing behind it.</p>
 */
public final class Browser {
	/**
	 * How long to wait for a launcher before deciding it has worked.
	 *
	 * <p>The failures are immediate - the program is not installed, or it exits 3 because it
	 * cannot find a handler. What a <i>working</i> launcher does varies: {@code xdg-open} usually
	 * returns at once, but where it execs the browser rather than detaching it, it does not return
	 * until the browser does. So a launcher still running after this long is taken to be doing its
	 * job, and is left alone.</p>
	 */
	private static final long LAUNCHER_TIMEOUT_MS = 3000;

	private Browser() {
	}

	/**
	 * Show this URL to the user, however this machine can. Answers whether anything took it.
	 *
	 * <p>Blocking, for up to {@link #LAUNCHER_TIMEOUT_MS} per attempt: call it from a worker, not
	 * from the event thread.</p>
	 */
	public static boolean open(String url, Logger logger) {
		return openWithDesktop(url, logger) || openWithLauncher(url, logger);
	}

	private static boolean openWithDesktop(String url, Logger logger) {
		try {
			if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				//-- Not a failure, and common: see the class comment.
				logger.log(LogChannel.OTHER, "Desktop.browse is not offered here; trying the platform launcher");
				return false;
			}
			Desktop.getDesktop().browse(URI.create(url));
			return true;
		} catch(Exception x) {
			logger.log(LogChannel.OTHER, "Desktop.browse failed: %s", x);
			return false;
		}
	}

	private static boolean openWithLauncher(String url, Logger logger) {
		for(List<String> command : launcherCommands(System.getProperty("os.name", ""), url)) {
			if(run(command, logger))
				return true;
		}
		return false;
	}

	/** Start one launcher and find out whether it got anywhere. */
	private static boolean run(List<String> command, Logger logger) {
		try {
			Process p = new ProcessBuilder(command)
				//-- Nothing reads either stream, and a launcher that fills a pipe nobody drains
				//-- would block for good.
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			if(!p.waitFor(LAUNCHER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				logger.log(LogChannel.OTHER, "%s is still running, so it has the page", command.get(0));
				return true;
			}
			if(p.exitValue() == 0)
				return true;
			logger.log(LogChannel.OTHER, "%s exited %d", command.get(0), p.exitValue());
			return false;
		} catch(IOException x) {
			//-- Almost always "no such file": this launcher is not installed. The next one may be.
			logger.log(LogChannel.OTHER, "Cannot run %s: %s", command.get(0), x.getMessage());
			return false;
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
			//-- The launcher was started and is still going; whether it opened anything is no
			//-- longer knowable here, and claiming failure would put a dialog on the screen on top
			//-- of a browser that is coming up.
			return true;
		}
	}

	/**
	 * What to try, in order, on a given platform.
	 *
	 * <p>Takes the OS name rather than reading it, so that all three platforms can be checked from
	 * a test running on any one of them - the same reason {@code ConfigDir} takes its environment
	 * as an argument. Two of the three cannot be verified by running here.</p>
	 *
	 * <p>Windows gets {@code url.dll} rather than {@code cmd /c start}, whose first quoted argument
	 * is the window title and whose {@code &amp;} handling would mangle a URL with a query in it.</p>
	 */
	static List<List<String>> launcherCommands(String osName, String url) {
		String os = osName.toLowerCase(Locale.ROOT);
		if(os.contains("win"))
			return List.of(List.of("rundll32", "url.dll,FileProtocolHandler", url));
		if(os.contains("mac") || os.contains("darwin"))
			return List.of(List.of("open", url));
		//-- xdg-open is the standard and is what a desktop installs; gio is what GNOME-derived
		//-- systems have when xdg-utils is not installed at all.
		return List.of(List.of("xdg-open", url), List.of("gio", "open", url));
	}
}
