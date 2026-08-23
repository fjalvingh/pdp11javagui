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
	MEMORY("Memory", true),
	ASSEMBLER("Assembler"),
	EXECUTION("Execution control"),
	DISASSEMBLER("Disassembler"),
	BITFIELDS("Bitfields"),
	MEMORY_TEST("Memory test"),
	MEMORY_DUMPER("Memory dumper"),
	MEMORY_LOADER("Memory loader"),
	IO_PAGE_SCANNER("I/O page scanner"),
	MMU("MMU"),
	MICROCODE("Microcode"),
	NUMBER_CONVERTER("Number converter"),
	REGISTER_GROUP("Registers", true),
	/**
	 * SimH's {@code sim>} channel, with a command line on it.
	 *
	 * <p>One window where the Pascal has two. {@code FormSimhConsoleU} showed the emulated
	 * machine's own console, which is the main window's terminal here, and
	 * {@code FormSimhRemoteLogU} showed a transcript of the {@code sim>} protocol - which is all
	 * that is left to have a window of its own.</p>
	 */
	SIMH_CONSOLE("SimH console");

	private final String m_title;

	private final boolean m_multiple;

	WindowType(String title) {
		this(title, false);
	}

	WindowType(String title, boolean multiple) {
		m_title = title;
		m_multiple = multiple;
	}

	/** The window's title, before any instance name is added to it. */
	public String getTitle() {
		return m_title;
	}

	/**
	 * Whether there can be more than one of these, each with its own {@code instanceId}.
	 *
	 * <p>Memory views, because looking at two parts of memory at once is the ordinary way to use
	 * one; register groups, because the machine description creates one per group and the Pascal
	 * builds them the same way ({@code FormMainU.pas:608-645}).</p>
	 */
	public boolean isMultiple() {
		return m_multiple;
	}
}
