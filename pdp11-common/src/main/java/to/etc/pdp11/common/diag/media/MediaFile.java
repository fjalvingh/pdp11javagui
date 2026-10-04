package to.etc.pdp11.common.diag.media;

import java.time.LocalDate;

/**
 * One file taken off a disk image or a tape: its name as the medium spells it, its date, and its
 * bytes.
 *
 * <p>The bytes are what the medium holds, which for a linked XXDP file is a whole number of
 * 510-byte block payloads. What lies past the end of the program in the last block is whatever was
 * there when it was written, and is kept: the XXDP monitor reads the same bytes.</p>
 *
 * @param name     {@code NAME.EXT} in upper case, without padding; {@code NAME.} has no extension
 * @param date     the creation date the directory gives, or {@code null} when it gives none or one
 *                 that is not a date
 * @param data     the file's contents
 * @param damaged  whether reading it went wrong - a block chain that broke, looped or disagreed
 *                 with the directory - so that what is here is probably not the whole program
 */
public record MediaFile(String name, LocalDate date, byte[] data, boolean damaged) {
	public MediaFile(String name, LocalDate date, byte[] data) {
		this(name, date, data, false);
	}

	public MediaFile {
		if(name == null || name.isBlank())
			throw new IllegalArgumentException("A file needs a name");
		if(data == null)
			throw new IllegalArgumentException("A file needs contents, even empty ones");
	}

	/** The part of the name after the dot, or {@code ""}. */
	public String extension() {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}

	/** The part of the name before the dot. */
	public String baseName() {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(0, dot);
	}

	@Override
	public String toString() {
		return name + " (" + data.length + " bytes" + (damaged ? ", damaged)" : ")");
	}
}
