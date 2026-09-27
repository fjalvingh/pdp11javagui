package to.etc.pdp11.core.bits;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every bitfield definition a machine description declares, plus the addresses they apply to.
 *
 * <p>Ported from {@code TBitfieldsDefs} ({@code BitFieldU.pas:74-91}), minus the
 * {@code LoadFromIniFile} half - machine-description parsing arrives in phase 2 and calls
 * {@link #add} and {@link #linkAddress}.</p>
 *
 * <h2>Lookup by address</h2>
 *
 * <p>{@code BitFieldsDefByAddr} ({@code BitFieldU.pas:275-293}) scans the whole address list
 * linearly, and when a stored address is at a different width than the one being looked up it
 * <i>rewrites the stored address in place</i> as a cache, on the theory that the machine
 * rarely changes. That mutation cannot follow into an immutable {@link Address}, and it is
 * not needed: machine descriptions define device registers only, always in the I/O page,
 * always as 16-bit addresses in the range {@code 0160000}..{@code 0177776}. So the map is
 * keyed on the 16-bit form and the lookup converts once, which is a hash lookup rather than a
 * scan and has no cached state to get stale.</p>
 */
public final class BitfieldsDefs {
	/** Machine descriptions write I/O page addresses in 16 bits; that is the canonical key. */
	private static final MemoryAddressType KEY_TYPE = MemoryAddressType.PHYSICAL16;

	private final Map<String, BitfieldsDef> m_byName = new LinkedHashMap<>();

	private final Map<Long, BitfieldsDef> m_byIopageAddress = new HashMap<>();

	/**
	 * @throws IllegalArgumentException if a definition of that name already exists.
	 */
	public void add(BitfieldsDef def) {
		String key = def.getName().toUpperCase(Locale.ROOT);
		BitfieldsDef old = m_byName.putIfAbsent(key, def);
		if(old != null)
			throw new IllegalArgumentException("Duplicate bitfields definition '" + def.getName() + "'");
	}

	/** Ported from {@code BitFieldsDefByName}; case-insensitive, {@code null} if absent. */
	public BitfieldsDef findByName(String name) {
		return name == null ? null : m_byName.get(name.toUpperCase(Locale.ROOT));
	}

	public List<BitfieldsDef> getDefinitions() {
		return Collections.unmodifiableList(new ArrayList<>(m_byName.values()));
	}

	/**
	 * Declare that the register at {@code addr} is described by the named definition. Ported
	 * from {@code LinkAddr2BitfieldsDef} ({@code BitFieldU.pas:257-270}); returns {@code false}
	 * when the name is unknown, which is how a machine description referring to a definition
	 * it never declares is reported rather than crashing the load.
	 *
	 * @throws IllegalArgumentException if the address is not a concrete physical one, or lies
	 *                                  outside the I/O page.
	 */
	public boolean linkAddress(Address addr, String bitfieldsDefName) {
		BitfieldsDef def = findByName(bitfieldsDefName);
		if(def == null)
			return false;
		m_byIopageAddress.put(canonicalKey(addr), def);
		return true;
	}

	/**
	 * The definition describing the register at this address, or {@code null} if there is
	 * none. Ported from {@code BitFieldsDefByAddr}.
	 *
	 * <p>Accepts an address at any concrete physical width and converts it. An address
	 * outside the I/O page always answers {@code null} rather than throwing: asking "does this
	 * ordinary memory location have named bits" is a reasonable question with a boring
	 * answer, and the memory windows ask it for every cell they display.</p>
	 */
	public BitfieldsDef findByAddress(Address addr) {
		if(addr == null || !addr.type().isConcretePhysical() || !addr.isInIopage())
			return null;
		return m_byIopageAddress.get(addr.withWidth(KEY_TYPE).val());
	}

	public boolean isEmpty() {
		return m_byName.isEmpty();
	}

	/** Ported from {@code Clear}/{@code UnLoad}, which do the same thing. */
	public void clear() {
		m_byName.clear();
		m_byIopageAddress.clear();
	}

	private static long canonicalKey(Address addr) {
		if(!addr.type().isConcretePhysical())
			throw new IllegalArgumentException("Bitfields can only be linked to a concrete physical address, not " + addr);
		if(!addr.isInIopage())
			throw new IllegalArgumentException("Bitfields can only be linked to I/O page registers; " + addr
				+ " is below the I/O page at 0" + Long.toOctalString(addr.type().getIopageBase()));
		return addr.withWidth(KEY_TYPE).val();
	}

	@Override
	public String toString() {
		return m_byName.size() + " bitfield definitions, " + m_byIopageAddress.size() + " addresses";
	}
}
