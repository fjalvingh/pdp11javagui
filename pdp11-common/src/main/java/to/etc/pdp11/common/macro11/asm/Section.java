package to.etc.pdp11.common.macro11.asm;

/**
 * A program section: the absolute section, or a {@code .PSECT}/{@code .CSECT}.
 *
 * <p>An absolute section's offsets are addresses. A relocatable one's are not until the
 * assembly is over: with no linker behind this assembler, the relocatable sections are placed
 * one after the other from {@link AssemblerOptions#relocationBase()} once their sizes are known,
 * and only then does a label in one of them have an address.</p>
 */
final class Section {
	/** The attributes a {@code .PSECT} can give a section. */
	record Attributes(boolean relocatable, boolean readOnly, boolean data, boolean global, boolean overlay,
		boolean saved) {
		static final Attributes ABSOLUTE = new Attributes(false, false, false, true, true, false);

		static final Attributes PSECT_DEFAULT = new Attributes(true, false, false, false, false, false);

		static final Attributes CSECT_DEFAULT = new Attributes(true, false, false, true, true, false);
	}

	private final String m_name;

	private final Location m_declaredAt;

	private Attributes m_attributes;

	/** The location counter, as an offset from the start of the section. */
	private int m_location;

	/** The highest offset anything was put at, plus its size. */
	private int m_size;

	/** Where the section ends up; set by placement, and always 0 for an absolute section. */
	private int m_base;

	private boolean m_placed;

	Section(String name, Attributes attributes, Location declaredAt) {
		m_name = name;
		m_attributes = attributes;
		m_declaredAt = declaredAt;
		m_placed = !attributes.relocatable();
	}

	String getName() {
		return m_name;
	}

	/** The name for a message: the blank section has none. */
	String describe() {
		if(!isRelocatable())
			return m_name.equals(". ABS.") ? "the absolute section" : "absolute section " + m_name;
		return m_name.isEmpty() ? "the unnamed section" : "section " + m_name;
	}

	Attributes getAttributes() {
		return m_attributes;
	}

	void setAttributes(Attributes attributes) {
		m_attributes = attributes;
		m_placed = !attributes.relocatable();
	}

	boolean isRelocatable() {
		return m_attributes.relocatable();
	}

	Location getDeclaredAt() {
		return m_declaredAt;
	}

	int getLocation() {
		return m_location;
	}

	void setLocation(int location) {
		m_location = location & 0xFFFF;
		if(m_location > m_size)
			m_size = m_location;
	}

	/** Move the location counter past {@code count} bytes that were just stored. */
	void advance(int count) {
		setLocation(m_location + count);
	}

	int getSize() {
		return m_size;
	}

	boolean isPlaced() {
		return m_placed;
	}

	int getBase() {
		return m_base;
	}

	void place(int base) {
		m_base = base;
		m_placed = true;
	}

	/** The address of an offset in this section; only once it is placed. */
	int addressOf(int offset) {
		if(!m_placed)
			throw new IllegalStateException(describe() + " has not been placed yet");
		return (m_base + offset) & 0xFFFF;
	}

	@Override
	public String toString() {
		return describe();
	}
}
