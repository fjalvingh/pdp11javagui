package to.etc.pdp11.core.mem;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The propagation bus, and specifically the three guards PLAN.md §2 says must be preserved
 * exactly or propagation storms.
 */
class MemoryCellPropagationTest {
	private static final long PSW = 0177776;

	private final MemoryCellGroups m_groups = new MemoryCellGroups();

	private MemoryCellGroup group(String name) {
		return m_groups.addGroup(MemoryAddressType.PHYSICAL16, name);
	}

	/** Records what a window would have been told to redraw. */
	private static List<MemoryCell> recordOn(MemoryCellGroup g) {
		List<MemoryCell> seen = new ArrayList<>();
		g.addListener((group, cell) -> seen.add(cell));
		return seen;
	}

	@Test
	void aValueReachesEveryOtherCellAtTheSameAddress() {
		MemoryCell a = group("A").add(PSW);
		MemoryCellGroup gb = group("B");
		MemoryCell b = gb.add(PSW);
		List<MemoryCell> seen = recordOn(gb);

		a.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(a);

		assertEquals(CellValue.of(0340), b.getPdpValue());
		assertEquals(List.of(b), seen);
	}

	/** Guard 1: a group the user edits in opts out of being overwritten. */
	@Test
	void aGroupWithPdpOverwritesEditOffIsNotTouched() {
		MemoryCell a = group("A").add(PSW);
		MemoryCellGroup gb = group("B");
		gb.setPdpOverwritesEdit(false);
		MemoryCell b = gb.add(PSW);
		List<MemoryCell> seen = recordOn(gb);

		a.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(a);

		assertEquals(CellValue.UNKNOWN, b.getPdpValue());
		assertTrue(seen.isEmpty(), "an opted-out group must not even be notified");
	}

	/** Guard 2: the cell that changed is not told about its own change. */
	@Test
	void theSourceCellIsNotNotified() {
		MemoryCellGroup ga = group("A");
		MemoryCell a = ga.add(PSW);
		List<MemoryCell> seen = recordOn(ga);

		a.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(a);

		assertTrue(seen.isEmpty());
	}

	/** Guard 3: an equal value stops propagation dead. This is what terminates it. */
	@Test
	void anUnchangedValueFiresNothing() {
		MemoryCell a = group("A").add(PSW);
		MemoryCellGroup gb = group("B");
		MemoryCell b = gb.add(PSW);
		b.setPdpValue(CellValue.of(0340));
		List<MemoryCell> seen = recordOn(gb);

		a.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(a);

		assertTrue(seen.isEmpty(), "the value already agreed, so nothing changed");
	}

	/**
	 * A listener that writes back the same value settles after one round - the ordinary case,
	 * and the reason the Pascal survives without a recursion guard.
	 */
	@Test
	void aListenerWritingBackTheSameValueTerminates() {
		MemoryCell a = group("A").add(PSW);
		MemoryCellGroup gb = group("B");
		MemoryCell b = gb.add(PSW);
		gb.addListener((g, c) -> m_groups.syncMemoryCells(c));

		a.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(a);

		assertEquals(CellValue.of(0340), b.getPdpValue());
	}

	/**
	 * A listener writing back a <i>different</i> value is a bug, and in the Pascal it recurses
	 * until the stack runs out. The depth guard turns that into a message naming the address.
	 */
	@Test
	void aListenerWritingBackADifferentValueHitsTheDepthGuard() {
		MemoryCellGroup ga = group("A");
		MemoryCell a = ga.add(PSW);
		MemoryCellGroup gb = group("B");
		gb.add(PSW);
		//-- Both sides bump the value and re-announce it, so neither ever agrees with the
		//-- other and the equality short-circuit never fires. In the Pascal this runs until
		//-- the stack is gone.
		MemoryCellListener bumper = (g, c) -> {
			c.setPdpValue(CellValue.of(c.getPdpValue().wordOr(0) + 1));
			m_groups.syncMemoryCells(c);
		};
		ga.addListener(bumper);
		gb.addListener(bumper);

		a.setPdpValue(CellValue.of(0340));
		IllegalStateException x = assertThrows(IllegalStateException.class,
			() -> m_groups.syncMemoryCells(a));
		assertTrue(x.getMessage().contains("177776"), "the message should name the address: " + x.getMessage());
	}

	@Test
	void onlyThePdpValuePropagatesNeverTheEdit() {
		MemoryCell a = group("A").add(PSW);
		MemoryCell b = group("B").add(PSW);
		b.setEditValue(CellValue.of(01234));

		a.setEditValue(CellValue.of(07777));
		a.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(a);

		assertEquals(CellValue.of(0340), b.getPdpValue());
		assertEquals(CellValue.of(01234), b.getEditValue(), "an unsaved edit must survive");
	}

	/**
	 * The MMU builds its group at 22 bits while machine descriptions load at 16, so the two
	 * must recognise each other's addresses. The Pascal compares raw values and does not.
	 */
	@Test
	void cellsAtDifferentWidthsAreTheSameLocation() {
		MemoryCell wide = m_groups.addGroup(MemoryAddressType.PHYSICAL22, "MMU")
			.add(Address.of(MemoryAddressType.PHYSICAL16, PSW).withWidth(MemoryAddressType.PHYSICAL22));
		MemoryCell narrow = group("CPU").add(PSW);

		wide.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(wide);

		assertEquals(CellValue.of(0340), narrow.getPdpValue());
		assertEquals(2, m_groups.cellsAt(narrow.getAddr()).size());
	}

	@Test
	void aVirtualAddressIsNotThePhysicalOneWithTheSameNumber() {
		MemoryCell physical = group("CPU").add(PSW);
		MemoryCell virtual = m_groups.addGroup(MemoryAddressType.VIRTUAL, "Listing")
			.add(Address.of(MemoryAddressType.VIRTUAL, PSW));

		virtual.setPdpValue(CellValue.of(0340));
		m_groups.syncMemoryCells(virtual);

		assertEquals(CellValue.UNKNOWN, physical.getPdpValue());
	}

	/**
	 * Several names for one register at one address is normal - the RX211 declares six. They
	 * are separate cells and they must all show the same word.
	 */
	@Test
	void severalCellsAtOneAddressInOneGroupAllTrack() {
		MemoryCellGroup g = group("RX211");
		MemoryCell ta = g.add(0177172);
		MemoryCell sa = g.add(0177172);
		MemoryCell db = g.add(0177172);
		ta.setName("RX2TA");
		sa.setName("RX2SA");
		db.setName("RX2DB");

		ta.setPdpValue(CellValue.of(042));
		m_groups.syncMemoryCells(ta);

		assertEquals(CellValue.of(042), sa.getPdpValue());
		assertEquals(CellValue.of(042), db.getPdpValue());
		//-- Lookup answers with the first one declared, as CellIndexByAddr does.
		assertSame(ta, g.findByAddress(0177172));
	}

	@Test
	void changingTheAddressWidthMovesEveryCellAndKeepsThemFindable() {
		MemoryCellGroup g = group("CPU");
		MemoryCell psw = g.add(PSW);
		MemoryCell low = g.add(01000);

		m_groups.changeAddressWidth(MemoryAddressType.PHYSICAL22);

		assertEquals(MemoryAddressType.PHYSICAL22, g.getType());
		assertEquals(017777776L, psw.getAddr().val());
		assertEquals(01000L, low.getAddr().val());
		assertSame(psw, g.findByAddress(017777776L));
		assertNotNull(m_groups.cellsAt(psw.getAddr()));
		assertEquals(1, m_groups.cellsAt(psw.getAddr()).size());
	}

	@Test
	void aWidthChangeThatCannotFitLeavesEverythingAlone() {
		MemoryCellGroup g = m_groups.addGroup(MemoryAddressType.PHYSICAL22, "Big");
		g.add(0400000);                                     // real memory on a 22-bit machine

		assertThrows(IllegalArgumentException.class,
			() -> m_groups.changeAddressWidth(MemoryAddressType.PHYSICAL16));
		assertEquals(MemoryAddressType.PHYSICAL22, g.getType(), "nothing should have moved");
		assertEquals(0400000L, g.cell(0).getAddr().val());
	}

	@Test
	void aDumpBorrowsARegisterNameFromTheMachineDescription() {
		MemoryCell named = group("CPU").add(PSW);
		named.setName("PSW");
		MemoryCell anonymous = group("Dump").add(PSW);

		assertSame(named, m_groups.findNamedCellAt(anonymous));
		assertNull(m_groups.findNamedCellAt(named), "a cell does not name itself");
	}

	@Test
	void removingCellsAndGroupsKeepsTheIndexHonest() {
		MemoryCellGroup ga = group("A");
		MemoryCell a = ga.add(PSW);
		MemoryCellGroup gb = group("B");
		gb.add(PSW);
		assertEquals(2, m_groups.cellsAt(a.getAddr()).size());

		ga.remove(a);
		assertEquals(1, m_groups.cellsAt(Address.of(MemoryAddressType.PHYSICAL16, PSW)).size());

		m_groups.removeGroup(gb);
		assertTrue(m_groups.cellsAt(Address.of(MemoryAddressType.PHYSICAL16, PSW)).isEmpty());
	}

	@Test
	void invalidateForgetsMachineValuesButKeepsEdits() {
		MemoryCellGroup g = group("A");
		MemoryCell c = g.add(PSW);
		c.setPdpValue(CellValue.of(0340));
		c.setEditValue(CellValue.of(01234));

		g.invalidate();

		assertEquals(CellValue.UNKNOWN, c.getPdpValue());
		assertEquals(CellValue.of(01234), c.getEditValue());
	}
}
