package to.etc.pdp11.ui.log;

import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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

	private volatile Consumer<LogLine> m_listener;

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
		}
		Consumer<LogLine> l = m_listener;
		if(l != null)
			l.accept(line);
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
	 * Where new lines go as well as into the buffer.
	 *
	 * <p>Called from whichever thread logged, so the listener marshals for itself.</p>
	 */
	public void setListener(Consumer<LogLine> listener) {
		m_listener = listener;
	}
}
