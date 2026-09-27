package to.etc.pdp11.core.disas;

/**
 * A 64 KB byte image with a per-byte "this is real data" flag, as the disassembler sees
 * memory.
 *
 * <p>Replaces the {@code coremem}/{@code coremem_valid} pair of {@code PAnsiChar}s that
 * {@code Pdp11DisasU.pas} takes ({@code :53-56}). Those exist only because the unit had to
 * match the ABI of the retired {@code PDP11DISAS.DLL} ({@code Pdp11DisasU.pas:56-58}); with
 * the DLL gone there is no reason to pass raw pointers, and every reason not to.</p>
 *
 * <p>The validity flags are what let the Disassembler window work at all: memory read back
 * from a real PDP-11 is sparse - the user examined some ranges and not others - and an
 * instruction whose extension word was never read cannot be decoded. Words are little-endian,
 * and both bytes of a word must be valid for it to be readable.</p>
 */
public final class MemoryImage {
	/** A PDP-11 program's address space is 64 KB, whatever the physical machine is. */
	public static final int SIZE = 65536;

	private final byte[] m_data = new byte[SIZE];

	private final boolean[] m_valid = new boolean[SIZE];

	/** An image with nothing in it; every address reads as invalid. */
	public MemoryImage() {
	}

	/**
	 * An image with {@code bytes} loaded at {@code baseAddr} and marked valid, everything else
	 * unknown. Wraps at 64 KB, like the machine does.
	 */
	public static MemoryImage of(int baseAddr, byte[] bytes) {
		MemoryImage mi = new MemoryImage();
		mi.put(baseAddr, bytes);
		return mi;
	}

	/** An image containing a single instruction word plus any extension words, at {@code addr}. */
	public static MemoryImage ofWords(int addr, int... words) {
		MemoryImage mi = new MemoryImage();
		for(int i = 0; i < words.length; i++) {
			mi.putWord(addr + i * 2, words[i]);
		}
		return mi;
	}

	public void put(int baseAddr, byte[] bytes) {
		for(int i = 0; i < bytes.length; i++) {
			int a = (baseAddr + i) & 0xFFFF;
			m_data[a] = bytes[i];
			m_valid[a] = true;
		}
	}

	/** Store one little-endian word and mark both its bytes valid. */
	public void putWord(int addr, int value) {
		int lo = addr & 0xFFFF;
		int hi = (addr + 1) & 0xFFFF;
		m_data[lo] = (byte) value;
		m_data[hi] = (byte) (value >>> 8);
		m_valid[lo] = true;
		m_valid[hi] = true;
	}

	/**
	 * Whether a whole word can be read at this address. Ported from {@code WordValid}
	 * ({@code Pdp11DisasU.pas:347-350}), including the address wrap on the high byte: at
	 * {@code 0177777} the second byte is address {@code 0}.
	 */
	public boolean isWordValid(int addr) {
		return m_valid[addr & 0xFFFF] && m_valid[(addr + 1) & 0xFFFF];
	}

	public boolean isByteValid(int addr) {
		return m_valid[addr & 0xFFFF];
	}

	/**
	 * Read a little-endian word, as an unsigned {@code 0..0177777}. Ported from
	 * {@code ReadWord} ({@code Pdp11DisasU.pas:352-355}). Reads of invalid memory return
	 * whatever is there, which is zero; callers check {@link #isWordValid} first.
	 */
	public int readWord(int addr) {
		int lo = m_data[addr & 0xFFFF] & 0xFF;
		int hi = m_data[(addr + 1) & 0xFFFF] & 0xFF;
		return lo | (hi << 8);
	}

	public int readByte(int addr) {
		return m_data[addr & 0xFFFF] & 0xFF;
	}
}
