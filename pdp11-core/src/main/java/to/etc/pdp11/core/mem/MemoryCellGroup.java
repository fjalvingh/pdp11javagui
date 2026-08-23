package to.etc.pdp11.core.mem;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A set of memory cells that belong together - the registers of one controller, a range being
 * dumped, the words of an assembled program.
 *
 * <p>Ported from {@code TMemoryCellGroup} ({@code MemoryCellU.pas:104-157}). All cells in a
 * group share one {@link MemoryAddressType}.</p>
 *
 * <h2>pdpOverwritesEdit</h2>
 *
 * <p>Per-group opt-out from incoming propagation, documented at
 * {@code FrameMemoryCellGroupGridU.pas:38-48}. Without it, another window refreshing the same
 * addresses silently clobbers values the user has typed and not yet deposited. It is one of
 * the three things PLAN.md §2 says must be preserved exactly.</p>
 *
 * <h2>The listener list</h2>
 *
 * <p>A real list, not the Pascal's single delegate. See {@link MemoryCellListener}.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The cell list, its index and the range are guarded by the owning
 * {@link MemoryCellGroups}' one monitor - see "Who owns this, and on which thread" there for
 * why it is that object's and not this one's. {@link #getCells} answers with a copy.</p>
 */
public final class MemoryCellGroup {
	private final MemoryCellGroups m_owner;

	/**
	 * Volatile rather than lock-guarded: {@link #getType} is asked from every thread there is,
	 * including inside {@code toString} on a group being rebuilt, and it is a single reference.
	 * It only ever moves under the owner's monitor, in {@link #shiftRange} and
	 * {@link #changeWidthInternal}.
	 */
	private volatile MemoryAddressType m_type;

	/** Free-form tag naming where this group came from, so a reload can drop just those. */
	private volatile String m_usageTag = "";

	private volatile String m_groupName = "";

	private volatile String m_groupInfo = "";

	private final List<MemoryCell> m_cells = new ArrayList<>();

	/** Address value to cell, so a lookup is not a scan. */
	private final Map<Long, MemoryCell> m_byAddress = new HashMap<>();

	private volatile AddressRange m_range;

	/**
	 * Whether a value arriving from the machine may overwrite this group's cells. False for
	 * windows the user edits in.
	 */
	private volatile boolean m_pdpOverwritesEdit = true;

	private final List<MemoryCellListener> m_listeners = new CopyOnWriteArrayList<>();

	MemoryCellGroup(MemoryCellGroups owner, MemoryAddressType type, String groupName) {
		m_owner = owner;
		m_type = type;
		m_groupName = groupName == null ? "" : groupName;
		m_range = AddressRange.empty(type);
	}

	public MemoryCellGroups getOwner() {
		return m_owner;
	}

	public MemoryAddressType getType() {
		return m_type;
	}

	public String getUsageTag() {
		return m_usageTag;
	}

	public void setUsageTag(String usageTag) {
		m_usageTag = usageTag == null ? "" : usageTag;
	}

	public String getGroupName() {
		return m_groupName;
	}

	public void setGroupName(String groupName) {
		m_groupName = groupName == null ? "" : groupName;
	}

	public String getGroupInfo() {
		return m_groupInfo;
	}

	public void setGroupInfo(String groupInfo) {
		m_groupInfo = groupInfo == null ? "" : groupInfo;
	}

	public boolean isPdpOverwritesEdit() {
		return m_pdpOverwritesEdit;
	}

	public void setPdpOverwritesEdit(boolean pdpOverwritesEdit) {
		m_pdpOverwritesEdit = pdpOverwritesEdit;
	}

	public AddressRange getRange() {
		synchronized(m_owner.lock()) {
			return m_range;
		}
	}

	public int size() {
		synchronized(m_owner.lock()) {
			return m_cells.size();
		}
	}

	public boolean isEmpty() {
		synchronized(m_owner.lock()) {
			return m_cells.isEmpty();
		}
	}

	/**
	 * The cells, in insertion order unless {@link #sort()} has been called.
	 *
	 * <p>An immutable <b>copy</b>, taken under the owner's monitor, so it can be walked on any
	 * thread for as long as the caller likes. It used to be a view over the live list with a
	 * note telling every caller to copy it first; that note was obeyed in the panels and missed
	 * in the fakes and in the connect path, which is FABLE-ISSUES #64. A rule kept by whoever
	 * remembers it is not a rule.</p>
	 *
	 * <p>What the copy does not say is whether the group still holds these cells - see
	 * {@link #holdsExactly}.</p>
	 */
	public List<MemoryCell> getCells() {
		synchronized(m_owner.lock()) {
			return List.copyOf(m_cells);
		}
	}

	/**
	 * Whether this group still holds exactly the cells {@code snapshot} was taken of.
	 *
	 * <p>The other half of copying the list before a long job: the copy stops the iteration
	 * throwing, but it does not make the job's results relevant. A group re-ranged or cleared
	 * while the machine was being read is showing something else now, and writing the answers
	 * back into it would put a previous range's values under the current range's addresses.
	 * Identity, not equality: a cell is the thing the grid holds a reference to.</p>
	 */
	public boolean holdsExactly(List<MemoryCell> snapshot) {
		synchronized(m_owner.lock()) {
			if(m_cells.size() != snapshot.size())
				return false;
			for(int i = 0; i < snapshot.size(); i++) {
				if(m_cells.get(i) != snapshot.get(i))
					return false;
			}
			return true;
		}
	}

	public MemoryCell cell(int index) {
		synchronized(m_owner.lock()) {
			return m_cells.get(index);
		}
	}

	/**
	 * Add a cell at this address value, expressed at the group's own width.
	 *
	 * <p><b>Several cells may share an address, and that is not a mistake.</b> A PDP-11 device
	 * register often means different things at different points in a transfer, and the machine
	 * descriptions give each meaning its own name: the RX211 floppy controller declares
	 * {@code RX2TA}, {@code RX2SA}, {@code RX2WC}, {@code RX2BA}, {@code RX2DB} and
	 * {@code RX2ES} all at {@code 0177172}, because that is one register the controller
	 * reinterprets six ways. The register window shows all six rows; they hold the same word,
	 * and propagation keeps them agreeing.</p>
	 *
	 * <p>{@link #findByAddress} therefore answers with the <i>first</i> cell declared at an
	 * address, matching {@code CellIndexByAddr}.</p>
	 */
	public MemoryCell add(long addrValue) {
		return add(Address.of(m_type, addrValue));
	}

	public MemoryCell add(Address addr) {
		synchronized(m_owner.lock()) {
			if(addr.type() != m_type)
				throw new IllegalArgumentException("Cell address " + addr + " does not match this group's " + m_type);
			MemoryCell mc = new MemoryCell(this, addr);
			m_cells.add(mc);
			m_byAddress.putIfAbsent(addr.val(), mc);
			m_range = m_range.extend(addr.val());
			m_owner.indexAdd(mc);
			return mc;
		}
	}

	/**
	 * Add {@code wordCount} consecutive words starting at {@code startAddrValue}. Ported from
	 * the second {@code Add} overload ({@code MemoryCellU.pas:375-386}); the step is 2,
	 * because PDP-11 words are two bytes apart.
	 */
	public void add(long startAddrValue, int wordCount) {
		synchronized(m_owner.lock()) {
			for(int i = 0; i < wordCount; i++) {
				add(startAddrValue + 2L * i);
			}
		}
	}

	/** The cell at this address, or {@code null}. Ported from {@code CellIndexByAddr}. */
	public MemoryCell findByAddress(Address addr) {
		synchronized(m_owner.lock()) {
			if(addr == null || addr.type() != m_type)
				return null;
			return m_byAddress.get(addr.val());
		}
	}

	public MemoryCell findByAddress(long addrValue) {
		synchronized(m_owner.lock()) {
			return m_byAddress.get(addrValue);
		}
	}

	public void remove(MemoryCell cell) {
		synchronized(m_owner.lock()) {
			if(!m_cells.remove(cell))
				return;
			m_owner.indexRemove(cell);
			//-- Rebuild rather than remove: another cell may share this address and has to become
			//-- the one findByAddress answers with.
			reindex();
			recalcRange();
		}
	}

	private void reindex() {
		m_byAddress.clear();
		for(MemoryCell mc : m_cells) {
			m_byAddress.putIfAbsent(mc.getAddr().val(), mc);
		}
	}

	/** Drop every cell. Ported from {@code Clear} ({@code :269-274}). */
	public void clear() {
		synchronized(m_owner.lock()) {
			for(MemoryCell mc : m_cells) {
				m_owner.indexRemove(mc);
			}
			m_cells.clear();
			m_byAddress.clear();
			m_range = AddressRange.empty(m_type);
		}
	}

	/**
	 * Forget every value read from the machine, keeping the cells and any edits. Ported from
	 * {@code Invalidate} ({@code :276-282}); used when the connection changes and nothing
	 * previously read can still be trusted.
	 */
	public void invalidate() {
		synchronized(m_owner.lock()) {
			for(MemoryCell mc : m_cells) {
				mc.setPdpValue(CellValue.UNKNOWN);
			}
		}
	}

	/**
	 * Re-point this group at {@code count} consecutive words starting at {@code start}, keeping
	 * the values of any address that is still in range.
	 *
	 * <p>Ported from {@code ShiftRange} ({@code MemoryCellU.pas:463-517}). This is how a memory
	 * window scrolls and how the disassembler follows the PC: the range moves, the cells that
	 * overlap the old range keep what the machine already told us about them, and only the new
	 * addresses come back unknown and have to be examined.</p>
	 *
	 * <p>The Pascal does this by copying the whole group, growing it, renumbering every address
	 * and looking each one up in the copy. Here it is a snapshot of the old values, a rebuild,
	 * and a lookup - which also removes the ordering trap in the original, where the list has to
	 * be grown <i>before</i> the shift and shrunk <i>after</i> it or the tail is lost.</p>
	 *
	 * <p>One correction. {@code CellIndexByAddr} compares raw address values and ignores the
	 * width they are expressed at, so a group told to shift to a different width matches cells
	 * that are not at the same location at all. Here a value is carried over only when the two
	 * addresses genuinely name the same word - which for the concrete physical widths means
	 * after conversion, exactly as {@link MemoryCellGroups} indexes them.</p>
	 *
	 * @param count how many words. Negative means "as many as there are now", matching the
	 *              Pascal's {@code newsize < 0}.
	 * @param optimize whether to keep the values of addresses that survive the move. False
	 *                 discards everything, which is what a "reload, trust nothing" wants.
	 */
	public void shiftRange(Address start, int count, boolean optimize) {
		if(start == null)
			throw new IllegalArgumentException("A range has to start somewhere");
		synchronized(m_owner.lock()) {
			if(count < 0)
				count = m_cells.size();

			//-- Snapshot before anything moves, keyed at the *new* width, so the lookup below is a
			//-- comparison of locations rather than of numbers that happen to be equal.
			Map<Long, MemoryCell> previous = new HashMap<>();
			if(optimize) {
				for(MemoryCell mc : m_cells) {
					Long key = sameLocationAs(mc.getAddr(), start.type());
					if(key != null)
						previous.putIfAbsent(key, mc);
				}
			}

			clear();
			m_type = start.type();
			m_range = AddressRange.empty(m_type);
			for(int i = 0; i < count; i++) {
				MemoryCell mc = add(start.val() + 2L * i);
				MemoryCell old = previous.get(mc.getAddr().val());
				if(old == null)
					continue;
				//-- Everything but the address, which is the one thing that just changed.
				mc.setPdpValue(old.getPdpValue());
				mc.setEditValue(old.getEditValue());
				mc.setName(old.getName());
				mc.setInfo(old.getInfo());
				mc.setListingLineNr(old.getListingLineNr());
			}
		}
	}

	/** This address expressed at {@code type}, or {@code null} if it is not that kind of address. */
	private static Long sameLocationAs(Address addr, MemoryAddressType type) {
		if(addr.type() == type)
			return addr.val();
		if(addr.type().isConcretePhysical() && type.isConcretePhysical() && addr.fitsWidth(type))
			return addr.withWidth(type).val();
		return null;
	}

	/** Sort by address. Ported from {@code Sort} ({@code :520-545}). */
	public void sort() {
		synchronized(m_owner.lock()) {
			m_cells.sort((a, b) -> Long.compareUnsigned(a.getAddr().val(), b.getAddr().val()));
		}
	}

	public void addListener(MemoryCellListener listener) {
		m_listeners.add(listener);
	}

	public void removeListener(MemoryCellListener listener) {
		m_listeners.remove(listener);
	}

	/** Package-private: only {@link MemoryCellGroups#syncMemoryCells} fires these. */
	void fireMemoryCellChanged(MemoryCell cell) {
		for(MemoryCellListener l : m_listeners) {
			l.memoryCellChanged(this, cell);
		}
	}

	/**
	 * Re-express every cell at a new width and rebuild the index. Called only by
	 * {@link MemoryCellGroups#changeAddressWidth}.
	 */
	void changeWidthInternal(MemoryAddressType newType) {
		assert Thread.holdsLock(m_owner.lock());
		if(newType == m_type)
			return;
		//-- Convert first, index after: a half-converted index would answer lookups wrongly,
		//-- and a conversion that does not fit must leave the group untouched.
		List<Address> converted = new ArrayList<>(m_cells.size());
		for(MemoryCell mc : m_cells) {
			converted.add(mc.getAddr().withWidth(newType));
		}
		for(MemoryCell mc : m_cells) {
			m_owner.indexRemove(mc);
		}
		for(int i = 0; i < m_cells.size(); i++) {
			m_cells.get(i).setAddrInternal(converted.get(i));
		}
		reindex();
		for(MemoryCell mc : m_cells) {
			m_owner.indexAdd(mc);
		}
		m_type = newType;
		recalcRange();
	}

	private void recalcRange() {
		AddressRange r = AddressRange.empty(m_type);
		for(MemoryCell mc : m_cells) {
			r = r.extend(mc.getAddr().val());
		}
		m_range = r;
	}

	@Override
	public String toString() {
		synchronized(m_owner.lock()) {
			return m_groupName + " [" + m_cells.size() + " cells, " + m_range + "]";
		}
	}
}
