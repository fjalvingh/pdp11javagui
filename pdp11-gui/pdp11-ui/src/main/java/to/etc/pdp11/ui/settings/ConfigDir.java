package to.etc.pdp11.ui.settings;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Where this application's files belong on each platform.
 *
 * <p>Replaces the Windows registry the Pascal writes to through {@code TJH_Registry}
 * ({@code JH_Utilities.pas:46-130}), which is not a thing on the two platforms this now has to
 * run on. PLAN.md §4: JSON under the platform config dir.</p>
 *
 * <p>Pure, and takes the environment as arguments, so all three platforms can be checked from a
 * test running on any one of them - which matters, because two of the three cannot be verified
 * by running here.</p>
 */
public final class ConfigDir {
	/** The directory name, used under whichever base the platform prescribes. */
	public static final String APP_DIR = "pdp11gui";

	private ConfigDir() {
	}

	/** Configuration for the machine running this. */
	public static Path config() {
		return config(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home", "."));
	}

	/**
	 * Configuration, worked out from an environment rather than from the one we are in.
	 *
	 * <ul>
	 * <li><b>Windows</b>: {@code %APPDATA%\pdp11gui}.</li>
	 * <li><b>macOS</b>: {@code ~/Library/Application Support/pdp11gui}.</li>
	 * <li><b>Everything else</b>: {@code $XDG_CONFIG_HOME/pdp11gui}, or {@code ~/.config/pdp11gui}
	 *     when the variable is unset - which is what the XDG base directory specification says to
	 *     do, and what every other Linux application does.</li>
	 * </ul>
	 */
	public static Path config(String osName, java.util.Map<String, String> env, String userHome) {
		String os = osName.toLowerCase(Locale.ROOT);
		if(os.contains("win")) {
			String appData = env.get("APPDATA");
			if(appData != null && !appData.isBlank())
				return Path.of(appData, APP_DIR);
			return Path.of(userHome, "AppData", "Roaming", APP_DIR);
		}
		if(os.contains("mac") || os.contains("darwin"))
			return Path.of(userHome, "Library", "Application Support", APP_DIR);
		String xdg = env.get("XDG_CONFIG_HOME");
		if(xdg != null && !xdg.isBlank())
			return Path.of(xdg, APP_DIR);
		return Path.of(userHome, ".config", APP_DIR);
	}

	/**
	 * Where working files go - SimH's generated configuration, temporary listings.
	 *
	 * <p>Separate from the configuration directory on purpose: one of these is worth backing up
	 * and the other is worth deleting. On Linux that is the XDG state directory; elsewhere there
	 * is no such distinction and both live together.</p>
	 */
	public static Path data() {
		return data(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home", "."));
	}

	public static Path data(String osName, java.util.Map<String, String> env, String userHome) {
		String os = osName.toLowerCase(Locale.ROOT);
		if(os.contains("win") || os.contains("mac") || os.contains("darwin"))
			return config(osName, env, userHome).resolve("data");
		String xdg = env.get("XDG_STATE_HOME");
		if(xdg != null && !xdg.isBlank())
			return Path.of(xdg, APP_DIR);
		return Path.of(userHome, ".local", "state", APP_DIR);
	}
}
