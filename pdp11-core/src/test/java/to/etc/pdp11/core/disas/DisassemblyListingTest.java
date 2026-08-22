package to.etc.pdp11.core.disas;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning what the machine answered into a listing, and finding the PC in it.
 *
 * <p>All of this is what the Disassembler window shows, and none of it needs the window: the
 * point of {@link DisassemblyListing} living in the core is that the awkward part - a PC that
 * lands inside an instruction rather than at the start of one - is checkable here.</p>
 */
class DisassemblyListingTest {
	private static Address v(int val) {
		return Address.of(MemoryAddressType.VIRTUAL, val);
	}

	/** A group of consecutive virtual words holding {@code words}, starting at {@code start}. */
	private static MemoryCellGroup code(int start, int... words) {
		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
		g.add(start, words.length);
		for(int i = 0; i < words.length; i++) {
			g.cell(i).setPdpValue(CellValue.of(words[i]));
		}
		return g;
	}

	@Test
	void oneLinePerInstructionWithItsRawWordsBesideIt() {
		//-- mov #200,r1 / halt
		MemoryCellGroup g = code(01000, 012701, 0200, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), null);

		assertEquals(2, l.getLines().size());
		assertEquals(v(01000), l.getLines().get(0).address());
		assertEquals("mov     #000200,r1", l.getLines().get(0).text().stripTrailing());
		assertEquals(v(01004), l.getLines().get(1).address());
		assertEquals("halt", l.getLines().get(1).text().stripTrailing());
		//-- The two-word instruction shows both its words, and the padding leaves room for three.
		assertTrue(l.getLines().get(0).words().startsWith("012701 000200 "), l.getLines().get(0).words());
	}

	@Test
	void wordsTheMachineNeverAnsweredAreNotInvented() {
		MemoryCellGroup g = code(01000, 010001, 010203);
		g.cell(0).setPdpValue(CellValue.UNKNOWN);                   // never examined
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01002), null);

		assertEquals(1, l.getLines().size(), "the unread word is skipped, not decoded as zero");
		assertEquals(v(01002), l.getLines().get(0).address());
	}

	@Test
	void anEditedValueIsNotWhatTheProcessorWouldExecute() {
		MemoryCellGroup g = code(01000, 010001);
		g.cell(0).setEditValue(CellValue.of(0));                    // typed, not deposited
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01000), null);
		assertEquals("mov     r0,r1", l.getLines().get(0).text().stripTrailing());
	}

	@Test
	void thePcMarksItsLine() {
		MemoryCellGroup g = code(01000, 010001, 010203, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), v(01002));

		assertEquals(1, l.pcLine());
		assertTrue(l.getLines().get(1).atPc());
		assertEquals(v(01002), l.getLines().get(1).address());
	}

	/**
	 * The reason {@link DisassemblyListing#startAddress()} exists.
	 *
	 * <p>{@code mov #200,r1} occupies 1000 and 1002. Start at 1000 and the PC at 1002 is inside
	 * that instruction, not at the start of a line - so the listing has to begin at 1002
	 * instead, which is exactly what the Pascal's retry loop does
	 * ({@code FormDisasU.pas:274-279}).</p>
	 */
	@Test
	void aPcInsideAnInstructionMovesTheStartOfTheListing() {
		MemoryCellGroup g = code(01000, 012701, 0200, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01004), v(01002));

		assertEquals(v(01002), l.startAddress());
		assertEquals(0, l.pcLine());
		assertEquals(v(01002), l.getLines().get(0).address());
	}

	@Test
	void aPcThatCannotBeFoundLeavesTheListingWhereItWas() {
		MemoryCellGroup g = code(01000, 010001);
		//-- The PC is outside the range shown, which happens whenever the user has scrolled away.
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01000), v(02000));
		assertEquals(-1, l.pcLine());
		assertEquals(1, l.getLines().size(), "the listing stays where it was asked to be");
		assertEquals(v(01000), l.startAddress());
	}

	@Test
	void noPcAtAllIsOrdinary() {
		//-- The M9312's console emulator cannot say where the PC is, and the Pascal spells that
		//-- with its illegal-value sentinel. Here it is simply null.
		MemoryCellGroup g = code(01000, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01000), v(01000), null);
		assertEquals(-1, l.pcLine());
		assertEquals("001000: 000000                halt    \n", l.toText());
	}

	@Test
	void onlyTheRangeAskedForIsShown() {
		MemoryCellGroup g = code(01000, 0, 0, 0, 0);
		DisassemblyListing l = DisassemblyListing.of(g, v(01002), v(01004), null);
		assertEquals(2, l.getLines().size());
		assertEquals(v(01002), l.getLines().get(0).address());
		assertEquals(v(01004), l.getLines().get(1).address());
	}
}
