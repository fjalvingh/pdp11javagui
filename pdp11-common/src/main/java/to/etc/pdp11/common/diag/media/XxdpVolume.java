package to.etc.pdp11.common.diag.media;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The file system on an XXDP disk, DECtape or TU58: its directory, and the files it names.
 *
 * <p>XXDP uses the DOS-11 structure in two variants, and both are read here. The reference is
 * DEC's <i>XXDP+ File Structure Document</i> (CHQFSA0, April 1981) as AK6DN's {@code xxdpdir}
 * reads it, which was in turn checked against real media:</p>
 *
 * <ul>
 * <li><b>DOS-11 form</b> - TU58, RX01, RX02, RK05, the RP disks. Block 1 is MFD1, whose word 0
 *     links to MFD2; word 2 of MFD2 is the first directory (UFD) block.</li>
 * <li><b>XXDP+ form</b> - RL01/02, RK06/07, RM, MSCP. Block 1 is a single MFD whose word 0 is
 *     zero, and whose word 1 is the first UFD block.</li>
 * </ul>
 *
 * <p>Either way the directory is a chain of blocks linked through word 0, each holding 28 entries
 * of nine words: the name in two RADIX-50 words, the extension in one, the date, a word nobody
 * uses, the first block, the length in blocks, the last block, and another unused word. An entry of
 * three zero words is a free slot. A file is a chain of blocks too, linked through word 0 with 510
 * bytes of data behind it - except a contiguous file, which runs from its first block to its last,
 * all 512 bytes of each. DOS-11 marks those with the top bit of the date word; XXDP+ only ever
 * writes {@code .SAV} files that way and does not set the bit.</p>
 *
 * <p>DECtape is the odd one out: on the 1972 diagnostic tapes the MFD blocks are empty and the
 * directory simply starts at block 66, where {@code xxdpdir} also looks for it.</p>
 */
public final class XxdpVolume {
	/** Words per directory entry, and entries per directory block. */
	private static final int ENTRY_WORDS = 9;

	private static final int ENTRIES_PER_BLOCK = 28;

	/** Where a DECtape's directory begins when its MFD does not say. */
	private static final int TU56_UFD = 66;

	/** The size of a DECtape image, in blocks: 578, of which XXDP uses 576. */
	private static final int TU56_BLOCKS = 578;

	/**
	 * Below this proportion of sensible entries, the directory is taken to be something else read
	 * as one. Real directories are entirely sensible; a block of program code is barely at all.
	 */
	private static final double PLAUSIBLE = 0.9;

	public enum Form {
		DOS11("DOS-11"),
		XXDP_PLUS("XXDP+"),
		DECTAPE("DOS-11 DECtape");

		private final String m_label;

		Form(String label) {
			m_label = label;
		}

		public String getLabel() {
			return m_label;
		}
	}

	/**
	 * One directory entry.
	 *
	 * @param name   {@code NAME.EXT}
	 * @param date   the date the entry gives, or null
	 * @param start  first block
	 * @param length length in blocks, as the directory says
	 * @param last   last block
	 */
	public record Entry(String name, LocalDate date, int start, int length, int last, boolean isContiguous) {
	}

	private final BlockImage m_image;

	private final Form m_form;

	private final List<Entry> m_entries;

	private final int m_rejected;

	private XxdpVolume(BlockImage image, Form form, List<Entry> entries, int rejected) {
		m_image = image;
		m_form = form;
		m_entries = Collections.unmodifiableList(entries);
		m_rejected = rejected;
	}

	/**
	 * Read the directory of an image under one block mapping.
	 *
	 * @throws MediaFormatException when there is no sensible directory where one should be, which
	 *                              under the wrong mapping is the usual answer
	 */
	public static XxdpVolume open(BlockImage image) throws MediaFormatException {
		MediaFormatException first = null;
		try {
			return openFromMfd(image);
		} catch(MediaFormatException x) {
			first = x;
		}
		if(image.blockCount() == TU56_BLOCKS) {
			try {
				return fromDirectory(image, Form.DECTAPE, TU56_UFD);
			} catch(MediaFormatException x) {
				//-- Report the MFD's problem: that is where a directory is supposed to start.
			}
		}
		throw first;
	}

	private static XxdpVolume openFromMfd(BlockImage image) throws MediaFormatException {
		byte[] mfd = image.block(1);
		if(mfd == null)
			throw new MediaFormatException("The image is too small to hold a directory");
		int link = BlockImage.word(mfd, 0);
		if(link == 0)
			return fromDirectory(image, Form.XXDP_PLUS, BlockImage.word(mfd, 1));
		byte[] mfd2 = image.block(link);
		if(mfd2 == null)
			throw new MediaFormatException("The MFD links to block " + link + ", which is past the end of the image");
		return fromDirectory(image, Form.DOS11, BlockImage.word(mfd2, 2));
	}

	private static XxdpVolume fromDirectory(BlockImage image, Form form, int firstUfd) throws MediaFormatException {
		if(firstUfd == 0)
			throw new MediaFormatException("The MFD names no directory");
		List<Entry> entries = new ArrayList<>();
		int total = 0;
		Set<Integer> seen = new HashSet<>();
		for(int ufd = firstUfd; ufd != 0; ) {
			if(!seen.add(ufd))
				throw new MediaFormatException("The directory chain loops at block " + ufd);
			byte[] b = image.block(ufd);
			if(b == null)
				throw new MediaFormatException("The directory chain runs to block " + ufd + ", past the end of the image");
			for(int i = 0; i < ENTRIES_PER_BLOCK; i++) {
				int at = 1 + i * ENTRY_WORDS;
				int name1 = BlockImage.word(b, at);
				int name2 = BlockImage.word(b, at + 1);
				int ext = BlockImage.word(b, at + 2);
				if(name1 == 0 && name2 == 0 && ext == 0)
					continue;
				total++;
				String name = DosNames.fileName(name1, name2, ext);
				int date = BlockImage.word(b, at + 3);
				//-- Contiguous files are flagged by the top bit of the date word in DOS-11, which is
				//-- how the 1972 DECtapes mark their .SAC files; XXDP+ dropped the flag and simply
				//-- treats .SAV as contiguous. Either is enough.
				boolean contiguous = (date & 0100000) != 0 || name.endsWith(".SAV");
				Entry e = new Entry(name, DosNames.date(date), BlockImage.word(b, at + 5), BlockImage.word(b, at + 6),
					BlockImage.word(b, at + 7), contiguous);
				if(isPlausible(e, image.blockCount()))
					entries.add(e);
			}
			ufd = BlockImage.word(b, 0);
		}
		if(total == 0)
			throw new MediaFormatException("The directory is empty");
		if(entries.size() < total * PLAUSIBLE)
			throw new MediaFormatException("Only " + entries.size() + " of " + total + " directory entries make sense");
		return new XxdpVolume(image, form, entries, total - entries.size());
	}

	private static boolean isPlausible(Entry e, int blocks) {
		return DosNames.isPlausibleName(e.name())
			&& e.start() > 0 && e.start() < blocks
			&& e.length() > 0 && e.length() <= blocks
			&& e.last() < blocks;
	}

	public Form getForm() {
		return m_form;
	}

	public BlockImage getImage() {
		return m_image;
	}

	/** The directory, in its own order, without the entries that made no sense. */
	public List<Entry> getEntries() {
		return m_entries;
	}

	/** How many entries were left out of {@link #getEntries()} because they made no sense. */
	public int getRejectedCount() {
		return m_rejected;
	}

	/**
	 * A file's contents.
	 *
	 * <p>A chain that runs off the image or loops is cut where it goes wrong, and so is one that
	 * runs on far past the length the directory gave; both are damage, and what was read before it
	 * is still worth having. {@code problems} is told about it.</p>
	 */
	public byte[] read(Entry entry, List<String> problems) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(entry.length() * BlockImage.BLOCK_SIZE);
		if(entry.isContiguous()) {
			for(int n = entry.start(); n <= entry.last(); n++) {
				byte[] b = m_image.block(n);
				if(b == null) {
					problems.add(entry.name() + ": block " + n + " is past the end of the image");
					break;
				}
				out.writeBytes(b);
			}
			return out.toByteArray();
		}
		Set<Integer> seen = new HashSet<>();
		int limit = Math.max(entry.length() * 2, entry.length() + 16);
		for(int n = entry.start(); n != 0; ) {
			if(!seen.add(n)) {
				problems.add(entry.name() + ": the block chain loops at block " + n);
				break;
			}
			if(seen.size() > limit) {
				problems.add(entry.name() + ": the block chain runs past twice its length; cut short");
				break;
			}
			byte[] b = m_image.block(n);
			if(b == null) {
				problems.add(entry.name() + ": the block chain runs to block " + n + ", past the end of the image");
				break;
			}
			out.write(b, 2, BlockImage.BLOCK_SIZE - 2);
			n = BlockImage.word(b, 0);
		}
		if(seen.size() != entry.length())
			problems.add(entry.name() + ": the directory says " + entry.length() + " blocks, the chain has " + seen.size());
		return out.toByteArray();
	}

	/** Every file, read; one whose reading added to {@code problems} is marked damaged. */
	public List<MediaFile> readAll(List<String> problems) {
		List<MediaFile> files = new ArrayList<>(m_entries.size());
		for(Entry e : m_entries) {
			int before = problems.size();
			byte[] data = read(e, problems);
			files.add(new MediaFile(e.name(), e.date(), data, problems.size() > before));
		}
		return files;
	}
}
