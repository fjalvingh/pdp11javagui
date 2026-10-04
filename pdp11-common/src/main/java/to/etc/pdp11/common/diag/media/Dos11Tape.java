package to.etc.pdp11.common.diag.media;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The files on a DOS-11 format magnetic tape, read from a SimH {@code .tap} image.
 *
 * <p>This is how DEC shipped the MMDP and MSDP diagnostic tapes. Each file is a 14-byte header
 * record - name and extension in RADIX-50, owner, protection, date, and a spare word - followed by
 * its data in 512-byte records, and closed by a tape mark. Two tape marks in a row end the tape. A
 * data record is a disk block - a link word and 510 bytes of the file - so a file read off tape is
 * byte for byte the file read off an XXDP disk.</p>
 *
 * <p>The container is SimH's: every record is a 32-bit little-endian length, the data padded to an
 * even length, and the length again. A length of zero is a tape mark, {@code 0xFFFFFFFF} the end of
 * the medium, and {@code 0xFFFFFFFE} an erase gap. A record SimH marked bad in the top bits keeps
 * its data and is read like any other; that is what the drive would have handed the monitor.</p>
 */
public final class Dos11Tape {
	private static final int HEADER_LENGTH = 14;

	private static final long TAPE_MARK = 0;

	private static final long END_OF_MEDIUM = 0xFFFFFFFFL;

	private static final long ERASE_GAP = 0xFFFFFFFEL;

	private Dos11Tape() {
	}

	/** A record, or a mark. {@code data} is null for a tape mark. */
	private record Record(byte[] data) {
	}

	/**
	 * Every file on the tape, in tape order.
	 *
	 * @throws MediaFormatException when the image is not a SimH tape, or holds no DOS-11 file
	 */
	public static List<MediaFile> read(byte[] tape, List<String> problems) throws MediaFormatException {
		List<Record> records = records(tape, problems);
		List<MediaFile> files = new ArrayList<>();
		int i = 0;
		int marks = 0;
		while(i < records.size()) {
			Record r = records.get(i++);
			if(r.data() == null) {
				if(++marks >= 2)
					break;
				continue;
			}
			marks = 0;
			if(r.data().length != HEADER_LENGTH) {
				//-- Not a file header where one should be: skip to the next mark, and say so once.
				problems.add("A " + r.data().length + "-byte record where a file header should be; skipped to the next tape mark");
				while(i < records.size() && records.get(i).data() != null)
					i++;
				continue;
			}
			byte[] h = r.data();
			String name = DosNames.fileName(BlockImage.word(h, 0), BlockImage.word(h, 1), BlockImage.word(h, 2));
			ByteArrayOutputStream data = new ByteArrayOutputStream();
			while(i < records.size() && records.get(i).data() != null) {
				byte[] rec = records.get(i++).data();
				//-- Each data record is a disk block as XXDP's tape driver writes it: a link word,
				//-- which on tape is -1 for "more follows", and 510 bytes of file. The file ends
				//-- with a record whose link is zero and whose data is empty.
				if(rec.length > 2 && !(BlockImage.word(rec, 0) == 0 && isZero(rec)))
					data.write(rec, 2, rec.length - 2);
			}
			if(!DosNames.isPlausibleName(name)) {
				problems.add("A file header that names no file (\"" + name.replace('\0', '?') + "\"); skipped");
				continue;
			}
			files.add(new MediaFile(name, DosNames.date(BlockImage.word(h, 5)), data.toByteArray()));
		}
		if(files.isEmpty())
			throw new MediaFormatException("The tape holds no DOS-11 files");
		return files;
	}

	private static List<Record> records(byte[] tape, List<String> problems) throws MediaFormatException {
		List<Record> records = new ArrayList<>();
		int p = 0;
		while(p + 4 <= tape.length) {
			long header = u32(tape, p);
			p += 4;
			if(header == TAPE_MARK) {
				records.add(new Record(null));
				continue;
			}
			if(header == END_OF_MEDIUM)
				break;
			if(header == ERASE_GAP)
				continue;
			int length = (int) (header & 0x00FFFFFF);
			int padded = length + (length & 1);
			if(p + padded + 4 > tape.length) {
				if(records.isEmpty())
					throw new MediaFormatException("This is not a SimH tape image");
				problems.add("The tape image ends inside a record");
				break;
			}
			byte[] data = new byte[length];
			System.arraycopy(tape, p, data, 0, length);
			p += padded;
			if(u32(tape, p) != header) {
				if(records.isEmpty())
					throw new MediaFormatException("This is not a SimH tape image");
				problems.add("A record's trailing length does not match its leading one; the rest of the tape is ignored");
				break;
			}
			p += 4;
			records.add(new Record(data));
		}
		return records;
	}

	private static boolean isZero(byte[] b) {
		for(byte x : b) {
			if(x != 0)
				return false;
		}
		return true;
	}

	private static long u32(byte[] b, int p) {
		return (b[p] & 0xffL) | (b[p + 1] & 0xffL) << 8 | (b[p + 2] & 0xffL) << 16 | (b[p + 3] & 0xffL) << 24;
	}
}
