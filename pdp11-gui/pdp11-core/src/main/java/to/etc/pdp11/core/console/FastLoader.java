package to.etc.pdp11.core.console;

import to.etc.pdp11.common.macro11.asm.AssemblyResult;
import to.etc.pdp11.common.macro11.asm.Diagnostic;
import to.etc.pdp11.common.macro11.asm.Macro11Assembler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.stream.Collectors;

/**
 * The machine-free half of the M9312 fast loader: the loader program, where to put it, and the
 * blocks it is sent.
 *
 * <h2>Why</h2>
 *
 * <p>The console emulator deposits a word with {@code D nnnnnn<CR>} and answers with its echo and
 * a prompt, and it cannot be sent the next command before the prompt arrives: it is not reading
 * while it prints, and the console line holds one character. That is about twelve characters on
 * the line per word, plus a round trip. At the 2400 baud an 11/05's console line cannot exceed,
 * that is under twenty words a second - four minutes for the median XXDP diagnostic. The loader
 * in {@code fastload.mac} is deposited that slow way, 59 words of it, and then started; it takes
 * the data as binary, two characters a word in blocks of {@link #BLOCK_WORDS}, about six times
 * as fast.</p>
 *
 * <h2>The protocol</h2>
 *
 * <p>It is described in full in {@code fastload.mac}, which is the authority. In short: a block
 * is a count byte, then address, data and checksum words low byte first, and the loader answers
 * every block with {@link #ACK} or {@link #NAK}, writing nothing that did not check. A count of
 * {@link #PAD} is ignored, so a run of them finishes whatever block the loader believes it is in
 * after a byte was lost or gained, and {@link #SYNC} is then answered with an {@link #ACK} that
 * says it is waiting for a block again. {@link #EXIT} sends it back to the console emulator. All
 * three are seven-bit characters, so a line that loses the eighth bit can still be got out of.</p>
 *
 * <p>This class holds no state and does no I/O; {@link M9312Console} drives it.</p>
 */
public final class FastLoader {
	/** The most data words one block carries: the size of the loader's buffer. */
	public static final int BLOCK_WORDS = 64;

	/** The loader is running and listening. */
	public static final char READY = '*';

	/** A block arrived intact and has been written. */
	public static final char ACK = '.';

	/** A block did not arrive intact; nothing was written. */
	public static final char NAK = '?';

	/** A count of zero: padding, ignored. */
	public static final int PAD = 0;

	/** A count of 0176: answer {@link #ACK} and nothing else. Seven bits, like {@link #EXIT}. */
	public static final int SYNC = 0176;

	/** A count of 0177: jump to the console emulator. */
	public static final int EXIT = 0177;

	/** The bytes that finish any block the loader may be inside, however it got there. */
	public static final int RESYNC_PADDING = 1 + 2 * (BLOCK_WORDS + 2);

	/** 8KW: the least memory any PDP-11 has, and so memory that is there whatever the image. */
	public static final int GUARANTEED_MEMORY = 040000;

	/** Where memory ends and the I/O page begins, in a 16-bit address space. */
	public static final int IOPAGE_BASE = 0160000;

	private static final String RESOURCE = "fastload.mac";

	private static final String SOURCE = readSource();

	/** How many bytes the loader occupies, its buffer included. The same wherever it goes. */
	public static final int SIZE = assemble(0, 0).end();

	private FastLoader() {
	}

	/**
	 * The loader, assembled to run at one address.
	 *
	 * @param origin  where it was assembled, and so where it must be deposited
	 * @param code    the words to deposit, in address order: the program without its buffer
	 * @param end     the first address past the loader, buffer included
	 * @param probe   two words the host may write to test the line, which nothing reads
	 */
	public record Image(int origin, List<Word> code, int end, int probe) {
		/** Whether {@code address} is a word the loader occupies, as code or as space. */
		public boolean covers(int address) {
			return address >= origin && address < end;
		}
	}

	public record Word(int address, int value) {
	}

	/**
	 * Assemble the loader at {@code origin}, returning to {@code monitorEntry} when it is done.
	 *
	 * <p>The assembler is the project's own, and the loader is assembled for each load rather than
	 * shipped as words and relocated: it is absolute code, and the address it runs at is not known
	 * until the image it carries is.</p>
	 */
	public static Image assemble(int origin, int monitorEntry) {
		if((origin & 1) != 0 || origin < 0 || origin + 01000 > 0200000)
			throw new IllegalArgumentException("Cannot put the loader at " + Integer.toOctalString(origin));
		String src = "LDORG=" + Integer.toOctalString(origin) + "\n"
			+ "MONENT=" + Integer.toOctalString(monitorEntry & 0xFFFF) + "\n"
			+ "BLKMAX=" + Integer.toOctalString(BLOCK_WORDS) + "\n"
			+ SOURCE;
		AssemblyResult r = new Macro11Assembler().assemble(RESOURCE, src);
		if(r.hasErrors())
			throw new IllegalStateException("The fast loader does not assemble: "
				+ r.getErrors().stream().map(Diagnostic::describe).collect(Collectors.joining("; ")));
		Map<String, Integer> sym = r.getSymbols();
		List<Word> code = new ArrayList<>();
		for(AssemblyResult.Word w : r.getWords()) {
			code.add(new Word(w.address(), w.value() & 0xFFFF));
		}
		code.sort((a, b) -> Integer.compare(a.address(), b.address()));
		return new Image(origin, Collections.unmodifiableList(code), sym.get("LDEND"), sym.get("PROBE"));
	}

	/**
	 * Where the loader goes when it carries these words, or -1 for nowhere.
	 *
	 * <p>Only memory known to exist is used, and none the image is about to need: the highest gap
	 * the image does not write that the loader fits in whole, below whichever is higher of the
	 * image's top and {@link #GUARANTEED_MEMORY}. Memory on a UNIBUS machine is contiguous from
	 * zero, so everything below a word being deposited is there, and so is the first 8KW on any
	 * PDP-11. Above both nothing is guessed at: depositing the loader into nonexistent memory
	 * stops the console emulator dead. For an image smaller than 8KW, which is most of them, the
	 * space just above it is where the loader goes.</p>
	 *
	 * <p>When no gap is big enough, the loader overlays the top of the image itself, and the words
	 * it covers are deposited the slow way after it has finished. That costs a few seconds and
	 * never touches memory outside the image; a gap costs nothing, but its old contents are gone.</p>
	 *
	 * @param targets the 16-bit addresses being deposited: even, and below the I/O page
	 * @param size    how many bytes the loader needs; {@link #SIZE}
	 * @return the loader's origin, or -1 if the image is too small to hold it even as an overlay
	 */
	public static int place(NavigableSet<Integer> targets, int size) {
		if(targets.isEmpty())
			return -1;
		int top = targets.last() + 2;                       // exclusive
		//-- Walk the gaps from the top down: from the end of known memory to the top target,
		//-- then between each target and the one below it, and finally down to zero.
		int gapEnd = Math.max(top, GUARANTEED_MEMORY);
		Integer below = targets.last();
		for(;;) {
			int gapLow = below == null ? 0 : below + 2;
			if(gapEnd - gapLow >= size)
				return gapEnd - size;
			if(below == null)
				break;
			gapEnd = below;
			below = targets.lower(below);
		}
		return top >= size ? top - size : -1;
	}

	/**
	 * Cut consecutive words into blocks: a block never spans a gap, and never holds more than
	 * {@link #BLOCK_WORDS}.
	 *
	 * @param words address to value, all even
	 * @return each block's address and values, in address order
	 */
	public static List<Block> blocks(SortedMap<Integer, Integer> words) {
		List<Block> out = new ArrayList<>();
		int start = -1;
		int next = -1;
		List<Integer> vals = new ArrayList<>();
		for(Map.Entry<Integer, Integer> e : words.entrySet()) {
			int a = e.getKey();
			if(a != next || vals.size() == BLOCK_WORDS) {
				if(!vals.isEmpty())
					out.add(new Block(start, toArray(vals)));
				vals.clear();
				start = a;
			}
			vals.add(e.getValue() & 0xFFFF);
			next = a + 2;
		}
		if(!vals.isEmpty())
			out.add(new Block(start, toArray(vals)));
		return out;
	}

	private static int[] toArray(List<Integer> l) {
		int[] a = new int[l.size()];
		for(int i = 0; i < a.length; i++) {
			a[i] = l.get(i);
		}
		return a;
	}

	/** Some consecutive words, starting at {@code address}. */
	public record Block(int address, int[] values) {
		public Block {
			if(values.length < 1 || values.length > BLOCK_WORDS)
				throw new IllegalArgumentException("A block holds 1.." + BLOCK_WORDS + " words, not " + values.length);
			if((address & 1) != 0)
				throw new IllegalArgumentException("Odd block address " + Integer.toOctalString(address));
		}

		/** The address of the word {@code i} words in. */
		public int addressOf(int i) {
			return address + 2 * i;
		}

		/**
		 * As the loader reads it: count, address, data and a checksum that makes count, address,
		 * data and checksum add up to zero in sixteen bits.
		 */
		public byte[] encode() {
			byte[] b = new byte[1 + 2 * (values.length + 2)];
			int sum = values.length + address;
			b[0] = (byte) values.length;
			int at = put(b, 1, address);
			for(int v : values) {
				at = put(b, at, v);
				sum += v;
			}
			put(b, at, -sum);
			return b;
		}

		private static int put(byte[] b, int at, int word) {
			b[at] = (byte) word;
			b[at + 1] = (byte) (word >> 8);
			return at + 2;
		}
	}

	/** {@link #RESYNC_PADDING} padding bytes. */
	public static byte[] padding() {
		return new byte[RESYNC_PADDING];
	}

	/**
	 * The block that tests whether the line carries eight bits: two words whose bytes all have
	 * the top bit set, written where nothing reads them. A line that drops the bit corrupts it,
	 * and the loader refuses it, before any real data is at stake.
	 */
	public static Block probe(Image image) {
		return new Block(image.probe(), new int[] {0177600, 0100377});
	}

	private static String readSource() {
		try(InputStream is = FastLoader.class.getResourceAsStream(RESOURCE)) {
			if(is == null)
				throw new IllegalStateException("Resource " + RESOURCE + " is missing from the build");
			return new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch(IOException x) {
			throw new UncheckedIOException(x);
		}
	}
}
