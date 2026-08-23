package to.etc.pdp11.ui.settings;

import to.etc.pdp11.core.conn.ConnectionProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the application remembers between runs.
 *
 * <p>Replaces {@code TJH_Registry} ({@code JH_Utilities.pas:46-130}), which descends from
 * {@code TRegistry} and is overloaded <b>per widget type</b> - {@code Save(TComboBox)},
 * {@code Save(TCheckBox)}, {@code Save(TEdit)}, {@code Save(TPageControl)} and five more - with
 * a {@code Loading} re-entrancy flag ({@code :61}) that exists because loading a control fires
 * its {@code OnChange}, which saves it again. None of that survives: settings are a typed object
 * here, and widgets are told what it says rather than being asked what they hold.</p>
 *
 * <p>A mutable class rather than a record, because that is what a settings tree is - things get
 * changed one at a time by whoever owns them. The fields are package-visible for the binder and
 * reached through accessors by everyone else.</p>
 *
 * <h2>Who owns it</h2>
 *
 * <p><b>The event thread, and nothing else.</b> It is unsynchronized, it is edited from the
 * settings dialog and from every window that remembers where it was, and {@code saveSettings()}
 * hands the whole object to Gson to walk. A worker that writes one field into it while the EDT
 * is doing any of that is a data race over a whole object tree, for the sake of one string. The
 * connect worker used to do exactly that with {@link #setLastProfileName} (FABLE-ISSUES #44);
 * it marshals with {@code AppContext.onUi} now, and so must anything else off the EDT.</p>
 */
public final class Settings {
	/**
	 * The shape of this file.
	 *
	 * <p>Versioned from day one, per PLAN.md §4, because the alternative is guessing later what
	 * an old file meant. Nothing migrates yet - there is only one version - but the field being
	 * there is what makes the first migration possible rather than archaeological.</p>
	 */
	public static final int CURRENT_SCHEMA_VERSION = 1;

	private int schemaVersion = CURRENT_SCHEMA_VERSION;

	/** Window geometry, keyed by {@code WindowKey.toStorageKey()}. */
	private Map<String, WindowGeometry> windows = new LinkedHashMap<>();

	/** Saved connection profiles, in the order the user put them in. */
	private List<ConnectionProfile> profiles = new ArrayList<>();

	/** The name of the profile to offer first. Null means "the first one", or the default. */
	private String lastProfileName;

	/**
	 * The MACRO-11 source the assembler window had open last, or null.
	 *
	 * <p>Replaces {@code TheRegistry.Load('SourceFilename')} ({@code FormMacro11SourceU.pas:205}).
	 * A path that no longer exists is not an error - the window opens empty and says so.</p>
	 */
	private String lastSourceFile;

	/**
	 * The microcode listing the Microcode window had open last, or null for the packaged one.
	 *
	 * <p>Replaces {@code TheRegistry.Load('MicroCodeListingFilePattern')}
	 * ({@code FormMicroCodeU.pas:104}), which holds a wildcard rather than a file. A path that no
	 * longer exists is not an error: the window says so and falls back to the listing shipped
	 * with the application.</p>
	 */
	private String lastMicrocodeFile;

	public String getLastMicrocodeFile() {
		return lastMicrocodeFile;
	}

	public void setLastMicrocodeFile(String lastMicrocodeFile) {
		this.lastMicrocodeFile = lastMicrocodeFile;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}

	public WindowGeometry getWindowGeometry(String storageKey) {
		return windows().get(storageKey);
	}

	public void setWindowGeometry(String storageKey, WindowGeometry geometry) {
		windows().put(storageKey, geometry);
	}

	public Map<String, WindowGeometry> windows() {
		//-- Gson leaves a field null when the JSON has no such key, which is exactly what an
		//-- older or hand-edited file looks like. Repairing it here rather than trusting the
		//-- file means nothing downstream has to null-check a collection.
		if(windows == null)
			windows = new LinkedHashMap<>();
		return windows;
	}

	public List<ConnectionProfile> profiles() {
		if(profiles == null)
			profiles = new ArrayList<>();
		return profiles;
	}

	public String getLastProfileName() {
		return lastProfileName;
	}

	public String getLastSourceFile() {
		return lastSourceFile;
	}

	public void setLastSourceFile(String lastSourceFile) {
		this.lastSourceFile = lastSourceFile;
	}

	public void setLastProfileName(String lastProfileName) {
		this.lastProfileName = lastProfileName;
	}

	/**
	 * The profile to connect with, which is always something.
	 *
	 * <p>A fresh installation has no profiles and no last-used name, and offering nothing at all
	 * would mean a first run where the only working action is to open the settings dialog. The
	 * default is SimH launched by us, which needs nothing but SimH.</p>
	 */
	public ConnectionProfile currentProfile() {
		List<ConnectionProfile> list = profiles();
		if(lastProfileName != null) {
			for(ConnectionProfile p : list) {
				if(lastProfileName.equals(p.name()))
					return p;
			}
		}
		return list.isEmpty() ? ConnectionProfile.defaultProfile() : list.get(0);
	}

	/** Add or replace by name, and remember it as the one last used. */
	public void putProfile(ConnectionProfile profile) {
		List<ConnectionProfile> list = profiles();
		for(int i = 0; i < list.size(); i++) {
			if(list.get(i).name().equals(profile.name())) {
				list.set(i, profile);
				lastProfileName = profile.name();
				return;
			}
		}
		list.add(profile);
		lastProfileName = profile.name();
	}
}
