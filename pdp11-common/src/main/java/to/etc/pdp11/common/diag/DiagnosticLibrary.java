package to.etc.pdp11.common.diag;

import to.etc.pdp11.common.diag.media.AbsoluteLoaderImage;
import to.etc.pdp11.common.diag.media.MediaFile;
import to.etc.pdp11.common.diag.media.MediaVolume;
import to.etc.pdp11.common.util.AppVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The diagnostics collected onto this machine, and the directory they are kept in.
 *
 * <p>Laid out so that a person can use it without this program:</p>
 *
 * <pre>
 * library.tsv                          what is here and where it came from
 * files/ZRLGE0.BIC                     every program, under its own name
 * files/variants/3f9a0c21b4de/ZRLGE0.BIC   a different copy under a name already taken
 * media/bitsavers-rl02/xxdp25.rl02     the media themselves, decompressed, ready for SimH
 * </pre>
 *
 * <p>A program found on many media is stored once. Two copies are the same when their content is,
 * up to where an absolute loader stops reading - see {@link AbsoluteLoaderImage}. Where copies
 * really differ the first one keeps the plain name, unless it is damaged and the newcomer is not;
 * then they change places, so {@code files/} always holds the best copy there is.</p>
 *
 * <p>The index is a tab-separated text file rather than JSON because this module has no JSON
 * library and needs none. A line that cannot be read is skipped and counted, never fatal: the
 * files are all still on disk, and the next collection rewrites the index whole.</p>
 *
 * <p>Thread-safe: every method that touches the index holds this object's monitor, and what is
 * handed out is a copy.</p>
 */
public final class DiagnosticLibrary {
	public static final String INDEX_FILE = "library.tsv";

	private static final String HEADER = "# " + AppVersion.NAME + " diagnostic library, format 1";

	private final Path m_root;

	/** By {@link LibraryMedium#key()}. */
	private final Map<String, LibraryMedium> m_media = new LinkedHashMap<>();

	/** By name and digest. */
	private final Map<String, FileState> m_files = new LinkedHashMap<>();

	private int m_unreadableLines;

	/** A file as kept here; {@link LibraryFile} is the copy handed out. */
	private static final class FileState {
		final String m_name;

		final String m_digest;

		long m_size;

		LocalDate m_date;

		String m_path;

		boolean m_damaged;

		final List<String> m_foundOn = new ArrayList<>();

		FileState(String name, String digest) {
			m_name = name;
			m_digest = digest;
		}

		LibraryFile toFile() {
			return new LibraryFile(m_name, m_digest, m_size, m_date, m_path, m_damaged, m_foundOn);
		}
	}

	private DiagnosticLibrary(Path root) {
		m_root = root;
	}

	/**
	 * The library in {@code root}, which need not exist yet.
	 *
	 * @throws IOException only when the index is there and cannot be read at all
	 */
	public static DiagnosticLibrary open(Path root) throws IOException {
		DiagnosticLibrary lib = new DiagnosticLibrary(root);
		lib.load();
		return lib;
	}

	public Path getRoot() {
		return m_root;
	}

	public Path getFilesDirectory() {
		return m_root.resolve("files");
	}

	/** Where a path from a {@link LibraryFile} or a {@link LibraryMedium} is. */
	public Path resolve(String relative) {
		return m_root.resolve(relative);
	}

	/** Index lines skipped on loading because they made no sense. */
	public synchronized int getUnreadableLines() {
		return m_unreadableLines;
	}

	public synchronized List<LibraryFile> files() {
		List<LibraryFile> out = new ArrayList<>(m_files.size());
		for(FileState f : m_files.values())
			out.add(f.toFile());
		return out;
	}

	public synchronized List<LibraryMedium> media() {
		return new ArrayList<>(m_media.values());
	}

	/** Whether anything from this URL is in the library already. */
	public synchronized boolean hasDownloaded(String url) {
		for(LibraryMedium m : m_media.values()) {
			if(m.url().equals(url))
				return true;
		}
		return false;
	}

	public synchronized boolean isEmpty() {
		return m_files.isEmpty() && m_media.isEmpty();
	}

	/**
	 * Put a medium and everything on it into the library, replacing what an earlier collection of
	 * the same medium put there.
	 *
	 * <p>Writes the files, and the medium's image when it is a medium rather than a single program,
	 * but not the index: call {@link #save()} once a batch is in.</p>
	 *
	 * @return how many of its files were not in the library before, by content
	 */
	public synchronized int add(String sourceId, String url, MediaVolume volume, LocalDate collected) throws IOException {
		String key = LibraryMedium.keyOf(url, volume.name());
		forget(key);
		String imagePath = "";
		if(!volume.isLooseFile()) {
			imagePath = "media/" + safeName(sourceId) + "/" + safeName(volume.name().replace('/', '_'));
			writeAtomically(m_root.resolve(imagePath), volume.image());
		}
		m_media.put(key, new LibraryMedium(sourceId, url, volume.name(), imagePath, volume.description(),
			volume.files().size(), collected, volume.problems()));
		int added = 0;
		for(MediaFile f : volume.files()) {
			if(addFile(f, key))
				added++;
		}
		return added;
	}

	/** Take a medium's record out, and its claim on every file it held. Files stay on disk. */
	private void forget(String mediumKey) {
		if(m_media.remove(mediumKey) == null)
			return;
		for(FileState f : m_files.values())
			f.m_foundOn.remove(mediumKey);
	}

	private boolean addFile(MediaFile f, String mediumKey) throws IOException {
		String name = safeName(f.name());
		String digest = digest(f.data());
		String id = name + "/" + digest;
		FileState existing = m_files.get(id);
		if(existing != null) {
			if(!existing.m_foundOn.contains(mediumKey))
				existing.m_foundOn.add(mediumKey);
			if(existing.m_date == null)
				existing.m_date = f.date();
			if(existing.m_damaged && !f.damaged()) {
				//-- Same program up to the loader's end, but this copy was read whole.
				writeAtomically(m_root.resolve(existing.m_path), f.data());
				existing.m_damaged = false;
				existing.m_size = f.data().length;
			}
			return false;
		}
		FileState primary = primary(name);
		String plain = "files/" + name;
		FileState s = new FileState(name, digest);
		s.m_size = f.data().length;
		s.m_date = f.date();
		s.m_damaged = f.damaged();
		s.m_foundOn.add(mediumKey);
		if(primary == null) {
			s.m_path = plain;
		} else if(primary.m_damaged && !f.damaged()) {
			//-- The plain name goes to the better copy.
			String moved = variantPath(primary.m_name, primary.m_digest);
			move(m_root.resolve(primary.m_path), m_root.resolve(moved));
			primary.m_path = moved;
			s.m_path = plain;
		} else {
			s.m_path = variantPath(name, digest);
		}
		writeAtomically(m_root.resolve(s.m_path), f.data());
		m_files.put(id, s);
		return true;
	}

	/** The copy that has the plain name, if any. */
	private FileState primary(String name) {
		String plain = "files/" + name;
		for(FileState f : m_files.values()) {
			if(f.m_path.equals(plain))
				return f;
		}
		return null;
	}

	private static String variantPath(String name, String digest) {
		return "files/variants/" + digest.substring(0, 12) + "/" + name;
	}

	/**
	 * Hex SHA-256 over what a loader would read: see {@link AbsoluteLoaderImage}. Other files are
	 * taken whole.
	 */
	static String digest(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(data, 0, AbsoluteLoaderImage.significantLength(data));
			return HexFormat.of().formatHex(md.digest());
		} catch(NoSuchAlgorithmException x) {
			throw new IllegalStateException("Every Java has SHA-256", x);
		}
	}

	/**
	 * A name that is safe as a file name on all three platforms: the characters Windows refuses
	 * become {@code _}, and so do control characters. Names off a PDP-11 medium never contain any of
	 * them; names off a web server might.
	 */
	static String safeName(String name) {
		StringBuilder sb = new StringBuilder(name.length());
		for(int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			sb.append(c < ' ' || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
		}
		String s = sb.toString().strip();
		if(s.isEmpty() || s.equals(".") || s.equals(".."))
			return "_";
		return s;
	}

	// -------------------------------------------------------------------------------------
	// The index
	// -------------------------------------------------------------------------------------

	/** Write the index. Atomically: a crash halfway leaves the previous one. */
	public synchronized void save() throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append(HEADER).append('\n');
		for(LibraryMedium m : m_media.values()) {
			line(sb, "M", m.sourceId(), m.url(), m.volume(), m.imagePath(), m.description(), Integer.toString(m.fileCount()),
				m.collected() == null ? "" : m.collected().toString(), String.join(" | ", m.problems()));
		}
		for(FileState f : m_files.values()) {
			for(String on : f.m_foundOn) {
				line(sb, "F", f.m_name, f.m_digest, Long.toString(f.m_size), f.m_date == null ? "" : f.m_date.toString(),
					f.m_path, f.m_damaged ? "damaged" : "", on);
			}
			if(f.m_foundOn.isEmpty())
				line(sb, "F", f.m_name, f.m_digest, Long.toString(f.m_size), f.m_date == null ? "" : f.m_date.toString(),
					f.m_path, f.m_damaged ? "damaged" : "", "");
		}
		writeAtomically(m_root.resolve(INDEX_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void line(StringBuilder sb, String... fields) {
		for(int i = 0; i < fields.length; i++) {
			if(i > 0)
				sb.append('\t');
			sb.append(fields[i].replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
		}
		sb.append('\n');
	}

	private void load() throws IOException {
		List<String> lines;
		try {
			lines = Files.readAllLines(m_root.resolve(INDEX_FILE), StandardCharsets.UTF_8);
		} catch(NoSuchFileException x) {
			return;
		}
		for(String line : lines) {
			if(line.isBlank() || line.startsWith("#"))
				continue;
			String[] f = line.split("\t", -1);
			try {
				if(f[0].equals("M") && f.length == 9) {
					List<String> problems = f[8].isEmpty() ? List.of() : List.of(f[8].split(" \\| "));
					LibraryMedium m = new LibraryMedium(f[1], f[2], f[3], f[4], f[5], Integer.parseInt(f[6]),
						f[7].isEmpty() ? null : LocalDate.parse(f[7]), problems);
					m_media.put(m.key(), m);
				} else if(f[0].equals("F") && f.length == 8) {
					String id = f[1] + "/" + f[2];
					FileState s = m_files.get(id);
					if(s == null) {
						s = new FileState(f[1], f[2]);
						s.m_size = Long.parseLong(f[3]);
						s.m_date = f[4].isEmpty() ? null : LocalDate.parse(f[4]);
						s.m_path = f[5];
						s.m_damaged = f[6].equals("damaged");
						m_files.put(id, s);
					}
					if(!f[7].isEmpty() && !s.m_foundOn.contains(f[7]))
						s.m_foundOn.add(f[7]);
				} else {
					m_unreadableLines++;
				}
			} catch(NumberFormatException | DateTimeParseException x) {
				m_unreadableLines++;
			}
		}
	}

	private static void writeAtomically(Path target, byte[] data) throws IOException {
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(tmp, data);
		move(tmp, target);
	}

	private static void move(Path from, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch(AtomicMoveNotSupportedException x) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
