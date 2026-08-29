package to.etc.pdp11.core.disas;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Octal;

import java.util.ArrayList;
import java.util.List;

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
	 * One line: where it is, the raw words behind it, and what they decode to.
	 *
	 * @param atPc whether the program counter is here
	 */
	public record Line(Address address, String words, String text, boolean atPc) {
		/** The whole line, in the layout {@code Disas11} produces. */
		public String toDisplayString() {
			return address.toOctal() + ": " + words + " " + text;
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
		requireVirtual(start, "start");
		requireVirtual(end, "end");
		if(pc != null)
			requireVirtual(pc, "PC");

		MemoryImage image = imageOf(group, start.val(), end.val());
		//-- Only worth hunting for the PC when it is inside the range being shown at all. The
		//-- Pascal instead loops until the start address reaches the PC, which for a PC outside
		//-- the range walks the start past the end and leaves the window blank; scrolling away
		//-- from the PC should show the listing you scrolled to.
		boolean pcInRange = pc != null && pc.val() >= start.val() && pc.val() <= end.val();
		Address from = start;
		DisassemblyListing asAsked = null;
		for(;;) {
			DisassemblyListing listing = build(image, from, end, pc, maxLines);
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

	/** Every valid word of the group inside {@code [lo, hi]}, as the disassembler sees memory. */
	private static MemoryImage imageOf(MemoryCellGroup group, long lo, long hi) {
		MemoryImage image = new MemoryImage();
		for(MemoryCell mc : group.getCells()) {
			long a = mc.getAddr().val();
			if(a < lo || a > hi)
				continue;
			//-- Only what the machine actually answered. An edited-but-not-deposited value is
			//-- not what the CPU would execute, and showing it as though it were is a lie.
			if(!mc.getPdpValue().isKnown())
				continue;
			image.putWord((int) (a & 0xFFFF), mc.getPdpValue().word());
		}
		return image;
	}

	private static DisassemblyListing build(MemoryImage image, Address start, Address end, Address pc,
		int maxLines) {
		List<Line> lines = new ArrayList<>();
		int pcLine = -1;
		int addr = (int) (start.val() & 0xFFFF);
		int last = (int) (end.val() & 0xFFFF);
		int next = addr;
		while(addr <= last && lines.size() < maxLines) {
			if(!image.isWordValid(addr)) {
				addr += 2;
				continue;
			}
			DecodedInstruction di = Disassembler.disassemble(image, addr);
			boolean atPc = pc != null && pc.val() == addr;
			if(atPc)
				pcLine = lines.size();
			lines.add(new Line(Address.of(MemoryAddressType.VIRTUAL, addr), wordsOf(image, di), di.text(), atPc));
			addr += di.words() * 2;
			//-- Not simply addr: a run of unread words at the end is not part of the listing, and
			//-- the next page must not begin past the last instruction it actually showed.
			next = addr;
		}
		return new DisassemblyListing(lines, pcLine, start,
			Address.of(MemoryAddressType.VIRTUAL, next & 0xFFFF));
	}

	/** Up to three raw words, blank-padded, exactly as {@code Disas11}'s listing has them. */
	private static String wordsOf(MemoryImage image, DecodedInstruction di) {
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < 3; i++) {
			if(i < di.words())
				sb.append(Octal.word(image.readWord(di.address() + i * 2))).append(' ');
			else
				sb.append("       ");
		}
		return sb.toString();
	}

	private static void requireVirtual(Address a, String what) {
		if(a.type() != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("The " + what + " of a disassembly is a virtual address, not " + a);
	}
}
