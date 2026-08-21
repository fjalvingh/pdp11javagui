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
	 * <p><b>Overlapping fields are legal and common.</b> Many PDP-11 device registers mean one
	 * thing when read and another when written, and the descriptions define both at one
	 * address with a name prefix - the DZ11's {@code Bits.M7819.RBUF_LPR} carries
	 * {@code RBUF.PAR ERR<12>} and {@code LPR.RX ON<12>} together, and there are 41 such
	 * fields across the shipped description. Rejecting overlap looked like a sensible
	 * tightening until the real data said otherwise.</p>
	 *
	 * @throws IllegalArgumentException if the name is already used - that one really is a typo.
	 */
	public void add(BitfieldDef field) {
		for(BitfieldDef existing : m_fields) {
			if(existing.name().equalsIgnoreCase(field.name()))
				throw new IllegalArgumentException(m_name + " already has a field called '" + field.name() + "'");
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

	/**
	 * Every field covering the given bit, in declaration order. More than one is normal: see
	 * {@link #add}. Empty when the bit is undefined in this register, which the register
	 * display shows differently from a defined zero.
	 */
	public List<BitfieldDef> fieldsAtBit(int bit) {
		List<BitfieldDef> found = new ArrayList<>(1);
		for(BitfieldDef f : m_fields) {
			if(bit >= f.bitLo() && bit <= f.bitHi())
				found.add(f);
		}
		return found;
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
