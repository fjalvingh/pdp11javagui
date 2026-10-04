package to.etc.pdp11.common.macro11.asm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A macro library: an {@code .MLB} or {@code .SML} file as RT-11's {@code LIBR} or RSX's
 * {@code LBR} makes it.
 *
 * <h2>The format</h2>
 *
 * <p>Both systems use the same header, in the first 512-byte block: the word {@code 001001} (a
 * library, of macros), a version, and at byte offsets 032, 034 and 036 the size of a directory
 * entry, the block the directory starts in, and how many entries it has room for. An entry is a
 * name in two RAD50 words and where the module starts, as a block and a byte offset in it; an
 * unused entry is all ones, or all zeroes. Where the two systems differ:</p>
 *
 * <ul>
 *   <li><b>RT-11</b> (version {@code 000500}) counts blocks from 0, and a module is the text of
 *       its macro as it is, lines ending in CR LF, up to where the next module starts.</li>
 *   <li><b>RSX</b> (any other version; it is RAD50, {@code V05} and the like) counts blocks from
 *       1, and a module starts with a header whose first word is its length, 16, and whose words
 *       2 and 3 are the module's size in bytes, header included. The text after it is a series
 *       of records: a length word, that many characters, and a pad byte when the length is odd.
 *       Records do not have line ends; each one is a line.</li>
 * </ul>
 *
 * <p>Nothing in the header says which of the two a file is beyond the version, so an RSX
 * reading is checked against the header of the first module it finds, and a library whose first
 * module does not look like an RSX one is read as RT-11.</p>
 *
 * <h2>Damaged copies</h2>
 *
 * <p>A library copied as if it were text has gained a line end after every 512-byte block -
 * most of the RSX libraries in the trailing-edge archive arrive like that. Such a file is one
 * byte too long per block, with a newline at every 513th position, and is read as it was before
 * the copy.</p>
 */
public final class MacroLibrary {
	/** Which system made a library. */
	public enum Format {
		RT11,
		RSX
	}

	/** The library header's identification: a library, of macros. */
	private static final int LIBRARY_ID = 001001;

	/** The version word of an RT-11 V5 library; RSX ones are RAD50. */
	private static final int RT11_VERSION = 000500;

	private static final int BLOCK = 512;

	/** The length of an RSX module header. */
	private static final int RSX_MODULE_HEADER = 16;

	private final String m_name;

	private final Format m_format;

	/** Macro name to the text of its module, in the order of the directory. */
	private final Map<String, String> m_modules;

	private MacroLibrary(String name, Format format, Map<String, String> modules) {
		m_name = name;
		m_format = format;
		m_modules = Collections.unmodifiableMap(modules);
	}

	/** Read a library from a file. */
	public static MacroLibrary read(Path file) throws IOException {
		return read(file.getFileName().toString(), Files.readAllBytes(file));
	}

	/**
	 * Read a library from its bytes.
	 *
	 * @param name what to call it in messages
	 * @throws IOException when the bytes are not a macro library, with what is wrong with them
	 */
	public static MacroLibrary read(String name, byte[] bytes) throws IOException {
		byte[] data = withoutAddedLineEnds(bytes);
		if(data.length < BLOCK || word(data, 0) != LIBRARY_ID)
			throw new IOException(name + " is not a macro library: it does not start with a library header");

		int entrySize = word(data, 032);
		int directoryBlock = word(data, 034);
		int entries = word(data, 036);
		if(entrySize < 8)
			throw new IOException(name + " is not a macro library: its directory entries are " + entrySize + " bytes");

		if(word(data, 2) != RT11_VERSION) {
			List<Entry> rsx = directory(data, entrySize, directoryBlock, entries, 1);
			if(!rsx.isEmpty() && isRsxModule(data, rsx.get(0).position()))
				return new MacroLibrary(name, Format.RSX, readRsx(name, data, rsx));
		}
		return new MacroLibrary(name, Format.RT11, readRt11(name, data, entrySize, directoryBlock, entries));
	}

	public String getName() {
		return m_name;
	}

	public Format getFormat() {
		return m_format;
	}

	/** The names of the macros in it, in the order of its directory. */
	public List<String> getMacroNames() {
		return List.copyOf(m_modules.keySet());
	}

	/** The text of the module holding macro {@code name}, if there is one. */
	public Optional<String> getText(String name) {
		return Optional.ofNullable(m_modules.get(name));
	}

	@Override
	public String toString() {
		return m_name + " (" + m_format + ", " + m_modules.size() + " macros)";
	}

	// -------------------------------------------------------------------------------------
	// The directory
	// -------------------------------------------------------------------------------------

	/** A directory entry: a name, and where in the file its module starts. */
	private record Entry(String name, int position) {
	}

	/**
	 * The used entries of the directory.
	 *
	 * @param firstBlock the number the file's first block has: 0 for RT-11, 1 for RSX
	 */
	private static List<Entry> directory(byte[] data, int entrySize, int directoryBlock, int entries, int firstBlock) {
		List<Entry> list = new ArrayList<>();
		int start = (directoryBlock - firstBlock) * BLOCK;
		for(int i = 0; i < entries; i++) {
			int at = start + i * entrySize;
			if(at < 0 || at + 8 > data.length)
				break;
			int name1 = word(data, at);
			int name2 = word(data, at + 2);
			if((name1 == 0177777 && name2 == 0177777) || (name1 == 0 && name2 == 0))
				continue;
			int block = word(data, at + 4) & 077777;
			int offset = word(data, at + 6) & 0777;
			String name = (Rad50.decode(name1) + Rad50.decode(name2)).strip();
			list.add(new Entry(name, (block - firstBlock) * BLOCK + offset));
		}
		return list;
	}

	// -------------------------------------------------------------------------------------
	// RT-11
	// -------------------------------------------------------------------------------------

	/**
	 * RT-11: a module is the text from its start to the start of the next one, and the last one
	 * runs to the last byte that is not a NUL.
	 */
	private static Map<String, String> readRt11(String name, byte[] data, int entrySize, int directoryBlock, int entries)
		throws IOException {
		List<Entry> list = directory(data, entrySize, directoryBlock, entries, 0);
		List<Entry> byPosition = new ArrayList<>(list);
		byPosition.sort(Comparator.comparingInt(Entry::position));
		int end = data.length;
		while(end > 0 && data[end - 1] == 0)
			end--;

		Map<String, String> modules = new LinkedHashMap<>();
		for(Entry e : list) {
			if(e.position() < 0 || e.position() >= end)
				throw new IOException(name + ": the module " + e.name() + " is past the end of the file; it is incomplete");
			int next = end;
			for(Entry other : byPosition) {
				if(other.position() > e.position()) {
					next = other.position();
					break;
				}
			}
			modules.putIfAbsent(e.name(), rt11Text(data, e.position(), next));
		}
		return modules;
	}

	/** Text as it is stored, without carriage returns and NULs. */
	private static String rt11Text(byte[] data, int from, int to) {
		StringBuilder sb = new StringBuilder(to - from);
		for(int i = from; i < to; i++) {
			char c = (char) (data[i] & 0xFF);
			if(c != '\r' && c != 0)
				sb.append(c);
		}
		return sb.toString();
	}

	// -------------------------------------------------------------------------------------
	// RSX
	// -------------------------------------------------------------------------------------

	/** Whether an RSX module header starts here. */
	private static boolean isRsxModule(byte[] data, int at) {
		return at >= 0 && at + RSX_MODULE_HEADER <= data.length && word(data, at) == RSX_MODULE_HEADER;
	}

	/** RSX: a module header, then records up to the module's size. */
	private static Map<String, String> readRsx(String name, byte[] data, List<Entry> entries) throws IOException {
		Map<String, String> modules = new LinkedHashMap<>();
		for(Entry e : entries) {
			int at = e.position();
			if(!isRsxModule(data, at))
				throw damaged(name, e, "there is no module header where the directory says it starts");
			int size = (word(data, at + 4) << 16) | word(data, at + 6);
			int end = at + size;
			if(size < RSX_MODULE_HEADER || end > data.length)
				throw damaged(name, e, "it runs past the end of the file");

			StringBuilder text = new StringBuilder();
			int p = at + RSX_MODULE_HEADER;
			while(p + 2 <= end) {
				int length = word(data, p);
				p += 2;
				if(p + length > end)
					throw damaged(name, e, "a line runs past the end of the module");
				for(int i = 0; i < length; i++)
					text.append((char) (data[p + i] & 0xFF));
				text.append('\n');
				p += length + (length & 1);
			}
			modules.putIfAbsent(e.name(), text.toString());
		}
		return modules;
	}

	private static IOException damaged(String library, Entry e, String what) {
		return new IOException(library + ": the module " + e.name() + " is damaged: " + what);
	}

	// -------------------------------------------------------------------------------------
	// Bytes
	// -------------------------------------------------------------------------------------

	private static int word(byte[] data, int at) {
		return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
	}

	/**
	 * The file without the line end a text-mode copy put after every block, if it has them.
	 */
	private static byte[] withoutAddedLineEnds(byte[] data) {
		int stride = BLOCK + 1;
		if(data.length % BLOCK == 0 || data.length % stride != 0)
			return data;
		for(int at = BLOCK; at < data.length; at += stride) {
			if(data[at] != '\n')
				return data;
		}
		byte[] clean = new byte[data.length / stride * BLOCK];
		for(int i = 0; i < data.length / stride; i++)
			System.arraycopy(data, i * stride, clean, i * BLOCK, BLOCK);
		return clean;
	}
}
