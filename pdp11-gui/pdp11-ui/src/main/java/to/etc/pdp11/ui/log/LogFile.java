package to.etc.pdp11.ui.log;

import to.etc.pdp11.common.util.LogChannel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The session's log on disk: every channel, the byte-level ones included, one file per run.
 *
 * <p>The Log window answers "what is happening"; this answers "what happened", after the
 * dialog that said the console did not respond has been dismissed and the window's switches
 * were off at the time. An intermittent fault on a real serial line cannot be made to happen
 * again on demand, so the bytes that preceded it have to have been written down already. That
 * is why this records every channel regardless of what the Log window shows, and flushes every
 * line: a hang or a kill must not take the last second of the conversation with it.</p>
 *
 * <p>Not thread-safe on its own; {@link UiLogger} calls it under its monitor, which is also
 * what keeps the file in the order things happened.</p>
 */
public final class LogFile implements AutoCloseable {
	/** Older session logs than this many are deleted when a new one starts. */
	public static final int KEEP = 20;

	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

	private final Path m_file;

	private final BufferedWriter m_out;

	/** Set when a write fails, after which this stops trying rather than failing per line. */
	private boolean m_broken;

	private LogFile(Path file, BufferedWriter out) {
		m_file = file;
		m_out = out;
	}

	/**
	 * Start a new session log in {@code dir}, pruning old ones.
	 *
	 * @throws IOException when the directory or the file cannot be made; the caller carries on
	 *                     without a file and says so
	 */
	public static LogFile open(Path dir) throws IOException {
		Files.createDirectories(dir);
		prune(dir, KEEP - 1);
		String base = "pdp11gui-" + LocalDateTime.now().format(NAME);
		Path file = dir.resolve(base + ".log");
		for(int i = 2; Files.exists(file); i++) {
			file = dir.resolve(base + "-" + i + ".log");
		}
		BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		return new LogFile(file, out);
	}

	/** Delete all but the newest {@code keep} session logs. Failure to delete one is not an error. */
	static void prune(Path dir, int keep) {
		List<Path> logs = new ArrayList<>();
		try(Stream<Path> s = Files.list(dir)) {
			s.filter(p -> {
				String n = p.getFileName().toString();
				return n.startsWith("pdp11gui-") && n.endsWith(".log");
			}).forEach(logs::add);
		} catch(IOException x) {
			return;
		}
		//-- The names sort by time, which is the order wanted.
		logs.sort(Comparator.comparing(p -> p.getFileName().toString()));
		for(int i = 0; i < logs.size() - keep; i++) {
			try {
				Files.deleteIfExists(logs.get(i));
			} catch(IOException x) {
				//-- Somebody has it open; it goes next time.
			}
		}
	}

	public Path getFile() {
		return m_file;
	}

	/** One line, with its time, channel and the thread that logged it. */
	public void write(long millis, LogChannel channel, String text, Throwable x) {
		if(m_broken)
			return;
		try {
			String time = TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
			m_out.write(String.format(Locale.ROOT, "%s %-9s [%s] %s", time, channel.getColumnTitle(),
				Thread.currentThread().getName(), text));
			m_out.newLine();
			if(x != null) {
				StringWriter sw = new StringWriter();
				x.printStackTrace(new PrintWriter(sw));
				m_out.write(sw.toString());
			}
			m_out.flush();
		} catch(IOException e) {
			m_broken = true;
		}
	}

	@Override
	public void close() {
		try {
			m_out.close();
		} catch(IOException x) {
			//-- Nothing left to tell.
		}
	}
}
