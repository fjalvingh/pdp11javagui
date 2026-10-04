package to.etc.pdp11.common.disas;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The comment a disassembly line gets when its instruction names an address everybody knows. */
class WellKnownAddressesTest {
	private static final int BASE = 01000;

	private static String commentOf(int... words) {
		return WellKnownAddresses.builtin().comment(Disassembler.disassemble(MemoryImage.ofWords(BASE, words), BASE));
	}

	@Test
	void thePackagedTableReadsAndKnowsTheConsole() {
		WellKnownAddresses t = WellKnownAddresses.builtin();
		assertTrue(t.getEntries().size() > 200, "the table is all there");
		assertEquals("TKS: Console receiver status", t.describe(0177560));
		assertEquals("PSW: Processor status word", t.describe(0177776));
		assertEquals("KIPAR0: Kernel I page address 0", t.describe(0172340));
		assertNull(t.describe(0177002), "an address nobody owns is not guessed at");
	}

	/** Two entries at one address, unless they are two devices that really do share it. */
	@Test
	void theTableNamesNoAddressTwiceExceptWhereDevicesShareIt() {
		Set<Integer> seen = new HashSet<>();
		Set<Integer> shared = new HashSet<>();
		for(WellKnownAddresses.Entry e : WellKnownAddresses.builtin().getEntries()) {
			if(!seen.add(e.first()))
				shared.add(e.first());
		}
		assertEquals(Set.of(0172520, 0172522), shared, "only the TM11 and TS11 share registers");
	}

	@Test
	void anImmediateInTheIoPageIsTheCsrABootstrapIsAboutToUse() {
		//-- mov #177560,r1
		assertEquals("TKS: Console receiver status", commentOf(012701, 0177560));
	}

	@Test
	void aSmallImmediateIsANumberNotAVector() {
		//-- mov #4,r0
		assertEquals("", commentOf(012700, 4));
	}

	@Test
	void anAbsoluteLowAddressIsAVector() {
		//-- mov #1000,@#4: the immediate is not commented, the destination is.
		assertEquals("Bus error / CPU error trap vector", commentOf(012737, 01000, 4));
	}

	@Test
	void everyNamedOperandIsCommentedInOrder() {
		//-- movb @#177562,@#177566
		assertEquals("TKB: Console receiver buffer; TPB: Console transmitter buffer",
			commentOf(0113737, 0177562, 0177566));
	}

	@Test
	void aRelativeOperandIsCommentedByWhereItPoints() {
		//-- tst 177560, PC-relative: the offset is from the word after the extension word.
		assertEquals("TKS: Console receiver status", commentOf(005767, 0177560 - (BASE + 4)));
		//-- tst @177560 points through it, and names the same address.
		assertEquals("TKS: Console receiver status", commentOf(005777, 0177560 - (BASE + 4)));
	}

	@Test
	void anOddAddressIsTheHighByteOfTheRegisterBelowIt() {
		//-- tstb @#177565
		assertEquals("TPS: Console transmitter status, high byte", commentOf(0105737, 0177565));
	}

	@Test
	void theConsolesRegisterAddressesAreNamedThemselvesNotAsHighBytes() {
		assertEquals("PC: Program counter", WellKnownAddresses.builtin().describe(0177707));
	}

	@Test
	void anAddressInsideARomIsTheRom() {
		//-- jmp @#173024
		assertEquals("Bootstrap ROM (M9312, M9301)", commentOf(000137, 0173024));
	}

	@Test
	void devicesSharingAnAddressAreBothNamed() {
		//-- clr @#172520
		assertEquals("MTS: TM11 status / TSDB: TS11 data buffer", commentOf(005037, 0172520));
	}

	@Test
	void aFloatLiteralIsNotAnAddress() {
		//-- ldf #160000,ac0: the word is the top half of a float.
		assertEquals("", commentOf(0172427, 0160000));
	}

	@Test
	void indexOffsetsAndBranchTargetsAreNotCommented() {
		//-- mov 177560(r1),r0
		assertEquals("", commentOf(016100, 0177560));
		//-- br .
		assertEquals("", commentOf(000777));
	}

	@Test
	void aMistakeInTheTableNamesItsLine() {
		IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
			() -> WellKnownAddresses.parse(List.of("# fine", "177560  TKS  ok", "17756  TKB  short")));
		assertTrue(x.getMessage().contains(":3:"), x.getMessage());
		assertThrows(IllegalArgumentException.class, () -> WellKnownAddresses.parse(List.of("177570-177560  -  backwards")));
		assertThrows(IllegalArgumentException.class, () -> WellKnownAddresses.parse(List.of("177560  TKS")));
	}

	@Test
	void aListingLineCarriesTheCommentLinedUpAfterTheInstruction() {
		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "code");
		int[] words = {0105737, 0177564, 0000240};                  // tstb @#177564 / nop
		g.add(BASE, words.length);
		for(int i = 0; i < words.length; i++) {
			g.cell(i).setPdpValue(CellValue.of(words[i]));
		}
		DisassemblyListing l = DisassemblyListing.of(g, Address.of(MemoryAddressType.VIRTUAL, BASE),
			Address.of(MemoryAddressType.VIRTUAL, BASE + 4), null);

		DisassemblyListing.Line tstb = l.getLines().get(0);
		assertEquals("TPS: Console transmitter status", tstb.comment());
		assertEquals("001000: 105737 177564         tstb    @#177564            ; TPS: Console transmitter status",
			tstb.toDisplayString());
		assertEquals("", l.getLines().get(1).comment());
		assertEquals("001004: 000240                nop     ", l.getLines().get(1).toDisplayString(),
			"a line with nothing to say is laid out exactly as before");
	}
}
