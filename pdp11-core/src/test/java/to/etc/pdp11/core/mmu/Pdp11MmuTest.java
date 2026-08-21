package to.etc.pdp11.core.mmu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MMU, and in particular the four corrections to the Pascal listed on {@link Pdp11Mmu}.
 *
 * <p>Unlike the disassembler there is no oracle here - SimH's console {@code examine} does not
 * relocate, so it cannot be asked what a translation should be. Every expectation below is
 * therefore stated as the rule from the memory management chapter of the processor handbook
 * that it follows, so a future reader can check the rule rather than the number.</p>
 */
class Pdp11MmuTest {
	private MemoryCellGroups m_groups;

	private Pdp11Mmu m_mmu;

	@BeforeEach
	void setUp() {
		m_groups = new MemoryCellGroups();
		m_mmu = new Pdp11Mmu(m_groups);
	}

	/**
	 * Set a register by its 16-bit I/O page address and let the MMU see it. Converts to
	 * whatever width the group currently sits at, which changes with the target machine.
	 */
	private void poke(int addr16, int value) {
		MemoryCell mc = m_mmu.getRegisterGroup().findByAddress(
			Address.of(MemoryAddressType.PHYSICAL16, addr16)
				.withWidth(m_mmu.getPhysicalAddressType()).val());
		if(mc == null)
			throw new IllegalArgumentException("the MMU has no register at 0" + Integer.toOctalString(addr16));
		mc.setPdpValue(CellValue.of(value));
		m_mmu.evalAll();
	}

	private static Address virt(int v) {
		return Address.of(MemoryAddressType.VIRTUAL, v);
	}

	/** PDR with the given page length field and upward expansion. */
	private static int pdrUp(int plf) {
		return plf << 8;
	}

	/** PDR with the given page length field and downward expansion (bit 3 set). */
	private static int pdrDown(int plf) {
		return (plf << 8) | 0x8;
	}

	@Test
	void withRelocationOffPhysicalEqualsVirtual() {
		poke(0177572, 0);                                   // MMR0, relocation disabled
		assertFalse(m_mmu.isRelocationEnabled());
		assertEquals(01000L, m_mmu.translateData(virt(01000)).address().val());
	}

	/** The top 8 KB of virtual space is the I/O page and is never relocated. */
	@Test
	void theIoPageIsNeverRelocated() {
		poke(0177572, 1);                                   // relocation on
		TranslationResult r = m_mmu.translateData(virt(0177570));
		assertTrue(r.isValid());
		//-- The MMU group is built at 22 bits, so the answer is the 22-bit form.
		assertEquals(017777570L, r.address().val());
		assertEquals(MemoryAddressType.PHYSICAL22, r.address().type());
	}

	/**
	 * The physical address is the page address field shifted up six bits plus the
	 * displacement, which is the low 13 bits of the virtual address.
	 */
	@Test
	void relocationIsPageAddressFieldTimesSixtyFourPlusDisplacement() {
		poke(0177572, 1);                                   // MMR0: relocation on
		poke(0177776, 0);                                   // PSW: kernel mode
		poke(0172340, 01000);                               // Kernel I PAR 0: PAF = 01000
		poke(0172300, pdrUp(0177));                         // Kernel I PDR 0: whole page
		assertEquals(CpuMode.KERNEL, m_mmu.getCpuMode());
		assertEquals(0100000L, m_mmu.translateInstruction(virt(0)).address().val());
		assertEquals(0100100L, m_mmu.translateInstruction(virt(0100)).address().val());
	}

	/**
	 * Correction 1. The displacement is the low <b>13</b> bits. The Pascal masks with
	 * {@code $1777}, which is {@code 0001011101110111} - not a contiguous field at all - so
	 * bits 3, 7 and 11 fall out of the middle of every offset.
	 */
	@Test
	void theDisplacementIsThirteenContiguousBits() {
		poke(0177572, 1);
		poke(0177776, 0);
		poke(0172340, 0);                                   // PAF 0, so physical == displacement
		poke(0172300, pdrUp(0177));

		//-- 017777 sets every one of the 13 bits; under the Pascal's mask it would come back
		//-- as 016567, having quietly lost bits 3, 7 and 11.
		assertEquals(017777L, m_mmu.translateInstruction(virt(017777)).address().val());
		assertEquals(010L, m_mmu.translateInstruction(virt(010)).address().val());
		assertEquals(0200L, m_mmu.translateInstruction(virt(0200)).address().val());
		assertEquals(04000L, m_mmu.translateInstruction(virt(04000)).address().val());
	}

	/**
	 * Correction 2. A page holds PLF+1 blocks of 32 words, and a length error is block number
	 * &gt; PLF. The Pascal rejects {@code displacement >= 64 * PLF}, which is one block short.
	 * PLF = 0 is the case that makes it obvious: a legal one-block page in which the Pascal
	 * rejects every single address.
	 */
	@Test
	void aPageOfLengthFieldZeroStillHasOneValidBlock() {
		poke(0177572, 1);
		poke(0177776, 0);
		poke(0172340, 0);
		poke(0172300, pdrUp(0));                            // PLF 0: exactly one 64-byte block

		assertTrue(m_mmu.translateInstruction(virt(0)).isValid());
		assertTrue(m_mmu.translateInstruction(virt(077)).isValid(), "the last byte of block 0");
		assertFalse(m_mmu.translateInstruction(virt(0100)).isValid(), "block 1 is past the end");
		assertEquals(TranslationResult.Failure.PAGE_LENGTH_ERROR,
			m_mmu.translateInstruction(virt(0100)).failure());
	}

	@Test
	void theLastBlockOfALongerPageIsAlsoValid() {
		poke(0177572, 1);
		poke(0177776, 0);
		poke(0172340, 0);
		poke(0172300, pdrUp(3));                            // four blocks: 0..0377

		assertTrue(m_mmu.translateInstruction(virt(0377)).isValid(), "last byte of block 3");
		assertFalse(m_mmu.translateInstruction(virt(0400)).isValid(), "block 4 is past the end");
	}

	/**
	 * Correction 3. Downward expansion is what stack pages use, so it is the first thing you
	 * meet in a running kernel - the Pascal raises an exception rather than translating. The
	 * direction only flips the length comparison; the arithmetic is unchanged.
	 */
	@Test
	void downwardExpandingPagesTranslateInsteadOfThrowing() {
		poke(0177572, 1);
		poke(0177776, 0);
		poke(0172340, 0);
		poke(0172300, pdrDown(0175));                       // valid blocks are 0175..0177

		assertFalse(m_mmu.translateInstruction(virt(017377)).isValid(), "block 0174, below the page");
		assertTrue(m_mmu.translateInstruction(virt(017500)).isValid(), "block 0175, the first valid one");
		assertTrue(m_mmu.translateInstruction(virt(017777)).isValid(), "the top of the page");
		assertEquals(017777L, m_mmu.translateInstruction(virt(017777)).address().val());
	}

	/**
	 * Correction 4. In the Pascal, kernel instruction PAR and PDR are both written into the
	 * <i>user</i> arrays, and the kernel-I PDR, supervisor-D PDR and supervisor-I PDR branches
	 * re-test the address range of the PAR above them, so they can never be reached.
	 */
	@Test
	void everyPageRegisterLandsInItsOwnModeAndSpace() {
		int[][] cases = {
			//-- {address, mode ordinal, space ordinal, isPar}
			{0177660, CpuMode.USER.ordinal(), AccessSpace.DATA.ordinal(), 1},
			{0177620, CpuMode.USER.ordinal(), AccessSpace.DATA.ordinal(), 0},
			{0177640, CpuMode.USER.ordinal(), AccessSpace.INSTRUCTION.ordinal(), 1},
			{0177600, CpuMode.USER.ordinal(), AccessSpace.INSTRUCTION.ordinal(), 0},
			{0172360, CpuMode.KERNEL.ordinal(), AccessSpace.DATA.ordinal(), 1},
			{0172320, CpuMode.KERNEL.ordinal(), AccessSpace.DATA.ordinal(), 0},
			{0172340, CpuMode.KERNEL.ordinal(), AccessSpace.INSTRUCTION.ordinal(), 1},
			{0172300, CpuMode.KERNEL.ordinal(), AccessSpace.INSTRUCTION.ordinal(), 0},
			{0172260, CpuMode.SUPERVISOR.ordinal(), AccessSpace.DATA.ordinal(), 1},
			{0172220, CpuMode.SUPERVISOR.ordinal(), AccessSpace.DATA.ordinal(), 0},
			{0172240, CpuMode.SUPERVISOR.ordinal(), AccessSpace.INSTRUCTION.ordinal(), 1},
			{0172200, CpuMode.SUPERVISOR.ordinal(), AccessSpace.INSTRUCTION.ordinal(), 0},
		};
		//-- Give every one of the 96 registers a value unique to it, then check each landed
		//-- where it belongs. A dispatch that writes to the wrong array shows up immediately.
		int v = 1;
		for(int[] c : cases) {
			for(int page = 0; page < 8; page++) {
				poke(c[0] + 2 * page, v++);
			}
		}
		v = 1;
		for(int[] c : cases) {
			CpuMode mode = CpuMode.values()[c[1]];
			AccessSpace space = AccessSpace.values()[c[2]];
			for(int page = 0; page < 8; page++) {
				int expected = v++;
				int actual = c[3] == 1 ? m_mmu.getPar(mode, space, page) : m_mmu.getPdr(mode, space, page);
				assertEquals(expected, actual, (c[3] == 1 ? "PAR" : "PDR") + " " + mode + " " + space + " page " + page);
			}
		}
	}

	@Test
	void mmr3SelectsWhichModesHaveASeparateDataSpace() {
		poke(0172516, 0b0000_0111);                         // all three modes get D space
		assertTrue(m_mmu.isDSpaceEnabled(CpuMode.USER));
		assertTrue(m_mmu.isDSpaceEnabled(CpuMode.SUPERVISOR));
		assertTrue(m_mmu.isDSpaceEnabled(CpuMode.KERNEL));

		poke(0172516, 0b0011_0000);
		assertFalse(m_mmu.isDSpaceEnabled(CpuMode.KERNEL));
		assertTrue(m_mmu.isMapping22Bit());
		assertTrue(m_mmu.isUnibusRelocation());
	}

	/** Without D space, a data access goes through the instruction map. */
	@Test
	void aModeWithoutDataSpaceUsesTheInstructionMap() {
		poke(0177572, 1);
		poke(0177776, 0);                                   // kernel
		poke(0172516, 0);                                   // no D space anywhere
		poke(0172340, 01000);                               // Kernel I PAR 0
		poke(0172300, pdrUp(0177));
		poke(0172360, 02000);                               // Kernel D PAR 0, should be ignored
		poke(0172320, pdrUp(0177));

		assertEquals(0100000L, m_mmu.translateData(virt(0)).address().val());

		poke(0172516, 0b100);                               // now kernel has D space
		assertEquals(0200000L, m_mmu.translateData(virt(0)).address().val());
	}

	@Test
	void thePswSelectsTheMode() {
		poke(0177776, 0);
		assertEquals(CpuMode.KERNEL, m_mmu.getCpuMode());
		poke(0177776, 0b11 << 14);
		assertEquals(CpuMode.USER, m_mmu.getCpuMode());
		poke(0177776, 0b01 << 14);
		assertEquals(CpuMode.SUPERVISOR, m_mmu.getCpuMode());
	}

	/**
	 * The MMU listens on the cell bus, which is why that bus lives in {@code pdp11-core}: this
	 * is business logic subscribing to memory cells, not a window redrawing itself.
	 */
	@Test
	void aDepositToThePswFromAnotherWindowUpdatesTheMmu() {
		MemoryCellGroup cpu = m_groups.addGroup(MemoryAddressType.PHYSICAL16, "CPU");
		MemoryCell psw = cpu.add(0177776);
		int[] notified = {0};
		m_mmu.addChangeListener(() -> notified[0]++);

		psw.setPdpValue(CellValue.of(0b11 << 14));
		m_groups.syncMemoryCells(psw);

		assertEquals(CpuMode.USER, m_mmu.getCpuMode());
		assertEquals(1, notified[0]);
	}

	@Test
	void theRegisterGroupMovesWithTheTargetMachine() {
		assertEquals(MemoryAddressType.PHYSICAL22, m_mmu.getPhysicalAddressType());
		m_groups.changeAddressWidth(MemoryAddressType.PHYSICAL18);
		assertEquals(MemoryAddressType.PHYSICAL18, m_mmu.getPhysicalAddressType());

		//-- The PSW is still findable, and translation now answers in 18 bits.
		assertSame(m_mmu.getPswCell(),
			m_mmu.getRegisterGroup().findByAddress(0777776L));
		poke(0177572, 0);
		assertEquals(MemoryAddressType.PHYSICAL18, m_mmu.translateData(virt(01000)).address().type());
	}
}
