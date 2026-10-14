package to.etc.pdp11.ui.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.util.LogChannel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session log on disk: what it records, and that it does not grow without bound.
 */
class LogFileTest {
	/**
	 * The file gets the byte-level channels even with the window's switches off, and the stack
	 * trace of a failure - the two things a console fault cannot be diagnosed without afterwards.
	 */
	@Test
	void fileRecordsEveryChannelAndStackTraces(@TempDir Path dir) throws Exception {
		UiLogger logger = new UiLogger();
		LogFile file = LogFile.open(dir);
		logger.setFile(file);
		assertTrue(logger.isEnabled(LogChannel.TRANSPORT_WRITE), "the file wants the bytes even when the window does not");

		logger.log(LogChannel.TRANSPORT_WRITE, "sent \"L 001000<0d>\"");
		logger.logFailure(LogChannel.OTHER, "Examine failed", new IllegalStateException("no prompt"));
		logger.setFile(null);

		String text = Files.readString(file.getFile(), StandardCharsets.UTF_8);
		assertTrue(text.contains("Write") && text.contains("sent \"L 001000<0d>\""), text);
		assertTrue(text.contains("Examine failed"), text);
		assertTrue(text.contains("java.lang.IllegalStateException: no prompt") && text.contains("\tat "), text);

		//-- The window's buffer keeps its switches: no bytes, and the failure on one line.
		List<String> buffered = logger.snapshot().stream().map(LogLine::text).toList();
		assertEquals(List.of("Examine failed: java.lang.IllegalStateException: no prompt"), buffered);
	}

	@Test
	void oldLogsArePruned(@TempDir Path dir) throws Exception {
		for(int i = 0; i < LogFile.KEEP + 5; i++) {
			Files.writeString(dir.resolve(String.format("pdp11gui-20200101-%06d.log", i)), "x");
		}
		Files.writeString(dir.resolve("unrelated.txt"), "keep me");
		LogFile.open(dir).close();
		try(Stream<Path> s = Files.list(dir)) {
			List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
			assertEquals(LogFile.KEEP + 1, names.size(), names.toString());
			assertTrue(names.contains("unrelated.txt"));
			assertFalse(names.contains("pdp11gui-20200101-000000.log"), "the oldest goes first");
		}
	}
}
