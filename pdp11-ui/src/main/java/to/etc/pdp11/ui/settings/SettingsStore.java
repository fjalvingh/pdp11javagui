package to.etc.pdp11.ui.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes {@link Settings} as JSON.
 *
 * <p>PLAN.md §4: a typed settings object tree serialised to JSON under the platform config dir,
 * versioned with a schema field from day one.</p>
 *
 * <h2>Nothing here may stop the application</h2>
 *
 * <p>A settings file is not load-bearing. It can be missing, empty, truncated by a crash, edited
 * by hand into something that is not JSON at all, or written by a version that has not been
 * invented yet. In every one of those cases the right answer is to carry on with defaults and
 * say so - a program that will not start because it cannot remember where its windows were is
 * worse than one that forgets. Gson reports all of them as unchecked exceptions, which is why
 * the catch is as wide as it is rather than naming a parse exception that would cover half.</p>
 *
 * <p>Writes go through a temporary file and an atomic move, so that being interrupted mid-write
 * loses the <i>new</i> settings rather than the old ones.</p>
 */
public final class SettingsStore {
	public static final String FILE_NAME = "settings.json";

	private final Path m_file;

	private final Gson m_gson;

	private Settings m_settings;

	/** What went wrong last time, or {@code null}. Worth showing once in the log. */
	private String m_lastProblem;

	public SettingsStore(Path file) {
		m_file = file;
		m_gson = new GsonBuilder()
			//-- Human-editable on purpose: this is a file a user may well want to look at, and
			//-- diffing it should be possible.
			.setPrettyPrinting()
			//-- Nulls left out rather than written as "field": null, which keeps a fresh file
			//-- short enough to read at a glance.
			.create();
	}

	/** The store for this machine's own configuration directory. */
	public static SettingsStore forThisMachine() {
		return new SettingsStore(ConfigDir.config().resolve(FILE_NAME));
	}

	public Path getFile() {
		return m_file;
	}

	/**
	 * What went wrong reading the file, or {@code null}.
	 *
	 * <p>Loads first if nothing has been loaded yet, because otherwise this answers "nothing" for
	 * the only reason anyone asks it - a caller checking on the way up, before anything has
	 * touched the settings, would be told there was no problem and the problem would never be
	 * reported at all.</p>
	 */
	public String getLastProblem() {
		get();
		return m_lastProblem;
	}

	/** The settings, loading them the first time they are asked for. Never null. */
	public Settings get() {
		if(m_settings == null)
			m_settings = load();
		return m_settings;
	}

	private Settings load() {
		m_lastProblem = null;
		if(!Files.isReadable(m_file))
			return new Settings();                          // a first run, which is not a problem
		try {
			String json = Files.readString(m_file, StandardCharsets.UTF_8);
			Settings s = m_gson.fromJson(json, Settings.class);
			if(s == null) {
				//-- An empty file, or one containing only "null". Both are what an interrupted
				//-- write used to look like before writes became atomic.
				m_lastProblem = "Settings file " + m_file + " is empty; starting from defaults";
				return new Settings();
			}
			if(s.getSchemaVersion() > Settings.CURRENT_SCHEMA_VERSION) {
				//-- Written by a newer version. Reading it anyway would silently drop whatever it
				//-- knows that we do not, and then write that loss back.
				m_lastProblem = "Settings file " + m_file + " is version " + s.getSchemaVersion()
					+ ", newer than this version understands (" + Settings.CURRENT_SCHEMA_VERSION
					+ "); starting from defaults and leaving it alone";
				return new Settings();
			}
			return s;
		} catch(IOException | RuntimeException x) {
			m_lastProblem = "Could not read settings from " + m_file + ": " + x + "; starting from defaults";
			return new Settings();
		}
	}

	/**
	 * Write the settings out.
	 *
	 * @return null if it worked, or why it did not - a caller that cares can say so, and one that
	 *         does not can ignore it, which is the right shape for something called on the way out
	 */
	public String save() {
		if(m_settings == null)
			return null;                                    // nothing was ever loaded or changed
		try {
			Path dir = m_file.getParent();
			if(dir != null)
				Files.createDirectories(dir);
			Path tmp = m_file.resolveSibling(m_file.getFileName() + ".tmp");
			Files.writeString(tmp, m_gson.toJson(m_settings), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, m_file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch(java.nio.file.AtomicMoveNotSupportedException x) {
				//-- Some filesystems cannot; a plain replace is still better than writing in place.
				Files.move(tmp, m_file, StandardCopyOption.REPLACE_EXISTING);
			}
			return null;
		} catch(IOException | RuntimeException x) {
			return "Could not save settings to " + m_file + ": " + x;
		}
	}
}
