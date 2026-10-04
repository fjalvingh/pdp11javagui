package to.etc.pdp11.common.diag;

import java.time.LocalDate;
import java.util.List;

/**
 * One medium the library collected: where it came from, what it is, and where its image is kept.
 *
 * @param sourceId    the {@link DiagnosticSource} it was found through
 * @param url         what was downloaded
 * @param volume      the medium inside the download - the download's own name decompressed, or a
 *                    path inside an archive
 * @param imagePath   the stored image, relative to the library root, or {@code ""} when there is
 *                    none (a single program file is kept only as a file)
 * @param description what kind of medium it is
 * @param fileCount   how many files were taken off it
 * @param collected   when
 * @param problems    what went wrong reading it
 */
public record LibraryMedium(String sourceId, String url, String volume, String imagePath, String description,
	int fileCount, LocalDate collected, List<String> problems) {
	public LibraryMedium {
		problems = List.copyOf(problems);
	}

	/** Identifies the medium among all the library holds. */
	public String key() {
		return keyOf(url, volume);
	}

	public static String keyOf(String url, String volume) {
		return url + "!" + volume;
	}
}
