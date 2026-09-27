package to.etc.pdp11.core.memfile;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Octal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a block of memory out to a file, in whichever {@link MemoryFileFormat} was asked for.
 *
 * <p>Ported from the {@code Save} half of {@code MemoryLoaderU.pas} - five classes there, one
 * method per format here, because the state each of those classes carried was the filename
 * controls of the window that owned it.</p>
 *
 * <h2>Unknown words</h2>
 *
 * <p>A memory dump is made from cells that were read back from a machine, and a cell that was
 * never read has no value. The positional formats have to put <i>something</i> there, and this
 * writes zero and <b>says how many</b> in {@link Result#unknownWords()} so the window can warn.
 * The Pascal writes its {@code $ffffffff} sentinel truncated to sixteen bits, which is
 * {@code 0177777} - a real value, silently, in the middle of a dump somebody is about to burn
 * into a ROM. The text and paper tape formats simply leave unknown words out, which they can
 * because both carry their addresses.</p>
 */
public final class MemoryDumper {
	/** Values per line in the text format. {@code max_vals_per_line} ({@code :465}). */
	private static final int TEXT_VALUES_PER_LINE = 8;

	/** Paper tape blocks are addressed with sixteen bits. */
	private static final long MAX_PAPERTAPE_ADDRESS = 0xFFFE;

	/**
	 * @param wordsWritten   how many words reached the file
	 * @param unknownWords   how many cells had no value read back from the machine
	 * @param blocks         paper tape only: how many blocks the range broke into
	 */
	public record Result(int wordsWritten, int unknownWords, int blocks) {
		public boolean isComplete() {
			return unknownWords == 0;
		}
	}

	private MemoryDumper() {
	}

	/**
	 * Write {@code group} to {@code files}.
	 *
	 * @param files one path per {@link MemoryFileFormat#getFileCount()}
	 * @param entry where execution should start, for a format that records one; may be null
	 */
	public static Result save(MemoryFileFormat format, MemoryCellGroup group, List<Path> files,
		Address entry) throws IOException {
		if(group == null || group.isEmpty())
			throw new IOException("There is nothing to write: no memory has been read");
		if(files.size() != format.getFileCount())
			throw new IllegalArgumentException(format.getLabel() + " needs " + format.getFileCount()
				+ " file name(s), got " + files.size());
		for(Path p : files) {
			if(p == null || p.toString().isBlank())
				throw new IOException("Choose a file to write first");
		}
		return switch(format) {
			case BYTE_STREAM -> saveByteStream(group, files.get(0));
			case LOW_HIGH_BYTE_FILES -> saveSplitBytes(group, files.get(0), files.get(1));
			case TEXT_ONE_ADDR_PER_LINE -> saveText(group, files.get(0));
			case ABSOLUTE_PAPERTAPE -> savePaperTape(group, files.get(0), entry);
		};
	}

	// -------------------------------------------------------------------------------------
	// Byte stream
	// -------------------------------------------------------------------------------------

	/** Every word as two bytes, low first. {@code TMemoryLoader_BytestreamLH.Save}. */
	private static Result saveByteStream(MemoryCellGroup group, Path file) throws IOException {
		int unknown = 0;
		byte[] out = new byte[group.size() * 2];
		int i = 0;
		for(MemoryCell mc : group.getCells()) {
			int w = mc.getEditValue().wordOr(0);
			if(!mc.getEditValue().isKnown())
				unknown++;
			out[i++] = (byte) w;
			out[i++] = (byte) (w >>> 8);
		}
		Files.write(file, out);
		return new Result(group.size(), unknown, 1);
	}

	/**
	 * The low bytes in one file and the high bytes in another.
	 *
	 * <p><b>A bug not carried across.</b> The Pascal writes {@code byte_h := w shl 8}
	 * ({@code :364}) where every other place in the unit shifts <i>right</i>. Assigning a
	 * left-shifted word to a byte keeps the low eight bits, which after that shift are always
	 * zero - so the high byte file it produces is entirely zeros. The {@code Load} side of the
	 * same class gets it right, which is what makes it a typo rather than a convention.</p>
	 */
	private static Result saveSplitBytes(MemoryCellGroup group, Path lowFile, Path highFile) throws IOException {
		int unknown = 0;
		byte[] low = new byte[group.size()];
		byte[] high = new byte[group.size()];
		int i = 0;
		for(MemoryCell mc : group.getCells()) {
			int w = mc.getEditValue().wordOr(0);
			if(!mc.getEditValue().isKnown())
				unknown++;
			low[i] = (byte) w;
			high[i] = (byte) (w >>> 8);
			i++;
		}
		Files.write(lowFile, low);
		Files.write(highFile, high);
		return new Result(group.size(), unknown, 1);
	}

	// -------------------------------------------------------------------------------------
	// Text
	// -------------------------------------------------------------------------------------

	/**
	 * {@code "001000: 012701 000200 000000 ..."}, eight values a line.
	 *
	 * <p>Ported from {@code TMemoryLoader_TextfileOneAddrPerLine.Save} ({@code :470-528}),
	 * including all three reasons it starts a new line: the file has just begun, the addresses
	 * are no longer consecutive, or the line is full. A cell with no value is left out entirely,
	 * which is exactly why the next one starts a new line.</p>
	 */
	private static Result saveText(MemoryCellGroup group, Path file) throws IOException {
		StringBuilder out = new StringBuilder();
		StringBuilder line = new StringBuilder();
		long lastAddr = -1;
		int written = 0;
		int unknown = 0;
		for(MemoryCell mc : group.getCells()) {
			if(!mc.getEditValue().isKnown()) {
				unknown++;
				continue;                                   // dump only what was actually read
			}
			long addr = mc.getAddr().val();
			boolean newLine = line.length() == 0
				|| addr != lastAddr + 2
				|| addr % (TEXT_VALUES_PER_LINE * 2L) == 0;
			if(newLine) {
				flush(out, line);
				line.append(Octal.format(addr, 6)).append(':');
			}
			line.append(' ').append(Octal.word(mc.getEditValue().word()));
			lastAddr = addr;
			written++;
		}
		flush(out, line);
		Files.writeString(file, out.toString(), StandardCharsets.US_ASCII);
		return new Result(written, unknown, 1);
	}

	/** {@code putln}: a line that has nothing on it is not written at all ({@code :477-483}). */
	private static void flush(StringBuilder out, StringBuilder line) {
		if(line.length() > 0) {
			out.append(line).append('\n');
			line.setLength(0);
		}
	}

	// -------------------------------------------------------------------------------------
	// DEC absolute paper tape
	// -------------------------------------------------------------------------------------

	/**
	 * The format a PDP-11's absolute loader reads off paper tape.
	 *
	 * <p>Ported from {@code TMemoryLoader_StandardAbsolutePapertape.Save} ({@code :849-971}),
	 * whose own comment gives the layout: each block is {@code 01 00}, the block size and the
	 * start address as little-endian words, the data, and a checksum byte chosen so that every
	 * byte from the {@code 01} onwards sums to zero modulo 256. Four zero bytes follow each
	 * block - "makes a visible gap on paper tape", and the loader skips them.</p>
	 *
	 * <p>A run of consecutive addresses is one block; a gap starts a new one. The last block has
	 * no data and carries the entry address, which is how the loader is told where to start.</p>
	 */
	private static Result savePaperTape(MemoryCellGroup group, Path file, Address entry) throws IOException {
		List<MemoryCell> cells = new ArrayList<>();
		int unknown = 0;
		for(MemoryCell mc : group.getCells()) {
			//-- Unknown words are left out, and leaving one out splits the block, which is
			//-- correct: the loader must not write a value nobody ever read.
			if(mc.getEditValue().isKnown())
				cells.add(mc);
			else
				unknown++;
		}
		if(cells.isEmpty())
			throw new IOException("None of the memory in this range has been read yet");
		cells.sort((a, b) -> Long.compareUnsigned(a.getAddr().val(), b.getAddr().val()));

		for(MemoryCell mc : cells) {
			if(Long.compareUnsigned(mc.getAddr().val(), MAX_PAPERTAPE_ADDRESS) > 0)
				throw new IOException("Address " + mc.getAddr().toOctal()
					+ " is too large for the 16 bit addresses of a paper tape image");
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int blocks = 0;
		int start = 0;
		for(int i = 1; i <= cells.size(); i++) {
			boolean end = i == cells.size()
				|| cells.get(i).getAddr().val() != cells.get(i - 1).getAddr().val() + 2;
			if(!end)
				continue;
			writeBlock(out, cells.get(start).getAddr().val(), cells.subList(start, i));
			blocks++;
			start = i;
		}
		//-- The entry block: no data, and its address is where to start executing. The Pascal
		//-- writes one even for address 1, which is not a valid entry point, and says so.
		if(entry != null) {
			writeBlock(out, entry.val() & 0xFFFF, List.of());
			blocks++;
		}
		Files.write(file, out.toByteArray());
		return new Result(cells.size(), unknown, blocks);
	}

	/** One block, with the checksum that makes its bytes sum to zero. */
	private static void writeBlock(OutputStream out, long startAddr, List<MemoryCell> data) throws IOException {
		//-- Six bytes of header and two per word, which is what the loader uses to know when the
		//-- block ends.
		int blockSize = 6 + 2 * data.size();
		int[] sum = {0};
		writeByte(out, sum, 1);
		writeByte(out, sum, 0);
		writeWord(out, sum, blockSize);
		writeWord(out, sum, (int) (startAddr & 0xFFFF));
		for(MemoryCell mc : data) {
			writeWord(out, sum, mc.getEditValue().word());
		}
		//-- Every byte from the 01 onwards, including this one, must sum to zero.
		out.write((256 - (sum[0] & 0xFF)) & 0xFF);
		//-- Stuff bytes. Not part of the block and not in the checksum; they are a visible gap
		//-- in the punched tape.
		out.write(0);
		out.write(0);
		out.write(0);
		out.write(0);
	}

	private static void writeByte(OutputStream out, int[] sum, int b) throws IOException {
		out.write(b & 0xFF);
		sum[0] += b & 0xFF;
	}

	/** Little endian, like everything else on a PDP-11. */
	private static void writeWord(OutputStream out, int[] sum, int w) throws IOException {
		writeByte(out, sum, w & 0xFF);
		writeByte(out, sum, (w >>> 8) & 0xFF);
	}
}
