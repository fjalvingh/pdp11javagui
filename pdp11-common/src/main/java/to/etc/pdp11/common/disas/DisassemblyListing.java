package to.etc.pdp11.common.disas;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.util.Octal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A range of memory cells turned into a listing, with the line the program counter is on.
 *
 * <p>Ported from {@code TFormDisas.Disassemble} and the loop above it in {@code UpdateDisplay}
 * ({@code FormDisasU.pas:190-300}). It lives here rather than in the window because everything
 * it does is arithmetic over data - which words are valid, where an instruction starts, which
 * line holds the PC - and none of it needs a widget to be checked.</p>
 *
 * <h2>Why the start address moves</h2>
 *
 * <p>Instruction boundaries are not knowable from an address. Disassembly starts at the first
 * valid word and walks forward, so a word of data ahead of the PC that decodes as a two- or
 * three-word instruction swallows the PC as an operand, and the PC then sits in the middle of
 * a line rather than at the start of one. The Pascal's answer is to try again two bytes later
 * and keep trying until the PC lands on a line boundary, and that is what
 * {@link #startAddress()} reports: where the listing actually had to begin for the PC to be
 * visible in it.</p>
 *
 * <p>Note it is genuinely possible for no alignment to work - the PC's own word may not have
 * been read back at all. Then {@link #pcLine()} is -1 and the listing starts where it was
 * asked to.</p>
 */
public final class DisassemblyListing {
	/**
	 * One line: where it is, the raw words or bytes behind it, and what they decode to.
	 *
	 * @param length  how many bytes it covers: two to six for an instruction, any number for data
	 * @param format  how it is laid out as data, or null when it is an instruction. See
	 *                {@link DataMarks}.
	 * @param atPc    whether the program counter is here
	 * @param pending whether any of its words is an edit the machine does not hold yet - code
	 *                loaded or assembled and not deposited, which the CPU would not execute
	 * @param comment what the well-known addresses it names are, without the {@code ;}; empty
	 *                when it names none. See {@link WellKnownAddresses}.
	 */
	public record Line(Address address, int length, DataMarks.Format format, String words, String text, boolean atPc,
		boolean pending, String comment) {
		/** The column a comment starts in, counted from the start of the instruction text. */
		static final int COMMENT_COLUMN = 28;

		/** How wide the raw column is: three words, each with a space after it. */
		static final int RAW_WIDTH = 3 * 7;

		/** How many raw words a word-laid-out line shows. */
		static final int RAW_WORDS = 3;

		/** How many raw bytes a byte-laid-out line shows; a longer string shows its start. */
		static final int RAW_BYTES = 5;

		/**
		 * The whole line, in the layout {@code Disas11} produces, and then the comment if there
		 * is one - lined up, as a MACRO-11 listing's are.
		 */
		public String toDisplayString() {
			String line = address.toOctal() + ": " + words + " " + text;
			if(comment.isEmpty())
				return line;
			StringBuilder sb = new StringBuilder(address.toOctal()).append(": ").append(words).append(' ').append(text.stripTrailing());
			int col = line.length() - text.length() + COMMENT_COLUMN;
			do {
				sb.append(' ');
			} while(sb.length() < col);
			return sb.append("; ").append(comment).toString();
		}

		/** Whether the raw column shows bytes rather than words. */
		public boolean showsBytes() {
			return format == DataMarks.Format.BYTES || format == DataMarks.Format.ASCIZ;
		}

		/** The first and the last word this line covers, as 16-bit virtual addresses. */
		public int firstWord() {
			return (int) address.val() & 0xFFFE;
		}

		public int lastWord() {
			return (int) (address.val() + length - 1) & 0xFFFE;
		}

		/** Where the raw column starts in {@link #toDisplayString()}. */
		private int rawColumn() {
			return address.toOctal().length() + 2;
		}

		/**
		 * Which word of memory is shown at this column of {@link #toDisplayString()}, or -1 when
		 * the column is not on one of the raw words or bytes. A byte answers with its word.
		 */
		public int wordAtColumn(int column) {
			int at = column - rawColumn();
			if(at < 0 || at >= RAW_WIDTH)
				return -1;
			if(showsBytes()) {
				int index = at / 4;
				if(at % 4 == 3 || index >= Math.min(RAW_BYTES, length))
					return -1;
				return (int) (address.val() + index) & 0xFFFE;
			}
			int index = at / 7;
			if(at % 7 == 6 || index >= Math.min(RAW_WORDS, length / 2))
				return -1;
			return (int) (address.val() + 2L * index) & 0xFFFE;
		}

		/**
		 * The columns of {@link #toDisplayString()} that show this word, as {@code [first, end)},
		 * or null when this line does not show it.
		 */
		public int[] columnsOfWord(int word) {
			int first = -1;
			int end = -1;
			for(int c = rawColumn(); c < rawColumn() + RAW_WIDTH; c++) {
				if(wordAtColumn(c) == (word & 0xFFFE)) {
					if(first < 0)
						first = c;
					end = c + 1;
				}
			}
			return first < 0 ? null : new int[]{first, end};
		}

		@Override
		public String toString() {
			return toDisplayString();
		}
	}

	private final List<Line> m_lines;

	private final int m_pcLine;

	private final Address m_startAddress;

	private final Address m_nextAddress;

	private DisassemblyListing(List<Line> lines, int pcLine, Address startAddress, Address nextAddress) {
		m_lines = List.copyOf(lines);
		m_pcLine = pcLine;
		m_startAddress = startAddress;
		m_nextAddress = nextAddress;
	}

	public List<Line> getLines() {
		return m_lines;
	}

	/** Which line the PC is on, or -1 if it is not on one. */
	public int pcLine() {
		return m_pcLine;
	}

	/** Where the listing begins, which may be past where it was asked to. */
	public Address startAddress() {
		return m_startAddress;
	}

	/**
	 * Where the listing left off: the address after the last instruction in it.
	 *
	 * <p>Which is where the next one has to begin. An instruction boundary is not knowable from
	 * an address, so "the next hundred lines" can only mean "the hundred that follow the last
	 * one decoded" - continuing from anywhere else re-guesses the boundaries and can decode the
	 * same bytes into different instructions. An empty listing left off where it started.</p>
	 */
	public Address nextAddress() {
		return m_nextAddress;
	}

	public boolean isEmpty() {
		return m_lines.isEmpty();
	}

	/** The whole listing as text, one line each. */
	public String toText() {
		StringBuilder sb = new StringBuilder();
		for(Line l : m_lines) {
			sb.append(l.toDisplayString()).append('\n');
		}
		return sb.toString();
	}

	/**
	 * Disassemble the part of {@code group} that lies between {@code start} and {@code end}.
	 *
	 * <p>All three addresses are virtual: a PDP-11 program's address space is 64 KB whatever
	 * the physical machine is, and that is the only space an instruction stream means
	 * anything in.</p>
	 *
	 * @param pc where the program counter is, or {@code null} if it is not known or should not
	 *           be shown - which is what the Pascal's {@code MEMORYCELL_ILLEGALVAL} in
	 *           {@code CodeAddr} means, and it means it often: the M9312's console emulator
	 *           cannot say where the PC is.
	 */
	public static DisassemblyListing of(MemoryCellGroup group, Address start, Address end, Address pc) {
		return of(group, start, end, pc, Integer.MAX_VALUE);
	}

	/**
	 * The same, giving up after {@code maxLines} instructions.
	 *
	 * <p>The window asks for a page of a fixed number of lines rather than for a range of
	 * addresses, and how many words that is cannot be known before the words have been decoded -
	 * an instruction is one, two or three of them. So it reads a little more than it can need and
	 * stops the listing at the line it was asked for; {@link #nextAddress()} is then where the
	 * following page begins.</p>
	 */
	public static DisassemblyListing of(MemoryCellGroup group, Address start, Address end, Address pc,
		int maxLines) {
		return of(group, start, end, pc, maxLines, new DataMarks());
	}

	/**
	 * The same, laying out the words {@code marks} says are data as data.
	 *
	 * <p>An instruction never reaches into data: one whose operand word is marked as data is
	 * decoded as though that word had not been read, which makes it the bare {@code .WORD} it most
	 * likely is.</p>
	 */
	public static DisassemblyListing of(MemoryCellGroup group, Address start, Address end, Address pc,
		int maxLines, DataMarks marks) {
		requireVirtual(start, "start");
		requireVirtual(end, "end");
		if(pc != null)
			requireVirtual(pc, "PC");

		Set<Integer> pending = new HashSet<>();
		MemoryImage image = imageOf(group, start.val(), end.val(), pending);
		Memory memory = new Memory(image, codeImageOf(image, start.val(), end.val(), marks), marks, pending);
		//-- Only worth hunting for the PC when it is inside the range being shown at all. The
		//-- Pascal instead loops until the start address reaches the PC, which for a PC outside
		//-- the range walks the start past the end and leaves the window blank; scrolling away
		//-- from the PC should show the listing you scrolled to.
		boolean pcInRange = pc != null && pc.val() >= start.val() && pc.val() <= end.val();
		Address from = start;
		DisassemblyListing asAsked = null;
		for(;;) {
			DisassemblyListing listing = build(memory, from, end, pc, maxLines);
			if(listing.m_pcLine >= 0 || !pcInRange)
				return listing;
			if(asAsked == null)
				asAsked = listing;
			if(from.val() >= pc.val()) {
				//-- Walked all the way up to the PC without ever landing on it, which means its
				//-- own word was never read from the machine: no realignment can mark a line
				//-- that does not exist. Give back the listing as it was asked for rather than
				//-- the one starting at the PC - the lines between start and the PC are real,
				//-- and throwing them away made a sparsely examined range look empty up to the
				//-- PC with no PC marker to explain why.
				return asAsked;
			}
			//-- The PC is inside an instruction rather than at the start of one. Begin two bytes
			//-- later and decode again; eventually the boundaries line up, or we reach the PC.
			from = from.plus(2);
		}
	}

	/**
	 * What a listing is built from: memory, the same memory with the data left out for the
	 * decoder, the marks, and which words are edits.
	 */
	private record Memory(MemoryImage image, MemoryImage code, DataMarks marks, Set<Integer> pending) {
		boolean isPending(int addr, int length) {
			for(int a = addr & 0xFFFE; a < addr + length; a += 2) {
				if(pending.contains(a & 0xFFFF))
					return true;
			}
			return false;
		}
	}

	/**
	 * Every valid word of the group inside {@code [lo, hi]}, as the disassembler sees memory,
	 * with the addresses of the words that are edits rather than what the machine holds.
	 *
	 * <p>What should be there, not only what the machine said: a program read from a file or
	 * assembled is in shared memory before it is deposited, and seeing it disassembled before
	 * it goes to the machine is the point. It is not what the CPU would execute, though, and
	 * showing it as though it were would be a lie - so every line with such a word in it says
	 * so, in {@link Line#pending()}.</p>
	 */
	private static MemoryImage imageOf(MemoryCellGroup group, long lo, long hi, Set<Integer> pending) {
		MemoryImage image = new MemoryImage();
		for(MemoryCell mc : group.getCells()) {
			long a = mc.getAddr().val();
			if(a < lo || a > hi)
				continue;
			CellValue v = mc.getEditValue();
			if(!v.isKnown())
				continue;
			int at = (int) (a & 0xFFFF);
			image.putWord(at, v.word());
			if(mc.isEdited())
				pending.add(at);
		}
		return image;
	}

	/** The image as the decoder may see it: without the words marked as data. */
	private static MemoryImage codeImageOf(MemoryImage image, long lo, long hi, DataMarks marks) {
		MemoryImage code = new MemoryImage();
		for(long a = lo & 0xFFFE; a <= hi; a += 2) {
			int at = (int) (a & 0xFFFF);
			if(image.isWordValid(at) && marks.formatAt(at) == null)
				code.putWord(at, image.readWord(at));
		}
		return code;
	}

	private static DisassemblyListing build(Memory memory, Address start, Address end, Address pc, int maxLines) {
		List<Line> lines = new ArrayList<>();
		int pcLine = -1;
		int addr = (int) (start.val() & 0xFFFF);
		//-- The last byte, not the last word: a string can end in the middle of a word, and the
		//-- next one then begins at an odd address.
		int last = (int) (end.val() & 0xFFFF) | 1;
		int next = addr;
		while(addr <= last && lines.size() < maxLines) {
			DataMarks.Format format = memory.marks().formatAt(addr);
			Line line;
			if(format == null) {
				if(!memory.code().isWordValid(addr)) {
					addr = (addr + 2) & 0xFFFE;
					continue;
				}
				DecodedInstruction di = Disassembler.disassemble(memory.code(), addr);
				line = new Line(v(addr), di.words() * 2, null, wordsOf(memory.image(), addr, di.words()), di.text(),
					false, memory.isPending(addr, di.words() * 2), WellKnownAddresses.builtin().comment(di));
			} else {
				if(!memory.image().isByteValid(addr)) {
					addr = (addr + 2) & 0xFFFE;
					continue;
				}
				line = dataLine(memory, addr, last, format, pc);
			}
			boolean atPc = pc != null && pc.val() == addr;
			if(atPc) {
				pcLine = lines.size();
				line = new Line(line.address(), line.length(), line.format(), line.words(), line.text(), true,
					line.pending(), line.comment());
			}
			lines.add(line);
			addr += line.length();
			//-- Not simply addr: a run of unread words at the end is not part of the listing, and
			//-- the next page must not begin past the last instruction it actually showed.
			next = addr;
		}
		return new DisassemblyListing(lines, pcLine, start, v(next & 0xFFFF));
	}

	/** Most bytes of text on one line; a longer string carries on as {@code .ASCII} lines. */
	static final int MAX_STRING_BYTES = 40;

	private static final int BYTES_PER_LINE = 4;

	private static final int WORDS_PER_LINE = 3;

	/**
	 * One line of data starting at {@code addr}, which is marked {@code format} and readable.
	 * It runs as far as its format allows, and stops where the format changes, where memory was
	 * not read, at the end of the range and at the PC - so the PC is always at the start of a line.
	 */
	private static Line dataLine(Memory memory, int addr, int last, DataMarks.Format format, Address pc) {
		MemoryImage image = memory.image();
		int max = switch(format) {
			case BYTES -> BYTES_PER_LINE;
			case WORDS -> WORDS_PER_LINE * 2;
			case ASCIZ -> MAX_STRING_BYTES;
		};
		int length = 0;
		while(length < max) {
			int a = addr + length;
			if(a > last || a > 0xFFFF || !image.isByteValid(a) || memory.marks().formatAt(a) != format)
				break;
			if(length > 0 && pc != null && pc.val() == a)
				break;
			length++;
			if(format == DataMarks.Format.ASCIZ && image.readByte(a) == 0)
				break;
		}
		if(format == DataMarks.Format.WORDS)
			length = Math.max(2, length & ~1);
		String text;
		String words;
		if(format == DataMarks.Format.WORDS) {
			StringBuilder sb = new StringBuilder(".word   ");
			for(int i = 0; i < length; i += 2) {
				if(i > 0)
					sb.append(',');
				sb.append(Octal.word(image.readWord(addr + i)));
			}
			text = sb.toString();
			words = wordsOf(image, addr, length / 2);
		} else {
			if(format == DataMarks.Format.BYTES) {
				StringBuilder sb = new StringBuilder(".byte   ");
				for(int i = 0; i < length; i++) {
					if(i > 0)
						sb.append(',');
					sb.append(Octal.format(image.readByte(addr + i), 3));
				}
				text = sb.toString();
			} else {
				text = stringText(image, addr, length);
			}
			words = bytesOf(image, addr, length);
		}
		return new Line(v(addr), length, format, words, text, false, memory.isPending(addr, length), "");
	}

	/**
	 * A string as MACRO-11 writes it: the printable runs between delimiters, everything else as
	 * {@code <octal>}. {@code .ASCIZ} when it ends at its zero byte, which is then not shown,
	 * {@code .ASCII} when it does not. A lone zero byte at an odd address is the padding
	 * {@code .EVEN} puts after a string, and says so.
	 */
	static String stringText(MemoryImage image, int addr, int length) {
		boolean terminated = image.readByte(addr + length - 1) == 0;
		if(terminated && length == 1 && (addr & 1) != 0)
			return ".even";
		int textLength = terminated ? length - 1 : length;
		StringBuilder printable = new StringBuilder();
		for(int i = 0; i < textLength; i++) {
			int b = image.readByte(addr + i);
			if(isPrintable(b))
				printable.append((char) b);
		}
		char delimiter = '/';
		for(char c : new char[]{'/', '"', '|', '\'', '!', '#', '%'}) {
			if(printable.indexOf(String.valueOf(c)) < 0) {
				delimiter = c;
				break;
			}
		}
		StringBuilder sb = new StringBuilder(terminated ? ".asciz  " : ".ascii  ");
		boolean inText = false;
		for(int i = 0; i < textLength; i++) {
			int b = image.readByte(addr + i);
			if(isPrintable(b)) {
				if(!inText)
					sb.append(delimiter);
				inText = true;
				sb.append((char) b);
			} else {
				if(inText)
					sb.append(delimiter);
				inText = false;
				sb.append('<').append(Integer.toOctalString(b)).append('>');
			}
		}
		if(inText)
			sb.append(delimiter);
		else if(textLength == 0)
			sb.append(delimiter).append(delimiter);
		return sb.toString();
	}

	private static boolean isPrintable(int b) {
		return b >= 040 && b < 0177;
	}

	private static Address v(int addr) {
		return Address.of(MemoryAddressType.VIRTUAL, addr);
	}

	/** Up to three raw words, blank-padded, exactly as {@code Disas11}'s listing has them. */
	private static String wordsOf(MemoryImage image, int addr, int count) {
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < Line.RAW_WORDS; i++) {
			if(i < count)
				sb.append(Octal.word(image.readWord(addr + i * 2))).append(' ');
			else
				sb.append("       ");
		}
		return sb.toString();
	}

	/** The first few raw bytes, the way a MACRO-11 listing shows bytes, padded to the raw column. */
	private static String bytesOf(MemoryImage image, int addr, int count) {
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < Math.min(count, Line.RAW_BYTES); i++) {
			sb.append(Octal.format(image.readByte(addr + i), 3)).append(' ');
		}
		while(sb.length() < Line.RAW_WIDTH) {
			sb.append(' ');
		}
		return sb.toString();
	}

	private static void requireVirtual(Address a, String what) {
		if(a.type() != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("The " + what + " of a disassembly is a virtual address, not " + a);
	}
}
