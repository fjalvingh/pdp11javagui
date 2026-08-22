package to.etc.pdp11.ui.window;

/**
 * Which tool window this is.
 *
 * <p>Replaces identifying windows <b>by caption</b>, which is what {@code ChildFormByCaption}
 * does today ({@code FormMainU.pas:993-1005}): it runs {@code String2ID} over the part of the
 * caption before {@code " - "} and compares that. Captions are for people; two windows that
 * happen to be titled the same thing are then the same window, and renaming one in the UI
 * changes program behaviour.</p>
 */
public enum WindowType {
	LOG("Log"),
	TERMINAL("Terminal"),
	MEMORY("Memory"),
	EXECUTION("Execution control"),
	DISASSEMBLER("Disassembler"),
	REGISTER_GROUP("Registers"),
	SIMH_CONSOLE("SimH console"),
	SIMH_REMOTE_LOG("SimH remote console log");

	private final String m_title;

	WindowType(String title) {
		m_title = title;
	}

	/** The window's title, before any instance name is added to it. */
	public String getTitle() {
		return m_title;
	}
}
