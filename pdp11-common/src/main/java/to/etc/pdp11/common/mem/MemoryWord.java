package to.etc.pdp11.common.mem;

import to.etc.pdp11.common.addr.Address;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the values of a {@link MemoryCell} live: what the machine said, and what should be
 * there instead.
 *
 * <p>A word in memory below the I/O page has exactly one of these, held by the
 * {@link SharedMemory}, and every cell at that address in every window points at it. That is
 * what lets a program the Loader read or the Assembler produced show in the Disassembler and in
 * a Memory window before it has been deposited. Anything else - a device register, a cell in a
 * group that asked for its own values - has one of its own, and is kept agreeing with the
 * others by the propagation bus as before. See PLAN.md §1, "Shared memory".</p>
 *
 * <p>The edit value here is a <b>pending edit</b>: unknown means none. What a window shows is
 * {@link #effective()} - the edit when there is one, the machine value otherwise. An examine
 * sets only the machine value, ever, so an edit is never lost to another window's read; it goes
 * away by being deposited ({@link #setDeposited}) or by being discarded ({@link #discardEdit}).</p>
 *
 * <p>Fields are {@code volatile} rather than lock-guarded, as for the cell it replaces: single
 * references written by the command thread and read by the event thread a word at a time.</p>
 */
final class MemoryWord {
	/** The image this word belongs to, or {@code null} for one private to a single cell. */
	private final SharedMemory m_image;

	/** The 22-bit physical address of a shared word; {@code null} for a private one. */
	private final Address m_physical;

	private volatile CellValue m_machine = CellValue.UNKNOWN;

	/** The pending edit; {@link CellValue#UNKNOWN} means there is none. */
	private volatile CellValue m_edit = CellValue.UNKNOWN;

	/** The group whose cell made the pending edit, while it is still open. */
	private volatile MemoryCellGroup m_owner;

	/** The image's run generation when the machine value was read. */
	private volatile long m_readGeneration;

	/**
	 * Bumped on every change, so the propagation bus can tell whether a cell sharing this word
	 * has already been told about its current state. That is the equality short-circuit for a
	 * shared word, where comparing values says nothing - every cell holds the same one.
	 */
	private final AtomicLong m_version = new AtomicLong();

	MemoryWord(SharedMemory image, Address physical) {
		m_image = image;
		m_physical = physical;
	}

	boolean isShared() {
		return m_physical != null;
	}

	Address getPhysical() {
		return m_physical;
	}

	long version() {
		return m_version.get();
	}

	CellValue machine() {
		return m_machine;
	}

	void setMachine(CellValue value) {
		m_machine = value == null ? CellValue.UNKNOWN : value;
		m_readGeneration = m_image == null ? 0 : m_image.generation();
		m_version.incrementAndGet();
	}

	/**
	 * Whether the machine value is still what the machine holds, as far as anyone knows: read,
	 * and not before the last run. A private word is never stale - device registers are reread
	 * when they are looked at, not tracked.
	 */
	boolean isMachineCurrent() {
		if(!m_machine.isKnown())
			return false;
		return m_image == null || m_readGeneration == m_image.generation();
	}

	boolean isStale() {
		return m_machine.isKnown() && !isMachineCurrent();
	}

	CellValue edit() {
		return m_edit;
	}

	CellValue effective() {
		CellValue e = m_edit;
		return e.isKnown() ? e : m_machine;
	}

	MemoryCellGroup owner() {
		return m_owner;
	}

	/**
	 * Set the pending edit, made through a cell of {@code owner}.
	 *
	 * <p>Writing the edit that is already there changes nothing, including who owns it - a
	 * window copying a value it was shown back into its own cell does not take the word over.
	 * Unknown removes the edit.</p>
	 */
	void setEdit(CellValue value, MemoryCellGroup owner) {
		CellValue v = value == null ? CellValue.UNKNOWN : value;
		if(v.equals(m_edit))
			return;
		m_edit = v;
		m_owner = v.isKnown() ? owner : null;
		m_version.incrementAndGet();
		if(m_image != null)
			m_image.pendingChanged();
	}

	void discardEdit() {
		setEdit(CellValue.UNKNOWN, null);
	}

	/** Forget who made the edit, keeping it. Used when the owning window goes away. */
	void orphan() {
		m_owner = null;
	}

	/**
	 * Whether there is something to deposit: an edit, and not one the machine is known to hold
	 * already. An edit equal to a <i>stale</i> machine value is still pending - the program may
	 * have written that word since.
	 */
	boolean isPending() {
		CellValue e = m_edit;
		if(!e.isKnown())
			return false;
		return !(isMachineCurrent() && e.equals(m_machine));
	}

	/** The machine now holds what was shown; there is no edit any more. */
	void setDeposited() {
		CellValue e = effective();
		boolean hadEdit = m_edit.isKnown();
		m_machine = e;
		m_readGeneration = m_image == null ? 0 : m_image.generation();
		m_edit = CellValue.UNKNOWN;
		m_owner = null;
		m_version.incrementAndGet();
		if(hadEdit && m_image != null)
			m_image.pendingChanged();
	}

	/** Copy both values of another private word; used when a group re-ranges. */
	void copyFrom(MemoryWord other) {
		m_machine = other.m_machine;
		m_readGeneration = other.m_readGeneration;
		m_edit = other.m_edit;
		m_owner = other.m_owner;
		m_version.incrementAndGet();
	}

	@Override
	public String toString() {
		return (m_physical == null ? "private" : m_physical.toOctal()) + "=" + m_machine
			+ (m_edit.isKnown() ? " edit " + m_edit : "");
	}
}
