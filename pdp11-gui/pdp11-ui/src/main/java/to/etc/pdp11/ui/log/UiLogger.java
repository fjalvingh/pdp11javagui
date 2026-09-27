package to.etc.pdp11.ui.log;

import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The application's log: a bounded buffer that the Log window draws, and that exists before it.
 *
 * <p>Existing before the window matters. The interesting lines are the early ones - which
 * settings file was unreadable, which port the probe picked, what the console said during its
 * handshake - and all of them happen before anybody opens a log window. So this buffers from the
 * first line, and the window attaches to it later and finds the history already there.</p>
 *
 * <p>Thread-safe because everything logs: the reader thread per byte, the command thread per
 * phrase, the event thread whenever the user does something.</p>
 */
public final class UiLogger implements Logger {
	/** Beyond this the oldest lines go. A long session must not become a memory leak. */
	public static final int MAX_LINES = 20_000;

	private final Deque<LogLine> m_lines = new ArrayDeque<>();

	/**
	 * Which channels are recorded at all.
	 *
	 * <p>The byte-level ones are off by default and that is the point of {@code isEnabled}:
	 * {@link LogChannel#TRANSPORT_READ} fires once per byte, so leaving it on costs a formatted
	 * string per character of every transcript. The Pascal has the same switch
	 * ({@code Connection_LogIoStream}, {@code SerialIoHubU.pas:845}).</p>
	 */
	private final Set<LogChannel> m_enabled = EnumSet.complementOf(
		EnumSet.of(LogChannel.TRANSPORT_READ, LogChannel.TRANSPORT_WRITE));

	/** Guarded by {@link #m_lines}, like everything else here - see {@link #subscribe}. */
	private Listener m_listener;

	/**
	 * What the Log window implements.
	 *
	 * <p>Called on whichever thread logged, holding this logger's monitor, so an implementation
	 * marshals and returns rather than doing anything.</p>
	 */
	public interface Listener {
		/**
		 * Everything buffered when this listener subscribed, oldest first. Exactly one call, and
		 * always before the first {@link #onLine}.
		 *
		 * <p>Unlike {@link #onLine} this arrives on the subscriber's own thread, from inside
		 * {@link UiLogger#subscribe}, so a window may draw it directly.</p>
		 */
		void onHistory(List<LogLine> lines);

		void onLine(LogLine line);
	}

	@Override
	public boolean isEnabled(LogChannel channel) {
		synchronized(m_lines) {
			return m_enabled.contains(channel);
		}
	}

	public void setEnabled(LogChannel channel, boolean enabled) {
		synchronized(m_lines) {
			if(enabled)
				m_enabled.add(channel);
			else
				m_enabled.remove(channel);
		}
	}

	/**
	 * Record one line, and tell whoever is watching - both under this logger's monitor.
	 *
	 * <p>Delivering inside the lock is what makes the order a listener sees the order things
	 * actually happened in. Outside it, two threads logging at once could reach the listener in
	 * the opposite order to the one they reached the buffer in, so the window would show a reply
	 * before the command that produced it (FABLE-ISSUES #51). {@code TextChannel} does the same
	 * thing for the same reason, and imposes the same duty on a listener: do not block.</p>
	 */
	@Override
	public void log(LogChannel channel, String message) {
		LogLine line = new LogLine(System.currentTimeMillis(), channel, message);
		synchronized(m_lines) {
			if(!m_enabled.contains(channel))
				return;
			m_lines.addLast(line);
			while(m_lines.size() > MAX_LINES) {
				m_lines.removeFirst();
			}
			if(m_listener != null)
				m_listener.onLine(line);
		}
	}

	/** Everything buffered so far, oldest first. */
	public List<LogLine> snapshot() {
		synchronized(m_lines) {
			return List.copyOf(m_lines);
		}
	}

	public void clear() {
		synchronized(m_lines) {
			m_lines.clear();
		}
	}

	/**
	 * Take everything logged so far and start following, in one step.
	 *
	 * <p>Both halves under one lock, so nothing can arrive in between. Snapshotting and then
	 * subscribing as two calls has exactly that gap, and a line that fell into it was buffered
	 * but never shown until the window was closed and opened again (FABLE-ISSUES #51) - which is
	 * the same gap {@code TextChannel.subscribe} exists to close, and this is now the same
	 * shape.</p>
	 *
	 * <p>One listener at a time: there is one Log window, and it replaces whatever was there.</p>
	 */
	public void subscribe(Listener listener) {
		synchronized(m_lines) {
			listener.onHistory(List.copyOf(m_lines));
			m_listener = listener;
		}
	}

	/** Stop following. The buffer keeps filling. */
	public void unsubscribe() {
		synchronized(m_lines) {
			m_listener = null;
		}
	}
}
