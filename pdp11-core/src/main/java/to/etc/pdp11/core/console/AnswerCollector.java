package to.etc.pdp11.core.console;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The phrases a console has said, and how a command waits for the one it wants.
 *
 * <p>Replaces {@code TConsoleGeneric.AnswerLines} plus {@code GetLastAnswer} and
 * {@code WaitForAnswer} ({@code ConsoleGenericU.pas:179, 430-469}). The shape is kept: a
 * command clears the list, sends, and waits for what it expects. Keeping the whole list rather
 * than consuming a queue matters - checking that a command was not rejected means looking at
 * everything that came back, not just at the prompt
 * ({@code ConsolePDP11SimHU.CheckPromptNoOutput}).</p>
 *
 * <p>Two things change. The waiting: the Pascal spins on
 * {@code Application.ProcessMessages}/{@code sleep(1)} because nothing is received unless the
 * message loop is pumped ({@code :459-464}), whereas here the reader thread publishes and the
 * command thread waits on a monitor. And the searching: as well as the Pascal's "last phrase of
 * this type anywhere", there is "first phrase of this type <i>after</i> position n".</p>
 *
 * <h2>Why position matters</h2>
 *
 * <p>SimH prints its prompt <i>before</i> echoing the command it is about to run, so "clear,
 * send, wait for a prompt" can be satisfied by the prompt belonging to the <i>previous</i>
 * command if its bytes were still in flight when the list was cleared. That is not theoretical:
 * phase 3 could reach a working exchange live and then watch the same test fail on the next
 * run, which is what PLAN.md records as phase 4's problem to solve. The fix is to anchor on
 * something that cannot predate the command - SimH's echo of the command itself - and then look
 * only at what came after it. That needs positions, so this offers them.</p>
 */
public final class AnswerCollector {
	private final List<AnswerPhrase> m_answers = new ArrayList<>();

	private boolean m_closed;

	/** Called from the reader thread as the decoder recognises phrases. */
	public synchronized void publish(AnswerPhrase phrase) {
		m_answers.add(phrase);
		notifyAll();
	}

	/** Forget everything said so far. Done before sending a command. */
	public synchronized void clear() {
		m_answers.clear();
	}

	public synchronized List<AnswerPhrase> snapshot() {
		return List.copyOf(m_answers);
	}

	/** Everything from {@code fromIndex} on. */
	public synchronized List<AnswerPhrase> snapshotFrom(int fromIndex) {
		if(fromIndex >= m_answers.size())
			return List.of();
		return List.copyOf(m_answers.subList(Math.max(0, fromIndex), m_answers.size()));
	}

	/** Everything said so far, and clear it - one step, so nothing published in between is lost. */
	public synchronized List<AnswerPhrase> takeAll() {
		List<AnswerPhrase> l = List.copyOf(m_answers);
		m_answers.clear();
		return l;
	}

	public synchronized int size() {
		return m_answers.size();
	}

	public synchronized AnswerPhrase get(int index) {
		return m_answers.get(index);
	}

	/** The most recent phrase of any type, or {@code null}. */
	public synchronized AnswerPhrase getLast() {
		return m_answers.isEmpty() ? null : m_answers.get(m_answers.size() - 1);
	}

	/**
	 * The phrase {@code back} places from the end - 0 is the last one - or {@code null} if the
	 * list is not that long.
	 *
	 * <p>One lock, one answer. {@link #size()} and {@link #get} are each synchronized, but a
	 * decoder computing {@code get(size() - 2)} out of the two holds the lock over neither the
	 * arithmetic nor the gap between them: a command thread calling {@link #clear()} in between
	 * empties the list and the {@code get} throws {@link IndexOutOfBoundsException} on the
	 * <i>reader</i> thread, where any throwable is read as the transport dying and reported as
	 * "Console connection lost". Looking back from the end is a single operation because that is
	 * the only way it can be a safe one.</p>
	 */
	public synchronized AnswerPhrase getFromEnd(int back) {
		if(back < 0)
			throw new IllegalArgumentException("Cannot look forwards from the end: " + back);
		int at = m_answers.size() - 1 - back;
		return at < 0 ? null : m_answers.get(at);
	}

	/**
	 * The most recent phrase of a type, or {@code null}.
	 *
	 * <p>Searching backwards is the Pascal's own choice ({@code :435}).</p>
	 */
	public synchronized <T extends AnswerPhrase> T getLast(Class<T> type) {
		for(int i = m_answers.size() - 1; i >= 0; i--) {
			AnswerPhrase p = m_answers.get(i);
			if(type.isInstance(p))
				return type.cast(p);
		}
		return null;
	}

	/** Where the first phrase at or after {@code fromIndex} matching {@code test} is, or -1. */
	public synchronized int indexOf(Predicate<AnswerPhrase> test, int fromIndex) {
		for(int i = Math.max(0, fromIndex); i < m_answers.size(); i++) {
			if(test.test(m_answers.get(i)))
				return i;
		}
		return -1;
	}

	/**
	 * Wait for a phrase of a type to arrive, up to a timeout, the Pascal's way.
	 *
	 * @return the phrase, or {@code null} if the console did not say one in time - or if the
	 *         connection died while waiting, which is not worth waiting the rest of the timeout
	 *         for.
	 */
	public synchronized <T extends AnswerPhrase> T waitFor(Class<T> type, long timeoutMillis) {
		int at = waitForIndex(p -> type.isInstance(p), 0, timeoutMillis);
		return at < 0 ? null : type.cast(m_answers.get(at));
	}

	/** Wait for the first phrase of a type at or after {@code fromIndex}. */
	public synchronized <T extends AnswerPhrase> T waitFor(Class<T> type, int fromIndex, long timeoutMillis) {
		int at = waitForIndex(p -> type.isInstance(p), fromIndex, timeoutMillis);
		return at < 0 ? null : type.cast(m_answers.get(at));
	}

	/**
	 * Wait for a matching phrase at or after {@code fromIndex}.
	 *
	 * @return its position, or -1 on timeout or a closed connection
	 */
	public synchronized int waitForIndex(Predicate<AnswerPhrase> test, int fromIndex, long timeoutMillis) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		for(;;) {
			int at = indexOf(test, fromIndex);
			if(at >= 0)
				return at;
			if(m_closed)
				return -1;
			long remainingNanos = deadline - System.nanoTime();
			if(remainingNanos <= 0)
				return -1;
			try {
				//-- +1 so a sub-millisecond remainder does not become wait(0), which waits forever.
				wait(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
				return -1;
			}
		}
	}

	/**
	 * The connection is gone: wake anyone waiting rather than let them sit out a timeout for an
	 * answer that can no longer arrive.
	 */
	public synchronized void close() {
		m_closed = true;
		notifyAll();
	}

	public synchronized boolean isClosed() {
		return m_closed;
	}
}
