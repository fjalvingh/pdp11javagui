package to.etc.pdp11.core.addr;

/**
 * Where PDP11GUI puts the CPU's own registers in the I/O page.
 *
 * <p>A PDP-11's general registers are not memory and have no address; the console reaches them
 * with a command of its own ({@code R3/} on ODT, {@code E R3} on SimH, {@code E/G 3} on the
 * 11/44). PDP11GUI nonetheless needs one name for a location, so it gives them <b>pseudo
 * addresses</b> at the top of the I/O page and every console converts back to its own syntax.
 * The convention comes from the Pascal and is worth stating once: the registers are
 * <b>byte-spaced</b>, one apart rather than two, because eight registers at two bytes each
 * would run into the PSW.</p>
 *
 * <p>These are <b>offsets within the I/O page</b>, not addresses. That is what lets one set of
 * numbers serve a 16, 18 and 22-bit machine: the page sits at the top of the address space and
 * the top moves, so {@link #addressIn} is how an offset becomes an address. The same three
 * numbers were written out separately in four classes - two consoles and both fakes - which is
 * three chances for a machine and its simulator to disagree about where R0 is.</p>
 */
public final class CpuRegisters {
	/** R0's offset within the I/O page. R1..R7 follow at one byte each. */
	public static final int R0_OFFSET = 017700;

	/** R7, which is the PC. */
	public static final int R7_OFFSET = 017707;

	/** R7 under its other name. */
	public static final int PC_OFFSET = R7_OFFSET;

	/** The processor status word, which really is a location on most models. */
	public static final int PSW_OFFSET = 017776;

	private CpuRegisters() {
	}

	/** That offset as an address on a machine of the given width. */
	public static long addressIn(MemoryAddressType type, int iopageOffset) {
		return type.getIopageBase() + iopageOffset;
	}
}
