package to.etc.pdp11.core.mem;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

/**
 * The lowest and highest address in a group, used as a cheap "is it even worth looking" filter
 * and to show a group's extent.
 *
 * <p>Ported from the {@code min_addr}/{@code max_addr} pair on {@code TMemoryCellGroup}
 * ({@code MemoryCellU.pas:120}), and specifically from {@code extendAddrRange}
 * ({@code :331-341}), which PLAN.md §2 singles out: it does sentinel-detection and ordering in
 * the same {@code min_addr.val > addrval} comparison, and assigns {@code min_addr.val} without
 * setting {@code .mat}. That is precisely the expression that inverts under a signed
 * {@code int}, and precisely the half-initialised value that then propagates.</p>
 *
 * <p>Emptiness is a separate flag here, so no comparison against a magic address value ever
 * happens.</p>
 *
 * @param type  the width all addresses in the range are expressed at
 * @param lo    lowest address, meaningless when {@code empty}
 * @param hi    highest address, meaningless when {@code empty}
 * @param empty whether the range holds anything at all
 */
public record AddressRange(MemoryAddressType type, long lo, long hi, boolean empty) {
	public static AddressRange empty(MemoryAddressType type) {
		return new AddressRange(type, 0, 0, true);
	}

	public static AddressRange of(MemoryAddressType type, long lo, long hi) {
		if(lo > hi)
			throw new IllegalArgumentException("Range low 0" + Long.toOctalString(lo)
				+ " is above high 0" + Long.toOctalString(hi));
		return new AddressRange(type, lo, hi, false);
	}

	/** This range widened to include {@code addrValue}. */
	public AddressRange extend(long addrValue) {
		if(empty)
			return new AddressRange(type, addrValue, addrValue, false);
		return new AddressRange(type, Math.min(lo, addrValue), Math.max(hi, addrValue), false);
	}

	public AddressRange extend(Address addr) {
		if(addr.type() != type)
			throw new IllegalArgumentException("Cannot extend a " + type + " range with a " + addr.type() + " address");
		return extend(addr.val());
	}

	/**
	 * Whether the address could be in this range. A "no" is definite; a "yes" still needs the
	 * lookup, because a group's cells are dense but not contiguous.
	 */
	public boolean mayContain(long addrValue) {
		return !empty && addrValue >= lo && addrValue <= hi;
	}

	public boolean mayContain(Address addr) {
		return addr.type() == type && mayContain(addr.val());
	}

	/** Number of bytes spanned, ends included. Zero for an empty range. */
	public long span() {
		return empty ? 0 : hi - lo + 2;
	}

	@Override
	public String toString() {
		if(empty)
			return "(empty)";
		return Address.of(type, lo).toOctal() + ".." + Address.of(type, hi).toOctal();
	}
}
