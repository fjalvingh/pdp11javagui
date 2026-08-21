package to.etc.pdp11.core.mem;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import java.util.ArrayList;
import java.util.Collections;
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

	private final List<MemoryCellGroup> m_groups = new ArrayList<>();

	private final Map<CellKey, List<MemoryCell>> m_byAddress = new HashMap<>();

	private int m_syncDepth;

	public MemoryCellGroup addGroup(MemoryAddressType type, String groupName) {
		MemoryCellGroup g = new MemoryCellGroup(this, type, groupName);
		m_groups.add(g);
		return g;
	}

	public List<MemoryCellGroup> getGroups() {
		return Collections.unmodifiableList(m_groups);
	}

	public int size() {
		return m_groups.size();
	}

	public MemoryCellGroup findByName(String groupName) {
		for(MemoryCellGroup g : m_groups) {
			if(g.getGroupName().equalsIgnoreCase(groupName))
				return g;
		}
		return null;
	}

	public void removeGroup(MemoryCellGroup group) {
		if(m_groups.remove(group))
			group.clear();
	}

	/**
	 * Drop every group carrying this usage tag. The Pascal reloads machine descriptions by
	 * tagging groups on the way in ({@code AddGroupsFromIniFile}'s {@code aUsageTag}) so they
	 * can be found again on the way out.
	 */
	public void removeGroupsByUsageTag(String usageTag) {
		for(MemoryCellGroup g : new ArrayList<>(m_groups)) {
			if(g.getUsageTag().equals(usageTag))
				removeGroup(g);
		}
	}

	public void clear() {
		for(MemoryCellGroup g : new ArrayList<>(m_groups)) {
			g.clear();
		}
		m_groups.clear();
		m_byAddress.clear();
	}

	/** Every cell at this address, across all groups. Empty list if none. */
	public List<MemoryCell> cellsAt(Address addr) {
		List<MemoryCell> l = m_byAddress.get(keyOf(addr));
		return l == null ? List.of() : Collections.unmodifiableList(l);
	}

	/**
	 * A value has arrived for {@code source}; give every other cell at the same address the
	 * same value and tell that group's listeners.
	 *
	 * <p>Ported from {@code SyncMemoryCells} ({@code :793-812}), guards and all.</p>
	 */
	public void syncMemoryCells(MemoryCell source) {
		List<MemoryCell> at = m_byAddress.get(keyOf(source.getAddr()));
		if(at == null)
			return;

		if(m_syncDepth >= MAX_SYNC_DEPTH) {
			throw new IllegalStateException("Memory cell propagation is " + MAX_SYNC_DEPTH
				+ " deep at " + source.getAddr().toOctal()
				+ "; a MemoryCellListener is writing back a different value than it was given");
		}
		m_syncDepth++;
		try {
			//-- Copy: a listener may legitimately add or remove cells while being notified.
			for(MemoryCell mc : new ArrayList<>(at)) {
				if(mc == source)                                    // (2) self-exclusion
					continue;
				MemoryCellGroup group = mc.getGroup();
				if(!group.isPdpOverwritesEdit())                    // (1) per-group opt-out
					continue;
				if(mc.getPdpValue().equals(source.getPdpValue()))   // (3) equality terminates
					continue;
				mc.setPdpValue(source.getPdpValue());
				group.fireMemoryCellChanged(mc);
			}
		} finally {
			m_syncDepth--;
		}
	}

	/**
	 * Another cell at the same address that carries a register name, or {@code null}. Ported
	 * from {@code getSymbolInfoCell} ({@code :820-839}): a memory dump has no idea that
	 * {@code 0177776} is the PSW, but the machine description group does, so the dump borrows
	 * the label.
	 */
	public MemoryCell findNamedCellAt(MemoryCell cell) {
		List<MemoryCell> at = m_byAddress.get(keyOf(cell.getAddr()));
		if(at == null)
			return null;
		for(MemoryCell mc : at) {
			if(mc != cell && !mc.getName().isEmpty())
				return mc;
		}
		return null;
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
	 */
	public void changeAddressWidth(MemoryAddressType newType) {
		if(!newType.isConcretePhysical())
			throw new IllegalArgumentException("Can only change to a concrete physical width, not " + newType);

		//-- Dry run first, so a group that cannot convert stops this before any state moves.
		for(MemoryCellGroup g : m_groups) {
			if(g.getType() == MemoryAddressType.VIRTUAL || g.getType() == newType)
				continue;
			for(MemoryCell mc : g.getCells()) {
				mc.getAddr().withWidth(newType);                    // throws if it does not fit
			}
		}
		for(MemoryCellGroup g : m_groups) {
			if(g.getType() == MemoryAddressType.VIRTUAL)
				continue;
			g.changeWidthInternal(newType);
		}
	}

	void indexAdd(MemoryCell cell) {
		m_byAddress.computeIfAbsent(keyOf(cell.getAddr()), k -> new ArrayList<>()).add(cell);
	}

	void indexRemove(MemoryCell cell) {
		CellKey key = keyOf(cell.getAddr());
		List<MemoryCell> l = m_byAddress.get(key);
		if(l == null)
			return;
		l.remove(cell);
		if(l.isEmpty())
			m_byAddress.remove(key);
	}

	private static CellKey keyOf(Address addr) {
		if(addr.type().isConcretePhysical())
			return new CellKey(MemoryAddressType.PHYSICAL22, addr.withWidth(MemoryAddressType.PHYSICAL22).val());
		return new CellKey(addr.type(), addr.val());
	}

	@Override
	public String toString() {
		return m_groups.size() + " groups, " + m_byAddress.size() + " distinct addresses";
	}
}
