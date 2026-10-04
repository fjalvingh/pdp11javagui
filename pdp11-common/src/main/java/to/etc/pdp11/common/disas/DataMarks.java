package to.etc.pdp11.common.disas;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which words of the 64 KB a program sees are data rather than code, and what kind of data.
 *
 * <p>A disassembler cannot tell a table from an instruction stream: every word decodes as
 * something. So the user says, and this remembers it - per word, in virtual addresses, because
 * that is the only space a listing means anything in. A word with no mark is code.</p>
 *
 * <p>Marks are kept for the session: until they are {@linkplain #clear() cleared}, which is what
 * "Forget all" does, or until the program ends. They say nothing about what is in memory, so
 * reading or loading leaves them alone.</p>
 *
 * <p>Thread-safe; listeners are told outside the monitor, on whatever thread made the change.</p>
 */
public final class DataMarks {
	/** How a run of words marked as data is laid out. */
	public enum Format {
		/** {@code .BYTE}: octal bytes, four to a line. */
		BYTES,
		/** {@code .WORD}: octal words, three to a line. */
		WORDS,
		/** {@code .ASCIZ}: text, one string to a line, each ending at its zero byte. */
		ASCIZ
	}

	private final Format[] m_marks = new Format[MemoryImage.SIZE / 2];

	private final List<Runnable> m_listeners = new CopyOnWriteArrayList<>();

	/** How the word at this address is marked, or null when it is code. An odd address means its word. */
	public synchronized Format formatAt(int addr) {
		return m_marks[(addr & 0xFFFF) >>> 1];
	}

	/**
	 * Mark the words from {@code from} to {@code to}, both inclusive, as data of this format - or
	 * as code again, with a null format. Either address may be odd; it means the word it is in.
	 */
	public void mark(int from, int to, Format format) {
		int lo = (from & 0xFFFF) >>> 1;
		int hi = (to & 0xFFFF) >>> 1;
		if(hi < lo)
			throw new IllegalArgumentException("Range ends before it starts: " + Integer.toOctalString(from)
				+ " to " + Integer.toOctalString(to));
		synchronized(this) {
			for(int i = lo; i <= hi; i++) {
				m_marks[i] = format;
			}
		}
		fireChanged();
	}

	/** Whether nothing is marked as data. */
	public synchronized boolean isEmpty() {
		for(Format f : m_marks) {
			if(f != null)
				return false;
		}
		return true;
	}

	/** Everything is code again. */
	public void clear() {
		synchronized(this) {
			java.util.Arrays.fill(m_marks, null);
		}
		fireChanged();
	}

	public void addListener(Runnable listener) {
		m_listeners.add(listener);
	}

	public void removeListener(Runnable listener) {
		m_listeners.remove(listener);
	}

	private void fireChanged() {
		for(Runnable r : m_listeners) {
			r.run();
		}
	}
}
