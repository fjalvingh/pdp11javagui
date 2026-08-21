package to.etc.pdp11.core.addr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static to.etc.pdp11.core.addr.MemoryAddressType.PHYSICAL16;
import static to.etc.pdp11.core.addr.MemoryAddressType.PHYSICAL18;
import static to.etc.pdp11.core.addr.MemoryAddressType.PHYSICAL22;
import static to.etc.pdp11.core.addr.MemoryAddressType.VIRTUAL;

/**
 * PLAN.md phase 1 is "done when {@code Address.withWidth} round-trips across all four width
 * conversions", because this one rule affects every window whenever the target machine
 * changes and is easy to get subtly wrong.
 */
class AddressTest {
	private static final MemoryAddressType[] WIDTHS = {PHYSICAL16, PHYSICAL18, PHYSICAL22};

	@Test
	void iopageBasesMatchThePdp11Architecture() {
		assertEquals(0160000L, PHYSICAL16.getIopageBase());
		assertEquals(0760000L, PHYSICAL18.getIopageBase());
		assertEquals(017760000L, PHYSICAL22.getIopageBase());
		assertEquals(0160000L, VIRTUAL.getIopageBase());
	}

	@Test
	void addressesBelowTheIopageAreWidthInvariant() {
		Address a = Address.of(PHYSICAL16, 01000);
		assertEquals(01000L, a.withWidth(PHYSICAL18).val());
		assertEquals(01000L, a.withWidth(PHYSICAL22).val());
		assertEquals(01000L, a.withWidth(PHYSICAL16).val());
	}

	@Test
	void iopageAddressesAreRebasedOntoTheNewTopOfMemory() {
		//-- The console switch register, the canonical example.
		Address sw16 = Address.of(PHYSICAL16, 0177570);
		assertEquals(0777570L, sw16.withWidth(PHYSICAL18).val());
		assertEquals(017777570L, sw16.withWidth(PHYSICAL22).val());
	}

	/** The "done when" of PLAN.md phase 1: every ordered pair of widths, both ways. */
	@Test
	void roundTripsAcrossEveryPairOfWidths() {
		long[] belowIopage = {0, 2, 01000, 0157776};
		for(MemoryAddressType from : WIDTHS) {
			for(MemoryAddressType to : WIDTHS) {
				for(long val : belowIopage) {
					Address a = Address.of(from, val);
					assertEquals(a, a.withWidth(to).withWidth(from),
						"below-I/O-page " + from + " -> " + to + " -> " + from);
				}
				//-- Every word in the 8 KB I/O page, which is where the rebasing happens.
				for(long off = 0; off < 8192; off += 2) {
					Address a = Address.of(from, from.getIopageBase() + off);
					Address there = a.withWidth(to);
					assertEquals(to.getIopageBase() + off, there.val(),
						"I/O page offset " + off + ", " + from + " -> " + to);
					assertEquals(a, there.withWidth(from),
						"I/O page offset " + off + ", " + from + " -> " + to + " -> " + from);
				}
			}
		}
	}

	@Test
	void narrowingAnAddressThatDoesNotFitIsRejectedRatherThanTruncated() {
		//-- Ordinary memory on a 22-bit machine that simply does not exist on a 16-bit one.
		Address big = Address.of(PHYSICAL22, 0400000);
		assertFalse(big.fitsWidth(PHYSICAL16));
		assertThrows(IllegalArgumentException.class, () -> big.withWidth(PHYSICAL16));

		//-- ...but the same machine's I/O page converts down fine, because it is rebased.
		assertTrue(Address.of(PHYSICAL22, 017777570).fitsWidth(PHYSICAL16));
		assertEquals(0177570L, Address.of(PHYSICAL22, 017777570).withWidth(PHYSICAL16).val());
	}

	@Test
	void theIopageBoundaryItselfConverts() {
		for(MemoryAddressType from : WIDTHS) {
			for(MemoryAddressType to : WIDTHS) {
				Address firstInPage = Address.of(from, from.getIopageBase());
				assertTrue(firstInPage.isInIopage());
				assertEquals(to.getIopageBase(), firstInPage.withWidth(to).val());

				Address lastBelow = Address.of(from, from.getIopageBase() - 2);
				assertFalse(lastBelow.isInIopage());
			}
		}
	}

	@Test
	void aVirtualAddressConvertsToPhysicalButNotTheOtherWay() {
		Address v = Address.of(VIRTUAL, 0177570);
		assertEquals(017777570L, v.withWidth(PHYSICAL22).val());
		assertThrows(IllegalArgumentException.class, () -> v.withWidth(MemoryAddressType.VIRTUAL));
	}

	@Test
	void conversionOnlyTargetsConcretePhysicalWidths() {
		Address a = Address.of(PHYSICAL16, 01000);
		for(MemoryAddressType t : MemoryAddressType.values()) {
			if(t.isConcretePhysical())
				continue;
			assertThrows(IllegalArgumentException.class, () -> a.withWidth(t), "target " + t);
		}
	}

	@Test
	void aValueTooWideForItsTypeIsRejectedAtConstruction() {
		assertThrows(IllegalArgumentException.class, () -> Address.of(PHYSICAL16, 0200000));
		assertThrows(IllegalArgumentException.class, () -> Address.of(PHYSICAL18, 01000000));
		assertThrows(IllegalArgumentException.class, () -> Address.of(PHYSICAL22, 020000000));
		assertThrows(IllegalArgumentException.class, () -> Address.of(PHYSICAL16, -1));
	}

	@Test
	void printsAndParsesOctalAtTheWidthOfTheType() {
		assertEquals("177570", Address.of(PHYSICAL16, 0177570).toOctal());
		assertEquals("777570", Address.of(PHYSICAL18, 0777570).toOctal());
		assertEquals("17777570", Address.of(PHYSICAL22, 017777570).toOctal());
		assertEquals("000000", Address.of(PHYSICAL16, 0).toOctal());

		for(MemoryAddressType t : WIDTHS) {
			Address a = Address.of(t, t.getIopageBase() + 0570);
			assertEquals(a, Address.parseOctal(a.toOctal(), t));
		}
	}

	@Test
	void eighteenBitAddressesPrintInSixDigitsJustLikeSixteenBitOnes() {
		assertEquals(6, PHYSICAL16.getOctalDigits());
		assertEquals(6, PHYSICAL18.getOctalDigits());
		assertEquals(8, PHYSICAL22.getOctalDigits());
	}

	@Test
	void typeIsPartOfIdentity() {
		assertNotEquals(Address.of(PHYSICAL16, 01000), Address.of(PHYSICAL18, 01000));
		assertThrows(IllegalArgumentException.class,
			() -> Address.of(PHYSICAL16, 01000).compareTo(Address.of(PHYSICAL18, 01000)));
	}

	@Test
	void onlyConcretePhysicalTypesClaimToBeOne() {
		assertTrue(PHYSICAL16.isConcretePhysical());
		assertTrue(PHYSICAL18.isConcretePhysical());
		assertTrue(PHYSICAL22.isConcretePhysical());
		//-- VIRTUAL is 16 bits wide but is not a physical address; the Pascal's
		//-- "mat > matAnyPhysical" excluded it too, by ordering rather than by saying so.
		assertFalse(VIRTUAL.isConcretePhysical());
		assertFalse(MemoryAddressType.ANY_PHYSICAL.isConcretePhysical());
		assertFalse(MemoryAddressType.UNKNOWN.isConcretePhysical());
		assertFalse(MemoryAddressType.SPECIAL_REGISTER.isConcretePhysical());
	}
}
