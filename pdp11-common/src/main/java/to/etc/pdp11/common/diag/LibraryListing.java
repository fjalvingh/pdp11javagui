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
	 * @param run            whether it can be run on its own; see {@link ProgramCheck}
	 */
	public record Row(LibraryFile file, DiagnosticCatalog.Identification identification, ProgramCheck.Brief run) {
		public Row(LibraryFile file, DiagnosticCatalog.Identification identification) {
			this(file, identification, ProgramCheck.Brief.NONE);
		}

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
		return rows(files, catalog, (f, id) -> ProgramCheck.Brief.NONE);
	}

	/** The same, with whether each can be run worked out by {@code check}. */
	public static List<Row> rows(List<LibraryFile> files, DiagnosticCatalog catalog,
		java.util.function.BiFunction<LibraryFile, DiagnosticCatalog.Identification, ProgramCheck.Brief> check) {
		List<Row> out = new ArrayList<>(files.size());
		for(LibraryFile f : files) {
			DiagnosticCatalog.Identification id = catalog.identify(f.name());
			out.add(new Row(f, id, check.apply(f, id)));
		}
		out.sort(ORDER);
		return out;
	}

	/**
	 * Whether each file in a library can be run, read off the disk.
	 *
	 * <p>Reads only what might be a program - see {@link ProgramCheck} - and treats a file that
	 * cannot be read as not one.</p>
	 */
	public static ProgramCheck.Brief check(DiagnosticLibrary library, LibraryFile file, DiagnosticCatalog.Identification id) {
		String ext = file.extension();
		if(!(ext.equals("BIN") || ext.equals("BIC") || ext.equals("LDA") || ext.equals("SYS")))
			return ProgramCheck.Brief.NONE;
		try {
			byte[] data = java.nio.file.Files.readAllBytes(library.resolve(file.path()));
			return ProgramCheck.check(file.name(), data, file.damaged(), id).brief();
		} catch(java.io.IOException x) {
			return new ProgramCheck.Brief(ProgramCheck.Kind.NOT_A_PROGRAM, "The file cannot be read: " + x.getMessage(), -1);
		}
	}

	/**
	 * The rows in a family, or all of them, that contain every word of {@code text} somewhere in
	 * their name, diagnostic number, title or family - in any case, in any order.
	 *
	 * @param family null for all families
	 */
	public static List<Row> filter(List<Row> rows, DiagnosticFamily family, String text) {
		return filter(rows, family, text, false);
	}

	/**
	 * As {@link #filter(List, DiagnosticFamily, String)}, and only what can be run standalone when
	 * {@code runnableOnly} says so.
	 */
	public static List<Row> filter(List<Row> rows, DiagnosticFamily family, String text, boolean runnableOnly) {
		String[] words = text == null ? new String[0] : text.strip().toUpperCase(Locale.ROOT).split("\\s+");
		List<Row> out = new ArrayList<>();
		for(Row r : rows) {
			if(family != null && r.family() != family)
				continue;
			if(runnableOnly && !r.run().isRunnable())
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
