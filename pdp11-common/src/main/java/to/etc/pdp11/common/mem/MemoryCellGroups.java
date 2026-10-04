package to.etc.pdp11.common.mem;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;

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
 *
 * <h2>Shared memory</h2>
 *
 * <p>Below the I/O page none of that copying happens any more: every cell at a word of memory
 * shares that word's values through the {@link SharedMemory}, so there is nothing to copy and
 * an edit made in one window is the edit in all of them. What the bus still does for such a
 * word is <i>tell</i> the other windows, and its equality short-circuit there is a version
 * rather than a value - see {@link MemoryCell}'s {@code m_seenVersion}. A cell with its own
 * values - in the I/O page, or in a group that asked for them - still has a machine value
 * copied in, and a shared word takes the machine value of such a cell when it is announced, so
 * the memory test's reads still reach a Memory window over the same range.</p>
 *
 * <p>The index is keyed on <b>where a cell is</b>, so a virtual address is keyed on the physical
 * word it resolves to through the {@link VirtualResolver} - the MMU, once a console has one. A
 * Disassembler showing virtual 1000 and a Memory window showing physical 1000 with the MMU off
 * are one word, and with relocation on they are not.</p>
 */
public final class MemoryCellGroups {
	/**
	 * How deep a listener may re-enter propagation before it is called a bug. Legitimate
	 * chains are one or two deep - the MMU recomputing itself after a PSW deposit, say.
	 */
	public static final int MAX_SYNC_DEPTH = 16;

	/**
	 * What two addresses have to share to be the same location. Concrete physical addresses,
	 * and virtual ones the resolver can place, normalise to 22-bit physical; everything else
	 * keeps its own type, so it only ever matches addresses of that same type.
	 */
	record CellLocation(MemoryAddressType space, long value) {
		boolean isMemory() {
			return space == MemoryAddressType.PHYSICAL22
				&& SharedMemory.isMemory(Address.of(MemoryAddressType.PHYSICAL22, value));
		}
	}

	/**
	 * Where a virtual address is in physical memory, as the MMU sees it now.
	 *
	 * <p>Called under the monitor, as cells are created, so it must not block or take a lock -
	 * {@code Pdp11Mmu}'s translation is arithmetic over registers it already holds, which is
	 * what qualifies it.</p>
	 */
	@FunctionalInterface
	public interface VirtualResolver {
		/** The concrete physical address, or {@code null} if this one does not translate. */
		Address toPhysical(Address virtual);
	}

	/**
	 * What the MMU does with relocation off, which is what a machine does before anything has
	 * said otherwise: below the I/O page physical equals virtual, and the top 8 KB is the I/O
	 * page.
	 */
	public static final VirtualResolver IDENTITY = virtual -> virtual.withWidth(MemoryAddressType.PHYSICAL22);

	/**
	 * The one monitor of the rule above. Held by everything in this class and in
	 * {@link MemoryCellGroup}; taken by nothing else, and never held across a call that leaves
	 * these two classes.
	 */
	private final Object m_lock = new Object();

	private final List<MemoryCellGroup> m_groups = new ArrayList<>();

	private final Map<CellLocation, List<MemoryCell>> m_byAddress = new HashMap<>();

	private final SharedMemory m_image = new SharedMemory(m_lock);

	private volatile VirtualResolver m_virtualResolver = IDENTITY;

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

	/** What is known about the machine's memory below the I/O page. */
	public SharedMemory getSharedMemory() {
		return m_image;
	}

	/**
	 * How virtual addresses find their physical word from now on; {@code null} goes back to
	 * {@link #IDENTITY}. Cells already made keep the word they were made over: a group re-ranged
	 * after this resolves again.
	 */
	public void setVirtualResolver(VirtualResolver resolver) {
		m_virtualResolver = resolver == null ? IDENTITY : resolver;
	}

	/**
	 * Make a cell of {@code group} at {@code addr}, over shared memory's word if it is memory and
	 * the group shares, over a word of its own otherwise. Under the monitor.
	 */
	MemoryCell newCell(MemoryCellGroup group, Address addr) {
		CellLocation loc = locationOf(addr);
		return new MemoryCell(group, addr, loc, wordFor(group, loc));
	}

	private MemoryWord wordFor(MemoryCellGroup group, CellLocation loc) {
		return group.isSharingMemory() && loc.isMemory()
			? m_image.word(Address.of(MemoryAddressType.PHYSICAL22, loc.value()))
			: new MemoryWord(null, null);
	}

	/**
	 * Resolve every virtual cell again through the {@link VirtualResolver}, and move each one
	 * that now names a different word to that word - after the MMU's registers have been read
	 * and found to map somewhere else.
	 *
	 * <p>Nothing in shared memory changes. Every console examines and deposits by physical
	 * address, so what was read under the old mapping is still true of the physical word it was
	 * filed under; a stale mapping makes a virtual view show the wrong words, not wrong values.
	 * An edit typed into a virtual view stays at the physical word it was typed at, which is the
	 * word it was shown as. A cell that moves to a word nobody has read shows as unknown, and
	 * its window reads it as it would any other.</p>
	 *
	 * <p>Virtual cells with values of their own - in the I/O page, or of a group that does not
	 * share - keep them; only their place in the index moves.</p>
	 *
	 * @return the groups that had a cell move, so their windows can be told
	 */
	public List<MemoryCellGroup> reresolveVirtual() {
		List<MemoryCellGroup> moved = new ArrayList<>();
		synchronized(m_lock) {
			for(MemoryCellGroup g : m_groups) {
				if(g.getType() != MemoryAddressType.VIRTUAL)
					continue;
				boolean any = false;
				for(MemoryCell mc : g.getCells()) {
					CellLocation now = locationOf(mc.getAddr());
					if(now.equals(mc.getLocation()))
						continue;
					indexRemove(mc);
					//-- A cell with values of its own that still has no shared word to go to keeps
					//-- them; anything else takes whatever word is at the new place.
					boolean toShared = g.isSharingMemory() && now.isMemory();
					mc.rebind(now, !mc.isShared() && !toShared ? mc.word() : wordFor(g, now));
					indexAdd(mc);
					any = true;
				}
				if(any)
					moved.add(g);
			}
		}
		if(!moved.isEmpty())
			m_image.announce();
		return moved;
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

	/**
	 * Drop a group. Any edit it made and did not deposit stays in the image, as nobody's; a
	 * window that would rather undo them calls {@link MemoryCellGroup#discardOwnedEdits} first.
	 */
	public void removeGroup(MemoryCellGroup group) {
		synchronized(m_lock) {
			if(m_groups.remove(group)) {
				group.clear();
				m_image.orphanEditsOwnedBy(group);
			}
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
			for(MemoryCellGroup g : m_groups) {
				m_image.orphanEditsOwnedBy(g);
			}
			m_groups.clear();
			m_byAddress.clear();
		}
	}

	/** Every cell at this address, across all groups, as a copy. Empty list if none. */
	public List<MemoryCell> cellsAt(Address addr) {
		synchronized(m_lock) {
			List<MemoryCell> l = m_byAddress.get(locationOf(addr));
			return l == null ? List.of() : List.copyOf(l);
		}
	}

	/**
	 * Something changed at {@code source}; bring every other cell at the same location up to
	 * date and tell that cell's group.
	 *
	 * <p>Ported from {@code SyncMemoryCells} ({@code :793-812}), guards and all, and extended
	 * for shared memory. For each other cell at the location:</p>
	 * <ul>
	 *   <li>if it has values of its own, it takes the source's machine value - unless its group
	 *       opted out (guard 1), or it already had that value (guard 3);</li>
	 *   <li>if it is a shared word other than the source's, which is a memory cell hearing from
	 *       one that keeps its own, the word takes the machine value likewise;</li>
	 *   <li>it is then told if, and only if, its word has changed since it last saw it. For a
	 *       cell sharing the source's word that is the whole of it: there is nothing to copy,
	 *       and the version is the equality check.</li>
	 * </ul>
	 *
	 * <p>Who is affected is decided under the monitor; the copying and the telling happen after
	 * it is released, so a listener is free to do anything at all - including coming back in
	 * here, which is what the depth guard is about.</p>
	 */
	public void syncMemoryCells(MemoryCell source) {
		List<MemoryCell> targets;
		CellValue value = source.getPdpValue();
		source.catchUp();
		synchronized(m_lock) {
			List<MemoryCell> at = m_byAddress.get(source.getLocation());
			if(at == null)
				return;
			targets = new ArrayList<>(at.size());
			for(MemoryCell mc : at) {
				if(mc == source)                                    // (2) self-exclusion
					continue;
				if(!mc.isShared() && !mc.getGroup().isPdpOverwritesEdit())  // (1) per-group opt-out
					continue;
				targets.add(mc);
			}
		}

		List<MemoryCell> changed = new ArrayList<>(targets.size());
		for(MemoryCell mc : targets) {
			if(mc.word() != source.word() && !mc.getPdpValue().equals(value))  // (3) equality
				mc.word().setMachine(value);
			if(mc.catchUp())
				changed.add(mc);
		}
		if(changed.isEmpty())
			return;

		int[] depth = m_syncDepth.get();
		if(depth[0] >= MAX_SYNC_DEPTH) {
			throw new IllegalStateException("Memory cell propagation is " + MAX_SYNC_DEPTH
				+ " deep at " + source.getAddr().toOctal()
				+ "; a MemoryCellListener is writing back a different value than it was given");
		}
		depth[0]++;
		try {
			for(MemoryCell mc : changed) {
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
			List<MemoryCell> at = m_byAddress.get(cell.getLocation());
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
			m_byAddress.computeIfAbsent(cell.getLocation(), k -> new ArrayList<>()).add(cell);
		}
	}

	void indexRemove(MemoryCell cell) {
		synchronized(m_lock) {
			CellLocation key = cell.getLocation();
			List<MemoryCell> l = m_byAddress.get(key);
			if(l == null)
				return;
			l.remove(cell);
			if(l.isEmpty())
				m_byAddress.remove(key);
		}
	}

	private CellLocation locationOf(Address addr) {
		if(addr.type() == MemoryAddressType.VIRTUAL) {
			Address p = m_virtualResolver.toPhysical(addr);
			if(p != null && p.type().isConcretePhysical())
				addr = p;
		}
		if(addr.type().isConcretePhysical())
			return new CellLocation(MemoryAddressType.PHYSICAL22, addr.withWidth(MemoryAddressType.PHYSICAL22).val());
		return new CellLocation(addr.type(), addr.val());
	}

	@Override
	public String toString() {
		synchronized(m_lock) {
			return m_groups.size() + " groups, " + m_byAddress.size() + " distinct addresses";
		}
	}
}
