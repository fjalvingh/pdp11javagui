package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The list of phrases is written by the reader thread and cleared by the command thread, so
 * anything that looks backwards from the end has to do it under one lock.
 */
class AnswerCollectorTest {
	private static AnswerPhrase line(String text) {
		return new AnswerPhrase.OtherLine(text);
	}

	@Test
	void lookingBackFromTheEndCountsFromTheLastPhrase() {
		AnswerCollector c = new AnswerCollector();
		AnswerPhrase a = line("a"), b = line("b"), d = line("c");
		c.publish(a);
		c.publish(b);
		c.publish(d);

		assertSame(d, c.getFromEnd(0));
		assertSame(b, c.getFromEnd(1));
		assertSame(a, c.getFromEnd(2));
	}

	@Test
	void lookingBackPastTheStartIsNullRatherThanAnException() {
		AnswerCollector c = new AnswerCollector();
		assertNull(c.getFromEnd(0), "and an empty list has no last phrase either");
		c.publish(line("only one"));
		assertNull(c.getFromEnd(1));
		assertNull(c.getFromEnd(99));
	}

	@Test
	void lookingForwardsFromTheEndIsAProgrammingError() {
		AnswerCollector c = new AnswerCollector();
		assertThrows(IllegalArgumentException.class, () -> c.getFromEnd(-1));
	}

	/**
	 * The decoder runs on the reader thread and looks two phrases back to pair a halt with the
	 * prompt that follows it. Every command clears the list from the command thread. Computing
	 * that from a separate {@code size()} and {@code get()} throws IndexOutOfBoundsException in
	 * the gap - on the reader thread, where a throwable is read as the transport dying and
	 * reported to the user as "Console connection lost" on a perfectly live connection.
	 */
	@Test
	void clearingFromAnotherThreadCannotMakeTheLookBackThrow() throws Exception {
		AnswerCollector c = new AnswerCollector();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		int rounds = 200_000;

		Thread reader = new Thread(() -> {
			try {
				for(int i = 0; i < rounds; i++) {
					c.publish(line("r" + i));
					c.getFromEnd(1);                        // what makePrompt does
				}
			} catch(Throwable x) {
				failure.set(x);
			}
		}, "reader");
		Thread commands = new Thread(() -> {
			try {
				for(int i = 0; i < rounds; i++) {
					c.clear();                              // what every command does
				}
			} catch(Throwable x) {
				failure.set(x);
			}
		}, "commands");

		reader.start();
		commands.start();
		reader.join(30_000);
		commands.join(30_000);
		assertEquals(null, failure.get(), () -> "the look back threw: " + failure.get());
	}
}
