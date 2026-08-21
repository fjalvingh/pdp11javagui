package to.etc.pdp11.core.util;

/**
 * The channel a log line belongs to.
 *
 * <p>These are not severity levels. The Pascal Log window shows one <i>column</i> per channel
 * ({@code TLogColumnIndex}, {@code FormLogU.pas:48-56}), which is what makes a byte-level
 * serial conversation readable: the bytes going out, the bytes coming back and the phrases
 * decoded from them line up side by side instead of interleaving into one stream. That is a
 * debugging tool worth keeping, so the abstraction keeps the channel and drops the levels.</p>
 *
 * <p>The names have been brought forward to the threading model of PLAN.md §1 rather than
 * transliterated; the Pascal originals are noted on each constant.</p>
 */
public enum LogChannel {
	/** Anything without a channel of its own. Was {@code LogCol_Other}. */
	OTHER("Other"),

	/** Bytes written to the transport. Was {@code LogCol_PhysicalWriteByte}. */
	TRANSPORT_WRITE("Write"),

	/** Bytes read from the transport. Was {@code LogCol_PhysicalReadByte}. */
	TRANSPORT_READ("Read"),

	/** Answer phrases decoded from the byte stream. Was {@code LogCol_DecodeNextAnswerPhrase}. */
	PROTOCOL("Protocol"),

	/**
	 * Console commands entering and leaving the command executor. Was
	 * {@code LogCol_CriticalSection}, which traced the hand-rolled nesting counter that the
	 * single-threaded executor replaces.
	 */
	COMMAND("Command"),

	/**
	 * CPU run/halt transitions. Was {@code LogCol_MonitorTimerCallback}, named after the
	 * 100 ms poll timer that detected them.
	 */
	EXECUTION("Execution");

	private final String m_columnTitle;

	LogChannel(String columnTitle) {
		m_columnTitle = columnTitle;
	}

	/** Short label, for the Log window's column header. */
	public String getColumnTitle() {
		return m_columnTitle;
	}
}
