package to.etc.pdp11.common.diag.media;

import java.util.List;

/**
 * One medium found in a download: a disk image, a tape, or a single program file.
 *
 * <p>A download is usually exactly one of these. A {@code .zip} can hold several, and each is its
 * own volume, named by its path inside the archive.</p>
 *
 * @param name        the medium's file name, decompressed: {@code xxdp25.rl02}, or
 *                    {@code archive.zip/disk1.dsk} for one inside an archive
 * @param description what kind of medium it is, for a person: "RL02 disk, XXDP+ file system"
 * @param image       the medium's bytes, decompressed; what SimH would attach
 * @param files       the files on it; empty when it could not be read
 * @param problems    what went wrong reading it, if anything, one sentence each
 */
public record MediaVolume(String name, String description, byte[] image, List<MediaFile> files, List<String> problems) {
	public MediaVolume {
		files = List.copyOf(files);
		problems = List.copyOf(problems);
	}

	/** Whether the medium itself is a single program rather than something holding programs. */
	public boolean isLooseFile() {
		return files.size() == 1 && files.get(0).data() == image;
	}

	public boolean isReadable() {
		return !files.isEmpty();
	}
}
