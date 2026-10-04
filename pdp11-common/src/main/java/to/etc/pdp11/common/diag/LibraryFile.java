package to.etc.pdp11.common.diag;

import java.time.LocalDate;
import java.util.List;

/**
 * One program in the local library: one name with one content, wherever it was found.
 *
 * <p>The same {@code ZRLGE0.BIC} turns up on a dozen images, almost always identical; it is one
 * entry here, with every medium it was on. A copy whose content differs is a separate entry - an
 * older revision shipped under the same name, or a damaged copy - stored beside the first rather
 * than over it.</p>
 *
 * @param name     {@code NAME.EXT}
 * @param digest   SHA-256 of the significant content, hex; what makes two copies the same
 * @param size     bytes as stored
 * @param date     the date the medium gave, or null
 * @param path     where the file is, relative to the library root
 * @param damaged  whether the copy is known to be incomplete
 * @param foundOn  the media it was found on, by {@link LibraryMedium#key()}
 */
public record LibraryFile(String name, String digest, long size, LocalDate date, String path, boolean damaged,
	List<String> foundOn) {
	public LibraryFile {
		foundOn = List.copyOf(foundOn);
	}

	/** The name's extension, upper case: {@code BIC}. */
	public String extension() {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}
}
