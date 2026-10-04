package to.etc.pdp11.common.diag.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the media the readers read, so they can be tested by round trip.
 *
 * <p>There is no writer in the product - the application never makes a disk - so this is the
 * test's own, kept as plain as the format allows. Its layouts are the real ones: an XXDP+ MFD at
 * block 1 naming the UFD in word 1, or a DOS-11 MFD1 at block 1 linking to an MFD2 that names it
 * in word 2; nine-word entries, 28 to a block; files linked through word 0. The things it could
 * share a misunderstanding with the reader about are checked separately, against hand-worked
 * positions and against real media in {@code RealMediaIT}.</p>
 */
public final class TestMedia {
	private TestMedia() {
	}

	public enum Form {
		XXDP_PLUS,
		DOS11
	}

	/** RADIX-50, the DOS character set. */
	static int rad50(String three) {
		String chars = " ABCDEFGHIJKLMNOPQRSTUVWXYZ$.%0123456789";
		String s = (three + "   ").substring(0, 3);
		return chars.indexOf(s.charAt(0)) * 1600 + chars.indexOf(s.charAt(1)) * 40 + chars.indexOf(s.charAt(2));
	}

	static int dosDate(LocalDate d) {
		return d == null ? 0 : (d.getYear() - 1970) * 1000 + d.getDayOfYear();
	}

	static void putWord(byte[] image, int block, int word, int value) {
		int at = block * 512 + word * 2;
		image[at] = (byte) value;
		image[at + 1] = (byte) (value >> 8);
	}

	/**
	 * A disk of {@code blocks} blocks holding {@code files}, linked unless the name ends in
	 * {@code .SAV}. Files start at block 40 and are laid out one after the other.
	 */
	public static byte[] disk(Form form, int blocks, List<MediaFile> files) {
		byte[] image = new byte[blocks * 512];
		int ufd = 3;
		if(form == Form.XXDP_PLUS) {
			putWord(image, 1, 0, 0);
			putWord(image, 1, 1, ufd);
		} else {
			putWord(image, 1, 0, 2);
			putWord(image, 2, 2, ufd);
			putWord(image, 2, 3, 9);
		}
		int ufdBlocks = Math.max(1, (files.size() + 27) / 28);
		for(int i = 0; i < ufdBlocks; i++)
			putWord(image, ufd + i, 0, i + 1 < ufdBlocks ? ufd + i + 1 : 0);
		int next = 40;
		for(int i = 0; i < files.size(); i++) {
			MediaFile f = files.get(i);
			boolean contiguous = f.name().endsWith(".SAV");
			int per = contiguous ? 512 : 510;
			int length = Math.max(1, (f.data().length + per - 1) / per);
			int start = next;
			for(int b = 0; b < length; b++) {
				int block = start + b;
				int from = b * per;
				int n = Math.min(per, f.data().length - from);
				if(contiguous) {
					System.arraycopy(f.data(), from, image, block * 512, Math.max(0, n));
				} else {
					putWord(image, block, 0, b + 1 < length ? block + 1 : 0);
					System.arraycopy(f.data(), from, image, block * 512 + 2, Math.max(0, n));
				}
			}
			next += length;
			int dirBlock = ufd + i / 28;
			int w = 1 + (i % 28) * 9;
			String base = f.baseName();
			putWord(image, dirBlock, w, rad50(base.substring(0, Math.min(3, base.length()))));
			putWord(image, dirBlock, w + 1, rad50(base.length() > 3 ? base.substring(3) : ""));
			putWord(image, dirBlock, w + 2, rad50(f.extension()));
			putWord(image, dirBlock, w + 3, dosDate(f.date()));
			putWord(image, dirBlock, w + 5, start);
			putWord(image, dirBlock, w + 6, length);
			putWord(image, dirBlock, w + 7, start + length - 1);
		}
		return image;
	}

	/**
	 * A logical image written out as a physical RX01 or RX02 diskette, track 0 empty.
	 *
	 * <p>This uses the same arithmetic as {@link BlockImage#rxInterleaved}, run the other way, so a
	 * round trip through it proves only that the two agree. {@code BlockImageTest} pins the
	 * arithmetic itself to sector positions worked out by hand.</p>
	 */
	public static byte[] rxPhysical(byte[] logical, int sectorSize) {
		int spt = 26;
		byte[] out = new byte[77 * spt * sectorSize];
		int sectors = Math.min(logical.length / sectorSize, 76 * spt);
		for(int lsn = 0; lsn < sectors; lsn++) {
			int track = lsn / spt;
			int sector = lsn % spt;
			sector = (2 * sector + (2 * sector >= spt ? 1 : 0)) % spt;
			sector = (sector + 6 * track) % spt;
			System.arraycopy(logical, lsn * sectorSize, out, ((track + 1) * spt + sector) * sectorSize, sectorSize);
		}
		return out;
	}

	/** A SimH tape with each file in DOS-11 format, as XXDP's tape driver writes it. */
	public static byte[] tape(List<MediaFile> files) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for(MediaFile f : files) {
			byte[] header = new byte[14];
			String base = f.baseName();
			word(header, 0, rad50(base.substring(0, Math.min(3, base.length()))));
			word(header, 1, rad50(base.length() > 3 ? base.substring(3) : ""));
			word(header, 2, rad50(f.extension()));
			word(header, 3, 01002);
			word(header, 4, 0233);
			word(header, 5, dosDate(f.date()));
			record(out, header);
			for(int from = 0; from < f.data().length; from += 510) {
				byte[] rec = new byte[512];
				word(rec, 0, 0177777);
				System.arraycopy(f.data(), from, rec, 2, Math.min(510, f.data().length - from));
				record(out, rec);
			}
			record(out, new byte[512]);
			u32(out, 0);
		}
		u32(out, 0);
		u32(out, 0xFFFFFFFFL);
		return out.toByteArray();
	}

	private static void word(byte[] b, int index, int value) {
		b[index * 2] = (byte) value;
		b[index * 2 + 1] = (byte) (value >> 8);
	}

	static void record(ByteArrayOutputStream out, byte[] data) {
		u32(out, data.length);
		out.writeBytes(data);
		if((data.length & 1) != 0)
			out.write(0);
		u32(out, data.length);
	}

	static void u32(ByteArrayOutputStream out, long v) {
		out.write((int) v);
		out.write((int) (v >> 8));
		out.write((int) (v >> 16));
		out.write((int) (v >> 24));
	}

	/**
	 * An ImageDisk file of a physical image of 26 sectors to the track, the first {@code tracks}
	 * tracks only. Sectors that are one byte repeated are written compressed, as ImageDisk does;
	 * every other sector after the first is listed out of order, so the numbering map matters.
	 */
	public static byte[] imd(byte[] physical, int sectorSize, int tracks) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("IMD 1.18: test image\r\n".getBytes());
		out.write(0x1A);
		int spt = 26;
		int code = Integer.numberOfTrailingZeros(sectorSize / 128);
		for(int t = 0; t < tracks; t++) {
			out.write(0);
			out.write(t);
			out.write(0);
			out.write(spt);
			out.write(code);
			int[] order = new int[spt];
			for(int i = 0; i < spt; i++)
				order[i] = i % 2 == 0 ? i / 2 + 1 : spt - i / 2;
			for(int s : order)
				out.write(s);
			for(int s : order) {
				int at = (t * spt + s - 1) * sectorSize;
				boolean same = true;
				for(int i = 1; i < sectorSize; i++)
					same &= physical[at + i] == physical[at];
				if(same) {
					out.write(2);
					out.write(physical[at]);
				} else {
					out.write(1);
					out.write(physical, at, sectorSize);
				}
			}
		}
		return out.toByteArray();
	}

	public static byte[] gzip(byte[] data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try(GZIPOutputStream gz = new GZIPOutputStream(out)) {
			gz.write(data);
		} catch(IOException x) {
			throw new IllegalStateException(x);
		}
		return out.toByteArray();
	}

	/**
	 * An absolute loader image: one data block of {@code bytes} at {@code address}, then the
	 * transfer block, then {@code slack} bytes of rubbish as a disk block would leave.
	 */
	public static byte[] absoluteLoader(int address, byte[] bytes, int start, byte[] slack) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, address, bytes);
		block(out, start, new byte[0]);
		out.writeBytes(slack);
		return out.toByteArray();
	}

	private static void block(ByteArrayOutputStream out, int address, byte[] data) {
		int count = data.length + 6;
		int sum = 1 + (count & 0xff) + (count >> 8) + (address & 0xff) + ((address >> 8) & 0xff);
		out.write(1);
		out.write(0);
		out.write(count);
		out.write(count >> 8);
		out.write(address);
		out.write(address >> 8);
		for(byte b : data) {
			out.write(b);
			sum += b & 0xff;
		}
		out.write(-sum & 0xff);
	}

	/** {@code n} bytes counting up from {@code seed}, so that a misplaced block shows. */
	public static byte[] pattern(int n, int seed) {
		byte[] b = new byte[n];
		for(int i = 0; i < n; i++)
			b[i] = (byte) (seed + i * 7 + i / 251);
		return b;
	}
}
