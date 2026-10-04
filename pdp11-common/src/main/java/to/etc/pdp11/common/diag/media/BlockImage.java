package to.etc.pdp11.common.diag.media;

/**
 * A disk image seen as the 512-byte logical blocks the file system addresses.
 *
 * <p>Most images are those blocks one after the other. Floppy images are not always: an image of a
 * whole RX01 or RX02 diskette holds the physical sectors in track order, and the driver that wrote
 * the file system skipped track 0 and interleaved the rest. Which kind an image is cannot be told
 * from its size alone - bitsavers has RX01 images of both kinds - so the caller tries each mapping
 * and keeps the one under which the directory makes sense.</p>
 */
public abstract class BlockImage {
	public static final int BLOCK_SIZE = 512;

	/** How many whole blocks the image holds. */
	public abstract int blockCount();

	/** What this mapping is, for a message: "linear", "RX02 interleaved". */
	public abstract String describe();

	/** Read block {@code n} into {@code into}, which is {@link #BLOCK_SIZE} long. */
	protected abstract void read(int n, byte[] into);

	/**
	 * Block {@code n}, or {@code null} when the image has no such block.
	 *
	 * <p>Null rather than an exception: a link out of range is the ordinary sign that the mapping
	 * being tried is the wrong one, and the caller is asking in order to find that out.</p>
	 */
	public final byte[] block(int n) {
		if(n < 0 || n >= blockCount())
			return null;
		byte[] b = new byte[BLOCK_SIZE];
		read(n, b);
		return b;
	}

	/** Word {@code index} of a block, little-endian as the PDP-11 stores it. */
	public static int word(byte[] block, int index) {
		return (block[index * 2] & 0xff) | (block[index * 2 + 1] & 0xff) << 8;
	}

	/** The image's bytes taken as consecutive blocks. Any partial block at the end is ignored. */
	public static BlockImage linear(byte[] data) {
		return new BlockImage() {
			@Override
			public int blockCount() {
				return data.length / BLOCK_SIZE;
			}

			@Override
			public String describe() {
				return "linear";
			}

			@Override
			protected void read(int n, byte[] into) {
				System.arraycopy(data, n * BLOCK_SIZE, into, 0, BLOCK_SIZE);
			}
		};
	}

	/**
	 * A physical RX01 or RX02 image - 77 tracks of 26 sectors, in track order - read the way DEC's
	 * drivers lay a file system on it.
	 *
	 * <p>Track 0 is skipped; logical sectors go round the remaining tracks with a 2:1 interleave
	 * inside the track and a further six-sector skew from each track to the next. The arithmetic is
	 * the one AK6DN's {@code xxdpdir} uses, which reads the real images.</p>
	 *
	 * @param sectorSize 128 for RX01, 256 for RX02
	 */
	public static BlockImage rxInterleaved(byte[] data, int sectorSize) {
		if(sectorSize != 128 && sectorSize != 256)
			throw new IllegalArgumentException("An RX sector is 128 or 256 bytes, not " + sectorSize);
		int sectorsPerTrack = 26;
		int sectorsPerBlock = BLOCK_SIZE / sectorSize;
		int dataTracks = data.length / (sectorsPerTrack * sectorSize) - 1;
		int blocks = Math.max(0, dataTracks * sectorsPerTrack / sectorsPerBlock);
		return new BlockImage() {
			@Override
			public int blockCount() {
				return blocks;
			}

			@Override
			public String describe() {
				return (sectorSize == 128 ? "RX01" : "RX02") + " interleaved";
			}

			@Override
			protected void read(int n, byte[] into) {
				for(int i = 0; i < sectorsPerBlock; i++) {
					int logical = n * sectorsPerBlock + i;
					int track = logical / sectorsPerTrack;
					int sector = logical % sectorsPerTrack;
					sector = (2 * sector + (2 * sector >= sectorsPerTrack ? 1 : 0)) % sectorsPerTrack;
					sector = (sector + 6 * track) % sectorsPerTrack;
					int position = ((track + 1) * sectorsPerTrack + sector) * sectorSize;
					System.arraycopy(data, position, into, i * sectorSize, sectorSize);
				}
			}
		};
	}
}
