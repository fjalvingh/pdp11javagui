package to.etc.pdp11.common.mem;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared memory: one word per address below the I/O page, shared by every window. PLAN.md
 * §1, "Shared memory".
 */
class SharedMemoryTest {
	private final MemoryCellGroups m_groups = new MemoryCellGroups();

	private final SharedMemory m_image = m_groups.getSharedMemory();

	private MemoryCellGroup group(String name) {
		return m_groups.addGroup(MemoryAddressType.PHYSICAL22, name);
	}

	// ---------------------------------------------------------------------------------------
	// One word, many windows
	// ---------------------------------------------------------------------------------------

	/** The point of the whole thing: what the Loader read is what the Memory window shows. */
	@Test
	void anEditInOneWindowIsTheEditInEveryWindow() {
		MemoryCell loaded = group("Loader").add(01000);
		MemoryCell shown = group("Memory").add(01000);

		loaded.setEditValue(CellValue.of(012737));

		assertEquals(CellValue.of(012737), shown.getEditValue());
		assertTrue(shown.isEdited(), "nothing has been deposited, so it is pending everywhere");
		assertEquals(1, m_image.getPendingCount());
	}

	/** And the Disassembler, which works in virtual addresses, sees it too with the MMU off. */
	@Test
	void aVirtualViewSeesThePhysicalWordWithRelocationOff() {
		MemoryCell loaded = group("Loader").add(01000);
		MemoryCell disassembly = m_groups.addGroup(MemoryAddressType.VIRTUAL, "Disassembly")
			.add(Address.of(MemoryAddressType.VIRTUAL, 01000));

		loaded.setEditValue(CellValue.of(000240));

		assertTrue(disassembly.isShared());
		assertEquals(CellValue.of(000240), disassembly.getEditValue());
	}

	/** A 16-bit and a 22-bit window over location 1000 are one word. */
	@Test
	void widthDoesNotMakeASecondWord() {
		MemoryCell narrow = m_groups.addGroup(MemoryAddressType.PHYSICAL16, "16").add(01000);
		MemoryCell wide = group("22").add(01000);

		narrow.setPdpValue(CellValue.of(0777));

		assertEquals(CellValue.of(0777), wide.getPdpValue());
		assertEquals(Address.of(MemoryAddressType.PHYSICAL22, 01000), wide.getPhysical());
	}

	@Test
	void theIoPageIsNotInTheImage() {
		MemoryCell psw = m_groups.addGroup(MemoryAddressType.PHYSICAL16, "CPU").add(0177776);
		MemoryCell other = m_groups.addGroup(MemoryAddressType.PHYSICAL16, "Bits").add(0177776);

		assertFalse(psw.isShared());
		assertNull(psw.getPhysical());
		psw.setEditValue(CellValue.of(0340));
		assertFalse(other.isEdited(), "a device register's edit belongs to the window it was typed in");
		assertEquals(0, m_image.size());
	}

	@Test
	void anOddAddressIsAByteAndNotAWord() {
		MemoryCell b = group("Bytes").add(01001);
		assertFalse(b.isShared());
	}

	// ---------------------------------------------------------------------------------------
	// Machine value and edit
	// ---------------------------------------------------------------------------------------

	/** An examine sets what the machine said and never what should be there. */
	@Test
	void anExamineNeverTouchesAnEdit() {
		MemoryCell loaded = group("Loader").add(01000);
		MemoryCell shown = group("Memory").add(01000);
		loaded.setEditValue(CellValue.of(5));

		shown.setPdpValue(CellValue.of(7));
		m_groups.syncMemoryCells(shown);

		assertEquals(CellValue.of(5), loaded.getEditValue());
		assertEquals(CellValue.of(7), loaded.getPdpValue());
		assertTrue(loaded.isEdited(), "the machine disagrees with the file, which is what a verify shows");
	}

	@Test
	void anEditTheMachineAlreadyHoldsIsNotPending() {
		MemoryCell c = group("Loader").add(01000);
		c.setEditValue(CellValue.of(5));
		c.setPdpValue(CellValue.of(5));

		assertFalse(c.isEdited());
		assertEquals(0, m_image.getPendingCount());
	}

	@Test
	void withNoEditTheMachineValueIsWhatIsShown() {
		MemoryCell c = group("Memory").add(01000);
		c.setPdpValue(CellValue.of(0123));
		assertEquals(CellValue.of(0123), c.getEditValue());
		assertFalse(c.isEdited());
	}

	@Test
	void aDepositLeavesNoEditAndNoOwner() {
		MemoryCellGroup loader = group("Loader");
		MemoryCell c = loader.add(01000);
		c.setEditValue(CellValue.of(5));

		c.setDeposited();

		assertEquals(CellValue.of(5), c.getPdpValue());
		assertFalse(c.isEdited());
		assertNull(c.getEditOwner());
		assertEquals(0, loader.getPendingEditCount());
	}

	@Test
	void discardingGoesBackToTheMachineValue() {
		MemoryCell c = group("Memory").add(01000);
		c.setPdpValue(CellValue.of(1));
		c.setEditValue(CellValue.of(2));

		c.discardEdit();

		assertEquals(CellValue.of(1), c.getEditValue());
		assertFalse(c.isEdited());
	}

	// ---------------------------------------------------------------------------------------
	// Staleness
	// ---------------------------------------------------------------------------------------

	@Test
	void aRunMakesEveryValueStaleAndKeepsEveryEdit() {
		MemoryCell read = group("Memory").add(01000);
		MemoryCell typed = group("Loader").add(01002);
		read.setPdpValue(CellValue.of(1));
		typed.setEditValue(CellValue.of(2));

		m_image.markRun();

		assertTrue(read.isStale());
		assertFalse(read.isMachineValueCurrent(), "an examine of what is not unknown has to reread this");
		assertEquals(CellValue.of(1), read.getPdpValue(), "still shown, as stale");
		assertTrue(typed.isEdited());
		assertEquals(CellValue.of(2), typed.getEditValue());

		read.setPdpValue(CellValue.of(3));
		assertFalse(read.isStale(), "read again after the run");
	}

	/** The program may have written that word since; only a read can say it has not. */
	@Test
	void anEditEqualToAStaleValueIsPendingAgain() {
		MemoryCell c = group("Loader").add(01000);
		c.setEditValue(CellValue.of(5));
		c.setPdpValue(CellValue.of(5));
		assertFalse(c.isEdited());

		m_image.markRun();

		assertTrue(c.isEdited());
	}

	@Test
	void aDeviceRegisterIsNeverStale() {
		MemoryCell psw = m_groups.addGroup(MemoryAddressType.PHYSICAL16, "CPU").add(0177776);
		psw.setPdpValue(CellValue.of(0340));
		m_image.markRun();
		assertFalse(psw.isStale());
	}

	// ---------------------------------------------------------------------------------------
	// Who owns an edit
	// ---------------------------------------------------------------------------------------

	@Test
	void theLastWriterOwnsTheWord() {
		MemoryCellGroup loader = group("Loader");
		MemoryCellGroup memory = group("Memory");
		MemoryCell a = loader.add(01000);
		MemoryCell b = memory.add(01000);

		a.setEditValue(CellValue.of(1));
		assertSame(loader, b.getEditOwner());
		b.setEditValue(CellValue.of(2));
		assertSame(memory, a.getEditOwner());

		loader.discardOwnedEdits();
		assertEquals(CellValue.of(2), a.getEditValue(), "the Memory window's edit is not the Loader's to undo");
	}

	/** A window copying back a value it was shown does not take the word over. */
	@Test
	void writingTheSameEditDoesNotChangeTheOwner() {
		MemoryCellGroup loader = group("Loader");
		MemoryCellGroup bits = group("Bitfields");
		loader.add(01000).setEditValue(CellValue.of(1));
		MemoryCell b = bits.add(01000);

		b.setEditValue(b.getEditValue());

		assertSame(loader, b.getEditOwner());
	}

	/** The Assembler assembling again drops what the last program put where this one does not. */
	@Test
	void discardingOwnedEditsReachesWordsTheGroupNoLongerShows() {
		MemoryCellGroup asm = group("Assembler");
		asm.add(01000, 4);
		for(MemoryCell mc : asm.getCells()) {
			mc.setEditValue(CellValue.of(0240));
		}
		asm.shiftRange(Address.of(MemoryAddressType.PHYSICAL22, 01000), 2, true);
		assertEquals(4, asm.getPendingEditCount(), "the edits are the image's, not the cells'");

		asm.discardOwnedEdits();

		assertEquals(0, m_image.getPendingCount());
	}

	@Test
	void closingAWindowKeepsItsEditsAsNobodys() {
		MemoryCellGroup loader = group("Loader");
		loader.add(01000).setEditValue(CellValue.of(1));
		MemoryCell shown = group("Memory").add(01000);

		m_groups.removeGroup(loader);

		assertTrue(shown.isEdited());
		assertNull(shown.getEditOwner());
		assertEquals("", m_image.getPending().get(0).owner());
	}

	@Test
	void thePendingListIsInAddressOrderAndSaysWhoseEachIs() {
		MemoryCellGroup loader = group("Loader");
		MemoryCellGroup memory = group("Memory");
		loader.add(02000).setEditValue(CellValue.of(2));
		memory.add(01000).setEditValue(CellValue.of(1));
		MemoryCell same = memory.add(01002);
		same.setPdpValue(CellValue.of(3));
		same.setEditValue(CellValue.of(3));                    // not pending: the machine has it

		List<SharedMemory.PendingWord> pending = m_image.getPending();

		assertEquals(2, pending.size());
		assertEquals(01000, pending.get(0).address().val());
		assertEquals("Memory", pending.get(0).owner());
		assertEquals(02000, pending.get(1).address().val());
		assertEquals("Loader", pending.get(1).owner());
	}

	// ---------------------------------------------------------------------------------------
	// Forgetting
	// ---------------------------------------------------------------------------------------

	@Test
	void forgettingMachineValuesKeepsEdits() {
		MemoryCell read = group("Memory").add(01000);
		MemoryCell typed = group("Loader").add(01002);
		read.setPdpValue(CellValue.of(1));
		typed.setEditValue(CellValue.of(2));

		m_image.forgetMachineValues();

		assertFalse(read.getPdpValue().isKnown());
		assertEquals(CellValue.of(2), typed.getEditValue());
	}

	@Test
	void forgettingEverythingIsACleanSlate() {
		MemoryCell read = group("Memory").add(01000);
		MemoryCell typed = group("Loader").add(01002);
		read.setPdpValue(CellValue.of(1));
		typed.setEditValue(CellValue.of(2));

		m_image.forgetAll();

		assertFalse(read.getEditValue().isKnown());
		assertFalse(typed.getEditValue().isKnown());
		assertEquals(0, m_image.getPendingCount());
	}

	// ---------------------------------------------------------------------------------------
	// Telling the other windows
	// ---------------------------------------------------------------------------------------

	@Test
	void anotherWindowOverTheWordIsToldOnce() {
		MemoryCell a = group("Memory").add(01000);
		MemoryCellGroup gb = group("Disassembly");
		MemoryCell b = gb.add(01000);
		List<MemoryCell> seen = new ArrayList<>();
		gb.addListener((g, c) -> seen.add(c));

		a.setPdpValue(CellValue.of(1));
		m_groups.syncMemoryCells(a);
		m_groups.syncMemoryCells(a);

		assertEquals(List.of(b), seen, "the second announcement changed nothing");
	}

	/**
	 * Two windows over one word that each re-announce what they are told. With values to
	 * compare that ends because they agree; over a shared word there is nothing to compare, and
	 * it ends because the second has already seen the version.
	 */
	@Test
	void windowsReannouncingASharedWordSettle() {
		MemoryCellGroup ga = group("A");
		MemoryCellGroup gb = group("B");
		MemoryCell a = ga.add(01000);
		gb.add(01000);
		MemoryCellListener reannounce = (g, c) -> m_groups.syncMemoryCells(c);
		ga.addListener(reannounce);
		gb.addListener(reannounce);

		a.setEditValue(CellValue.of(1));
		m_groups.syncMemoryCells(a);                            // must not reach the depth guard
	}

	/** The memory test keeps its own values, and what it reads still reaches a Memory window. */
	@Test
	void aGroupWithItsOwnValuesFeedsTheImageWhatItReads() {
		MemoryCellGroup test = group("Memory test");
		test.setSharingMemory(false);
		MemoryCell pattern = test.add(01000);
		MemoryCellGroup memory = group("Memory");
		MemoryCell shown = memory.add(01000);
		shown.setEditValue(CellValue.of(0777));                 // the user's, which the test must not take
		List<MemoryCell> seen = new ArrayList<>();
		memory.addListener((g, c) -> seen.add(c));

		pattern.setEditValue(CellValue.of(0125252));
		pattern.setPdpValue(CellValue.of(0125252));
		m_groups.syncMemoryCells(pattern);

		assertFalse(pattern.isShared());
		assertEquals(CellValue.of(0125252), shown.getPdpValue());
		assertEquals(CellValue.of(0777), shown.getEditValue(), "the pattern is not an edit");
		assertEquals(List.of(shown), seen);
	}

	// ---------------------------------------------------------------------------------------
	// The MMU moving
	// ---------------------------------------------------------------------------------------

	/**
	 * The MMU now maps virtual 1000 to physical 201000. The view moves to that word and is told
	 * so; nothing in shared memory changes, and an edit stays where it was typed.
	 */
	@Test
	void reresolvingMovesAVirtualViewAndLeavesMemoryAlone() {
		MemoryCellGroup disassembly = m_groups.addGroup(MemoryAddressType.VIRTUAL, "Disassembly");
		MemoryCell v = disassembly.add(Address.of(MemoryAddressType.VIRTUAL, 01000));
		MemoryCell low = group("Memory").add(01000);
		MemoryCell high = group("High").add(0201000);
		low.setEditValue(CellValue.of(1));
		high.setPdpValue(CellValue.of(2));
		AtomicInteger told = new AtomicInteger();
		m_image.addChangeListener(told::incrementAndGet);

		m_groups.setVirtualResolver(a -> a.val() >= 0160000
			? a.withWidth(MemoryAddressType.PHYSICAL22)
			: Address.of(MemoryAddressType.PHYSICAL22, a.val() + 0200000));
		List<MemoryCellGroup> moved = m_groups.reresolveVirtual();

		assertEquals(List.of(disassembly), moved);
		assertEquals(CellValue.of(2), v.getPdpValue());
		assertFalse(v.isEdited(), "the edit belongs to physical 1000");
		assertTrue(low.isEdited());
		assertEquals(2, m_groups.cellsAt(Address.of(MemoryAddressType.PHYSICAL22, 0201000)).size());
		assertEquals(1, m_groups.cellsAt(Address.of(MemoryAddressType.PHYSICAL22, 01000)).size());
		assertEquals(1, told.get());

		assertTrue(m_groups.reresolveVirtual().isEmpty(), "and again, nothing moves");
	}

	// ---------------------------------------------------------------------------------------
	// Pending listeners
	// ---------------------------------------------------------------------------------------

	@Test
	void aBulkOperationTellsOnceAndOutsideTheMonitor() {
		MemoryCellGroup g = group("Loader");
		g.add(01000, 100);
		for(MemoryCell mc : g.getCells()) {
			mc.setEditValue(CellValue.of(1));
		}
		AtomicInteger told = new AtomicInteger();
		m_image.addChangeListener(() -> {
			assertFalse(Thread.holdsLock(m_groups.lock()), "a listener is window code");
			told.incrementAndGet();
		});

		m_image.discardAllEdits();

		assertEquals(1, told.get());
	}

	@Test
	void aSingleEditTells() {
		MemoryCell c = group("Memory").add(01000);
		AtomicInteger told = new AtomicInteger();
		m_image.addChangeListener(told::incrementAndGet);

		c.setEditValue(CellValue.of(1));
		c.setEditValue(CellValue.of(1));                        // the same again: nothing changed

		assertEquals(1, told.get());
	}
}
