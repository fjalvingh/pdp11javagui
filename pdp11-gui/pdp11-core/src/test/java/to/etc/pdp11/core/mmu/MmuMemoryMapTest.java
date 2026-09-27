package to.etc.pdp11.core.mmu;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address map the MMU window shows.
 *
 * <p>Every case here is a map somebody has to be able to read off a screen and believe: an
 * unmapped machine, one page moved somewhere else, two pages that run together, a page too short
 * for the addresses in it. The Pascal computes all of this inside a grid-filling procedure, so
 * none of it was ever checked except by looking.</p>
 */
class MmuMemoryMapTest {
	/** An MMU whose registers can be set the way a machine's would be. */
	private static final class Rig {
		final MemoryCellGroups groups = new MemoryCellGroups();

		final Pdp11Mmu mmu = new Pdp11Mmu(groups);

		/**
		 * Set a register by the name {@link Pdp11Mmu} gave it, and let the MMU react.
		 *
		 * <p>Setting a cell's value notifies nobody by itself, and propagation excludes the cell
		 * it started from - so a console examining the MMU's <i>own</i> register group never
		 * reaches the MMU's listener. {@link Pdp11Mmu#evalAll()} is what closes that, and it is
		 * what the window's Refresh does after examining, exactly as {@code ExamineMMU}
		 * ({@code Pdp11MmuU.pas:365-370}) does.</p>
		 */
		void set(String name, int value) {
			MemoryCellGroup g = mmu.getRegisterGroup();
			for(MemoryCell mc : g.getCells()) {
				if(name.equals(mc.getName())) {
					mc.setPdpValue(CellValue.of(value));
					groups.syncMemoryCells(mc);
					mmu.evalAll();
					return;
				}
			}
			throw new IllegalArgumentException("No MMU register called " + name + " in " + g.getCells().size());
		}

		/** Relocation on, and this mode's pages addressed through I space only. */
		void enableRelocation() {
			set("MMR0", 1);
		}
	}

	private static MmuMemoryMap map(Rig rig, CpuMode mode, AccessSpace space) {
		return MmuMemoryMap.of(rig.mmu, mode, space);
	}

	private static long phys(MmuMemoryMap.Block b) {
		return b.physicalStart().val();
	}

	// ---------------------------------------------------------------------------------------
	// Relocation off
	// ---------------------------------------------------------------------------------------

	@Test
	void withRelocationOffThereAreTwoBlocksAndTheSecondIsTheIoPage() {
		//-- The map a machine shows before anybody has set the MMU up: virtual is physical, and
		//-- the top 8 KB is the I/O page, which is never relocated and is not at the same
		//-- physical address as its virtual one.
		Rig rig = new Rig();
		List<MmuMemoryMap.Block> blocks = map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks();

		assertEquals(2, blocks.size(), blocks.toString());
		assertEquals(0, blocks.get(0).virtualStart());
		assertEquals(0157776, blocks.get(0).virtualEnd());
		assertEquals(0, phys(blocks.get(0)));
		assertTrue(blocks.get(0).isMapped());
		assertFalse(blocks.get(0).ioPage());

		assertEquals(0160000, blocks.get(1).virtualStart());
		assertEquals(0177776, blocks.get(1).virtualEnd());
		assertTrue(blocks.get(1).ioPage());
		//-- The I/O page at 22 bits, which is where a 16-bit 0160000 lives.
		assertEquals(017760000L, phys(blocks.get(1)));
		assertEquals(017777776L, blocks.get(1).physicalEnd().val());
	}

	@Test
	void everyBlockIsNumberedFromOneAndTheyCoverAllOfVirtualSpace() {
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("KIPAR0", 0);
		rig.set("KIPDR0", 077406);                          // a full-length readable page
		rig.set("KIPAR3", 01000);
		rig.set("KIPDR3", 077406);

		List<MmuMemoryMap.Block> blocks = map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks();
		long covered = 0;
		long expectedStart = 0;
		for(int i = 0; i < blocks.size(); i++) {
			MmuMemoryMap.Block b = blocks.get(i);
			assertEquals(i + 1, b.number(), "numbered in order");
			assertEquals(expectedStart, b.virtualStart(), "no gap before block " + b.number());
			covered += b.byteCount();
			expectedStart = b.virtualEnd() + 2;
		}
		assertEquals(MmuMemoryMap.VIRTUAL_SIZE, covered, "all 64 KB accounted for");
	}

	// ---------------------------------------------------------------------------------------
	// Relocation on
	// ---------------------------------------------------------------------------------------

	@Test
	void aRelocatedPageShowsWhereItActuallyIs() {
		Rig rig = new Rig();
		rig.enableRelocation();
		//-- Kernel I page 0 at physical 0400000 (PAR is in 64-byte units), full length.
		rig.set("KIPAR0", 04000);
		rig.set("KIPDR0", 077406);

		List<MmuMemoryMap.Block> blocks = map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks();
		MmuMemoryMap.Block first = blocks.get(0);
		assertEquals(0, first.virtualStart());
		assertEquals(020000 - 2, first.virtualEnd(), "one 8 KB page");
		assertEquals(0400000L, phys(first));
		assertEquals(0400000L + 020000 - 2, first.physicalEnd().val());
		assertEquals(020000, first.byteCount());
	}

	@Test
	void twoPagesThatFollowEachOtherAreOneBlock() {
		//-- The whole point of showing blocks rather than pages: a kernel that maps its first
		//-- two pages consecutively has one 16 KB region, and reading eight rows to work that
		//-- out is what the block-finding is for.
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("KIPAR0", 04000);
		rig.set("KIPDR0", 077406);
		//-- 8 KB further on: a PAR counts 64-byte blocks, and 8192/64 is 0200 of them.
		rig.set("KIPAR1", 04000 + 0200);
		rig.set("KIPDR1", 077406);

		MmuMemoryMap.Block first = map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks().get(0);
		assertEquals(0, first.virtualStart());
		assertEquals(040000 - 2, first.virtualEnd(), "both pages, as one block");
		assertEquals(0400000L, phys(first));
	}

	@Test
	void aPageMappedSomewhereElseBreaksTheBlock() {
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("KIPAR0", 04000);
		rig.set("KIPDR0", 077406);
		rig.set("KIPAR1", 010000);                          // not where page 0 ended
		rig.set("KIPDR1", 077406);

		List<MmuMemoryMap.Block> blocks = map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks();
		assertEquals(020000 - 2, blocks.get(0).virtualEnd());
		assertEquals(020000, blocks.get(1).virtualStart());
		assertEquals(01000000L, phys(blocks.get(1)));
	}

	@Test
	void aShortPageIsMappedAsFarAsItGoesAndAPageLengthErrorAfterThat() {
		//-- PLF = 0 is a legal one-block page: 64 bytes, and everything above them in that page
		//-- traps. The Pascal rejected all of it, having its length check off by one block.
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("KIPAR0", 04000);
		rig.set("KIPDR0", 06);                              // PLF 0, read/write

		List<MmuMemoryMap.Block> blocks = map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks();
		assertEquals(0, blocks.get(0).virtualStart());
		assertEquals(64 - 2, blocks.get(0).virtualEnd(), "one 64-byte block is mapped");
		assertEquals(0400000L, phys(blocks.get(0)));

		MmuMemoryMap.Block bad = blocks.get(1);
		assertFalse(bad.isMapped());
		assertNull(bad.physicalStart());
		assertEquals(TranslationResult.Failure.PAGE_LENGTH_ERROR, bad.failure());
		assertEquals("page length error", bad.physicalRange());
		assertEquals(64, bad.virtualStart());
	}

	@Test
	void aDownwardExpandingPageIsMappedAtItsTopEnd() {
		//-- A stack page: the mapped part is at the high end of the page, and the low end is the
		//-- part that traps. The Pascal threw an exception rather than showing this at all.
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("KIPAR0", 04000);
		//-- PLF 0176 (bits 14..8), expand down (bit 3), read/write: the last two blocks.
		rig.set("KIPDR0", (0176 << 8) | 010 | 06);

		List<MmuMemoryMap.Block> blocks = map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks();
		assertFalse(blocks.get(0).isMapped(), "the bottom of a stack page is not there");
		assertEquals(TranslationResult.Failure.PAGE_LENGTH_ERROR, blocks.get(0).failure());
		assertTrue(blocks.get(1).isMapped());
		assertEquals(0176 * 64, blocks.get(1).virtualStart());
		assertEquals(020000 - 2, blocks.get(1).virtualEnd());
	}

	// ---------------------------------------------------------------------------------------
	// D space
	// ---------------------------------------------------------------------------------------

	@Test
	void withoutDSpaceDataGoesThroughTheInstructionMapAndTheMapSaysSo() {
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("KIPAR0", 04000);
		rig.set("KIPDR0", 077406);
		rig.set("KDPAR0", 010000);                          // set, and deliberately ignored
		rig.set("KDPDR0", 077406);

		MmuMemoryMap data = map(rig, CpuMode.KERNEL, AccessSpace.DATA);
		assertTrue(data.isUsingInstructionMapForData());
		assertEquals(AccessSpace.INSTRUCTION, data.effectiveSpace());
		assertEquals(0400000L, phys(data.blocks().get(0)), "the I map, not the D map");
	}

	@Test
	void withDSpaceOnTheTwoMapsDiffer() {
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("MMR3", 07);                                // D space in all three modes
		rig.set("KIPAR0", 04000);
		rig.set("KIPDR0", 077406);
		rig.set("KDPAR0", 010000);
		rig.set("KDPDR0", 077406);

		MmuMemoryMap data = map(rig, CpuMode.KERNEL, AccessSpace.DATA);
		assertFalse(data.isUsingInstructionMapForData());
		assertEquals(01000000L, phys(data.blocks().get(0)));
		assertEquals(0400000L, phys(map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks().get(0)));
	}

	@Test
	void eachModeHasItsOwnMap() {
		Rig rig = new Rig();
		rig.enableRelocation();
		rig.set("KIPAR0", 04000);
		rig.set("KIPDR0", 077406);
		rig.set("UIPAR0", 020000);
		rig.set("UIPDR0", 077406);

		assertEquals(0400000L, phys(map(rig, CpuMode.KERNEL, AccessSpace.INSTRUCTION).blocks().get(0)));
		assertEquals(02000000L, phys(map(rig, CpuMode.USER, AccessSpace.INSTRUCTION).blocks().get(0)));
		//-- Supervisor was never set up, so its page 0 is at physical 0.
		assertEquals(0, phys(map(rig, CpuMode.SUPERVISOR, AccessSpace.INSTRUCTION).blocks().get(0)));
	}

	@Test
	void theMapKnowsWhichModeAndSpaceItIs() {
		Rig rig = new Rig();
		MmuMemoryMap m = map(rig, CpuMode.SUPERVISOR, AccessSpace.DATA);
		assertEquals(CpuMode.SUPERVISOR, m.mode());
		assertEquals(AccessSpace.DATA, m.space());
	}
}
