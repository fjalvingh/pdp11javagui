package to.etc.pdp11.core.mmu;

/**
 * The processor mode, which selects a memory map. Ordinals match PSW bits 15..14, as the
 * hardware encodes them.
 *
 * <p>Ported from {@code TPdp11MmuCpuMode} ({@code Pdp11MmuU.pas:57-62}).</p>
 */
public enum CpuMode {
	KERNEL,
	SUPERVISOR,
	/** Encoding 2 exists in the PSW but no PDP-11 implements it. */
	ILLEGAL,
	USER;

	/** The mode PSW bits 15..14 select. */
	public static CpuMode fromPsw(int psw) {
		return values()[(psw >>> 14) & 3];
	}
}
