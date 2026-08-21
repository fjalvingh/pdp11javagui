package to.etc.pdp11.core.bits;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitfieldTest {
	/** The PSW as the machine descriptions define it, near enough for a test. */
	private static BitfieldsDef psw() {
		BitfieldsDef d = new BitfieldsDef("Bits.CPU.PSW");
		d.add(new BitfieldDef("PRIO", "Processor priority", 7, 5));
		d.add(BitfieldDef.of("T", "Trace trap", 4));
		d.add(BitfieldDef.of("N", "Negative", 3));
		d.add(BitfieldDef.of("Z", "Zero", 2));
		d.add(BitfieldDef.of("V", "Overflow", 1));
		d.add(BitfieldDef.of("C", "Carry", 0));
		return d;
	}

	@Test
	void masksCoverTheRequestedBitsInPlaceAndShiftedDown() {
		BitfieldDef prio = new BitfieldDef("PRIO", "", 7, 5);
		assertEquals(0b11100000, prio.mask());
		assertEquals(0b111, prio.unshiftedMask());
		assertEquals(3, prio.width());

		//-- The full-width case, where the Pascal needed a lookup table because dword shl 16
		//-- misbehaves. Java's int shift is well-defined.
		BitfieldDef whole = new BitfieldDef("ALL", "", 15, 0);
		assertEquals(0xFFFF, whole.mask());
		assertEquals(0xFFFF, whole.unshiftedMask());
		assertEquals(16, whole.width());
	}

	@Test
	void getExtractsShiftedDownToBitZero() {
		BitfieldDef prio = new BitfieldDef("PRIO", "", 7, 5);
		assertEquals(7, prio.get(0340));
		assertEquals(0, prio.get(0017));
		assertEquals(5, prio.get(0240));
	}

	@Test
	void setReplacesOnlyItsOwnBits() {
		BitfieldDef prio = new BitfieldDef("PRIO", "", 7, 5);
		assertEquals(0340 | 017, prio.set(017, 7));
		assertEquals(017, prio.set(0340 | 017, 0));
	}

	@Test
	void setRefusesAValueTooWideForTheFieldRatherThanTruncating() {
		BitfieldDef prio = new BitfieldDef("PRIO", "", 7, 5);
		//-- Silently dropping high bits would deposit a different value into a device register
		//-- than the user typed, which is the sort of thing that costs an afternoon.
		assertThrows(IllegalArgumentException.class, () -> prio.set(0, 8));
	}

	@Test
	void aBitRangeOutsideAWordIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new BitfieldDef("X", "", 16, 0));
		assertThrows(IllegalArgumentException.class, () -> new BitfieldDef("X", "", 15, -1));
		assertThrows(IllegalArgumentException.class, () -> new BitfieldDef("X", "", 3, 7));
		assertThrows(IllegalArgumentException.class, () -> new BitfieldDef(" ", "", 0, 0));
	}

	@Test
	void aRegisterDefinitionFindsItsFieldsByNameAndByBit() {
		BitfieldsDef psw = psw();
		assertEquals(6, psw.getFields().size());
		assertEquals("PRIO", psw.findByName("prio").name());
		assertNull(psw.findByName("nonesuch"));

		assertEquals("C", psw.fieldsAtBit(0).get(0).name());
		assertEquals("PRIO", psw.fieldsAtBit(6).get(0).name());
		//-- Bits 15..8 are undefined in this register, which the display shows differently
		//-- from a defined zero.
		assertTrue(psw.fieldsAtBit(15).isEmpty());
		assertEquals(0b11111111, psw.definedMask());
	}

	@Test
	void duplicateFieldNamesAreRejectedButOverlappingBitsAreNot() {
		BitfieldsDef d = psw();
		assertThrows(IllegalArgumentException.class, () -> d.add(BitfieldDef.of("N", "again", 12)));

		//-- Overlap is how the descriptions express a register that reads one way and writes
		//-- another: the DZ11 defines RBUF.PAR ERR<12> and LPR.RX ON<12> in one definition.
		d.add(BitfieldDef.of("WRITE.SOMETHING", "same bit, write side", 6));
		assertEquals(2, d.fieldsAtBit(6).size());
	}

	@Test
	void definitionsAreLookedUpByNameCaseInsensitively() {
		BitfieldsDefs defs = new BitfieldsDefs();
		BitfieldsDef psw = psw();
		defs.add(psw);
		assertSame(psw, defs.findByName("BITS.CPU.PSW"));
		assertSame(psw, defs.findByName("bits.cpu.psw"));
		assertNull(defs.findByName("Bits.CPU.PIRQ"));
		assertThrows(IllegalArgumentException.class, () -> defs.add(new BitfieldsDef("Bits.CPU.PSW")));
	}

	/**
	 * The point of keying the address map on the 16-bit form: a definition linked while one
	 * machine is connected must still be found when a machine of a different width is.
	 */
	@Test
	void addressLookupWorksAtEveryPhysicalWidth() {
		BitfieldsDefs defs = new BitfieldsDefs();
		BitfieldsDef psw = psw();
		defs.add(psw);
		assertTrue(defs.linkAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776), "Bits.CPU.PSW"));

		assertSame(psw, defs.findByAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776)));
		assertSame(psw, defs.findByAddress(Address.of(MemoryAddressType.PHYSICAL18, 0777776)));
		assertSame(psw, defs.findByAddress(Address.of(MemoryAddressType.PHYSICAL22, 017777776)));

		//-- A different register in the same I/O page has no definition.
		assertNull(defs.findByAddress(Address.of(MemoryAddressType.PHYSICAL22, 017777570)));
	}

	@Test
	void linkingReportsAnUnknownDefinitionRatherThanThrowing() {
		BitfieldsDefs defs = new BitfieldsDefs();
		//-- A machine description naming a definition it never declares should be reported,
		//-- not crash the load of the whole file.
		assertFalse(defs.linkAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776), "Bits.Nope"));
	}

	@Test
	void ordinaryMemoryHasNoBitfieldsAndSayingSoIsNotAnError() {
		BitfieldsDefs defs = new BitfieldsDefs();
		defs.add(psw());
		//-- The memory windows ask this for every cell they show.
		assertNull(defs.findByAddress(Address.of(MemoryAddressType.PHYSICAL22, 01000)));
		//-- Linking one, on the other hand, is a mistake worth hearing about.
		assertThrows(IllegalArgumentException.class,
			() -> defs.linkAddress(Address.of(MemoryAddressType.PHYSICAL22, 01000), "Bits.CPU.PSW"));
	}

	@Test
	void clearEmptiesBothTheNamesAndTheAddresses() {
		BitfieldsDefs defs = new BitfieldsDefs();
		defs.add(psw());
		defs.linkAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776), "Bits.CPU.PSW");
		defs.clear();
		assertTrue(defs.isEmpty());
		assertNull(defs.findByName("Bits.CPU.PSW"));
		assertNull(defs.findByAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776)));
	}
}
