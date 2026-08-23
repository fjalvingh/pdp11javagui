package to.etc.pdp11.core.mmu;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;

import java.util.ArrayList;
import java.util.List;

/**
 * A model of the PDP-11 memory management unit, as fitted to the 11/44 and 11/70.
 *
 * <p>Ported from {@code TPdp11Mmu} ({@code Pdp11MmuU.pas}). It owns a {@link MemoryCellGroup}
 * holding the registers it cares about - the PSW, MMR0, MMR3 and the four sets of page address
 * and page descriptor registers - and keeps its own state in step with them by listening on
 * that group.</p>
 *
 * <p>That listener is why the notification bus belongs in {@code pdp11-core} and not in the
 * UI: this is business logic subscribing to memory cells ({@code Pdp11MmuU.pas:153}), not a
 * window redrawing itself. Deposit to the PSW from any window and the MMU recomputes.</p>
 *
 * <h2>Four corrections to the Pascal</h2>
 *
 * <p>None of these could be checked against SimH - its console {@code examine} does not
 * relocate, so there is no oracle here the way there was for the disassembler. They are read
 * off the memory management chapter of the processor handbook, and each has a test that
 * states the rule it follows.</p>
 *
 * <ol>
 *   <li><b>The displacement mask was {@code $1777}, not {@code $1FFF}</b>
 *       ({@code Pdp11MmuU.pas:240}). The comment on that very line says "lower 13 bit
 *       erhalten", and 13 bits is {@code $1FFF}; {@code $1777} is {@code 0001011101110111},
 *       which drops bits 3, 7 and 11 out of the middle of the offset. Every translation of an
 *       address with any of those bits set landed somewhere else.</li>
 *   <li><b>The page length check was off by one block.</b> The Pascal computes
 *       {@code pagelen := 64 * PLF} and rejects {@code displacement >= pagelen}
 *       ({@code :251-256}). A page holds PLF+1 blocks of 64 bytes, so the last valid block was
 *       rejected - and with PLF = 0, which is a legal one-block page, <i>every</i> address in
 *       it was rejected.</li>
 *   <li><b>Downward-expanding pages threw.</b> ({@code :244-247}) Stack pages expand downward,
 *       so this is not an exotic case; it is the one you hit first when looking at a running
 *       kernel. The direction only changes the length check, never the address arithmetic.</li>
 *   <li><b>Four register dispatch branches were copy-paste wrong</b> ({@code :303-325}). Kernel
 *       instruction PAR and PDR were both written into the <i>user</i> arrays; the kernel
 *       instruction PDR, supervisor data PDR and supervisor instruction PDR branches all
 *       re-tested the address range of the PAR above them, so they were unreachable and those
 *       three PDR sets stayed zero forever.</li>
 * </ol>
 */
public final class Pdp11Mmu {
	/**
	 * What the MMU's register group is tagged with.
	 *
	 * <p>A connection's console builds one of these, so the group belongs to that connection and
	 * goes when it does - {@code ConnectionManager.close} removes it by this tag. Without that,
	 * every reconnect leaves another 99-cell group on the propagation index forever.</p>
	 */
	public static final String USAGE_TAG = "mmu";

	/** I/O page offsets, as 16-bit addresses. Pairs of (offset, what it is). */
	private static final int PSW = 0177776;

	private static final int MMR0 = 0177572;

	private static final int MMR3 = 0172516;

	/** Base address of each of the eight-register PAR/PDR blocks. */
	private static final int USER_D_PAR = 0177660;

	private static final int USER_D_PDR = 0177620;

	private static final int USER_I_PAR = 0177640;

	private static final int USER_I_PDR = 0177600;

	private static final int KERNEL_D_PAR = 0172360;

	private static final int KERNEL_D_PDR = 0172320;

	private static final int KERNEL_I_PAR = 0172340;

	private static final int KERNEL_I_PDR = 0172300;

	private static final int SUPER_D_PAR = 0172260;

	private static final int SUPER_D_PDR = 0172220;

	private static final int SUPER_I_PAR = 0172240;

	private static final int SUPER_I_PDR = 0172200;

	/** One block of eight page registers. */
	private record RegBlock(int base16, CpuMode mode, AccessSpace space, boolean isPar) {
	}

	private static final List<RegBlock> BLOCKS = List.of(
		new RegBlock(USER_D_PAR, CpuMode.USER, AccessSpace.DATA, true),
		new RegBlock(USER_D_PDR, CpuMode.USER, AccessSpace.DATA, false),
		new RegBlock(USER_I_PAR, CpuMode.USER, AccessSpace.INSTRUCTION, true),
		new RegBlock(USER_I_PDR, CpuMode.USER, AccessSpace.INSTRUCTION, false),
		new RegBlock(KERNEL_D_PAR, CpuMode.KERNEL, AccessSpace.DATA, true),
		new RegBlock(KERNEL_D_PDR, CpuMode.KERNEL, AccessSpace.DATA, false),
		new RegBlock(KERNEL_I_PAR, CpuMode.KERNEL, AccessSpace.INSTRUCTION, true),
		new RegBlock(KERNEL_I_PDR, CpuMode.KERNEL, AccessSpace.INSTRUCTION, false),
		new RegBlock(SUPER_D_PAR, CpuMode.SUPERVISOR, AccessSpace.DATA, true),
		new RegBlock(SUPER_D_PDR, CpuMode.SUPERVISOR, AccessSpace.DATA, false),
		new RegBlock(SUPER_I_PAR, CpuMode.SUPERVISOR, AccessSpace.INSTRUCTION, true),
		new RegBlock(SUPER_I_PDR, CpuMode.SUPERVISOR, AccessSpace.INSTRUCTION, false));

	/** Bit 3 of a PDR: the page expands downward, as a stack page does. */
	private static final int PDR_EXPAND_DOWN = 0x8;

	private final MemoryCellGroup m_group;

	private final MemoryCell m_pswCell;

	private CpuMode m_cpuMode = CpuMode.KERNEL;

	/** MMR0 bit 0. With relocation off, virtual and physical are the same thing. */
	private boolean m_relocationEnabled;

	/** MMR3 bits 2..0. A mode without D space sends data accesses through the I map. */
	private final boolean[] m_dSpaceEnabled = new boolean[CpuMode.values().length];

	/** MMR3 bit 4. */
	private boolean m_mapping22Bit;

	/** MMR3 bit 5. */
	private boolean m_unibusRelocation;

	private final int[][][] m_par = new int[CpuMode.values().length][AccessSpace.values().length][8];

	private final int[][][] m_pdr = new int[CpuMode.values().length][AccessSpace.values().length][8];

	private final List<Runnable> m_listeners = new ArrayList<>();

	/**
	 * Build the MMU's register group inside the application's groups, so that examining these
	 * registers anywhere updates the MMU and vice versa.
	 *
	 * <p>Created at 22 bits, like the Pascal ({@code :148}); a later
	 * {@link MemoryCellGroups#changeAddressWidth} moves it to the target machine's width.</p>
	 */
	public Pdp11Mmu(MemoryCellGroups groups) {
		m_group = groups.addGroup(MemoryAddressType.PHYSICAL22, "MMU");
		m_group.setUsageTag(USAGE_TAG);

		m_pswCell = addRegister(PSW, "PSW");
		addRegister(MMR0, "MMR0");
		addRegister(MMR3, "MMR3");
		for(RegBlock b : BLOCKS) {
			String name = b.mode().name().charAt(0)
				+ (b.space() == AccessSpace.INSTRUCTION ? "I" : "D")
				+ (b.isPar() ? "PAR" : "PDR");
			for(int i = 0; i < 8; i++) {
				addRegister(b.base16() + 2 * i, name + i);
			}
		}
		m_group.addListener((group, cell) -> {
			evalCell(cell);
			fireChanged();
		});
	}

	private MemoryCell addRegister(int addr16, String name) {
		MemoryCell mc = m_group.add(Address.of(MemoryAddressType.PHYSICAL16, addr16)
			.withWidth(MemoryAddressType.PHYSICAL22));
		mc.setName(name);
		return mc;
	}

	/** The group of registers this MMU watches, so the console can examine them all at once. */
	public MemoryCellGroup getRegisterGroup() {
		return m_group;
	}

	public MemoryCell getPswCell() {
		return m_pswCell;
	}

	/** Told whenever a register change moved the MMU's state. */
	public void addChangeListener(Runnable listener) {
		m_listeners.add(listener);
	}

	/** Stop telling this one - a window that has been closed. */
	public void removeChangeListener(Runnable listener) {
		m_listeners.remove(listener);
	}

	private void fireChanged() {
		for(Runnable r : m_listeners) {
			r.run();
		}
	}

	/**
	 * The physical width the MMU is currently mapping to, which is the width its register
	 * group is expressed at. Ported from {@code getPhysicalAddressType} ({@code :205-208}).
	 */
	public MemoryAddressType getPhysicalAddressType() {
		return m_group.getType();
	}

	public CpuMode getCpuMode() {
		return m_cpuMode;
	}

	public boolean isRelocationEnabled() {
		return m_relocationEnabled;
	}

	public boolean isDSpaceEnabled(CpuMode mode) {
		return m_dSpaceEnabled[mode.ordinal()];
	}

	public boolean isMapping22Bit() {
		return m_mapping22Bit;
	}

	public boolean isUnibusRelocation() {
		return m_unibusRelocation;
	}

	public int getPar(CpuMode mode, AccessSpace space, int page) {
		return m_par[mode.ordinal()][space.ordinal()][page];
	}

	public int getPdr(CpuMode mode, AccessSpace space, int page) {
		return m_pdr[mode.ordinal()][space.ordinal()][page];
	}

	/**
	 * Translate a 16-bit virtual address to a physical one.
	 *
	 * <p>Ported from {@code Virtual2Physical} ({@code :218-258}), with the corrections listed
	 * in the class comment. The order of the tests matters and is the handbook's:</p>
	 *
	 * <ol>
	 *   <li>The top 8 KB of virtual space is the I/O page and is never relocated; it maps to
	 *       the I/O page at the target width, which is the same rebasing
	 *       {@link Address#withWidth} does.</li>
	 *   <li>With MMR0 relocation off, physical equals virtual.</li>
	 *   <li>Otherwise page 15..13 selects a PAR/PDR pair, the PDR gives the page's length and
	 *       direction, and the physical address is the page address field shifted up six bits
	 *       plus the 13-bit displacement.</li>
	 * </ol>
	 */
	public TranslationResult translate(Address virtual, CpuMode mode, AccessSpace space) {
		if(virtual.type() != MemoryAddressType.VIRTUAL)
			throw new IllegalArgumentException("Can only translate a virtual address, got " + virtual);
		long v = virtual.val();
		if(v > 0xFFFF)
			return TranslationResult.failed(TranslationResult.Failure.NOT_A_SIXTEEN_BIT_ADDRESS);

		MemoryAddressType outType = getPhysicalAddressType();
		if(v >= MemoryAddressType.VIRTUAL.getIopageBase())
			return TranslationResult.of(Address.of(MemoryAddressType.VIRTUAL, v).withWidth(outType));
		if(!m_relocationEnabled)
			return TranslationResult.of(Address.of(outType, v));

		AccessSpace effective = m_dSpaceEnabled[mode.ordinal()] ? space : AccessSpace.INSTRUCTION;
		int page = (int) ((v >>> 13) & 7);
		//-- 13 bits. The Pascal masks with $1777, which is not 13 contiguous bits at all.
		int displacement = (int) (v & 0x1FFF);
		int paf = m_par[mode.ordinal()][effective.ordinal()][page];
		int pdr = m_pdr[mode.ordinal()][effective.ordinal()][page];

		//-- PLF is PDR bits 14..8, the page length in 32-word blocks, and a page holds PLF+1
		//-- of them. The block number is bits 12..6 of the virtual address.
		int plf = (pdr >>> 8) & 0x7F;
		int blockNr = displacement >>> 6;
		boolean expandsDown = (pdr & PDR_EXPAND_DOWN) != 0;
		boolean lengthError = expandsDown ? blockNr < plf : blockNr > plf;
		if(lengthError)
			return TranslationResult.failed(TranslationResult.Failure.PAGE_LENGTH_ERROR);

		//-- The physical adder is as wide as the machine's bus and a carry out of the top is
		//-- simply lost: a PAF of 0177777 over a full-length page addresses past 22 bits, and
		//-- the hardware wraps. {@code Address.of} refuses an address that wide instead, and a
		//-- throw here is not a local failure - every redraw of the MMU window is 32768 of
		//-- these, so one unlucky page register took the whole window out and left it showing
		//-- whatever it had said last. The Pascal computes the same sum into a dword and never
		//-- looks ({@code Pdp11MmuU.pas:253}).
		long physical = (((long) paf << 6) + displacement) & ((1L << outType.getBits()) - 1);
		return TranslationResult.of(Address.of(outType, physical));
	}

	public TranslationResult translateData(Address virtual) {
		return translate(virtual, m_cpuMode, AccessSpace.DATA);
	}

	public TranslationResult translateInstruction(Address virtual) {
		return translate(virtual, m_cpuMode, AccessSpace.INSTRUCTION);
	}

	/** Recompute everything from the register group's current values. */
	public void evalAll() {
		for(MemoryCell mc : m_group.getCells()) {
			evalCell(mc);
		}
		fireChanged();
	}

	/**
	 * Fold one register's value into the MMU state.
	 *
	 * <p>Ported from {@code evalMemoryCell} ({@code :277-333}), which is where the four
	 * copy-paste dispatch bugs live. Driving the dispatch off {@link #BLOCKS} rather than a
	 * ladder of twelve near-identical range tests is what stops them recurring: each block
	 * appears once, with its mode, its space and whether it is a PAR or a PDR written next to
	 * its address.</p>
	 */
	private void evalCell(MemoryCell cell) {
		if(!cell.getPdpValue().isKnown())
			return;
		int value = cell.getPdpValue().word();
		//-- Compare in 16 bits so this works whatever width the group currently sits at.
		long a = cell.getAddr().withWidth(MemoryAddressType.PHYSICAL16).val();

		if(a == PSW) {
			m_cpuMode = CpuMode.fromPsw(value);
			return;
		}
		if(a == MMR0) {
			m_relocationEnabled = (value & 0x01) != 0;
			return;
		}
		if(a == MMR3) {
			m_dSpaceEnabled[CpuMode.USER.ordinal()] = (value & 0x01) != 0;
			m_dSpaceEnabled[CpuMode.SUPERVISOR.ordinal()] = (value & 0x02) != 0;
			m_dSpaceEnabled[CpuMode.KERNEL.ordinal()] = (value & 0x04) != 0;
			m_mapping22Bit = (value & 0x10) != 0;
			m_unibusRelocation = (value & 0x20) != 0;
			return;
		}
		for(RegBlock b : BLOCKS) {
			if(a >= b.base16() && a < b.base16() + 16) {
				int idx = (int) ((a - b.base16()) / 2);
				int[][][] table = b.isPar() ? m_par : m_pdr;
				table[b.mode().ordinal()][b.space().ordinal()][idx] = value;
				return;
			}
		}
	}
}
