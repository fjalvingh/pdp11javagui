package to.etc.pdp11.common.diag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every PDP-11 diagnostic DEC knew of in 1990, read from DEC's own index.
 *
 * <p>The source is {@code AH-FG66P-MC}, DEC's "Package Content Report" of August 1990 (bitsavers
 * {@code pdf/dec/pdp11/xxdp/AH-FG66P-MC_pdp11DiagIdx_Jun92.txt}), shipped verbatim as a resource and
 * parsed when first asked for. It lists every diagnostic media package - DYDP floppies, DDDP TU58s,
 * MMDP tapes and the rest - and under each the diagnostics it holds, each with its revision and a
 * title. That is a list of what exists, not of what anybody still has, which is what makes it the
 * right thing to embed: it names what a collected file is, and it costs nobody a copyright.</p>
 *
 * <p>The report's lines are tab-separated, which is what makes them parseable at all:</p>
 *
 * <pre>
 *   CZYBZ		Q	CZYBZQ0 DDDP52 V2 RL02        a media package: id, revision, title
 * 	ZRLG	E0	CZRLGE0 RL11/RLV11/CTRL1          a diagnostic in it: id, revision, title
 * </pre>
 *
 * <p>Titles usually start with the full part number; that is taken off. A handful of files have
 * names that are not their numbers - XXDP V2.5 called its monitor {@code XXDPXM.SYS} rather than
 * {@code HMXMF0.SYS} - and those are listed in {@code catalog-aliases.txt} beside the index.</p>
 */
public final class DiagnosticCatalog {
	public static final String INDEX_RESOURCE = "AH-FG66P-MC_pdp11DiagIdx_Jun92.txt";

	public static final String ALIAS_RESOURCE = "catalog-aliases.txt";

	private static final Pattern PACKAGE = Pattern.compile("^\\*?\\s+(C[A-Z0-9]{4})(?:\\t+(\\S?)\\t?(.*))?$");

	private static final Pattern ENTRY = Pattern.compile("^\\t([A-Z0-9]{4})\\t(\\S*)\\t?(.*)$");

	/** A diagnostic's file name: four letters, then a revision letter and a patch digit. */
	private static final Pattern DIAGNOSTIC_NAME = Pattern.compile("^([A-Z][A-Z0-9]{3})([A-Z][0-9])$");

	private static volatile DiagnosticCatalog s_builtIn;

	private final Map<String, CatalogEntry> m_entries;

	private final Map<String, String> m_aliases;

	private DiagnosticCatalog(Map<String, CatalogEntry> entries, Map<String, String> aliases) {
		m_entries = Collections.unmodifiableMap(entries);
		m_aliases = Collections.unmodifiableMap(aliases);
	}

	/** The catalog shipped with the application, parsed once. */
	public static DiagnosticCatalog builtIn() {
		DiagnosticCatalog c = s_builtIn;
		if(c == null) {
			try(InputStream index = resource(INDEX_RESOURCE); InputStream aliases = resource(ALIAS_RESOURCE)) {
				c = parse(new InputStreamReader(index, StandardCharsets.ISO_8859_1),
					new InputStreamReader(aliases, StandardCharsets.ISO_8859_1));
			} catch(IOException x) {
				throw new UncheckedIOException("The built-in diagnostic index cannot be read", x);
			}
			s_builtIn = c;
		}
		return c;
	}

	private static InputStream resource(String name) throws IOException {
		InputStream in = DiagnosticCatalog.class.getResourceAsStream(name);
		if(in == null)
			throw new IOException(name + " is missing from the build");
		return in;
	}

	/**
	 * Read an index in the report's format, and an alias list.
	 *
	 * @param aliases may be null
	 */
	public static DiagnosticCatalog parse(Reader index, Reader aliases) throws IOException {
		Map<String, String> titles = new TreeMap<>();
		Map<String, Set<String>> revisions = new TreeMap<>();
		Map<String, Set<String>> packages = new TreeMap<>();
		Map<String, String> titleRevision = new TreeMap<>();
		String currentPackage = null;
		BufferedReader r = new BufferedReader(index);
		for(String line = r.readLine(); line != null; line = r.readLine()) {
			Matcher p = PACKAGE.matcher(line);
			if(p.matches()) {
				String title = p.group(3) == null ? "" : stripPartNumber(p.group(3).trim(), p.group(1).substring(1));
				currentPackage = title.isEmpty() || title.equals("OBSOLETE") ? null : title;
				continue;
			}
			Matcher e = ENTRY.matcher(line);
			if(!e.matches())
				continue;
			String id = e.group(1);
			String revision = e.group(2);
			String title = stripPartNumber(e.group(3).trim(), id);
			revisions.computeIfAbsent(id, k -> new TreeSet<>());
			if(isRevision(revision))
				revisions.get(id).add(revision);
			if(currentPackage != null)
				packages.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(currentPackage);
			//-- The title of the latest revision that has one: titles were corrected over the years.
			if(!title.isEmpty()) {
				String had = titleRevision.get(id);
				if(had == null || revision.compareTo(had) >= 0) {
					titles.put(id, title);
					titleRevision.put(id, revision);
				}
			}
		}
		Map<String, CatalogEntry> entries = new TreeMap<>();
		for(String id : revisions.keySet()) {
			entries.put(id, new CatalogEntry(id, titles.getOrDefault(id, ""), new ArrayList<>(revisions.get(id)),
				new ArrayList<>(packages.getOrDefault(id, Set.of()))));
		}
		return new DiagnosticCatalog(entries, aliases == null ? Map.of() : parseAliases(aliases));
	}

	/** A revision is a letter and a digit. The report also has the odd {@code [} or {@code ]}. */
	private static boolean isRevision(String s) {
		return s.length() == 2 && Character.isLetter(s.charAt(0)) && Character.isDigit(s.charAt(1));
	}

	/**
	 * "CZRLGE0 RL11/RLV11/CTRL1" is "RL11/RLV11/CTRL1": the leading word goes when it is a part
	 * number containing the id - {@code CZRLGE0}, {@code DFKACA1}, {@code HMTRDPB0} all occur.
	 */
	static String stripPartNumber(String title, String id) {
		int space = title.indexOf(' ');
		String first = space < 0 ? title : title.substring(0, space);
		if(first.contains(id) && first.length() <= id.length() + 4 && first.chars().allMatch(c -> Character.isLetterOrDigit(c)))
			return space < 0 ? "" : title.substring(space + 1).trim();
		return title;
	}

	private static Map<String, String> parseAliases(Reader aliases) throws IOException {
		Map<String, String> map = new TreeMap<>();
		BufferedReader r = new BufferedReader(aliases);
		for(String line = r.readLine(); line != null; line = r.readLine()) {
			String s = line.strip();
			if(s.isEmpty() || s.startsWith("#"))
				continue;
			String[] parts = s.split("\\s+");
			if(parts.length == 2)
				map.put(parts[0].toUpperCase(Locale.ROOT), parts[1].toUpperCase(Locale.ROOT));
		}
		return map;
	}

	public Collection<CatalogEntry> entries() {
		return m_entries.values();
	}

	public Optional<CatalogEntry> get(String id) {
		return Optional.ofNullable(m_entries.get(id));
	}

	/**
	 * What a file found on a medium is, as far as its name says.
	 *
	 * @param fileName {@code ZRLGE0.BIC}, any case
	 */
	public Identification identify(String fileName) {
		String name = fileName.toUpperCase(Locale.ROOT);
		String alias = m_aliases.get(name);
		if(alias != null) {
			CatalogEntry e = m_entries.get(alias);
			return new Identification(alias, "", e, DiagnosticFamily.ofId(alias));
		}
		int dot = name.indexOf('.');
		String base = dot < 0 ? name : name.substring(0, dot);
		Matcher m = DIAGNOSTIC_NAME.matcher(base);
		if(!m.matches())
			return new Identification(null, "", null, DiagnosticFamily.OTHER);
		String id = m.group(1);
		CatalogEntry e = m_entries.get(id);
		return new Identification(id, m.group(2), e, DiagnosticFamily.ofId(id));
	}

	/**
	 * What {@link #identify} made of a name.
	 *
	 * @param id       the four-letter diagnostic, or null when the name is not one
	 * @param revision the revision the name carries, or {@code ""}
	 * @param entry    the index's entry, or null when the index does not list it
	 * @param family   what it is for; {@link DiagnosticFamily#OTHER} when nothing says
	 */
	public record Identification(String id, String revision, CatalogEntry entry, DiagnosticFamily family) {
		public String title() {
			return entry == null ? "" : entry.title();
		}
	}
}
