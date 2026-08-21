package to.etc.pdp11.core.mem;

import to.etc.pdp11.core.addr.Address;

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
 */
public final class MemoryCell {
	private final MemoryCellGroup m_group;

	/**
	 * Not final: {@code MemoryCellGroups.changeAddressWidth} re-expresses every cell when the
	 * target machine changes. The {@link Address} itself stays immutable - what moves is which
	 * address this cell points at, and the group's index is rebuilt to match.
	 */
	private Address m_addr;

	/** What the machine last reported. Only this value propagates between groups. */
	private CellValue m_pdpValue = CellValue.UNKNOWN;

	/**
	 * What the user typed. Never propagates: another window's refresh must not overwrite an
	 * edit that has not been deposited yet.
	 */
	private CellValue m_editValue = CellValue.UNKNOWN;

	/** The register's name, when this address is a known one. Empty otherwise. */
	private String m_name = "";

	/** Description, shown as a tooltip. */
	private String m_info = "";

	/** Line in the MACRO-11 listing this came from, or -1. First line is 0. */
	private int m_listingLineNr = -1;

	MemoryCell(MemoryCellGroup group, Address addr) {
		m_group = group;
		m_addr = addr;
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

	public CellValue getPdpValue() {
		return m_pdpValue;
	}

	/**
	 * Record what the machine said.
	 *
	 * <p>Deliberately does <b>not</b> propagate to other groups. In the Pascal the two are
	 * welded together - {@code TMemoryCell.Examine} sets the value and calls
	 * {@code SyncMemoryCells} in the same breath ({@code :221-231}) - but that only works
	 * because examining is a method on the cell. Here the console sets values and then calls
	 * {@link MemoryCellGroups#syncMemoryCells} once, which is both clearer and lets a bulk
	 * examine avoid a propagation storm per word.</p>
	 */
	public void setPdpValue(CellValue value) {
		m_pdpValue = value == null ? CellValue.UNKNOWN : value;
	}

	public CellValue getEditValue() {
		return m_editValue;
	}

	public void setEditValue(CellValue value) {
		m_editValue = value == null ? CellValue.UNKNOWN : value;
	}

	/** After a successful deposit the two agree. */
	public void setDeposited() {
		m_pdpValue = m_editValue;
	}

	/** Whether the user has typed something that differs from what the machine holds. */
	public boolean isEdited() {
		return m_editValue.isKnown() && !m_editValue.equals(m_pdpValue);
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

	/**
	 * Copy address, values and labels from another cell, leaving group membership alone.
	 * Ported from {@code TMemoryCell.Assign} ({@code :211-219}).
	 */
	public void assignFrom(MemoryCell other) {
		m_addr = other.m_addr;
		m_pdpValue = other.m_pdpValue;
		m_editValue = other.m_editValue;
		m_name = other.m_name;
		m_info = other.m_info;
		m_listingLineNr = other.m_listingLineNr;
	}

	@Override
	public String toString() {
		return m_addr.toOctal() + "=" + m_pdpValue + (m_name.isEmpty() ? "" : " (" + m_name + ")");
	}
}
