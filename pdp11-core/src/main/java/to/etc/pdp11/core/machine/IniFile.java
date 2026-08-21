package to.etc.pdp11.core.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Windows {@code .ini} file, as the machine descriptions use one.
 *
 * <p>Stands in for Delphi's {@code TMemIniFile} ({@code MemoryCellU.pas:685}), but only the
 * parts that matter here, and with one difference that has to be right: <b>a {@code ;} only
 * starts a comment at the beginning of a line</b>. Mid-line it is data, because it is the
 * machine descriptions' own field separator:</p>
 *
 * <pre>
 * ; this whole line is a comment
 * RCSR= 177560 ;"Receiver Control/Status Register";Bits.SLU.RCSR
 * </pre>
 *
 * <p>Keys are kept in file order and are <b>not</b> deduplicated: a section may legitimately
 * repeat a key, and the machine description convention is that a later line overrides an
 * earlier one - which is what the module libraries tell you to rely on ({@code pdp11.ini:19-23}:
 * "If you want to override settings in the Module_xxx() macros, place your definitions AFTER
 * the macro call"). Deduplicating on read would silently drop the override.</p>
 */
public final class IniFile {
	/** One {@code key=value} line, with the line number for error messages. */
	public record Entry(String key, String value, int lineNr) {
	}

	/** One {@code [name]} section and the entries under it. */
	public record Section(String name, List<Entry> entries, int lineNr) {
		public String findLast(String key) {
			String found = null;
			for(Entry e : entries) {
				if(e.key().equalsIgnoreCase(key))
					found = e.value();
			}
			return found;
		}
	}

	private final List<Section> m_sections = new ArrayList<>();

	public List<Section> getSections() {
		return m_sections;
	}

	public Section findSection(String name) {
		for(Section s : m_sections) {
			if(s.name().equalsIgnoreCase(name))
				return s;
		}
		return null;
	}

	/**
	 * Parse. Lines before the first {@code [section]} are ignored, as they are in every ini
	 * reader; the machine descriptions put their file header comments there.
	 */
	public static IniFile parse(String text) {
		IniFile ini = new IniFile();
		Map<String, Section> byName = new LinkedHashMap<>();
		Section current = null;
		int lineNr = 0;

		for(String raw : text.split("\r\n|\n|\r", -1)) {
			lineNr++;
			String line = raw.strip();
			if(line.isEmpty() || line.charAt(0) == ';' || line.charAt(0) == '#')
				continue;

			if(line.charAt(0) == '[') {
				int end = line.indexOf(']');
				if(end < 0)
					throw new IllegalArgumentException("line " + lineNr + ": section header has no closing ']': " + line);
				String name = line.substring(1, end).strip();
				//-- A repeated section header continues the existing section, which is how
				//-- TMemIniFile behaves and how an override block after a macro call works.
				current = byName.get(name.toLowerCase());
				if(current == null) {
					current = new Section(name, new ArrayList<>(), lineNr);
					byName.put(name.toLowerCase(), current);
					ini.m_sections.add(current);
				}
				continue;
			}

			if(current == null)
				continue;                                       // header text before any section

			int eq = line.indexOf('=');
			if(eq < 0)
				throw new IllegalArgumentException("line " + lineNr + ": expected 'key = value', got: " + line);
			current.entries().add(new Entry(
				line.substring(0, eq).strip(),
				line.substring(eq + 1).strip(),
				lineNr));
		}
		return ini;
	}

	/**
	 * Strip one surrounding pair of double quotes, if present. The descriptions quote some
	 * info strings and not others - {@code Info=CPU Register} next to
	 * {@code Info="Emulex SC31 ..."} - so this has to tolerate both. Ported from
	 * {@code JH_Utilities.StripQuotes}.
	 */
	public static String stripQuotes(String s) {
		String t = s.strip();
		if(t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"')
			return t.substring(1, t.length() - 1);
		return t;
	}
}
