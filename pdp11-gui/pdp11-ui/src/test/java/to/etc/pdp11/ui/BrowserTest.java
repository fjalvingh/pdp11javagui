package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Browser} will try, per platform.
 *
 * <p>The Linux list is the whole point of the class. {@code Desktop.isSupported(BROWSE)} answers
 * false on an ordinary KDE desktop that has {@code xdg-open}, {@code gio} and a default browser,
 * so a {@code Desktop}-only implementation tells the user there is no browser on a machine that is
 * running one. That cannot be caught by a test of {@code Desktop} - it depends on the desktop
 * doing the running - so what is held down here is that something else is always tried after
 * it.</p>
 */
class BrowserTest {
	private static final String URL = "https://github.com/fjalvingh/pdp11javagui/blob/main/manual/README.md";

	@Test
	void linuxTriesXdgOpenAndThenGio() {
		assertEquals(List.of(List.of("xdg-open", URL), List.of("gio", "open", URL)),
			Browser.launcherCommands("Linux", URL));
	}

	@Test
	void macOsOpensWithOpen() {
		assertEquals(List.of(List.of("open", URL)), Browser.launcherCommands("Mac OS X", URL));
	}

	/**
	 * Windows goes through {@code url.dll} and not {@code cmd /c start}, whose first quoted
	 * argument is the window title and whose {@code &} handling would mangle a URL with a query
	 * in it.
	 */
	@Test
	void windowsOpensWithUrlDll() {
		assertEquals(List.of(List.of("rundll32", "url.dll,FileProtocolHandler", URL)),
			Browser.launcherCommands("Windows 11", URL));
	}

	/** An OS nobody anticipated gets the Unix answer rather than an empty list. */
	@Test
	void anUnknownPlatformStillTriesSomething() {
		assertFalse(Browser.launcherCommands("Haiku", URL).isEmpty());
	}

	/** Whatever is tried, the URL reaches it unmangled and as one argument. */
	@Test
	void theUrlIsAlwaysPassedWhole() {
		for(String os : List.of("Linux", "Mac OS X", "Windows 11", "SunOS")) {
			for(List<String> command : Browser.launcherCommands(os, URL)) {
				assertTrue(command.contains(URL), os + ": " + command);
			}
		}
	}
}
