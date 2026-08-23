package to.etc.pdp11.core.memtest;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.ProgressMonitor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Four tests that find out whether a PDP-11's memory works, and if not, which part of it does
 * not.
 *
 * <p>Ported from {@code TFormMemoryTest} ({@code FormMemoryTestU.pas}), which is a window with
 * four algorithms in it. The algorithms are here instead, because none of them needs a widget:
 * each is a pattern written through the console and read back, and what makes them worth having
 * is the <i>diagnosis</i> they draw from the differences. That diagnosis is exactly what can be
 * checked, by running them against a simulated machine with a deliberate fault in it.</p>
 *
 * <h2>Why not simply write every word and read it back</h2>
 *
 * <p>Because that takes hours. A console examine is a command, an answer and a round trip - at
 * 9600 baud on a real machine, testing 128 KB one word at a time is most of a day. Each of these
 * tests instead writes a pattern chosen so that a small number of accesses says something
 * definite about a whole class of fault: a dead data line, a shorted address line, a dead chip.
 * That is the whole design, and it is why {@link ChipSize} has to be told to them.</p>
 */
public final class MemoryTester {
	/** Every reading is a 16-bit word. */
	private static final int WORD_MASK = 0xFFFF;

	/** Data bits per word, which is how many steps a moving-one pattern takes. */
	private static final int DATA_BITS = 16;

	/** The words per chip block that the data-bit test writes: one per data line. */
	private static final int BLOCK_TEST_WORDS = 16;

	/** How many address lines a PDP-11 can have: 22. */
	private static final int MAX_ADDRESS_BITS = 22;

	private final Console m_console;

	private final MemoryCellGroup m_group;

	private final Address m_start;

	private final Address m_end;

	private final int m_chipSizeBytes;

	private final List<String> m_log = new ArrayList<>();

	/** Told each line as it happens, so a window can show a running test. May be null. */
	private final LogSink m_sink;

	@FunctionalInterface
	public interface LogSink {
		void line(String text);
	}

	/**
	 * @param group the cells to work through - they are what a window displays, and they are
	 *              updated as the test runs so it can be watched
	 * @param start the first address to test, even
	 * @param end   the last address to test, even and not before {@code start}
	 */
	public MemoryTester(Console console, MemoryCellGroup group, Address start, Address end,
		ChipSize chipSize, LogSink sink) {
		if(start.type() != end.type())
			throw new IllegalArgumentException("A test range needs one address width, not "
				+ start.type() + " and " + end.type());
		if(end.val() < start.val())
			throw new IllegalArgumentException("A test range ends after it starts");
		m_console = console;
		m_group = group;
		m_start = start;
		m_end = end;
		m_chipSizeBytes = chipSize.getBytes();
		m_sink = sink;
	}

	public List<String> getLog() {
		return List.copyOf(m_log);
	}

	// -------------------------------------------------------------------------------------
	// 1. Data lines
	// -------------------------------------------------------------------------------------

	/**
	 * Move a single one (or a single zero) through the sixteen data bits and see which bits
	 * never move.
	 *
	 * <p>Ported from {@code TestDataLines} ({@code :341-487}), whose German comment is a
	 * specification and is worth restating because the trick in it is not obvious.</p>
	 *
	 * <p><b>It has to survive other faults.</b> A chip that reads back all-zeros and a chip that
	 * reads back all-ones both look exactly like a stuck data line if you test one address. So
	 * the test repeats at the first word of every chip and combines the readings: OR them all
	 * together and a bit still zero is a line that was never high; AND them all together and a
	 * bit still one is a line that was never low. A dead chip pollutes one reading; it cannot
	 * make a working line look dead in <i>all</i> of them. If every line has been seen both ways
	 * it stops early, and if it runs out of chips it tries the <i>last</i> word of each chip too
	 * before giving up.</p>
	 *
	 * <p><b>This test writes to memory and does not put it back.</b> So does every other test
	 * here; they are for a machine that is not running anything.</p>
	 *
	 * @param movingOne true to move a one through zeros, false to move a zero through ones
	 */
	public MemoryTestResult testDataLines(boolean movingOne, ProgressMonitor pm) throws ConsoleException {
		m_log.clear();
		log("Test data lines, addr range = %s..%s, chip size = %s",
			Octal.format(m_start.val(), 1), Octal.format(m_end.val(), 1), Octal.format(m_chipSizeBytes, 1));

		//-- Both masks start all-ones and are worn away by the readings.
		int[] stuck = {WORD_MASK, WORD_MASK};                   // [0] = never high, [1] = never low
		List<MemoryTestResult.Mismatch> mismatches = new ArrayList<>();
		boolean allToggled = false;
		boolean cancelled = false;

		pm.begin("Testing data lines ...", 2 * DATA_BITS * chipsInRange());
		try {
			for(int atChipEnd = 0; atChipEnd <= 1 && !allToggled && !cancelled; atChipEnd++) {
				log("Testing moving %s at %s addr of memory chips ...",
					movingOne ? "ones" : "zeros", atChipEnd == 1 ? "last" : "first");
				long addr = m_start.val() + (atChipEnd == 1 ? m_chipSizeBytes - 2 : 0);
				while(addr <= m_end.val()) {
					if(pm.isCancelled()) {
						cancelled = true;
						break;
					}
					testWordDataLines(addr, movingOne, stuck, mismatches, pm);
					allToggled = stuck[0] == 0 && stuck[1] == 0;
					if(allToggled)
						break;
					addr += m_chipSizeBytes;
				}
			}
		} finally {
			pm.done();
		}

		boolean passed;
		if(!allToggled) {
			log("Data lines not OK.");
			log("Lines detected as permanent \"high\": %s, bits = %s",
				Octal.word(stuck[1]), bitNumbers(stuck[1]));
			log("Lines detected as permanent \"low\" : %s, bits = %s",
				Octal.word(stuck[0]), bitNumbers(stuck[0]));
			passed = false;
		} else if(mismatches.isEmpty()) {
			log("OK");
			passed = true;
		} else {
			//-- Every line moved, so the wiring is fine; something else in the memory is not.
			log("Data lines OK, but %d other errors!", mismatches.size());
			passed = false;
		}
		return new MemoryTestResult("Data lines, moving " + (movingOne ? "one" : "zero"),
			passed && !cancelled, cancelled, List.copyOf(mismatches),
			allToggled ? 0 : stuck[1], allToggled ? 0 : stuck[0], getLog());
	}

	/** All sixteen bits at one address. {@code TestWord}/{@code TestSingleBit} ({@code :368-406}). */
	private void testWordDataLines(long addrValue, boolean movingOne, int[] stuck,
		List<MemoryTestResult.Mismatch> mismatches, ProgressMonitor pm) throws ConsoleException {
		Address addr = Address.of(m_start.type(), addrValue);
		for(int bit = 0; bit < DATA_BITS; bit++) {
			if(pm.isCancelled())
				return;
			int testValue = movingOne ? 1 << bit : ~(1 << bit) & WORD_MASK;
			int read = writeAndRead(addr, testValue);
			pm.step(1);
			if(read != testValue)
				mismatches.add(MemoryTestResult.Mismatch.of(addr, testValue, read));
			//-- A bit that has now been seen high is not stuck low, and the other way round.
			stuck[0] = stuck[0] & ~read & WORD_MASK;
			stuck[1] = stuck[1] & read & WORD_MASK;
		}
	}

	// -------------------------------------------------------------------------------------
	// 2. Address lines
	// -------------------------------------------------------------------------------------

	/**
	 * Write each address into itself at 0, 2, 4, 8, 16 ... and check that they all come back.
	 *
	 * <p>Ported from {@code TestAdressLines} ({@code :507-611}). A moving one through the
	 * address bits touches one address per address line, so an address line that is stuck makes
	 * two of those addresses the same cell - and the second write lands on top of the first.
	 * Writing the address <i>as</i> the data is what makes that visible: the cell says which
	 * address actually reached the memory.</p>
	 *
	 * <p><b>Phase 2 is not a repeat.</b> It runs the same pattern with as many high address bits
	 * set as still fit in the range. If phase 1 passes and phase 2 fails, the high address bits
	 * are altering the low ones, which is a short between address lines rather than a dead one -
	 * and that is a fault the first phase cannot see at all.</p>
	 *
	 * @param phase 1 or 2
	 */
	public MemoryTestResult testAddressLines(int phase, ProgressMonitor pm) throws ConsoleException {
		m_log.clear();
		log("Test address lines, phase %d, addr range = %s..%s.", phase,
			Octal.format(m_start.val(), 1), Octal.format(m_end.val(), 1));

		//-- Which address bits may be varied: the ones below the start address's lowest set bit,
		//-- because anything above it would leave the range ({@code :521-534}).
		long varyMask = variablePartMask();
		Set<MemoryCell> written = new LinkedHashSet<>();
		boolean cancelled = false;

		pm.begin("Checking address lines ...", 2 * MAX_ADDRESS_BITS);
		try {
			log("Writing ...");
			for(int bitNo = 0; bitNo <= MAX_ADDRESS_BITS - 1; bitNo++) {
				if(pm.isCancelled()) {
					cancelled = true;
					break;
				}
				long addrValue = bitNo == 0 ? 0 : 1L << bitNo;
				addrValue = (m_start.val() & ~varyMask) + addrValue;
				if(addrValue < m_start.val() || addrValue > m_end.val())
					continue;
				if(phase != 1)
					addrValue = withHighBitsSet(addrValue);

				Address addr = Address.of(m_start.type(), addrValue);
				MemoryCell mc = cellAt(addr);
				if(mc == null)
					continue;
				//-- The data is the address, truncated to a word.
				int data = (int) (addrValue & WORD_MASK);
				mc.setEditValue(CellValue.of(data));
				m_console.deposit(addr, data);
				written.add(mc);
				pm.step(1);
			}
			return verify("address line", written, cancelled || pm.isCancelled(), pm,
				diff -> "This may be an error in address line " + lowestBitNumber(diff));
		} finally {
			pm.done();
		}
	}

	// -------------------------------------------------------------------------------------
	// 3. Data bits, chip by chip
	// -------------------------------------------------------------------------------------

	/**
	 * Write a moving-one pattern into sixteen words of every chip and read it back.
	 *
	 * <p>Ported from {@code TestDatabits} ({@code :615-750}). One chip usually supplies one or
	 * two data bits across a whole address range, so a dead chip shows up as the same bit wrong
	 * in every word of that range. Sixteen words per chip - one per data bit - is enough to see
	 * that, and is two orders of magnitude fewer accesses than testing the range.</p>
	 *
	 * <p>Phase 1 writes at the start of each chip, phase 2 writes the inverted pattern at the
	 * end of each chip, so between them every bit of every chip is seen both ways.</p>
	 */
	public MemoryTestResult testDataBits(int phase, ProgressMonitor pm) throws ConsoleException {
		m_log.clear();
		log("Test data bits, phase %d, addr range = %s..%s, chip size = %s", phase,
			Octal.format(m_start.val(), 1), Octal.format(m_end.val(), 1), Octal.format(m_chipSizeBytes, 1));

		Set<MemoryCell> written = new LinkedHashSet<>();
		boolean cancelled = false;
		int blockBytes = 2 * BLOCK_TEST_WORDS;
		pm.begin("Checking chips ...", 2 * BLOCK_TEST_WORDS * Math.max(1, chipsInRange()));
		try {
			long blockStart = m_start.val() + (phase == 1 ? 0 : m_chipSizeBytes - blockBytes);
			int blockNr = 0;
			while(blockStart + blockBytes - 2 <= m_end.val()) {
				if(pm.isCancelled()) {
					cancelled = true;
					break;
				}
				log("  Chip block %d: writing address range = %s .. %s", blockNr,
					Octal.format(blockStart, 1), Octal.format(blockStart + blockBytes - 2, 1));
				for(int i = 0; i < BLOCK_TEST_WORDS; i++) {
					long addrValue = blockStart + 2L * i;
					if(addrValue < m_start.val() || addrValue > m_end.val())
						continue;
					Address addr = Address.of(m_start.type(), addrValue);
					MemoryCell mc = cellAt(addr);
					if(mc == null)
						continue;                           // outside the group; nothing to test
					int data = dataBitPattern(phase, addrValue);
					mc.setEditValue(CellValue.of(data));
					m_console.deposit(addr, data);
					written.add(mc);
					pm.step(1);
					if(pm.isCancelled()) {
						cancelled = true;
						break;
					}
				}
				blockNr++;
				blockStart += m_chipSizeBytes;
			}
			return verify("data bit", written, cancelled || pm.isCancelled(), pm,
				diff -> "This may be an error in the data chip for bit " + lowestBitNumber(diff));
		} finally {
			pm.done();
		}
	}

	/**
	 * The pattern one word gets: a single one walking through the sixteen words of the block,
	 * inverted in phase 2. Ported from {@code getDataval} ({@code :641-652}).
	 */
	private static int dataBitPattern(int phase, long addrValue) {
		int index = (int) ((addrValue / 2) & 0xF);
		int value = 1 << index;
		return phase == 1 ? value : ~value & WORD_MASK;
	}

	// -------------------------------------------------------------------------------------
	// 4. Random
	// -------------------------------------------------------------------------------------

	/**
	 * Write random values to random addresses, then read them all back.
	 *
	 * <p>Ported from {@code TestRandom} ({@code :755-830}). The other three tests look for a
	 * specific class of fault with a pattern designed to expose it; this one looks for whatever
	 * they missed. Writing in random order and checking in address order matters: it is the
	 * ordering that catches a memory which remembers only the most recent access.</p>
	 */
	public MemoryTestResult testRandom(int count, Random random, ProgressMonitor pm) throws ConsoleException {
		m_log.clear();
		log("Random test, addr range = %s..%s: write %d cells, then check.",
			Octal.format(m_start.val(), 1), Octal.format(m_end.val(), 1), count);

		int words = (int) ((m_end.val() - m_start.val()) / 2) + 1;
		Set<MemoryCell> written = new LinkedHashSet<>();
		boolean cancelled = false;
		pm.begin("Testing random words ...", 2 * count);
		try {
			log("Writing ...");
			for(int i = 0; i < count; i++) {
				if(pm.isCancelled()) {
					cancelled = true;
					break;
				}
				long addrValue = m_start.val() + 2L * random.nextInt(words);
				Address addr = Address.of(m_start.type(), addrValue);
				MemoryCell mc = cellAt(addr);
				if(mc == null)
					continue;
				int data = random.nextInt(0x10000);
				mc.setEditValue(CellValue.of(data));
				m_console.deposit(addr, data);
				written.add(mc);
				pm.step(1);
			}
			return verify("random", written, cancelled || pm.isCancelled(), pm, diff -> null);
		} finally {
			pm.done();
		}
	}

	// -------------------------------------------------------------------------------------
	// Shared
	// -------------------------------------------------------------------------------------

	/**
	 * Read back everything that was written, in address order, and report what disagrees.
	 *
	 * <p>All three of the write-then-check tests end this way, and the ordering is deliberate in
	 * the original: written in whatever order the pattern needs, read in ascending address
	 * order.</p>
	 */
	private MemoryTestResult verify(String what, Set<MemoryCell> written, boolean cancelled,
		ProgressMonitor pm, java.util.function.IntFunction<String> hint) throws ConsoleException {
		List<MemoryTestResult.Mismatch> mismatches = new ArrayList<>();
		boolean stopped = cancelled;
		if(!stopped) {
			log("Checking ...");
			List<MemoryCell> ordered = new ArrayList<>(written);
			ordered.sort((a, b) -> Long.compareUnsigned(a.getAddr().val(), b.getAddr().val()));
			for(MemoryCell mc : ordered) {
				if(pm.isCancelled()) {
					stopped = true;
					break;
				}
				CellValue read = m_console.examine(mc.getAddr());
				mc.setPdpValue(read);
				pm.step(1);
				int expected = mc.getEditValue().wordOr(0);
				int actual = read.wordOr(-1);
				if(!read.isKnown() || actual != expected) {
					MemoryTestResult.Mismatch m = MemoryTestResult.Mismatch.of(mc.getAddr(), expected,
						read.isKnown() ? actual : 0);
					mismatches.add(m);
					log("Error: word at %s is %s, should be %s, diff mask = %s",
						mc.getAddr().toOctal(),
						read.isKnown() ? Octal.word(actual) : "not readable",
						Octal.word(expected), Octal.word(m.diffMask()));
					String extra = hint.apply(m.diffMask());
					if(extra != null)
						log("%s", extra);
				}
			}
		}
		if(stopped)
			log("Abort.");
		else if(mismatches.isEmpty())
			log("OK.");
		else
			log("There were errors.");
		return new MemoryTestResult(what, mismatches.isEmpty() && !stopped, stopped,
			List.copyOf(mismatches), 0, 0, getLog());
	}

	/** Deposit, read straight back, and keep the cell in step so a window can watch. */
	private int writeAndRead(Address addr, int value) throws ConsoleException {
		MemoryCell mc = cellAt(addr);
		if(mc != null)
			mc.setEditValue(CellValue.of(value));
		m_console.deposit(addr, value);
		CellValue read = m_console.examine(addr);
		if(mc != null)
			mc.setPdpValue(read);
		return read.wordOr(0);
	}

	private MemoryCell cellAt(Address addr) {
		return m_group == null ? null : m_group.findByAddress(addr);
	}

	private int chipsInRange() {
		long span = m_end.val() - m_start.val() + 2;
		return (int) Math.max(1, span / m_chipSizeBytes);
	}

	/**
	 * Which address bits this test may vary.
	 *
	 * <p>{@code :521-534}: a start address of zero means all of them; otherwise everything below
	 * its lowest set bit, because setting a bit above that would walk out of the range.</p>
	 */
	private long variablePartMask() {
		if(m_start.val() == 0)
			return -1L;
		long tmp = m_start.val();
		long mask = 0;
		while((tmp & 1) == 0) {
			tmp >>= 1;
			mask = (mask << 1) | 1;
		}
		return mask;
	}

	/** Set as many high address bits as still leave the address inside the range ({@code :551-561}). */
	private long withHighBitsSet(long addrValue) {
		long value = addrValue;
		int bit = 0;
		for(;;) {
			bit++;
			long next = value ^ (1L << bit);
			if(next < m_start.val() || next > m_end.val() || bit >= MAX_ADDRESS_BITS)
				return value;
			value = next;
		}
	}

	// -------------------------------------------------------------------------------------
	// Logging
	// -------------------------------------------------------------------------------------

	private void log(String format, Object... args) {
		String line = args.length == 0 ? format : String.format(Locale.ROOT, format, args);
		m_log.add(line);
		if(m_sink != null)
			m_sink.line(line);
	}

	/** The lowest set bit's number, or -1 for no bits at all. {@code getLowestBitNo}. */
	public static int lowestBitNumber(int value) {
		return value == 0 ? -1 : Integer.numberOfTrailingZeros(value);
	}

	/** {@code 0x001a} becomes {@code "4,3,1"}. Ported from {@code BitnumbersAsText}. */
	public static String bitNumbers(int value) {
		StringBuilder sb = new StringBuilder();
		for(int i = 31; i >= 0; i--) {
			if((value & (1 << i)) != 0) {
				if(sb.length() > 0)
					sb.append(',');
				sb.append(i);
			}
		}
		return sb.toString();
	}
}
