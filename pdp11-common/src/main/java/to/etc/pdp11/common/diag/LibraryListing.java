package to.etc.pdp11.common.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The library as a list a person reads: each program with what DEC's index says it is, in family
 * order, narrowed by what was typed.
 *
 * <p>Here rather than in the window because it is the part of the window that can be wrong - which
 * family a file lands in, whether "rl02 11/34" finds what it should - and a window can only be
 * checked by looking at it.</p>
 */
public final class LibraryListing {
	private LibraryListing() {
	}

	/**
	 * One line of the listing.
	 *
	 * @param file           the program
	 * @param identification what its name says it is
	 */
	public record Row(LibraryFile file, DiagnosticCatalog.Identification identification) {
		public DiagnosticFamily family() {
			return identification.family();
		}

		public String title() {
			return identification.title();
		}

		/** All the text a filter looks in, upper case. */
		String haystack() {
			return (file.name() + " " + nullToEmpty(identification.id()) + " " + title() + " " + family().getLabel())
				.toUpperCase(Locale.ROOT);
		}
	}

	private static final Comparator<Row> ORDER = Comparator.comparing(Row::family)
		.thenComparing(r -> r.file().name())
		.thenComparing(r -> r.file().damaged())
		.thenComparing(r -> r.file().path());

	/** Every file, identified, in family order and by name within a family. */
	public static List<Row> rows(List<LibraryFile> files, DiagnosticCatalog catalog) {
		List<Row> out = new ArrayList<>(files.size());
		for(LibraryFile f : files)
			out.add(new Row(f, catalog.identify(f.name())));
		out.sort(ORDER);
		return out;
	}

	/**
	 * The rows in a family, or all of them, that contain every word of {@code text} somewhere in
	 * their name, diagnostic number, title or family - in any case, in any order.
	 *
	 * @param family null for all families
	 */
	public static List<Row> filter(List<Row> rows, DiagnosticFamily family, String text) {
		String[] words = text == null ? new String[0] : text.strip().toUpperCase(Locale.ROOT).split("\\s+");
		List<Row> out = new ArrayList<>();
		for(Row r : rows) {
			if(family != null && r.family() != family)
				continue;
			String hay = r.haystack();
			boolean all = true;
			for(String w : words) {
				if(!w.isEmpty() && !hay.contains(w)) {
					all = false;
					break;
				}
			}
			if(all)
				out.add(r);
		}
		return out;
	}

	/** How many rows each family has, for the families that have any, in family order. */
	public static Map<DiagnosticFamily, Integer> counts(List<Row> rows) {
		Map<DiagnosticFamily, Integer> out = new EnumMap<>(DiagnosticFamily.class);
		for(Row r : rows)
			out.merge(r.family(), 1, Integer::sum);
		return out;
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
