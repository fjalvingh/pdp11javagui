package to.etc.pdp11.common.disas;

import to.etc.pdp11.common.util.Octal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The addresses every PDP-11 programmer knows by name - the console DL11 at {@code 177560}, the
 * MMU's page registers, the RK11 at {@code 177400}, the trap vectors - and the comment a
 * disassembly line gets when its instruction names one.
 *
 * <p>The Pascal has no such table in its disassembler. It knows register names only through the
 * machine description, and only the I/O page windows use it. This one is packaged with the
 * disassembler instead, in {@code pdp11-common}, because a disassembly means the same with no
 * machine described or connected at all - which is what the web's disassembler is.</p>
 *
 * <h2>Which operands are commented</h2>
 *
 * <p>An absolute operand ({@code @#177560}) and a PC-relative one name an address, and are
 * commented wherever that address is in the table, vectors included. An immediate
 * ({@code #177560}) is only a number, and is commented only when it is in the I/O page: that
 * is where a bootstrap loads a CSR address into a register before indexing off it, while a
 * {@code #4} is far more often a count than the bus error vector. Branch targets and index
 * offsets are never commented.</p>
 *
 * <p>An odd address is the high byte of the word below it, and is named so - {@code TSTB
 * @#177565} tests the high byte of {@code TPS} - unless the table names it itself, as it does
 * the console's view of the general registers.</p>
 */
public final class WellKnownAddresses {
	static final String RESOURCE = "well-known-addresses.txt";

	/** The lowest address of the I/O page, as a 16-bit address. */
	static final int IO_PAGE = 0160000;

	/**
	 * One line of the table.
	 *
	 * @param first       the address, or the first of a range
	 * @param last        the last address of a range; {@code first} for a single address
	 * @param name        the register's mnemonic, or {@code null} for a line that has none
	 * @param description what it is
	 */
	public record Entry(int first, int last, String name, String description) {
		public boolean isRange() {
			return last != first;
		}

		public boolean contains(int address) {
			return address >= first && address <= last;
		}

		/** {@code "TKS: Console receiver status"}, or just the description if there is no name. */
		public String label() {
			return name == null ? description : name + ": " + description;
		}
	}

	private static final class Holder {
		private static final WellKnownAddresses BUILTIN = load();
	}

	/** Single addresses, each with every entry at it; shared addresses have more than one. */
	private final TreeMap<Integer, List<Entry>> m_exact = new TreeMap<>();

	private final List<Entry> m_ranges = new ArrayList<>();

	private final List<Entry> m_all;

	WellKnownAddresses(List<Entry> entries) {
		m_all = List.copyOf(entries);
		for(Entry e : entries) {
			if(e.isRange())
				m_ranges.add(e);
			else
				m_exact.computeIfAbsent(e.first(), k -> new ArrayList<>()).add(e);
		}
	}

	/** The table packaged with this class. Read once; it never changes. */
	public static WellKnownAddresses builtin() {
		return Holder.BUILTIN;
	}

	public List<Entry> getEntries() {
		return m_all;
	}

	/**
	 * What the 16-bit {@code address} is, or {@code null} if it is nothing in the table.
	 * Several devices at one address come back joined with {@code " / "}.
	 */
	public String describe(int address) {
		address &= 0xFFFF;
		List<Entry> at = m_exact.get(address);
		if(at != null)
			return join(at, "");
		if((address & 1) != 0) {
			List<Entry> word = m_exact.get(address - 1);
			if(word != null)
				return join(word, ", high byte");
		}
		for(Entry r : m_ranges) {
			if(r.contains(address))
				return r.label();
		}
		return null;
	}

	/**
	 * The comment for {@code di}, without the {@code ;}: what each address it names is, in
	 * operand order, or the empty string if it names none the table knows.
	 */
	public String comment(DecodedInstruction di) {
		Set<String> parts = new LinkedHashSet<>();
		for(DecodedInstruction.Reference ref : di.references()) {
			if(ref.kind() == DecodedInstruction.ReferenceKind.IMMEDIATE && ref.address() < IO_PAGE)
				continue;
			String d = describe(ref.address());
			if(d != null)
				parts.add(d);
		}
		return String.join("; ", parts);
	}

	private static String join(List<Entry> entries, String suffix) {
		StringBuilder sb = new StringBuilder();
		for(Entry e : entries) {
			if(!sb.isEmpty())
				sb.append(" / ");
			sb.append(e.label()).append(suffix);
		}
		return sb.toString();
	}

	private static WellKnownAddresses load() {
		try(InputStream is = WellKnownAddresses.class.getResourceAsStream(RESOURCE)) {
			if(is == null)
				throw new IllegalStateException("The packaged address table is missing: " + RESOURCE);
			try(BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.US_ASCII))) {
				return new WellKnownAddresses(parse(r.lines().toList()));
			}
		} catch(IOException x) {
			throw new UncheckedIOException("Cannot read the packaged address table", x);
		}
	}

	/**
	 * Parse the table's lines: {@code address[-last] NAME description}, {@code #} comments and
	 * blank lines ignored, a name of {@code -} meaning none.
	 *
	 * @throws IllegalArgumentException naming the line, on anything else
	 */
	static List<Entry> parse(List<String> lines) {
		List<Entry> list = new ArrayList<>();
		int lineNo = 0;
		for(String line : lines) {
			lineNo++;
			String s = line.strip();
			if(s.isEmpty() || s.startsWith("#"))
				continue;
			String[] f = s.split("\\s+", 3);
			if(f.length < 3)
				throw new IllegalArgumentException(RESOURCE + ":" + lineNo + ": expected address, name and description: " + line);
			try {
				int first;
				int last;
				int dash = f[0].indexOf('-');
				if(dash < 0) {
					first = last = parseAddress(f[0]);
				} else {
					first = parseAddress(f[0].substring(0, dash));
					last = parseAddress(f[0].substring(dash + 1));
					if(last <= first)
						throw new IllegalArgumentException("range ends before it starts");
				}
				String name = f[1].equals("-") ? null : f[1];
				list.add(new Entry(first, last, name, f[2].strip()));
			} catch(IllegalArgumentException x) {
				throw new IllegalArgumentException(RESOURCE + ":" + lineNo + ": " + x.getMessage() + ": " + line, x);
			}
		}
		return list;
	}

	private static int parseAddress(String s) {
		if(s.length() != 6)
			throw new IllegalArgumentException("an address is six octal digits, not '" + s + "'");
		long v = Octal.parse(s);
		if(v < 0 || v > 0xFFFF)
			throw new IllegalArgumentException("not a 16-bit address: " + s);
		return (int) v;
	}
}
