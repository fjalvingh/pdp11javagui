package to.etc.pdp11.core.mem;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving a group's window over memory, which is what a memory view scrolling and a disassembler
 * following the PC both are.
 */
class ShiftRangeTest {
	private static MemoryCellGroups groups() {
		return new MemoryCellGroups();
	}

	private static MemoryCellGroup filled(MemoryCellGroups gs, long start, int count) {
		MemoryCellGroup g = gs.addGroup(MemoryAddressType.PHYSICAL16, "test");
		g.add(start, count);
		for(int i = 0; i < count; i++) {
			g.cell(i).setPdpValue(CellValue.of(0100 + i));
		}
		return g;
	}

	@Test
	void aShiftedRangeIsConsecutiveWordsFromTheNewStart() {
		MemoryCellGroup g = filled(groups(), 01000, 4);
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 02000), 3, false);

		assertEquals(3, g.size());
		assertEquals(02000, g.cell(0).getAddr().val());
		assertEquals(02002, g.cell(1).getAddr().val());
		assertEquals(02004, g.cell(2).getAddr().val());
		//-- Nothing overlapped, so nothing is known - which is what makes the caller examine.
		assertFalse(g.cell(0).getPdpValue().isKnown());
	}

	@Test
	void overlappingCellsKeepWhatTheMachineSaid() {
		MemoryCellGroup g = filled(groups(), 01000, 4);            // 1000, 1002, 1004, 1006
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 01004), 4, true);

		assertEquals(01004, g.cell(0).getAddr().val());
		//-- 1004 and 1006 were in the old range and keep their values...
		assertEquals(0102, g.cell(0).getPdpValue().word());
		assertEquals(0103, g.cell(1).getPdpValue().word());
		//-- ...1010 and 1012 are new and have to be read.
		assertFalse(g.cell(2).getPdpValue().isKnown());
		assertFalse(g.cell(3).getPdpValue().isKnown());
	}

	@Test
	void withoutOptimizeEverythingIsForgotten() {
		MemoryCellGroup g = filled(groups(), 01000, 4);
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 01000), 4, false);
		for(MemoryCell mc : g.getCells()) {
			assertFalse(mc.getPdpValue().isKnown(), "a reload that trusts nothing keeps nothing");
		}
	}

	@Test
	void aNegativeCountKeepsTheSizeItHas() {
		MemoryCellGroup g = filled(groups(), 01000, 5);
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 04000), -1, true);
		assertEquals(5, g.size(), "the Pascal's newsize < 0");
		assertEquals(04000, g.cell(0).getAddr().val());
	}

	@Test
	void growingAndShrinkingBothWork() {
		MemoryCellGroups gs = groups();
		MemoryCellGroup g = filled(gs, 01000, 2);
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 01000), 6, true);
		assertEquals(6, g.size());
		assertEquals(0100, g.cell(0).getPdpValue().word(), "the two that were there are still there");

		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 01000), 1, true);
		assertEquals(1, g.size());
		assertEquals(01000, g.cell(0).getAddr().val());
	}

	@Test
	void theRangeAndTheGroupsIndexBothFollow() {
		MemoryCellGroups gs = groups();
		MemoryCellGroup g = filled(gs, 01000, 4);
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 03000), 2, true);

		assertEquals(03000, g.getRange().lo());
		assertEquals(03002, g.getRange().hi());
		//-- The index the whole propagation bus runs on must not still be pointing at 1000.
		assertTrue(gs.cellsAt(Address.of(MemoryAddressType.PHYSICAL16, 01000)).isEmpty(),
			"the old addresses are gone from the index");
		assertEquals(1, gs.cellsAt(Address.of(MemoryAddressType.PHYSICAL16, 03000)).size());
	}

	@Test
	void shiftingToAnotherWidthCarriesIopageValuesAcrossAndNothingElse() {
		MemoryCellGroups gs = groups();
		MemoryCellGroup g = gs.addGroup(MemoryAddressType.PHYSICAL16, "iopage");
		g.add(0177570);                                            // the switch register
		g.cell(0).setPdpValue(CellValue.of(0123));

		//-- 16-bit 0177570 and 22-bit 017777570 are the same register. Comparing the raw values,
		//-- as CellIndexByAddr does, they are not even close.
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL22, 017777570), 1, true);
		assertEquals(MemoryAddressType.PHYSICAL22, g.getType());
		assertEquals(0123, g.cell(0).getPdpValue().word());

		//-- And a plain memory address is width-invariant, so it does *not* move with the I/O page.
		MemoryCellGroup m = gs.addGroup(MemoryAddressType.PHYSICAL16, "memory");
		m.add(01000);
		m.cell(0).setPdpValue(CellValue.of(0456));
		m.shiftRange(Address.of(MemoryAddressType.PHYSICAL22, 01000), 1, true);
		assertEquals(0456, m.cell(0).getPdpValue().word());
	}
	// ---------------------------------------------------------------------------------------
	// Shifting under a job that is reading the group
	// ---------------------------------------------------------------------------------------

	/**
	 * A long console job iterates the cells on the command thread while the event thread can
	 * shift the very same group - Show, {@code <}, {@code >}, a connection event.
	 *
	 * <p>{@link MemoryCellGroup#getCells()} used to hand out an unmodifiable <b>view</b> over a
	 * plain ArrayList, so iterating one across a shift threw ConcurrentModificationException,
	 * which reached the user as "Examining memory failed". It hands out a copy now
	 * (FABLE-ISSUES #64), and the job's iteration is safe without the caller having to know to
	 * copy it - but surviving the shift is still not the same as still being relevant, which is
	 * what {@link MemoryCellGroup#holdsExactly} is asked afterwards.</p>
	 */
	@Test
	void iteratingWhatGetCellsHandedOutSurvivesTheRangeMoving() {
		MemoryCellGroup g = filled(groups(), 01000, 4);
		java.util.Iterator<MemoryCell> handedOut = g.getCells().iterator();
		handedOut.next();
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 02000), 4, false);
		assertEquals(01002, handedOut.next().getAddr().val(), "the old range's second word, not a throw");

		java.util.List<MemoryCell> snapshot = g.getCells();
		g.shiftRange(Address.of(MemoryAddressType.PHYSICAL16, 03000), 4, false);
		for(MemoryCell mc : snapshot) {
			assertEquals(02000, mc.getAddr().val() & ~07L, "the cells it holds are the old range's");
		}
		assertFalse(g.holdsExactly(snapshot), "the group is showing a different range now");
	}

	@Test
	void aGroupHoldsExactlyTheCellsNothingHasTouched() {
		MemoryCellGroup g = filled(groups(), 01000, 4);
		java.util.List<MemoryCell> snapshot = java.util.List.copyOf(g.getCells());
		assertTrue(g.holdsExactly(snapshot));

		g.add(01010);
		assertFalse(g.holdsExactly(snapshot), "a cell was added");

		MemoryCellGroup other = filled(groups(), 01000, 4);
		assertFalse(other.holdsExactly(snapshot),
			"same addresses, same values, different cells - identity is what a grid holds");
	}

}
