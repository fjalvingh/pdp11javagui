package to.etc.pdp11.core.memfile;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Octal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a file into a block of memory cells, ready to be deposited into a machine.
 *
 * <p>The {@code Load} half of {@code MemoryLoaderU.pas}, and the other side of
 * {@link MemoryDumper}. What comes out is a {@link MemoryCellGroup} whose cells hold the file's
 * values as <b>edit values</b> and nothing as their machine value - which is exactly right: the
 * file says what the memory <i>should</i> contain, the machine has not been told yet, and the
 * grid shows every word as changed until it has been.</p>
 *
 * <h2>Two formats carry their own addresses and two do not</h2>
 *
 * <p>A byte stream is bytes; it has to be told where to load. A text listing and a paper tape
 * image say where each word goes, and a start address typed beside them is ignored - see
 * {@link MemoryFileFormat#definesOwnAddresses()}.</p>
 */
public final class MemoryFileLoader {
	/** The paper tape buffer covers a 16-bit address space, as the format's addresses do. */
	private static final int PAPERTAPE_BUFFER_SIZE = 0x10000;

	/**
	 * @param wordsLoaded  how many cells the file produced
	 * @param entryAddress where the file says execution starts, or null if it does not say
	 * @param warnings     anything odd that did not stop the load
	 */
	public record Result(int wordsLoaded, Address entryAddress, List<String> warnings) {
	}

	private MemoryFileLoader() {
	}

	/**
	 * Read {@code files} into {@code group}, replacing whatever was in it.
	 *
	 * @param startAddr where to load, for a format that does not say. The group's address width
	 *                  is taken from this.
	 */
	public static Result load(MemoryFileFormat format, MemoryCellGroup group, List<Path> files,
		Address startAddr) throws IOException {
		if(files.size() != format.getFileCount())
			throw new IllegalArgumentException(format.getLabel() + " needs " + format.getFileCount()
				+ " file name(s), got " + files.size());
		for(Path p : files) {
			if(p == null || p.toString().isBlank())
				throw new IOException("Choose a file to read first");
			if(!Files.isReadable(p))
				throw new IOException("Cannot read " + p);
		}
		Result r = switch(format) {
			case BYTE_STREAM -> loadByteStream(group, files.get(0), startAddr);
			case LOW_HIGH_BYTE_FILES -> loadSplitBytes(group, files.get(0), files.get(1), startAddr);
			case TEXT_ONE_ADDR_PER_LINE -> loadText(group, files.get(0), startAddr);
			case ABSOLUTE_PAPERTAPE -> loadPaperTape(group, files.get(0), startAddr);
		};
		//-- In address order, because that is how a grid lays cells out, and nothing about a file
		//-- guarantees it - a paper tape image is blocks, and a text listing is whatever somebody
		//-- pasted together.
		group.sort();
		return r;
	}

	// -------------------------------------------------------------------------------------
	// Byte stream
	// -------------------------------------------------------------------------------------

	/** Every two bytes are a word, low byte first. {@code TMemoryLoader_BytestreamLH.Load}. */
	private static Result loadByteStream(MemoryCellGroup group, Path file, Address startAddr)
		throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		List<String> warnings = new ArrayList<>();
		if(bytes.length % 2 != 0)
			warnings.add("The file has an odd number of bytes; the last one is not a whole word and is ignored");
		int words = bytes.length / 2;
		if(words == 0)
			throw new IOException(file.getFileName() + " holds no whole words");
		words = limitToAddressSpace(words, startAddr, warnings);

		group.clear();
		group.shiftRange(startAddr, words, false);
		for(int i = 0; i < words; i++) {
			int w = (bytes[2 * i] & 0xFF) | ((bytes[2 * i + 1] & 0xFF) << 8);
			group.cell(i).setEditValue(CellValue.of(w));
		}
		return new Result(words, null, warnings);
	}

	/**
	 * One file of low bytes and one of high bytes, a byte each per word.
	 *
	 * <p><b>A second bug in the same Pascal class, not carried across.</b> Its {@code Load} takes
	 * {@code wordcount := stream_l.Size div 2} ({@code :318}) - but {@code Save} writes one byte
	 * per word to each file, so a dump of N words is N bytes in each file and this reads half of
	 * them. The two halves of that class do not round-trip in either direction: the save writes
	 * an empty high byte file, and the load reads half the words.</p>
	 */
	private static Result loadSplitBytes(MemoryCellGroup group, Path lowFile, Path highFile,
		Address startAddr) throws IOException {
		byte[] low = Files.readAllBytes(lowFile);
		byte[] high = Files.readAllBytes(highFile);
		List<String> warnings = new ArrayList<>();
		if(low.length != high.length) {
			//-- The Pascal raises here, and it is right to care; but the shorter file is still
			//-- readable data, and refusing the whole load helps nobody.
			warnings.add("The two files are different sizes (" + low.length + " and " + high.length
				+ " bytes); only the words present in both were loaded");
		}
		int words = Math.min(low.length, high.length);
		if(words == 0)
			throw new IOException("One of the two files is empty");
		words = limitToAddressSpace(words, startAddr, warnings);

		group.clear();
		group.shiftRange(startAddr, words, false);
		for(int i = 0; i < words; i++) {
			group.cell(i).setEditValue(CellValue.of((low[i] & 0xFF) | ((high[i] & 0xFF) << 8)));
		}
		return new Result(words, null, warnings);
	}

	// -------------------------------------------------------------------------------------
	// Text
	// -------------------------------------------------------------------------------------

	/**
	 * {@code "001000: 012701 000200"} - an address and the values that follow from it.
	 *
	 * <p>Ported from {@code TMemoryLoader_TextfileOneAddrPerLine.Load} ({@code :404-460}),
	 * including how forgiving it is: a line that does not begin with an octal digit is skipped
	 * entirely, and within a line every character that is not an octal digit becomes a
	 * separator. So the colon this format writes needs no special handling, and neither does a
	 * comma, or a comment somebody added at the end of a line.</p>
	 */
	private static Result loadText(MemoryCellGroup group, Path file, Address startAddr) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
		List<String> warnings = new ArrayList<>();
		group.clear();
		group.shiftRange(startAddr, 0, false);              // keeps the width, holds no cells

		int loaded = 0;
		int skipped = 0;
		int outOfRange = 0;
		int masked = 0;
		for(String raw : lines) {
			String line = raw.strip();
			if(line.isEmpty() || !isOctalDigit(line.charAt(0))) {
				if(!line.isEmpty())
					skipped++;
				continue;
			}
			//-- Everything that is not an octal digit is a separator.
			StringBuilder sb = new StringBuilder(line.length());
			for(int i = 0; i < line.length(); i++) {
				sb.append(isOctalDigit(line.charAt(i)) ? line.charAt(i) : ' ');
			}
			String[] parts = sb.toString().trim().split("\\s+");
			if(parts.length < 2)
				continue;                                   // an address with no values says nothing
			long addr;
			try {
				addr = Octal.parse(parts[0]);
			} catch(NumberFormatException x) {
				skipped++;
				continue;
			}
			for(int i = 1; i < parts.length; i++) {
				try {
					long raw16 = Octal.parse(parts[i]);
					if(raw16 > 0xFFFF)
						masked++;
					int value = (int) (raw16 & 0xFFFF);
					//-- A forgiving format stays forgiving: an address the machine cannot express is
					//-- a bad line, not a reason to lose everything read so far.
					if(!fitsAddressSpace(addr, startAddr)) {
						outOfRange++;
					} else {
						addCell(group, startAddr, addr, value);
						loaded++;
					}
				} catch(NumberFormatException x) {
					warnings.add("Ignored \"" + parts[i] + "\", which is not an octal value");
				}
				addr += 2;
			}
		}
		if(skipped > 0)
			warnings.add(skipped + " line" + (skipped == 1 ? "" : "s") + " did not start with an octal address");
		if(masked > 0)
			warnings.add(masked + " value" + (masked == 1 ? " was" : "s were") + " wider than 16 bits and "
				+ (masked == 1 ? "was" : "were") + " truncated to a word");
		if(outOfRange > 0)
			warnings.add(outOfRange + " value" + (outOfRange == 1 ? "" : "s") + " named an address outside the "
				+ startAddr.type().getBits() + " bit address space and " + (outOfRange == 1 ? "was" : "were")
				+ " ignored");
		if(loaded == 0)
			throw new IOException("No octal address and value lines were found in " + file.getFileName());
		return new Result(loaded, null, warnings);
	}

	private static boolean isOctalDigit(char c) {
		return c >= '0' && c <= '7';
	}

	// -------------------------------------------------------------------------------------
	// DEC absolute paper tape
	// -------------------------------------------------------------------------------------

	/**
	 * Read a paper tape image back into memory.
	 *
	 * <p>Ported from {@code TMemoryLoader_StandardAbsolutePapertape.Load} ({@code :612-847}) and
	 * its state machine, which comes in turn from Mattis Lind's {@code maindec.c}. Bytes are
	 * read into a 64 KB buffer with a validity flag each and only then turned into words, because
	 * a block may start at an odd address and two blocks may meet inside one word - the format is
	 * byte-addressed and memory is not.</p>
	 *
	 * <p>A block whose data length is zero carries the entry address rather than data. A checksum
	 * that does not come to zero stops the load: a paper tape image with a bad block is a file
	 * that would deposit wrong values into a machine, and there is no way to tell which ones.</p>
	 */
	private static Result loadPaperTape(MemoryCellGroup group, Path file, Address startAddr)
		throws IOException {
		byte[] data = Files.readAllBytes(file);
		byte[] buffer = new byte[PAPERTAPE_BUFFER_SIZE];
		boolean[] valid = new boolean[PAPERTAPE_BUFFER_SIZE];
		List<String> warnings = new ArrayList<>();
		Address entry = null;

		int state = 0;
		int sum = 0;
		int blockByteIdx = 0;
		int blockSize = 0;
		int dataBytes = 0;
		long address = 0;
		for(int pos = 0; pos < data.length; pos++) {
			int b = data[pos] & 0xFF;
			switch(state) {
				case 0 -> {
					//-- Skip everything until a block header. Leader tape, stuff bytes, anything.
					sum = 0;
					if(b == 1) {
						state = 1;
						blockByteIdx = 1;
						sum += b;
					}
				}
				case 1 -> {
					if(b != 0) {
						state = 0;                          // not a header after all
					} else {
						state = 2;
						blockByteIdx++;
						sum += b;
					}
				}
				case 2 -> {
					blockSize = b;
					sum += b;
					blockByteIdx++;
					state = 3;
				}
				case 3 -> {
					blockSize |= b << 8;
					dataBytes = blockSize - 6;
					sum += b;
					blockByteIdx++;
					state = 4;
				}
				case 4 -> {
					address = b;
					sum += b;
					blockByteIdx++;
					state = 5;
				}
				case 5 -> {
					address |= (long) b << 8;
					sum += b;
					blockByteIdx++;
					if(blockByteIdx > blockSize) {
						warnings.add("Skipped a block at " + Octal.format(address, 6)
							+ " whose size field says " + blockSize + " bytes");
						state = 0;
					} else if(dataBytes == 0) {
						//-- A block with no data is where to start executing.
						entry = Address.of(startAddr.type(), address);
						state = 0;
					} else {
						state = 6;
					}
				}
				case 6 -> {
					if(address >= PAPERTAPE_BUFFER_SIZE)
						throw new IOException("The image loads at " + Octal.format(address, 1)
							+ ", past the 16 bit address space a paper tape can describe");
					sum += b;
					buffer[(int) address] = (byte) b;
					valid[(int) address] = true;
					address++;
					blockByteIdx++;
					if(blockByteIdx >= blockSize)
						state = 7;
				}
				default -> {
					sum += b;
					if((sum & 0xFF) != 0)
						throw new IOException("Checksum error in " + file.getFileName()
							+ " at byte " + pos + "; the image is damaged");
					sum = 0;
					state = 0;
				}
			}
		}
		if(state != 0)
			warnings.add("The image ends in the middle of a block");

		group.clear();
		group.shiftRange(startAddr, 0, false);
		int loaded = 0;
		for(int a = 0; a + 1 < PAPERTAPE_BUFFER_SIZE; a += 2) {
			//-- One valid byte is enough: a block can end mid-word, and the missing half is zero,
			//-- which is what the loader would have left there.
			if(!valid[a] && !valid[a + 1])
				continue;
			int w = (buffer[a] & 0xFF) | ((buffer[a + 1] & 0xFF) << 8);
			addCell(group, startAddr, a, w);
			loaded++;
		}
		if(loaded == 0)
			throw new IOException("No data blocks were found in " + file.getFileName());
		return new Result(loaded, entry, warnings);
	}

	// -------------------------------------------------------------------------------------
	// Shared
	// -------------------------------------------------------------------------------------

	/**
	 * The highest address a group of {@code like}'s width can hold, or -1 when the type has no
	 * width to speak of - in which case {@link Address} will not police the value either.
	 */
	private static long topOfAddressSpace(Address like) {
		MemoryAddressType type = like.type();
		if(!type.isConcretePhysical() && type != MemoryAddressType.VIRTUAL)
			return -1;
		return (1L << type.getBits()) - 1;
	}

	/** Whether {@code addrValue} can be expressed at {@code like}'s width. */
	private static boolean fitsAddressSpace(long addrValue, Address like) {
		if(addrValue < 0)
			return false;
		long top = topOfAddressSpace(like);
		return top < 0 || addrValue <= top;
	}

	/**
	 * How many of {@code words} words starting at {@code startAddr} fit in the address space,
	 * with a warning for the ones that do not.
	 *
	 * <p>Checked <b>before</b> the group is cleared and rebuilt: a file that runs off the top of
	 * a 16-bit machine used to throw {@link IllegalArgumentException} halfway through
	 * {@code shiftRange}, leaving the group holding part of a load nobody asked for.</p>
	 */
	private static int limitToAddressSpace(int words, Address startAddr, List<String> warnings) {
		long top = topOfAddressSpace(startAddr);
		if(top < 0)
			return words;                                   // Address does not police this width either
		int fits = (int) Math.min(words, (top - startAddr.val()) / 2 + 1);
		if(fits < words)
			warnings.add("The file holds " + words + " words but only " + fits + " fit at "
				+ startAddr.toOctal() + " in a " + startAddr.type().getBits()
				+ " bit address space; the rest were not loaded");
		return fits;
	}

	/** Add a cell, or overwrite one already at that address - a later line wins. */
	private static void addCell(MemoryCellGroup group, Address like, long addrValue, int value) {
		Address addr = Address.of(like.type(), addrValue);
		MemoryCell mc = group.findByAddress(addr);
		if(mc == null)
			mc = group.add(addr);
		mc.setEditValue(CellValue.of(value));
	}
}
