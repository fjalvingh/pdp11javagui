package to.etc.pdp11.core.mmu;

/**
 * Whether an access fetches an instruction or reads data. On a machine with separate I and D
 * space the two go through different page tables.
 *
 * <p>Ported from {@code TPdp11MmuIDMode} ({@code Pdp11MmuU.pas:65-68}).</p>
 */
public enum AccessSpace {
	INSTRUCTION,
	DATA
}
