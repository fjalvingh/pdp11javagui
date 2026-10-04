package to.etc.pdp11.common.memfile;

import to.etc.pdp11.common.util.Octal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A DEC absolute loader image - a paper tape, or a {@code .BIN}/{@code .BIC}/{@code .LDA} file -
 * read the way the absolute loader reads it.
 *
 * <p>The format is blocks: {@code 001 000}, a 16-bit byte count that includes the six header bytes,
 * a 16-bit load address, the data, and a checksum byte that makes all of them sum to zero. Anything
 * between blocks - leader, stuff bytes - is skipped. A block with no data carries the transfer
 * address, and <b>ends the load</b>: the loader jumps there, or halts when it is odd. Nothing after
 * it is read, which matters for a file off an XXDP disk, whose last block is padded out with
 * whatever was in the buffer; reading on would take that slack for more blocks, and stop with a
 * checksum error on a good program.</p>
 *
 * <p>The state machine is the one {@code MemoryLoaderU.pas} took from Mattis Lind's
 * {@code maindec.c}, kept byte for byte because its corner cases are tested: see
 * {@code MemoryFileLoaderTest}.</p>
 */
public final class AbsoluteLoaderTape {
	/** The format's addresses are 16 bits. */
	public static final int ADDRESS_SPACE = 0x10000;

	/** The address diagnostics start at when their transfer address does not say: DEC's default. */
	public static final int DEFAULT_START = 0200;

	private final byte[] m_memory;

	private final boolean[] m_loaded;

	private final Integer m_transfer;

	private final List<String> m_warnings;

	private final int m_bytes;

	private AbsoluteLoaderTape(byte[] memory, boolean[] loaded, Integer transfer, List<String> warnings, int bytes) {
		m_memory = memory;
		m_loaded = loaded;
		m_transfer = transfer;
		m_warnings = Collections.unmodifiableList(warnings);
		m_bytes = bytes;
	}

	/**
	 * Read an image.
	 *
	 * @param name what to call it in a message
	 * @throws IOException for a checksum error or a block past 16 bits - an image that would put
	 *                     wrong values into a machine, with no way to say which
	 */
	public static AbsoluteLoaderTape parse(byte[] data, String name) throws IOException {
		byte[] memory = new byte[ADDRESS_SPACE];
		boolean[] loaded = new boolean[ADDRESS_SPACE];
		List<String> warnings = new ArrayList<>();
		Integer transfer = null;
		int loadedBytes = 0;

		int state = 0;
		int sum = 0;
		int blockByteIdx = 0;
		int blockSize = 0;
		int dataBytes = 0;
		long address = 0;
		int pos = 0;
		loop:
		for(; pos < data.length; pos++) {
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
						//-- A block with no data is where to start executing. Its checksum byte
						//-- is still to come, and going straight back to state 0 left that byte
						//-- to be read as the start of the next block: when it happens to be 01 -
						//-- every entry address whose two bytes sum to 248 mod 256, 000370 among
						//-- them - the bytes after it were misparsed and a good tape came back
						//-- with a warning on it (FABLE-ISSUES #58). State 7 consumes it and
						//-- checks it, which is also the only thing that ever verified it.
						transfer = (int) address;
						state = 7;
					} else {
						state = 6;
					}
				}
				case 6 -> {
					if(address >= ADDRESS_SPACE)
						throw new IOException("The image loads at " + Octal.format(address, 1)
							+ ", past the 16 bit address space a paper tape can describe");
					sum += b;
					memory[(int) address] = (byte) b;
					if(!loaded[(int) address])
						loadedBytes++;
					loaded[(int) address] = true;
					address++;
					blockByteIdx++;
					if(blockByteIdx >= blockSize)
						state = 7;
				}
				//-- State 7: the checksum byte that ends every block, data or entry.
				default -> {
					sum += b;
					if((sum & 0xFF) != 0)
						throw new IOException("Checksum error in " + name + " at byte " + pos + "; the image is damaged");
					sum = 0;
					state = 0;
					if(transfer != null)
						break loop;                         // the loader stops here, and so does this
				}
			}
		}
		if(state != 0)
			warnings.add("The image ends in the middle of a block");
		return new AbsoluteLoaderTape(memory, loaded, transfer, warnings, loadedBytes);
	}

	/** The transfer address the image ends with, or null when it has none. */
	public Integer getTransferAddress() {
		return m_transfer;
	}

	/**
	 * Where to start the program: the transfer address when it is a real one, else
	 * {@link #DEFAULT_START}.
	 *
	 * <p>An odd transfer address means "halt after loading" - the loader cannot jump to it - and
	 * almost every diagnostic is shipped that way, to be started by hand at 200. DEC's monitors do
	 * the same: "the default starting address for files without specific transfer addresses is
	 * 200" (<i>PDP-11 Diagnostic Design Guide</i>, 6.4.1.2). Zero is treated as no address too:
	 * location 0 is the trap catcher, never an entry point.</p>
	 */
	public int startAddress() {
		if(m_transfer == null || (m_transfer & 1) != 0 || m_transfer == 0)
			return DEFAULT_START;
		return m_transfer;
	}

	public List<String> getWarnings() {
		return m_warnings;
	}

	/** How many bytes the image loads. */
	public int loadedBytes() {
		return m_bytes;
	}

	public boolean isLoaded(int address) {
		return address >= 0 && address < ADDRESS_SPACE && m_loaded[address];
	}

	public int byteAt(int address) {
		return m_memory[address] & 0xFF;
	}

	/** The lowest address loaded, or -1 when nothing is. */
	public int lowestAddress() {
		for(int a = 0; a < ADDRESS_SPACE; a++) {
			if(m_loaded[a])
				return a;
		}
		return -1;
	}

	/** The highest address loaded, or -1 when nothing is. */
	public int highestAddress() {
		for(int a = ADDRESS_SPACE - 1; a >= 0; a--) {
			if(m_loaded[a])
				return a;
		}
		return -1;
	}

	/**
	 * One word the image touches.
	 *
	 * @param address even
	 * @param value   the word, with zero in a byte the image does not load
	 * @param whole   whether the image loads both bytes; when not, the other byte is the machine's
	 */
	public record Word(int address, int value, boolean whole) {
	}

	/** Every word the image loads at least one byte of, in address order. */
	public List<Word> words() {
		List<Word> out = new ArrayList<>();
		for(int a = 0; a + 1 < ADDRESS_SPACE; a += 2) {
			boolean low = m_loaded[a];
			boolean high = m_loaded[a + 1];
			if(!low && !high)
				continue;
			int value = (low ? m_memory[a] & 0xFF : 0) | (high ? (m_memory[a + 1] & 0xFF) << 8 : 0);
			out.add(new Word(a, value, low && high));
		}
		return out;
	}
}
