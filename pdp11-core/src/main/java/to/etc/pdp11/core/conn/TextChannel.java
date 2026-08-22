package to.etc.pdp11.core.conn;

import java.util.ArrayList;
import java.util.List;

/**
 * A stream of console text: whatever has been said so far, plus whatever is said next.
 *
 * <p>There are two of these on a connection - the machine's own console and, on SimH, the
 * {@code sim>} protocol channel - and both have the same two readers' worth of problem. A window
 * that is opened <b>after</b> something interesting happened must still be able to see it, and a
 * window that is open <b>while</b> it happens must not miss anything between reading the backlog
 * and subscribing to the live stream. Snapshot-then-subscribe as two calls has exactly that gap,
 * and it is the kind of gap that loses the one line somebody needed.</p>
 *
 * <p>So {@link #subscribe} does both, under one lock: the listener is handed the backlog and
 * added to the list without anything being able to arrive in between. Everything after that
 * arrives in order, once.</p>
 *
 * <h2>Which thread</h2>
 *
 * <p>{@link #append} is called from a reader thread - the console connection's, or SimH's
 * console drain - and delivers on that thread, holding the lock. A listener must therefore not
 * block: a UI listener marshals to the event thread and returns, which is what
 * {@code TextChannel.Listener} implementations here do. Delivering under the lock is what
 * guarantees a subscriber sees the buffer's contents and the live stream in the order they
 * actually happened.</p>
 */
public final class TextChannel {
	/** What a reader of this channel implements. Called on a reader thread; must not block. */
	public interface Listener {
		void onText(String text);

		/** The channel was emptied - a new connection. A view showing it should empty too. */
		default void onCleared() {
		}
	}

	private final String m_name;

	/** Beyond this the oldest is dropped. A console left running for a week is not a memory leak. */
	private final int m_maxChars;

	private final StringBuilder m_text = new StringBuilder();

	private final List<Listener> m_listeners = new ArrayList<>();

	public TextChannel(String name, int maxChars) {
		m_name = name;
		m_maxChars = maxChars;
	}

	public String getName() {
		return m_name;
	}

	public int getMaxChars() {
		return m_maxChars;
	}

	/** Something arrived. Keeps it, and tells whoever is listening. */
	public synchronized void append(String text) {
		if(text == null || text.isEmpty())
			return;
		m_text.append(text);
		if(m_text.length() > m_maxChars)
			m_text.delete(0, m_text.length() - m_maxChars);
		//-- Over a copy: a listener is allowed to unsubscribe itself from inside onText.
		for(Listener l : List.copyOf(m_listeners)) {
			l.onText(text);
		}
	}

	/** Forget everything. Done when a connection is opened, so a session starts empty. */
	public synchronized void clear() {
		m_text.setLength(0);
		for(Listener l : List.copyOf(m_listeners)) {
			l.onCleared();
		}
	}

	/** Everything said so far, up to the size limit. */
	public synchronized String getText() {
		return m_text.toString();
	}

	public synchronized int length() {
		return m_text.length();
	}

	/**
	 * Start listening, having first been told everything that was said before now.
	 *
	 * <p>The replay arrives as one {@link Listener#onText} call. A view that already shows
	 * something should clear itself first - see {@code SimhConsolePanel.attach()} - because what
	 * this delivers is the whole buffer, not the part of it the view has not seen.</p>
	 */
	public synchronized void subscribe(Listener listener) {
		if(m_text.length() > 0)
			listener.onText(m_text.toString());
		m_listeners.add(listener);
	}

	public synchronized void unsubscribe(Listener listener) {
		m_listeners.remove(listener);
	}

	public synchronized int listenerCount() {
		return m_listeners.size();
	}

	@Override
	public String toString() {
		return "TextChannel[" + m_name + ", " + length() + " chars, " + listenerCount() + " listeners]";
	}
}
