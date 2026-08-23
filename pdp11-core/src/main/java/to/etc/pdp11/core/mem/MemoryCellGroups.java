package to.etc.pdp11.core.mem;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every {@link MemoryCellGroup} in the application, and the machinery that keeps cells at the
 * same address agreeing with each other.
 *
 * <p>Ported from {@code TMemoryCellGroups} ({@code MemoryCellU.pas:160-177}).</p>
 *
 * <h2>The address index</h2>
 *
 * <p>{@code SyncMemoryCells} ({@code :793-812}) walks every group and calls
 * {@code CellIndexByAddr} on each, which is a linear scan of that group's cells - so
 * propagating one value is O(groups x cells), on every single word of a bulk examine. Here an
 * index maps address to the cells at it, across all groups.</p>
 *
 * <p>The index is keyed on the address <b>normalised to 22 bits</b>, not on the raw value.
 * Cells in different groups can legitimately be at different widths - the MMU builds its group
 * at {@link MemoryAddressType#PHYSICAL22} ({@code Pdp11MmuU.pas:148}) while machine
 * descriptions load at {@link MemoryAddressType#PHYSICAL16} - and 16-bit {@code 0177776} and
 * 22-bit {@code 017777776} are the same processor status word. Comparing raw values, as the
 * Pascal does, gets that wrong in both directions. Widening never overflows, so the
 * normalisation is total. Virtual addresses key separately: a virtual address is not a
 * physical one and must not sync with it.</p>
 *
 * <h2>Who owns this, and on which thread</h2>
 *
 * <p>There is one of these in the application and three kinds of thread reach it: the event
 * thread (every window adds a group when it opens and removes it when it closes), the command
 * thread (a bulk examine writes values in and calls {@link #syncMemoryCells}), and any connect
 * worker (building a console builds an MMU, which adds a group; an attempt that is overtaken
 * removes it again). Nothing coordinated them, and the groups and their cell lists are plain
 * {@code ArrayList}s - so a walk from one thread saw another thread's {@code add} and threw
 * {@link java.util.ConcurrentModificationException}. That was observed, intermittently, out of
 * {@code ConnectionManager.connect} (FABLE-ISSUES #64), and the same hole is what #16 and #30
 * were each one instance of.</p>
 *
 * <p>The rule, which PLAN.md §1 states and this class implements:</p>
 * <ol>
 *   <li><b>One monitor.</b> This object's {@link #lock()} guards the group list, the address
 *       index, and the cell list, index and range of every {@link MemoryCellGroup} under it.
 *       Every method here and there takes it. It is the innermost lock in the application:
 *       nothing is called while holding it that could go and wait for something else.</li>
 *   <li><b>Copies cross the boundary, never views.</b> {@link #getGroups}, {@link #cellsAt} and
 *       {@link MemoryCellGroup#getCells} answer with an immutable copy, so a caller may walk
 *       what it was given for as long as it likes on whatever thread it likes. What that does
 *       <i>not</i> buy is relevance - a group re-ranged meanwhile is showing something else
 *       now, which is what {@link MemoryCellGroup#holdsExactly} is for.</li>
 *   <li><b>Listeners are told outside the monitor</b>, because a listener is arbitrary code
 *       from a window and the one thing an innermost lock may not do is call out.</li>
 * </ol>
 *
 * <p>A {@link MemoryCell}'s own fields are not covered by the monitor - they are written by the
 * command thread and read by the event thread one word at a time, and taking a lock per word of
 * a bulk examine would be a lock per word for a value that is a single reference. They are
 * {@code volatile} instead, which is the visibility half without the mutual exclusion nobody
 * needs there.</p>
 *
 * <h2>The three propagation guards</h2>
 *
 * <p>PLAN.md §2 requires all three, or propagation storms:</p>
 * <ol>
 *   <li>the per-group {@link MemoryCellGroup#isPdpOverwritesEdit()} opt-out,</li>
 *   <li>self-exclusion - the cell that changed is not told about its own change,</li>
 *   <li>the value-equality short-circuit, which is what actually <i>terminates</i>
 *       propagation. There is no explicit recursion guard in the Pascal: a listener that
 *       writes back a different value recurses until the stack runs out. The equality check is
 *       kept and {@link #MAX_SYNC_DEPTH} added behind it as a backstop.</li>
 * </ol>
 *
 * <p>Only {@code pdpValue} propagates. {@code editValue} never does.</p>
 */
public final class MemoryCellGroups {
	/**
	 * How deep a listener may re-enter propagation before it is called a bug. Legitimate
	 * chains are one or two deep - the MMU recomputing itself after a PSW deposit, say.
	 */
	public static final int MAX_SYNC_DEPTH = 16;

	/**
	 * What two addresses have to share to be the same location. Concrete physical addresses
	 * normalise to 22 bits; everything else keeps its own type, so it only ever matches
	 * addresses of that same type.
	 */
	private record CellKey(MemoryAddressType space, long value) {
	}

	/**
	 * The one monitor of the rule above. Held by everything in this class and in
	 * {@link MemoryCellGroup}; taken by nothing else, and never held across a call that leaves
	 * these two classes.
	 */
	private final Object m_lock = new Object();

	private final List<MemoryCellGroup> m_groups = new ArrayList<>();

	private final Map<CellKey, List<MemoryCell>> m_byAddress = new HashMap<>();

	/**
	 * Propagation depth, per thread. Recursion is a listener writing back on the thread it was
	 * called on, so this is a property of that thread and not of the object - and it has to be,
	 * now that two threads can legitimately be propagating at the same time.
	 */
	private static final ThreadLocal<int[]> m_syncDepth = ThreadLocal.withInitial(() -> new int[1]);

	/** The monitor guarding this object and every group under it. */
	Object lock() {
		return m_lock;
	}

	public MemoryCellGroup addGroup(MemoryAddressType type, String groupName) {
		synchronized(m_lock) {
			MemoryCellGroup g = new MemoryCellGroup(this, type, groupName);
			m_groups.add(g);
			return g;
		}
	}

	/** Every group, as a copy: walk it on whatever thread you like. */
	public List<MemoryCellGroup> getGroups() {
		synchronized(m_lock) {
			return List.copyOf(m_groups);
		}
	}

	public int size() {
		synchronized(m_lock) {
			return m_groups.size();
		}
	}

	public MemoryCellGroup findByName(String groupName) {
		synchronized(m_lock) {
			for(MemoryCellGroup g : m_groups) {
				if(g.getGroupName().equalsIgnoreCase(groupName))
					return g;
			}
			return null;
		}
	}

	public void removeGroup(MemoryCellGroup group) {
		synchronized(m_lock) {
			if(m_groups.remove(group))
				group.clear();
		}
	}

	/**
	 * Drop every group carrying this usage tag. The Pascal reloads machine descriptions by
	 * tagging groups on the way in ({@code AddGroupsFromIniFile}'s {@code aUsageTag}) so they
	 * can be found again on the way out.
	 */
	public void removeGroupsByUsageTag(String usageTag) {
		synchronized(m_lock) {
			for(MemoryCellGroup g : new ArrayList<>(m_groups)) {
				if(g.getUsageTag().equals(usageTag))
					removeGroup(g);
			}
		}
	}

	public void clear() {
		synchronized(m_lock) {
			for(MemoryCellGroup g : new ArrayList<>(m_groups)) {
				g.clear();
			}
			m_groups.clear();
			m_byAddress.clear();
		}
	}

	/** Every cell at this address, across all groups, as a copy. Empty list if none. */
	public List<MemoryCell> cellsAt(Address addr) {
		synchronized(m_lock) {
			List<MemoryCell> l = m_byAddress.get(keyOf(addr));
			return l == null ? List.of() : List.copyOf(l);
		}
	}

	/**
	 * A value has arrived for {@code source}; give every other cell at the same address the
	 * same value and tell that group's listeners.
	 *
	 * <p>Ported from {@code SyncMemoryCells} ({@code :793-812}), guards and all.</p>
	 *
	 * <p>Who is told what is decided under the monitor; the telling happens after it is
	 * released, so a listener is free to do anything at all - including coming back in here,
	 * which is what the depth guard is about.</p>
	 */
	public void syncMemoryCells(MemoryCell source) {
		List<MemoryCell> targets;
		CellValue value = source.getPdpValue();
		synchronized(m_lock) {
			List<MemoryCell> at = m_byAddress.get(keyOf(source.getAddr()));
			if(at == null)
				return;
			targets = new ArrayList<>(at.size());
			for(MemoryCell mc : at) {
				if(mc == source)                                    // (2) self-exclusion
					continue;
				if(!mc.getGroup().isPdpOverwritesEdit())            // (1) per-group opt-out
					continue;
				if(mc.getPdpValue().equals(value))                  // (3) equality terminates
					continue;
				targets.add(mc);
			}
		}
		if(targets.isEmpty())
			return;

		int[] depth = m_syncDepth.get();
		if(depth[0] >= MAX_SYNC_DEPTH) {
			throw new IllegalStateException("Memory cell propagation is " + MAX_SYNC_DEPTH
				+ " deep at " + source.getAddr().toOctal()
				+ "; a MemoryCellListener is writing back a different value than it was given");
		}
		depth[0]++;
		try {
			for(MemoryCell mc : targets) {
				mc.setPdpValue(value);
				mc.getGroup().fireMemoryCellChanged(mc);
			}
		} finally {
			depth[0]--;
		}
	}

	/**
	 * Another cell at the same address that carries a register name, or {@code null}. Ported
	 * from {@code getSymbolInfoCell} ({@code :820-839}): a memory dump has no idea that
	 * {@code 0177776} is the PSW, but the machine description group does, so the dump borrows
	 * the label.
	 */
	public MemoryCell findNamedCellAt(MemoryCell cell) {
		synchronized(m_lock) {
			List<MemoryCell> at = m_byAddress.get(keyOf(cell.getAddr()));
			if(at == null)
				return null;
			for(MemoryCell mc : at) {
				if(mc != cell && !mc.getName().isEmpty())
					return mc;
			}
			return null;
		}
	}

	/**
	 * Re-express every group at a new physical width, when a different target machine is
	 * selected.
	 *
	 * <p>Ported from {@code ChangeAdddressWidth} ({@code :841-857}). With an immutable
	 * {@link Address} this is a rebuild rather than an in-place edit: each group converts its
	 * addresses, then drops and re-adds its index entries. Virtual addresses are left alone,
	 * as in the Pascal - they are not physical and do not move with the machine.</p>
	 *
	 * <p>Unlike the Pascal this is all-or-nothing. A conversion that does not fit throws
	 * before anything has been modified, rather than leaving half the application at one width
	 * and half at another.</p>
	 *
	 * <p><b>Nothing in the application calls this</b>, and that is the design rather than an
	 * omission: the Pascal calls it from nine places in {@code FormMainU} because its addresses
	 * carry the width they were declared at, and here every console normalises to its own width
	 * in its own {@code toPhysical} while the propagation index is keyed on the 22-bit form.
	 * {@code RegisterGroupWidthTest} holds that down. It is kept because the address model has
	 * to be able to do this and a routine that only exists in a comment cannot be checked
	 * (FABLE-ISSUES #55). If a window looks like it needs this, an address is being compared at
	 * the wrong width somewhere else.</p>
	 */
	public void changeAddressWidth(MemoryAddressType newType) {
		if(!newType.isConcretePhysical())
			throw new IllegalArgumentException("Can only change to a concrete physical width, not " + newType);

		synchronized(m_lock) {
			//-- Dry run first, so a group that cannot convert stops this before any state moves.
			for(MemoryCellGroup g : m_groups) {
				if(g.getType() == MemoryAddressType.VIRTUAL || g.getType() == newType)
					continue;
				for(MemoryCell mc : g.getCells()) {
					mc.getAddr().withWidth(newType);                // throws if it does not fit
				}
			}
			for(MemoryCellGroup g : m_groups) {
				if(g.getType() == MemoryAddressType.VIRTUAL)
					continue;
				g.changeWidthInternal(newType);
			}
		}
	}

	void indexAdd(MemoryCell cell) {
		synchronized(m_lock) {
			m_byAddress.computeIfAbsent(keyOf(cell.getAddr()), k -> new ArrayList<>()).add(cell);
		}
	}

	void indexRemove(MemoryCell cell) {
		synchronized(m_lock) {
			CellKey key = keyOf(cell.getAddr());
			List<MemoryCell> l = m_byAddress.get(key);
			if(l == null)
				return;
			l.remove(cell);
			if(l.isEmpty())
				m_byAddress.remove(key);
		}
	}

	private static CellKey keyOf(Address addr) {
		if(addr.type().isConcretePhysical())
			return new CellKey(MemoryAddressType.PHYSICAL22, addr.withWidth(MemoryAddressType.PHYSICAL22).val());
		return new CellKey(addr.type(), addr.val());
	}

	@Override
	public String toString() {
		synchronized(m_lock) {
			return m_groups.size() + " groups, " + m_byAddress.size() + " distinct addresses";
		}
	}
}
