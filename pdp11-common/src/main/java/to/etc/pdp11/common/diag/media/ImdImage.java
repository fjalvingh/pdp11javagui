package to.etc.pdp11.common.diag.media;

import java.util.Arrays;

/**
 * Dave Dunfield's ImageDisk ({@code .IMD}) format, turned back into a plain sector image.
 *
 * <p>Some of the oldest diagnostic floppies on bitsavers, the 1978 RXDP set among them, exist
 * only in this form. An IMD file is an ASCII comment ended by {@code 0x1A}, then one record per
 * track: mode, cylinder, head (with flags for optional cylinder and head maps), sector count, a
 * sector size code, the sector numbering map, and then each sector, either written out in full,
 * compressed to a single fill byte, or missing.</p>
 *
 * <p>The result is the physical image, track after track in cylinder and head order, each track's
 * sectors in sector-number order. A missing sector reads as zeros. For an RX01 that is exactly the
 * physical layout {@link BlockImage#rxInterleaved} expects.</p>
 */
public final class ImdImage {
	private ImdImage() {
	}

	public static boolean looksLikeImd(byte[] data) {
		return data.length > 4 && data[0] == 'I' && data[1] == 'M' && data[2] == 'D' && data[3] == ' ';
	}

	/**
	 * The physical image and its shape.
	 *
	 * @param tracks          how many tracks the file holds, which for an imaging run that
	 *                        stopped early is fewer than the diskette has
	 */
	public record Decoded(byte[] data, int sectorSize, int sectorsPerTrack, int tracks) {
	}

	public static Decoded decode(byte[] imd) throws MediaFormatException {
		if(!looksLikeImd(imd))
			throw new MediaFormatException("This is not an ImageDisk file");
		int p = 0;
		while(p < imd.length && imd[p] != 0x1A)
			p++;
		p++;
		//-- First pass: the geometry. Every track of the floppies this is for has the same sector
		//-- count and size, and an image that does not is not one this needs to read.
		int sectorSize = -1;
		int sectors = -1;
		int maxCylinder = -1;
		int maxHead = 0;
		int minSector = Integer.MAX_VALUE;
		int q = p;
		while(q < imd.length) {
			Track t = track(imd, q);
			if(sectorSize < 0) {
				sectorSize = t.sectorSize;
				sectors = t.count;
			} else if(t.sectorSize != sectorSize || t.count != sectors)
				throw new MediaFormatException("The tracks are not all the same shape");
			maxCylinder = Math.max(maxCylinder, t.cylinder);
			maxHead = Math.max(maxHead, t.head);
			for(int s : t.numbers)
				minSector = Math.min(minSector, s);
			q = t.end;
		}
		if(sectorSize < 0)
			throw new MediaFormatException("The ImageDisk file holds no tracks");
		int heads = maxHead + 1;
		int trackBytes = sectors * sectorSize;
		byte[] out = new byte[(maxCylinder + 1) * heads * trackBytes];
		q = p;
		while(q < imd.length) {
			Track t = track(imd, q);
			int trackBase = (t.cylinder * heads + t.head) * trackBytes;
			int r = t.dataStart;
			for(int i = 0; i < t.count; i++) {
				int kind = imd[r++] & 0xff;
				int index = t.numbers[i] - minSector;
				if(index < 0 || index >= sectors)
					throw new MediaFormatException("Sector " + t.numbers[i] + " does not fit its track");
				int at = trackBase + index * sectorSize;
				if(kind == 0)
					continue;
				if(kind % 2 == 1) {
					System.arraycopy(imd, r, out, at, sectorSize);
					r += sectorSize;
				} else {
					Arrays.fill(out, at, at + sectorSize, imd[r]);
					r++;
				}
			}
			q = t.end;
		}
		return new Decoded(out, sectorSize, sectors, (maxCylinder + 1) * heads);
	}

	private record Track(int cylinder, int head, int count, int sectorSize, int[] numbers, int dataStart, int end) {
	}

	/** One track's header, and where its sectors start and end. */
	private static Track track(byte[] imd, int p) throws MediaFormatException {
		if(p + 5 > imd.length)
			throw new MediaFormatException("The ImageDisk file ends inside a track header");
		int cylinder = imd[p + 1] & 0xff;
		int headByte = imd[p + 2] & 0xff;
		int count = imd[p + 3] & 0xff;
		int sizeCode = imd[p + 4] & 0xff;
		if(sizeCode > 6)
			throw new MediaFormatException("Variable sector sizes are not supported");
		int sectorSize = 128 << sizeCode;
		int r = p + 5;
		if(r + count > imd.length)
			throw new MediaFormatException("The ImageDisk file ends inside a sector map");
		int[] numbers = new int[count];
		for(int i = 0; i < count; i++)
			numbers[i] = imd[r + i] & 0xff;
		r += count;
		if((headByte & 0x80) != 0)
			r += count;
		if((headByte & 0x40) != 0)
			r += count;
		int dataStart = r;
		for(int i = 0; i < count; i++) {
			if(r >= imd.length)
				throw new MediaFormatException("The ImageDisk file ends inside a track");
			int kind = imd[r++] & 0xff;
			if(kind > 8)
				throw new MediaFormatException("Unknown sector record type " + kind);
			if(kind == 0)
				continue;
			r += kind % 2 == 1 ? sectorSize : 1;
		}
		if(r > imd.length)
			throw new MediaFormatException("The ImageDisk file ends inside a sector");
		return new Track(cylinder, headByte & 0x3f, count, sectorSize, numbers, dataStart, r);
	}
}
