package to.etc.pdp11.core.memtest;

import to.etc.pdp11.core.addr.Address;

import java.util.List;

/**
 * What one run of a memory test found.
 *
 * <p>The Pascal returns a boolean and writes everything else into a memo as it goes
 * ({@code FormMemoryTestU.pas}), so the only way to find out <i>what</i> failed is to read the
 * log. Here the log is still produced - it is genuinely useful, and it is what the window shows -
 * but the findings are values as well, which is what makes a test of the test possible.</p>
 */
public record MemoryTestResult(
	/** What was run, for the log's first line. */
	String name,

	boolean passed,

	/** Stopped early because the user asked. The findings so far still stand. */
	boolean cancelled,

	/** Every word that read back as something other than what was written. */
	List<Mismatch> mismatches,

	/**
	 * Data lines that were high in every single reading, so probably tied high. Zero when the
	 * test was not a data line test, or when there were none.
	 */
	int stuckHighMask,

	/** Data lines that were low in every reading. */
	int stuckLowMask,

	/** Everything the run had to say, in order. */
	List<String> log
) {
	/**
	 * One word that did not read back what was written to it.
	 *
	 * @param diffMask which bits differ - the useful part, because a single bit says "data line"
	 *                 and a run of them says something else entirely
	 */
	public record Mismatch(Address address, int expected, int actual, int diffMask) {
		public static Mismatch of(Address address, int expected, int actual) {
			return new Mismatch(address, expected & 0xFFFF, actual & 0xFFFF,
				(expected ^ actual) & 0xFFFF);
		}

		/** The lowest differing bit, which is the one to suspect first. -1 if there is none. */
		public int lowestDifferingBit() {
			return MemoryTester.lowestBitNumber(diffMask);
		}
	}

	public int errorCount() {
		return mismatches.size();
	}

	/** Whether any data line looked dead. */
	public boolean hasStuckLines() {
		return stuckHighMask != 0 || stuckLowMask != 0;
	}
}
