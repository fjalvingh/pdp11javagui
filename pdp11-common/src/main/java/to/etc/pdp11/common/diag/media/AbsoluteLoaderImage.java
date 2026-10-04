package to.etc.pdp11.common.diag.media;

/**
 * How much of a {@code .BIN} or {@code .BIC} file is program.
 *
 * <p>Those are DEC absolute loader images: blocks of {@code 001 000}, a byte count, a load
 * address, the data and a checksum, ending with a block of count 6 that carries the start address.
 * Whatever follows that block is the slack of the last disk block, and differs from one copy of a
 * program to the next with no effect on what gets loaded. Comparing copies has to stop where the
 * loader stops.</p>
 */
public final class AbsoluteLoaderImage {
	private AbsoluteLoaderImage() {
	}

	/**
	 * The length up to and including the final block's checksum, or the whole length when the data
	 * is not an absolute loader image.
	 */
	public static int significantLength(byte[] data) {
		int p = 0;
		while(true) {
			while(p < data.length && data[p] == 0)
				p++;
			if(p + 6 > data.length || data[p] != 1 || data[p + 1] != 0)
				return data.length;
			int count = (data[p + 2] & 0xff) | (data[p + 3] & 0xff) << 8;
			if(count < 6 || p + count + 1 > data.length)
				return data.length;
			p += count + 1;
			if(count == 6)
				return p;
		}
	}
}
