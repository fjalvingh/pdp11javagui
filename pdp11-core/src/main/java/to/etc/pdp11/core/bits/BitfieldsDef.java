package to.etc.pdp11.core.bits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete set of named bitfields of one register.
 *
 * <p>Ported from {@code TBitfieldsDef} ({@code BitFieldU.pas:56-65}). Named after the machine
 * description section it comes from, like {@code Bits.CPU.PSW} or {@code Bits.CIM.RCSR}.</p>
 */
public final class BitfieldsDef {
	private final String m_name;

	private final List<BitfieldDef> m_fields = new ArrayList<>();

	public BitfieldsDef(String name) {
		if(name == null || name.isBlank())
			throw new IllegalArgumentException("A bitfields definition needs a name");
		m_name = name;
	}

	public String getName() {
		return m_name;
	}

	/**
	 * Add a field.
	 *
	 * <p>Overlapping fields are rejected. The Pascal accepts them silently, which lets a typo
	 * in a machine description produce a register display where editing one field quietly
	 * changes another - and the machine descriptions have never been validated by anything,
	 * because {@code m4.bat} means they cannot even be loaded on Linux (PLAN.md §7).</p>
	 *
	 * @throws IllegalArgumentException if the name is already used, or the bits overlap an
	 *                                  existing field.
	 */
	public void add(BitfieldDef field) {
		for(BitfieldDef existing : m_fields) {
			if(existing.name().equalsIgnoreCase(field.name()))
				throw new IllegalArgumentException(m_name + " already has a field called '" + field.name() + "'");
			if((existing.mask() & field.mask()) != 0)
				throw new IllegalArgumentException(m_name + ": field " + field.toBitRangeString()
					+ " overlaps " + existing.toBitRangeString());
		}
		m_fields.add(field);
	}

	/** The fields, in the order the machine description listed them. */
	public List<BitfieldDef> getFields() {
		return Collections.unmodifiableList(m_fields);
	}

	public boolean isEmpty() {
		return m_fields.isEmpty();
	}

	/** The field of that name, case-insensitively, or {@code null}. */
	public BitfieldDef findByName(String name) {
		for(BitfieldDef f : m_fields) {
			if(f.name().equalsIgnoreCase(name))
				return f;
		}
		return null;
	}

	/** The field containing the given bit, or {@code null} if that bit is undefined here. */
	public BitfieldDef findByBit(int bit) {
		for(BitfieldDef f : m_fields) {
			if(bit >= f.bitLo() && bit <= f.bitHi())
				return f;
		}
		return null;
	}

	/**
	 * A mask of every bit any field covers. Bits outside it are undefined in this register,
	 * which the register display shows differently from a defined zero.
	 */
	public int definedMask() {
		int mask = 0;
		for(BitfieldDef f : m_fields) {
			mask |= f.mask();
		}
		return mask;
	}

	@Override
	public String toString() {
		return m_name + " (" + m_fields.size() + " fields)";
	}
}
