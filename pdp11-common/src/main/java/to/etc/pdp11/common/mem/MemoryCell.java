package to.etc.pdp11.common.mem;

import to.etc.pdp11.common.addr.Address;

/**
 * One PDP-11 word: an address, what the machine last said was there, and what the user has
 * typed but not yet deposited.
 *
 * <p>Ported from {@code TMemoryCell} ({@code MemoryCellU.pas:64-96}), with three things left
 * behind.</p>
 *
 * <p><b>The widget back-references are gone.</b> The Pascal cell carries
 * {@code grid: TStringGrid} plus {@code grid_r, grid_c} ({@code :74-75}) - a model object
 * holding a pointer into a Swing-equivalent table. That cannot follow into a module that must
 * not depend on AWT, and it would not survive a second window showing the same cell anyway.</p>
 *
 * <p><b>{@code Examine} and {@code Deposit} are gone.</b> They called
 * {@code FormMain.PDP11Console} directly ({@code :221-244}), which is how a data class ends up
 * depending on the main form. Talking to the machine belongs to the console layer of PLAN.md
 * §1; a cell is data plus a notification.</p>
 *
 * <p><b>The {@code $ffffffff} sentinel is gone</b>, replaced by {@link CellValue}.</p>
 *
 * <p><b>A cell is a view; its values live in a {@link MemoryWord}.</b> For a word of memory
 * below the I/O page that word is the one in the {@link SharedMemory}, shared by every cell at
 * that address in every window - so what one window loads or types, all of them show. What is
 * per view stays here: the address as this window expresses it, the name, the tooltip and the
 * listing line. A device register, or a cell of a group that asked for its own values, has a
 * word of its own. See PLAN.md §1, "Shared memory".</p>
 *
 * <p>The edit value is what should be there: {@link #getEditValue} answers with the pending
 * edit when there is one and with the machine value otherwise, which is what a window shows.
 * An examine sets the machine value and nothing else; an edit goes away by being deposited or
 * discarded, never by being read over.</p>
 */
public final class MemoryCell {
	private final MemoryCellGroup m_group;

	/**
	 * Not final: {@code MemoryCellGroups.changeAddressWidth} re-expresses every cell when the
	 * target machine changes. The {@link Address} itself stays immutable - what moves is which
	 * address this cell points at, and the group's index is rebuilt to match.
	 */
	private volatile Address m_addr;

	/**
	 * Where this cell is, as the propagation index keys it; see {@link MemoryCellGroups}. Not
	 * final, for a virtual address: when the MMU maps it somewhere else, the cell moves to the
	 * word it now names ({@link MemoryCellGroups#reresolveVirtual}).
	 */
	private volatile MemoryCellGroups.CellLocation m_location;

	/** Where the values live: in shared memory, or this cell's own. Moves with the location. */
	private volatile MemoryWord m_word;

	/**
	 * The word's version when this cell last saw it change - by writing it, or by being told.
	 * The propagation bus tells a cell sharing a word only when this is behind, which is what
	 * stops two windows over one word re-announcing it to each other forever.
	 */
	private volatile long m_seenVersion = -1;

	/** The register's name, when this address is a known one. Empty otherwise. */
	private volatile String m_name = "";

	/** Description, shown as a tooltip. */
	private volatile String m_info = "";

	/** Line in the MACRO-11 listing this came from, or -1. First line is 0. */
	private volatile int m_listingLineNr = -1;

	MemoryCell(MemoryCellGroup group, Address addr, MemoryCellGroups.CellLocation location, MemoryWord word) {
		m_group = group;
		m_addr = addr;
		m_location = location;
		m_word = word;
		m_seenVersion = word.version();
	}

	public MemoryCellGroup getGroup() {
		return m_group;
	}

	public Address getAddr() {
		return m_addr;
	}

	/** Only {@link MemoryCellGroups#changeAddressWidth} may move a cell, and it reindexes. */
	void setAddrInternal(Address addr) {
		m_addr = addr;
	}

	MemoryCellGroups.CellLocation getLocation() {
		return m_location;
	}

	MemoryWord word() {
		return m_word;
	}

	/** Point this cell at another word. Only {@link MemoryCellGroups#reresolveVirtual}, under the monitor. */
	void rebind(MemoryCellGroups.CellLocation location, MemoryWord word) {
		m_location = location;
		m_word = word;
		m_seenVersion = -1;
	}

	/** Mark the word's current state as seen by this cell; true if it was not yet. */
	boolean catchUp() {
		long v = m_word.version();
		if(m_seenVersion == v)
			return false;
		m_seenVersion = v;
		return true;
	}

	/**
	 * Whether this cell's values are in shared memory, along with every other cell at the
	 * same physical word. False in the I/O page and for groups that keep their own.
	 */
	public boolean isShared() {
		return m_word.isShared();
	}

	/** The 22-bit physical address of the shared word, or {@code null} if this cell has its own. */
	public Address getPhysical() {
		return m_word.getPhysical();
	}

	/** What the machine last reported. */
	public CellValue getPdpValue() {
		return m_word.machine();
	}

	/**
	 * Record what the machine said. Never touches a pending edit.
	 *
	 * <p>Deliberately does <b>not</b> tell anyone. In the Pascal the two are welded together -
	 * {@code TMemoryCell.Examine} sets the value and calls {@code SyncMemoryCells} in the same
	 * breath ({@code :221-231}) - but that only works because examining is a method on the cell.
	 * Here the console sets values and then calls {@link MemoryCellGroups#syncMemoryCells} once,
	 * which is both clearer and lets a bulk examine avoid a propagation storm per word.</p>
	 */
	public void setPdpValue(CellValue value) {
		m_word.setMachine(value);
		m_seenVersion = m_word.version();
	}

	/**
	 * What an examine of this one cell means to a window: the machine said {@code value}. In the
	 * shared memory that is all it means. A device register keeps the old rule, where reading it
	 * also gives up whatever was typed there - the value just read is the one to start from, and
	 * nobody else shares the edit.
	 */
	public void setExamined(CellValue value) {
		setPdpValue(value);
		if(!isShared())
			discardEdit();
	}

	/** Whether the machine value is known and was read since the machine last ran. */
	public boolean isMachineValueCurrent() {
		return m_word.isMachineCurrent();
	}

	/** Whether the machine value was read, but before the machine last ran. */
	public boolean isStale() {
		return m_word.isStale();
	}

	/** What should be there: the pending edit if there is one, otherwise the machine value. */
	public CellValue getEditValue() {
		return m_word.effective();
	}

	/**
	 * Set what should be there, as an edit made by this cell's group. Unknown removes the edit.
	 * Like {@link #setPdpValue} this tells nobody; call {@link MemoryCellGroups#syncMemoryCells}
	 * when other windows should see it.
	 */
	public void setEditValue(CellValue value) {
		m_word.setEdit(value, m_group);
		m_seenVersion = m_word.version();
	}

	/** Drop the pending edit, so this word shows what the machine holds again. */
	public void discardEdit() {
		m_word.discardEdit();
		m_seenVersion = m_word.version();
	}

	/** After a successful deposit the machine holds what was shown, and there is no edit. */
	public void setDeposited() {
		m_word.setDeposited();
		m_seenVersion = m_word.version();
	}

	/**
	 * Whether there is something to deposit: an edit the machine is not known to hold. An edit
	 * equal to a value read before the machine last ran counts - it may have changed since.
	 */
	public boolean isEdited() {
		return m_word.isPending();
	}

	/** The group that made the pending edit, if it is still open; {@code null} otherwise. */
	public MemoryCellGroup getEditOwner() {
		return m_word.owner();
	}

	/** Take both values of another cell that keeps its own. Only for re-ranging a group. */
	void copyValuesFrom(MemoryCell other) {
		m_word.copyFrom(other.m_word);
		m_seenVersion = m_word.version();
	}

	public String getName() {
		return m_name;
	}

	public void setName(String name) {
		m_name = name == null ? "" : name;
	}

	public String getInfo() {
		return m_info;
	}

	public void setInfo(String info) {
		m_info = info == null ? "" : info;
	}

	public int getListingLineNr() {
		return m_listingLineNr;
	}

	public void setListingLineNr(int listingLineNr) {
		m_listingLineNr = listingLineNr;
	}

	@Override
	public String toString() {
		return m_addr.toOctal() + "=" + getPdpValue() + (m_name.isEmpty() ? "" : " (" + m_name + ")");
	}
}
