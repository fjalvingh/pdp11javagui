package to.etc.pdp11.ui.log;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.util.LogChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log buffer on its own: what a window sees when it starts watching one that is already
 * running.
 */
class UiLoggerTest {
	/** Collects the history and the live stream into one list, in the order they arrive. */
	private static final class Collector implements UiLogger.Listener {
		final List<String> seen = Collections.synchronizedList(new ArrayList<>());

		@Override
		public void onHistory(List<LogLine> lines) {
			for(LogLine l : lines) {
				seen.add(l.text());
			}
		}

		@Override
		public void onLine(LogLine line) {
			seen.add(line.text());
		}
	}

	/**
	 * A window that starts watching a log that is already being written misses nothing.
	 *
	 * <p>FABLE-ISSUES #51. Asking for the history and then subscribing is two calls with a gap
	 * between them, and a line logged in the gap went into the buffer and was never shown - until
	 * the window was closed and opened again, at which point it appeared out of nowhere.
	 * {@link UiLogger#subscribe} does both under one lock, so the two together are exactly every
	 * line, once, in order. The two-call shape cannot pass this.</p>
	 */
	@Test
	void aWindowThatStartsWatchingMidStreamSeesEveryLineOnceAndInOrder() throws Exception {
		UiLogger logger = new UiLogger();
		int count = 5000;
		CountDownLatch running = new CountDownLatch(1);
		Thread writer = new Thread(() -> {
			for(int i = 0; i < count; i++) {
				logger.log(LogChannel.OTHER, "line " + i);
				if(i == 0)
					running.countDown();
			}
		}, "log-writer");
		writer.start();
		assertTrue(running.await(5, TimeUnit.SECONDS), "the writer started");

		Collector collector = new Collector();
		logger.subscribe(collector);
		writer.join(10_000);

		//-- Line by line rather than list against list: a gap in five thousand entries is worth
		//-- being told the index of, not five thousand entries twice.
		assertEquals(count, collector.seen.size(), "every line, once");
		for(int i = 0; i < count; i++) {
			assertEquals("line " + i, collector.seen.get(i), "at position " + i);
		}
	}

	/** And unsubscribing really stops it, because a closed window must not keep drawing rows. */
	@Test
	void unsubscribingStopsTheLiveStreamAndNotTheBuffer() {
		UiLogger logger = new UiLogger();
		logger.log(LogChannel.OTHER, "before");
		Collector collector = new Collector();
		logger.subscribe(collector);
		logger.log(LogChannel.OTHER, "while watching");
		logger.unsubscribe();
		logger.log(LogChannel.OTHER, "after");

		assertEquals(List.of("before", "while watching"), collector.seen);
		assertEquals(3, logger.snapshot().size(), "the buffer kept filling regardless");
	}
}
