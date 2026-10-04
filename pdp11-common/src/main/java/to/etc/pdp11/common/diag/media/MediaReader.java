package to.etc.pdp11.common.diag.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Works out what a downloaded file is, and takes the files off it.
 *
 * <p>Diagnostics are published in more forms than anybody would choose: gzipped and plain disk
 * images, floppy images both physical and logical, ImageDisk files, SimH tapes, zip archives of
 * any of these, and single program files. This is the one place that knows all of them, so the
 * collector can hand it whatever it downloaded and get back the volumes inside.</p>
 *
 * <p>A disk image is recognised by its directory, not its name or size. Each possible block
 * mapping is tried and the one under which the most directory entries make sense wins; the size
 * only names the device afterwards. That matters because the same size means two things - an RX01
 * image of 256,256 bytes is physical, one of 252,928 is logical - and file names mean nothing at
 * all ({@code .dsk} is every kind of disk).</p>
 */
public final class MediaReader {
	/**
	 * Single files that are programs or what goes with them, taken as they are when they are not a
	 * medium. {@code .BIN} and {@code .BIC} are absolute loader images, {@code .LDA} the same under
	 * another name, {@code .CCC} an XXDP chain file.
	 */
	private static final Set<String> PROGRAM_EXTENSIONS = Set.of("BIN", "BIC", "LDA", "SAV", "SYS", "CCC", "LST", "MAC", "INI");

	/** An RX01 or RX02 diskette: 77 tracks of 26 sectors. */
	private static final int RX_TRACKS = 77;

	private static final int RX_SECTORS = 26;

	/** Archives and compressed files are opened this deep and no deeper. */
	private static final int MAX_NESTING = 3;

	private MediaReader() {
	}

	/** Known device sizes, to name a disk once its directory has been found. */
	private enum Device {
		TU58("TU58 tape", 262_144),
		RX01("RX01 floppy", 256_256, 252_928),
		RX02("RX02 floppy", 512_512, 505_856),
		TU56("DECtape", 295_936),
		RK05("RK05 disk", 2_494_464, 2_457_600),
		RL01("RL01 disk", 5_242_880),
		RL02("RL02 disk", 10_485_760),
		RK06("RK06 disk", 13_888_512),
		RK07("RK07 disk", 27_540_480),
		RM03("RM03/RP04 disk", 24_576_000),
		MSCP("MSCP disk", 33_553_920);

		private final String m_label;

		private final int[] m_sizes;

		Device(String label, int... sizes) {
			m_label = label;
			m_sizes = sizes;
		}

		static boolean isKnownSize(int size) {
			return !name(size).equals("disk image");
		}

		static String name(int size) {
			for(Device d : values()) {
				for(int s : d.m_sizes) {
					if(s == size)
						return d.m_label;
				}
			}
			return "disk image";
		}
	}

	/**
	 * The volumes in a downloaded file.
	 *
	 * <p>Never throws for content: something that cannot be read comes back as a volume with no
	 * files and a problem saying why, so one bad download is reported rather than fatal.</p>
	 */
	public static List<MediaVolume> read(String name, byte[] data) {
		List<MediaVolume> out = new ArrayList<>();
		read(name, data, out, 0, false);
		return out;
	}

	/**
	 * @param inArchive whether this came out of a zip, where whatever else was packed with the
	 *                  media - scans of the labels, a {@code .DS_Store} - is passed over in silence
	 */
	private static void read(String name, byte[] data, List<MediaVolume> out, int depth, boolean inArchive) {
		String lower = name.toLowerCase(Locale.ROOT);
		try {
			if(lower.endsWith(".gz") && depth < MAX_NESTING) {
				read(name.substring(0, name.length() - 3), gunzip(data), out, depth + 1, inArchive);
				return;
			}
			if(lower.endsWith(".zip") && depth < MAX_NESTING) {
				unzip(name, data, out, depth + 1);
				return;
			}
		} catch(IOException x) {
			out.add(unreadable(name, data, "the archive is damaged: " + x.getMessage()));
			return;
		}
		MediaVolume v = volume(name, data);
		if(v != null)
			out.add(v);
		else if(!inArchive)
			out.add(unreadable(name, data, Device.isKnownSize(data.length)
				? "no XXDP file system found; the directory may be damaged"
				: "not a medium this can read"));
	}

	/** The medium, an unreadable one with the reason, or null when it is not a medium at all. */
	private static MediaVolume volume(String name, byte[] data) {
		String extension = extension(name);
		List<String> problems = new ArrayList<>();
		if(extension.equals("TAP")) {
			try {
				return new MediaVolume(name, "magnetic tape, DOS-11 format", data, Dos11Tape.read(data, problems), problems);
			} catch(MediaFormatException x) {
				return unreadable(name, data, x.getMessage());
			}
		}
		if(ImdImage.looksLikeImd(data)) {
			try {
				ImdImage.Decoded decoded = ImdImage.decode(data);
				byte[] physical = decoded.data();
				if(decoded.sectorsPerTrack() == RX_SECTORS && decoded.tracks() < RX_TRACKS
					&& (decoded.sectorSize() == 128 || decoded.sectorSize() == 256)) {
					//-- An imaging run that stopped early, which happens: the directory is near
					//-- the start, so pad the diskette out to its full size and read what is there.
					problems.add("Only " + decoded.tracks() + " of the diskette's " + RX_TRACKS + " tracks were imaged");
					physical = java.util.Arrays.copyOf(physical, RX_TRACKS * RX_SECTORS * decoded.sectorSize());
				}
				MediaVolume v = disk(name, physical, problems);
				if(v != null)
					return new MediaVolume(v.name(), "ImageDisk " + v.description(), physical, v.files(), v.problems());
				return unreadable(name, data, "the ImageDisk file holds no XXDP file system");
			} catch(MediaFormatException x) {
				return unreadable(name, data, x.getMessage());
			}
		}
		MediaVolume disk = disk(name, data, problems);
		if(disk != null)
			return disk;
		if(PROGRAM_EXTENSIONS.contains(extension)) {
			String fileName = baseFileName(name);
			return new MediaVolume(name, "program file", data, List.of(new MediaFile(fileName, null, data)), List.of());
		}
		return null;
	}

	/**
	 * The disk under whichever block mapping makes the most sense of its directory, or null if none
	 * does.
	 */
	private static MediaVolume disk(String name, byte[] data, List<String> problems) {
		List<BlockImage> mappings = new ArrayList<>();
		mappings.add(BlockImage.linear(data));
		if(data.length == RX_TRACKS * RX_SECTORS * 128)
			mappings.add(BlockImage.rxInterleaved(data, 128));
		if(data.length == RX_TRACKS * RX_SECTORS * 256)
			mappings.add(BlockImage.rxInterleaved(data, 256));
		XxdpVolume best = null;
		for(BlockImage image : mappings) {
			try {
				XxdpVolume v = XxdpVolume.open(image);
				if(best == null || v.getEntries().size() > best.getEntries().size())
					best = v;
			} catch(MediaFormatException x) {
				//-- The wrong mapping, most likely; the next one may be right.
			}
		}
		if(best == null)
			return null;
		List<MediaFile> files = best.readAll(problems);
		if(best.getRejectedCount() > 0)
			problems.add(best.getRejectedCount() + " directory entries made no sense and were skipped");
		String description = Device.name(data.length) + ", " + best.getForm().getLabel() + " file system";
		return new MediaVolume(name, description, data, files, problems);
	}

	private static MediaVolume unreadable(String name, byte[] data, String why) {
		return new MediaVolume(name, "unreadable", data, List.of(), List.of(why));
	}

	private static byte[] gunzip(byte[] data) throws IOException {
		try(InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
			return in.readAllBytes();
		}
	}

	private static void unzip(String name, byte[] data, List<MediaVolume> out, int depth) throws IOException {
		int found = 0;
		int before = out.size();
		try(ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
			for(ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
				if(e.isDirectory())
					continue;
				found++;
				read(name + "/" + e.getName(), zip.readAllBytes(), out, depth, true);
			}
		}
		if(found == 0)
			out.add(unreadable(name, data, "the archive is empty, or not a zip archive"));
		else if(out.size() == before)
			out.add(unreadable(name, data, "the archive holds no medium this can read"));
	}

	/** The upper-case extension of the last path element, or {@code ""}. */
	static String extension(String name) {
		String base = baseFileName(name);
		int dot = base.lastIndexOf('.');
		return dot < 0 ? "" : base.substring(dot + 1);
	}

	/** The last path element, upper case, as XXDP would name it. */
	static String baseFileName(String name) {
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		return name.substring(slash + 1).toUpperCase(Locale.ROOT);
	}
}
