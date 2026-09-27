package to.etc.pdp11.core.mmu;

import to.etc.pdp11.core.addr.Address;

/**
 * What came of translating a virtual address.
 *
 * <p>The Pascal returns {@code MEMORYCELL_ILLEGALVAL} for a failure and raises an exception
 * for a page it cannot handle ({@code Pdp11MmuU.pas:245-247}) - so the caller gets a magic
 * value for one kind of failure and a stack unwind for another, and neither says why. A result
 * object says which of the three things happened, and the MMU window can show the reason.</p>
 *
 * @param address the physical address, only when {@link #isValid()}
 * @param failure why not, or {@code null} on success
 */
public record TranslationResult(Address address, Failure failure) {
	public enum Failure {
		/** The virtual address was wider than 16 bits. */
		NOT_A_SIXTEEN_BIT_ADDRESS,
		/**
		 * The offset within the page lies outside the length the PDR declares - a page length
		 * error, which on real hardware traps through the MMU abort vector.
		 */
		PAGE_LENGTH_ERROR
	}

	public static TranslationResult of(Address address) {
		return new TranslationResult(address, null);
	}

	public static TranslationResult failed(Failure failure) {
		return new TranslationResult(null, failure);
	}

	public boolean isValid() {
		return failure == null;
	}

	@Override
	public String toString() {
		return isValid() ? address.toOctal() : "invalid (" + failure + ")";
	}
}
