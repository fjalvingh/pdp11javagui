package to.etc.pdp11.core.mmu;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.util.Octal;

import java.util.ArrayList;
import java.util.List;

/**
 * What one CPU mode's 64 KB of virtual address space currently maps onto.
 *
 * <p>Ported from {@code UpdateMemoryMapGrid}, which is a nested procedure inside
 * {@code TFormMMU.UpdateDisplay} ({@code FormMmuU.pas:84-160}) and mixes the walk over address
 * space with {@code grid.Cells[]} assignments and column-width arithmetic. PLAN.md's rule is
 * that the awkward part of a window is a class in the core with a test - and this is the awkward
 * part: which addresses run together as one block is not something you can check by looking at
 * a screenshot.</p>
 *
 * <h2>What a block is</h2>
 *
 * <p>A run of consecutive virtual addresses that map onto consecutive physical addresses - so a
 * block ends where the mapping stops being linear, which is at a page boundary whose PAR does
 * not continue the previous one, at the I/O page, or where translation starts or stops failing.
 * A failing run is a block too, carrying the reason: the Pascal has only "not assigned" for
 * both of the ways translation can fail.</p>
 */
public record MmuMemoryMap(CpuMode mode, AccessSpace space, AccessSpace effectiveSpace, List<Block> blocks) {
	/** How much virtual address space there is: 16 bits of it. */
	public static final long VIRTUAL_SIZE = 0x10000;

	/**
	 * One run of virtual addresses that map the same way.
	 *
	 * @param number        1-based, as the window numbers its rows.
	 * @param virtualStart  first virtual address in the run.
	 * @param virtualEnd    last virtual address in the run - the address of its last word, not
	 *                      one past it.
	 * @param physicalStart where it starts in physical memory, or {@code null} when the run does
	 *                      not translate.
	 * @param physicalEnd   where it ends, or {@code null}.
	 * @param failure       why it does not translate, or {@code null} when it does.
	 * @param ioPage        whether this is the top 8 KB, which is never relocated - it is the
	 *                      I/O page, and it is the one block that is there whatever the MMU is
	 *                      set to.
	 */
	public record Block(int number, long virtualStart, long virtualEnd, Address physicalStart, Address physicalEnd,
		TranslationResult.Failure failure, boolean ioPage) {
		public boolean isMapped() {
			return failure == null;
		}

		/** How much of virtual space this covers, in bytes. */
		public long byteCount() {
			return virtualEnd - virtualStart + 2;
		}

		public String virtualRange() {
			return Octal.format(virtualStart, 6) + " .. " + Octal.format(virtualEnd, 6);
		}

		/** The physical range, or why there is not one. */
		public String physicalRange() {
			if(!isMapped())
				return switch(failure) {
					case PAGE_LENGTH_ERROR -> "page length error";
					case NOT_A_SIXTEEN_BIT_ADDRESS -> "not a 16-bit address";
				};
			return physicalStart.toOctal() + " .. " + physicalEnd.toOctal();
		}

		@Override
		public String toString() {
			return virtualRange() + " -> " + physicalRange();
		}
	}

	/**
	 * Walk all of virtual space and collect the runs.
	 *
	 * <p>Word by word, as the Pascal does. That is 32768 translations for a map and there is no
	 * cleverness worth its risk here: the mapping only changes at 64-byte block boundaries, so a
	 * coarser walk would give the same answer, and would give a subtly wrong one the day a page
	 * length lands somewhere unexpected.</p>
	 */
	public static MmuMemoryMap of(Pdp11Mmu mmu, CpuMode mode, AccessSpace space) {
		//-- A mode without D space sends data accesses through the I map, and the window should
		//-- say so rather than showing two identical tables with no explanation.
		AccessSpace effective = mmu.isDSpaceEnabled(mode) ? space : AccessSpace.INSTRUCTION;

		List<Block> blocks = new ArrayList<>();
		long start = 0;
		TranslationResult startResult = translate(mmu, 0, mode, space);

		for(long v = 2; v < VIRTUAL_SIZE; v += 2) {
			TranslationResult r = translate(mmu, v, mode, space);
			if(continuesRun(startResult, start, r, v))
				continue;
			blocks.add(block(blocks.size() + 1, start, v - 2, startResult));
			start = v;
			startResult = r;
		}
		//-- The last run reaches the end of virtual space, so it is closed here rather than by
		//-- something that follows it.
		blocks.add(block(blocks.size() + 1, start, VIRTUAL_SIZE - 2, startResult));
		return new MmuMemoryMap(mode, space, effective, List.copyOf(blocks));
	}

	private static TranslationResult translate(Pdp11Mmu mmu, long v, CpuMode mode, AccessSpace space) {
		return mmu.translate(Address.of(MemoryAddressType.VIRTUAL, v), mode, space);
	}

	/** Whether {@code v} maps the way the block starting at {@code start} does. */
	private static boolean continuesRun(TranslationResult startResult, long start, TranslationResult r, long v) {
		if(startResult.isValid() != r.isValid())
			return false;
		if(!r.isValid())
			return startResult.failure() == r.failure();
		//-- Consecutive virtual onto consecutive physical: the same distance from the start of
		//-- the block, which is the Pascal's test and holds across a page boundary whose PAR
		//-- happens to continue the one before it.
		return r.address().val() - startResult.address().val() == v - start;
	}

	private static Block block(int number, long start, long end, TranslationResult result) {
		boolean ioPage = start >= MemoryAddressType.VIRTUAL.getIopageBase();
		return result.isValid()
			? new Block(number, start, end, result.address(), result.address().plus(end - start), null, ioPage)
			: new Block(number, start, end, null, null, result.failure(), ioPage);
	}

	/** Whether data accesses in this mode go through the instruction map, D space being off. */
	public boolean isUsingInstructionMapForData() {
		return space == AccessSpace.DATA && effectiveSpace == AccessSpace.INSTRUCTION;
	}
}
