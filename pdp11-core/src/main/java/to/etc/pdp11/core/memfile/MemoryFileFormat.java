package to.etc.pdp11.core.memfile;

import java.util.List;

/**
 * The file formats a block of PDP-11 memory can be written to, and read back from.
 *
 * <p>From {@code TMemoryLoaderFormat} ({@code MemoryLoaderU.pas:38-46}). The same list serves the
 * Memory Dumper and the Memory Loader, which is why it is an enum here rather than the Pascal's
 * five classes each instantiated once per window.</p>
 *
 * <p><b>Not here yet:</b> {@code mlfmtBlinkenlightInstructions}, which is marked "only .Save" in
 * the original and generates a page of "set these switches, press LOAD ADDR" instructions from a
 * memory image. It needs {@code BlinkenlightInstructionsU}, which is its own window in this
 * phase; it will be added to this enum with the generator rather than offered as an entry that
 * does not work.</p>
 */
public enum MemoryFileFormat {
	/** Words as little-endian byte pairs, nothing else. The format a ROM burner wants. */
	BYTE_STREAM("Binary byte stream", false, false, false, List.of("Byte stream file")),

	/**
	 * Two files: every word's low byte in one and its high byte in the other.
	 *
	 * <p>Which is how a 16-bit memory built out of 8-bit chips is programmed - one file per
	 * device.</p>
	 */
	LOW_HIGH_BYTE_FILES("Separate low byte / high byte binary files", false, false, false,
		List.of("Low byte file", "High byte file")),

	/** {@code "addr: value value value ..."}, eight values a line. Readable, and diffable. */
	TEXT_ONE_ADDR_PER_LINE("Text file, one octal address and its values per line", true, false, true,
		List.of("Text file")),

	/**
	 * DEC Standard Absolute Paper Tape Format, the thing a real PDP-11's absolute loader reads.
	 *
	 * <p>Blocks of {@code 01 00 <size> <start> <data...> <checksum>}, and a final zero-length
	 * block whose address is where to start executing - which is why this format has an entry
	 * address and the others do not.</p>
	 */
	ABSOLUTE_PAPERTAPE("DEC standard absolute paper tape", false, true, true, List.of("Image file"));

	private final String m_label;

	private final boolean m_text;

	private final boolean m_entryAddress;

	private final boolean m_ownAddresses;

	private final List<String> m_filePrompts;

	MemoryFileFormat(String label, boolean text, boolean entryAddress, boolean ownAddresses,
		List<String> filePrompts) {
		m_label = label;
		m_text = text;
		m_entryAddress = entryAddress;
		m_ownAddresses = ownAddresses;
		m_filePrompts = filePrompts;
	}

	public String getLabel() {
		return m_label;
	}

	/** Whether the result is worth opening in an editor afterwards. */
	public boolean isText() {
		return m_text;
	}

	/** Whether the format records where to start executing. */
	public boolean hasEntryAddress() {
		return m_entryAddress;
	}

	/**
	 * Whether the file says where its data goes.
	 *
	 * <p>{@code StartAddrDefined} ({@code MemoryLoaderU.pas:68}). A byte stream is just bytes and
	 * has to be told where to load; a text listing and a paper tape carry their own addresses, so
	 * a start address entered beside them would be ignored - and a field that is ignored should
	 * not be offered.</p>
	 */
	public boolean definesOwnAddresses() {
		return m_ownAddresses;
	}

	/** One prompt per file this format needs; most need one, the byte-split format needs two. */
	public List<String> getFilePrompts() {
		return m_filePrompts;
	}

	public int getFileCount() {
		return m_filePrompts.size();
	}

	/** The extension to offer in a save dialog. */
	public String getDefaultExtension() {
		return switch(this) {
			case TEXT_ONE_ADDR_PER_LINE -> "txt";
			case ABSOLUTE_PAPERTAPE -> "ptap";
			default -> "bin";
		};
	}

	@Override
	public String toString() {
		return m_label;
	}
}
