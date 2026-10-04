package to.etc.pdp11.common.mem;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the application knows about the machine's memory: one {@link MemoryWord} per word below
 * the I/O page that anything has touched, keyed on its 22-bit physical address.
 *
 * <p>Every cell over such a word, in any window, shares it. So a program loaded from a file or
 * produced by the assembler is visible everywhere before it is deposited, and is shown as
 * pending until it is. See PLAN.md §1, "Shared memory".</p>
 *
 * <p><b>The I/O page is not in here.</b> Reading a device register can change it and its value
 * changes on its own, so it is not memory; cells there keep their own values and the
 * propagation bus of {@link MemoryCellGroups}, exactly as before.</p>
 *
 * <h2>Staleness</h2>
 *
 * <p>Once the machine has run, every machine value is suspect. Rather than sweep every word,
 * the image counts runs ({@link #markRun}) and a word remembers the count it was read at; it is
 * stale when that is older. Pending edits are not affected - they are what should be there,
 * whatever the program did since.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The map is guarded by the owning {@link MemoryCellGroups}' one monitor; everything that
 * answers with words answers with a copy. Pending-change listeners are told outside it.</p>
 */
public final class SharedMemory {
	/** One word with something to deposit, as a snapshot. */
	public record PendingWord(Address address, CellValue machine, CellValue edit, boolean stale, String owner) {
	}

	private final Object m_lock;

	private final Map<Long, MemoryWord> m_words = new HashMap<>();

	private final AtomicLong m_generation = new AtomicLong();

	private final List<Runnable> m_changeListeners = new CopyOnWriteArrayList<>();

	/**
	 * How many bulk operations this thread is inside, which tell the pending listeners once at
	 * the end instead of once per word - and outside the monitor, which per word they could not.
	 */
	private static final ThreadLocal<int[]> m_batch = ThreadLocal.withInitial(() -> new int[2]);

	SharedMemory(Object lock) {
		m_lock = lock;
	}

	/**
	 * Whether this 22-bit physical address is a word of memory, and so belongs in the image.
	 * An odd address is a byte, and the I/O page is devices.
	 */
	public static boolean isMemory(Address physical22) {
		if(physical22.type() != MemoryAddressType.PHYSICAL22)
			throw new IllegalArgumentException("Expected a 22-bit physical address, got " + physical22);
		long v = physical22.val();
		return (v & 1) == 0 && v < MemoryAddressType.PHYSICAL22.getIopageBase();
	}

	/** The word at this address, created unknown if nothing has touched it yet. */
	MemoryWord word(Address physical22) {
		synchronized(m_lock) {
			return m_words.computeIfAbsent(physical22.val(), v -> new MemoryWord(this, physical22));
		}
	}

	long generation() {
		return m_generation.get();
	}

	/**
	 * The machine has run - continued, started or single-stepped - so every value read before
	 * now may no longer be what it holds.
	 */
	public void markRun() {
		m_generation.incrementAndGet();
		fireChanged();                               // an edit equal to a now-stale value is pending again
	}

	/** How many words the image knows anything about. */
	public int size() {
		synchronized(m_lock) {
			return m_words.size();
		}
	}

	/** Every word with something to deposit, by address. */
	public List<PendingWord> getPending() {
		List<PendingWord> l = new ArrayList<>();
		synchronized(m_lock) {
			for(MemoryWord w : m_words.values()) {
				if(w.isPending()) {
					MemoryCellGroup owner = w.owner();
					l.add(new PendingWord(w.getPhysical(), w.machine(), w.edit(), w.isStale(),
						owner == null ? "" : owner.getGroupName()));
				}
			}
		}
		l.sort(Comparator.comparing(PendingWord::address));
		return l;
	}

	public int getPendingCount() {
		synchronized(m_lock) {
			int n = 0;
			for(MemoryWord w : m_words.values()) {
				if(w.isPending())
					n++;
			}
			return n;
		}
	}

	/** How many pending edits {@code group} made and still owns. */
	public int getPendingCountOwnedBy(MemoryCellGroup group) {
		synchronized(m_lock) {
			int n = 0;
			for(MemoryWord w : m_words.values()) {
				if(w.owner() == group && w.isPending())
					n++;
			}
			return n;
		}
	}

	/**
	 * Undo every edit {@code group} still owns: the words go back to what the machine holds. An
	 * edit another window has since overwritten is that window's, and stays.
	 */
	public void discardEditsOwnedBy(MemoryCellGroup group) {
		forEachWord(w -> {
			if(w.owner() == group)
				w.discardEdit();
		});
	}

	/** Keep every edit {@code group} owns, but as nobody's: the group is going away. */
	void orphanEditsOwnedBy(MemoryCellGroup group) {
		synchronized(m_lock) {
			for(MemoryWord w : m_words.values()) {
				if(w.owner() == group)
					w.orphan();
			}
		}
	}

	/** Undo the pending edits at these 22-bit physical addresses, whoever made them. */
	public void discardEditsAt(java.util.Collection<Address> addresses) {
		int[] batch = m_batch.get();
		batch[0]++;
		try {
			synchronized(m_lock) {
				for(Address a : addresses) {
					MemoryWord w = m_words.get(a.withWidth(MemoryAddressType.PHYSICAL22).val());
					if(w != null)
						w.discardEdit();
				}
			}
		} finally {
			batch[0]--;
		}
		if(batch[0] == 0) {
			batch[1] = 0;
			fireChanged();
		}
	}

	/**
	 * Forget what the machine said at these 22-bit physical addresses, keeping any edits there:
	 * something other than a deposit has written them - a loader of ours, using them as space.
	 */
	public void forgetMachineValuesAt(java.util.Collection<Address> addresses) {
		int[] batch = m_batch.get();
		batch[0]++;
		try {
			synchronized(m_lock) {
				for(Address a : addresses) {
					MemoryWord w = m_words.get(a.withWidth(MemoryAddressType.PHYSICAL22).val());
					if(w != null)
						w.setMachine(CellValue.UNKNOWN);
				}
			}
		} finally {
			batch[0]--;
		}
		if(batch[0] == 0) {
			batch[1] = 0;
			fireChanged();
		}
	}

	/** Undo every pending edit in shared memory. */
	public void discardAllEdits() {
		forEachWord(MemoryWord::discardEdit);
	}

	/**
	 * Forget what the machine said, keeping the edits: the connection went away, or is to a
	 * different machine, and nothing read before can be trusted.
	 */
	public void forgetMachineValues() {
		forEachWord(w -> w.setMachine(CellValue.UNKNOWN));
	}

	/**
	 * A clean slate: nothing read, nothing pending. The words themselves stay, because cells
	 * still point at them; they are simply unknown again.
	 */
	public void forgetAll() {
		forEachWord(w -> {
			w.discardEdit();
			w.setMachine(CellValue.UNKNOWN);
		});
	}

	private void forEachWord(java.util.function.Consumer<MemoryWord> action) {
		int[] batch = m_batch.get();
		batch[0]++;
		try {
			synchronized(m_lock) {
				for(MemoryWord w : m_words.values()) {
					action.accept(w);
				}
			}
		} finally {
			batch[0]--;
		}
		if(batch[0] == 0) {
			batch[1] = 0;
			fireChanged();
		}
	}

	/**
	 * Told whenever what a window over shared memory shows may have changed in a way no single
	 * cell announces: an edit made, deposited or discarded anywhere, a run making values stale,
	 * values forgotten, virtual views moved by the MMU. Called on whatever thread made the
	 * change, possibly once per word of a load, so a listener has to be cheap - a window
	 * coalesces onto its own thread. What the machine said about a word is announced to the
	 * cells over it instead, through {@link MemoryCellGroups#syncMemoryCells}.
	 */
	public void addChangeListener(Runnable listener) {
		m_changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener) {
		m_changeListeners.remove(listener);
	}

	/** Tell the change listeners, from outside any monitor. */
	void announce() {
		fireChanged();
	}

	void pendingChanged() {
		int[] batch = m_batch.get();
		if(batch[0] > 0 || Thread.holdsLock(m_lock)) {
			//-- Inside a bulk operation, or under the monitor where nothing may be called out to:
			//-- a bulk operation tells once when it is done. A single edit under the monitor is
			//-- only ever part of one.
			batch[1] = 1;
			return;
		}
		fireChanged();
	}

	private void fireChanged() {
		for(Runnable r : m_changeListeners) {
			r.run();
		}
	}

	@Override
	public String toString() {
		return "shared memory, " + size() + " words";
	}
}
