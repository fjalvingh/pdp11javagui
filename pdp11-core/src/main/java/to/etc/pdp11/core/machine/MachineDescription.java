package to.etc.pdp11.core.machine;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.bits.BitfieldDef;
import to.etc.pdp11.core.bits.BitfieldsDef;
import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Octal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads a machine description: which devices a particular PDP-11 has, and where they are
 * jumpered.
 *
 * <p>Ported from {@code AddGroupsFromIniFile} ({@code MemoryCellU.pas:659-763}) and
 * {@code TBitfieldsDefs.LoadFromIniFile} ({@code BitFieldU.pas:180-236}), with m4 handled by
 * {@link M4Preprocessor} instead of a batch file.</p>
 *
 * <h2>The format</h2>
 *
 * <p>Sections whose name begins with {@code Bits.} define the named bits of a register;
 * everything else is a device.</p>
 *
 * <pre>
 * [Console Terminal]
 * Info=Serial console terminal
 * Enabled=0                                       ; optional, drops the whole section
 * RCSR= 177560 ;"Receiver Control/Status Register";Bits.SLU.RCSR
 * BOOT= 173000:173776 ;"Boot ROM"                 ; a range becomes BOOT[0], BOOT[1], ...
 *
 * [Bits.SLU.RCSR]
 * Done=7;"Receiver done"
 * Priority=7:5;"Current level of processor priority"
 * </pre>
 *
 * <p>Addresses are octal and always 16-bit I/O page addresses, by the descriptions' own
 * convention, "so the same device definition can be used for 16, 18 and 22 bit machines"
 * ({@code pdp11.ini:12-15}). <b>Nothing has to be done about that here.</b> Every console
 * normalises an address to its own width on the way out, and {@link MemoryCellGroups} keys its
 * propagation index on the 22-bit form, so a 16-bit register group reaches the right register on
 * a 22-bit machine untouched - which {@code RegisterGroupWidthTest} checks against a live
 * simulated machine. The Pascal has to call {@code ChangeAdddressWidth} over every group in the
 * application whenever a console is chosen, from nine places in {@code FormMainU}, because its
 * consoles send the address they are given.</p>
 */
public final class MachineDescription {
	/** Everything loaded from a description carries this, so a reload can drop just it. */
	public static final String USAGE_TAG = "machine";

	/** Section names starting with this define bits, not devices. */
	private static final String BITS_PREFIX = "BITS.";

	private final String m_name;

	private final List<String> m_warnings = new ArrayList<>();

	private MachineDescription(String name) {
		m_name = name;
	}

	public String getName() {
		return m_name;
	}

	/**
	 * Problems that did not stop the load: a register naming a bitfield definition that does
	 * not exist, a bit range outside a word. The Pascal raises on the first of these
	 * ({@code MemoryCellU.pas:740-742}), which makes one typo in a 1300-line generated file
	 * cost the user every device in it. Collect and report instead.
	 */
	public List<String> getWarnings() {
		return m_warnings;
	}

	/**
	 * Load a description file, adding its groups and bitfield definitions to the given
	 * collections.
	 *
	 * @param file      the {@code .ini}, before m4
	 * @param groups    device register groups are added here
	 * @param bitfields bit definitions are added here
	 * @param logger    may be {@link Logger#NULL}
	 */
	public static MachineDescription load(Path file, MemoryCellGroups groups, BitfieldsDefs bitfields, Logger logger) {
		Path dir = file.toAbsolutePath().getParent();
		String expanded = new M4Preprocessor(dir).processFile(file);
		logger.log(LogChannel.OTHER, "Loaded machine description %s (%d bytes after m4)",
			file.getFileName(), expanded.length());
		return parse(expanded, file.getFileName().toString(), groups, bitfields);
	}

	/** Load from already-expanded text. Split out so it can be tested without a file. */
	public static MachineDescription parse(String expandedText, String name,
		MemoryCellGroups groups, BitfieldsDefs bitfields) {
		MachineDescription md = new MachineDescription(name);
		IniFile ini = IniFile.parse(expandedText);

		//-- Bit definitions first: device registers reference them by name, and a register can
		//-- appear before the section defining its bits.
		for(IniFile.Section s : ini.getSections()) {
			if(isBitsSection(s))
				md.loadBitfields(s, bitfields);
		}
		for(IniFile.Section s : ini.getSections()) {
			if(!isBitsSection(s))
				md.loadDevice(s, groups, bitfields);
		}
		return md;
	}

	private static boolean isBitsSection(IniFile.Section s) {
		return s.name().toUpperCase(Locale.ROOT).startsWith(BITS_PREFIX);
	}

	/**
	 * A {@code [Bits.*]} section: {@code name = bit_hi[:bit_lo] ; info}.
	 */
	private void loadBitfields(IniFile.Section section, BitfieldsDefs into) {
		BitfieldsDef def = new BitfieldsDef(section.name());
		for(IniFile.Entry e : section.entries()) {
			String[] parts = splitFields(e.value());
			try {
				int[] bits = parseRange(parts[0], "bit");
				//-- The Pascal swaps them if they arrive the wrong way round
				//-- (BitFieldU.pas:216-218), which is worth keeping: the descriptions are
				//-- hand-written and "0:7" happens.
				int hi = Math.max(bits[0], bits[1]);
				int lo = Math.min(bits[0], bits[1]);
				def.add(new BitfieldDef(e.key(), IniFile.stripQuotes(parts[1]), hi, lo));
			} catch(RuntimeException x) {
				m_warnings.add("line " + e.lineNr() + ", [" + section.name() + "] " + e.key()
					+ ": " + x.getMessage());
			}
		}
		if(!def.isEmpty())
			into.add(def);
	}

	/**
	 * A device section: {@code Info}, an optional {@code Enabled}, and one line per register.
	 */
	private void loadDevice(IniFile.Section section, MemoryCellGroups groups, BitfieldsDefs bitfields) {
		//-- Read Enabled first. The Pascal builds the whole group and then frees it again when
		//-- it turns out to be disabled (MemoryCellU.pas:748-750), which also leaves its
		//-- bitfield links behind in bitfieldsdefs.
		String enabled = section.findLast("Enabled");
		if(enabled != null) {
			String v = IniFile.stripQuotes(enabled).toUpperCase(Locale.ROOT);
			if("0".equals(v) || "FALSE".equals(v))
				return;
		}

		MemoryCellGroup group = groups.addGroup(MemoryAddressType.PHYSICAL16, section.name());
		group.setUsageTag(USAGE_TAG);

		for(IniFile.Entry e : section.entries()) {
			if(e.key().equalsIgnoreCase("Info")) {
				group.setGroupInfo(IniFile.stripQuotes(e.value()));
				continue;
			}
			if(e.key().equalsIgnoreCase("Enabled"))
				continue;
			try {
				addRegister(group, bitfields, e);
			} catch(RuntimeException x) {
				m_warnings.add("line " + e.lineNr() + ", [" + section.name() + "] " + e.key()
					+ ": " + x.getMessage());
			}
		}
		if(group.isEmpty())
			groups.removeGroup(group);
	}

	/**
	 * One register line: {@code NAME = addr[:addr_to] ; info ; BitfieldsDefName}.
	 *
	 * <p>A range creates one cell per word, named {@code NAME[0]}, {@code NAME[1]} ... with the
	 * word index and byte offset appended to the info - which is how a 64-word boot ROM becomes
	 * something the register window can show.</p>
	 */
	private void addRegister(MemoryCellGroup group, BitfieldsDefs bitfields, IniFile.Entry e) {
		String[] parts = splitFields(e.value());
		int[] addrs = parseRange(parts[0], "address");
		long from = addrs[0];
		long to = addrs[1];
		if(to < from)
			throw new IllegalArgumentException("address range 0" + Long.toOctalString(from)
				+ ":0" + Long.toOctalString(to) + " runs backwards");

		String info = IniFile.stripQuotes(parts[1]);
		String bitsName = parts[2].strip();
		boolean single = from == to;

		for(long addrValue = from, k = 0; addrValue <= to; addrValue += 2, k++) {
			Address addr = Address.of(MemoryAddressType.PHYSICAL16, addrValue);
			MemoryCell cell = group.add(addr);
			if(single) {
				cell.setName(e.key());
				cell.setInfo(info);
			} else {
				cell.setName(e.key() + "[" + k + "]");
				cell.setInfo(info + " Word #" + k + ", offset +" + Octal.format(2 * k, 1));
			}
			if(!bitsName.isEmpty() && !bitfields.linkAddress(addr, bitsName)) {
				m_warnings.add("line " + e.lineNr() + ", register " + e.key()
					+ ": bitfields definition '" + bitsName + "' does not exist");
			}
		}
	}

	/**
	 * Split a value on {@code ;} into exactly three fields, padding with empty strings.
	 *
	 * <p>The Pascal does this by appending {@code ';;'} and letting {@code TStringList} split
	 * it ({@code MemoryCellU.pas:717-718}), which also splits on spaces and processes quotes
	 * because {@code StrictDelimiter} is false by default. Splitting on the separator and
	 * nothing else is what the format means; where the two differ, this one is right.</p>
	 */
	private static String[] splitFields(String value) {
		String[] out = {"", "", ""};
		String[] parts = value.split(";", 3);
		System.arraycopy(parts, 0, out, 0, Math.min(parts.length, 3));
		return out;
	}

	/**
	 * {@code "n"} or {@code "n:m"}, in octal for addresses and decimal for bit numbers.
	 * Returns {@code {n, n}} for a single value.
	 */
	private static int[] parseRange(String text, String what) {
		String s = text.strip();
		//-- The Pascal splits on both ':' and ' ' here (ExtractWord with [' ', ':']), which
		//-- matters because the descriptions write "177560 " with trailing space.
		String[] parts = s.split("[\\s:]+");
		if(parts.length == 0 || parts[0].isEmpty())
			throw new IllegalArgumentException("missing " + what);
		int first = parseValue(parts[0], what);
		int second = parts.length > 1 ? parseValue(parts[1], what) : first;
		return new int[]{first, second};
	}

	private static int parseValue(String s, String what) {
		//-- Addresses are octal; bit numbers are small decimals, and every decimal 0..9 is
		//-- also octal, so one parser covers both. Two-digit bit numbers (10..15) would not
		//-- survive an octal read, so they are handled explicitly.
		if("bit".equals(what))
			return Integer.parseInt(s);
		long v = Octal.parse(s);
		if(v > 0xFFFF)
			throw new IllegalArgumentException("address 0" + Long.toOctalString(v)
				+ " does not fit in a 16 bit I/O page address");
		return (int) v;
	}
}
