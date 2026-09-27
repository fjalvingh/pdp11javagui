package to.etc.pdp11.core.memtest;

/**
 * How much address space one memory chip covers, which is what the tests step by.
 *
 * <p>From the {@code ChipAddrSizeComboBox} entries ({@code FormMemoryTestU.pas:257-265}). The
 * tests do not read every word - that would take hours over a serial line - they read a few
 * words per chip, so they have to be told how big a chip is. Guessing smaller than the truth
 * costs time; guessing larger misses whole chips.</p>
 */
public enum ChipSize {
	/** One word. Every address is its own "chip": the slowest and most thorough setting. */
	WORD(2, "Single word"),
	K1(1024, "1K"),
	K2(2048, "2K"),
	K4(4096, "4K"),
	K16(16384, "16K"),
	K64(65536, "64K"),
	K256(262144, "256K");

	private final int m_bytes;

	private final String m_label;

	ChipSize(int bytes, String label) {
		m_bytes = bytes;
		m_label = label;
	}

	public int getBytes() {
		return m_bytes;
	}

	public String getLabel() {
		return m_label;
	}

	/** What the Pascal preselects: {@code ItemIndex := 3} ({@code :202}). */
	public static ChipSize getDefault() {
		return K4;
	}

	@Override
	public String toString() {
		return m_label;
	}
}
