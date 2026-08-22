package to.etc.pdp11.core.macro11;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What {@link Macro11ListingParser} made of a MACRO-11 listing: the code, the errors, and the
 * three-way relation between source lines, listing lines and memory words.
 *
 * <p>The relation is the thing this type exists to hold, and the Pascal states it at the top of
 * {@code FormMacro11ListingU.pas}:</p>
 *
 * <pre>source line 1:n listing line 1:n memory cell</pre>
 *
 * <p>One source line can produce several listing lines - a {@code .ascii} of five characters
 * needs two, because only three byte columns fit - and one listing line can produce several
 * words. Both directions are needed: the PC arrives as an <i>address</i> and has to become a
 * listing line to highlight ({@link #listingLineOfAddress}), and an error arrives as a
 * <i>source</i> line and has to become the listing lines it produced
 * ({@link #listingLinesForSourceLine}).</p>
 *
 * <p>The code itself lives in a {@link MemoryCellGroup}, which is where every other window in
 * this application expects to find memory. Each cell carries
 * {@link MemoryCell#getListingLineNr()}, which is the foreign key back into
 * {@link #getLines()}.</p>
 */
public final class Macro11Listing {
	/** What kind of thing the assembler, or this parser, objected to. */
	public enum ProblemKind {
		/** MACRO-11 printed a diagnostic line. */
		ERROR,

		/**
		 * A value carries the {@code G} suffix: a global symbol the assembler could not resolve.
		 *
		 * <p>The Pascal calls this out as its own case and is right to
		 * ({@code FormMacro11ListingU.pas:509-515}): the assembler does not consider it an error
		 * and prints no diagnostic, but the word it emitted is a zero where an address should
		 * be, so the program will not run. Almost always a typo in a symbol name.</p>
		 */
		UNRESOLVED_GLOBAL,

		/** The listing held something this parser does not understand. */
		UNKNOWN_SUFFIX
	}

	/**
	 * One complaint, and where it came from.
	 *
	 * @param kind        what sort
	 * @param file        the file MACRO-11 named, or {@code ""}
	 * @param sourceLine  the 1-based source line, or -1 if unknown
	 * @param listingLine the 0-based index into {@link #getLines()} where this was found
	 * @param message     what to show
	 */
	public record Problem(ProblemKind kind, String file, int sourceLine, int listingLine, String message) {
		/** How this reads in a status bar or a dialog. */
		public String describe() {
			return sourceLine > 0
				? "MACRO-11 error in line " + sourceLine + ": \"" + message + "\""
				: "MACRO-11: " + message;
		}
	}

	private final List<String> m_lines;

	/**
	 * For each listing line, the 1-based source line that produced it; 0 where nothing has.
	 *
	 * <p>A listing line with no line number of its own - the continuation of a long
	 * {@code .ascii}, say - belongs to the last source line seen, which is what makes a
	 * continuation highlight along with the line it continues.</p>
	 */
	private final int[] m_sourceLineOf;

	private final List<Problem> m_problems;

	private final MemoryCellGroup m_group;

	Macro11Listing(List<String> lines, int[] sourceLineOf, List<Problem> problems, MemoryCellGroup group) {
		m_lines = List.copyOf(lines);
		m_sourceLineOf = sourceLineOf;
		m_problems = List.copyOf(problems);
		m_group = group;
	}

	/** The listing, one entry per line, as it will be shown. */
	public List<String> getLines() {
		return m_lines;
	}

	/** The assembled code. Values are <b>edit</b> values: the machine has not been told yet. */
	public MemoryCellGroup getGroup() {
		return m_group;
	}

	public List<Problem> getProblems() {
		return m_problems;
	}

	/**
	 * The first complaint, or null.
	 *
	 * <p>The one the Pascal keeps - {@code FirstErrorMsg}/{@code FirstErrorLineNr} - and the one
	 * worth putting in front of the user, since a MACRO-11 error usually cascades.</p>
	 */
	public Problem getFirstProblem() {
		return m_problems.isEmpty() ? null : m_problems.get(0);
	}

	public boolean isOk() {
		return m_problems.isEmpty();
	}

	/** How many words of code came out. */
	public int getWordCount() {
		return m_group.size();
	}

	/**
	 * The lowest address in the code, or null when there is none.
	 *
	 * <p>Which is what the Pascal shows as the program's start address
	 * ({@code FormMacro11CodeU.pas:96}), reading it off cell 0. Reading it off the range instead
	 * means an assembler that emitted its {@code .asect} out of order still gives the right
	 * answer.</p>
	 */
	public Address getStartAddress() {
		return m_group.isEmpty() ? null : Address.of(m_group.getType(), m_group.getRange().lo());
	}

	/** The 1-based source line that produced this 0-based listing line, or 0. */
	public int sourceLineOfListingLine(int listingLine) {
		if(listingLine < 0 || listingLine >= m_sourceLineOf.length)
			return 0;
		return m_sourceLineOf[listingLine];
	}

	/**
	 * Every listing line produced by one 1-based source line, in order.
	 *
	 * <p>{@code setErrorMark} ({@code FormMacro11ListingU.pas:559-577}) - which walks the array
	 * backwards and appends, so its "scroll to the first one" ends up scrolling to the last one
	 * it happens to visit. Here the list is in order and the caller picks an end.</p>
	 */
	public List<Integer> listingLinesForSourceLine(int sourceLine) {
		if(sourceLine <= 0)
			return List.of();
		List<Integer> l = new ArrayList<>();
		for(int i = 0; i < m_sourceLineOf.length; i++) {
			if(m_sourceLineOf[i] == sourceLine)
				l.add(i);
		}
		return Collections.unmodifiableList(l);
	}

	/**
	 * The 0-based listing line holding the word at this address, or -1.
	 *
	 * <p>This is {@code setPCMark} ({@code :540-556}): the machine stopped somewhere, and the
	 * question is which line of the program that is. Nothing in the window has to search - the
	 * parser already wrote the listing line into every cell it created.</p>
	 */
	public int listingLineOfAddress(Address addr) {
		if(addr == null)
			return -1;
		//-- A PC is a virtual address and the code group holds virtual addresses, but the two
		//-- can still be expressed at different widths; compare at the group's own.
		Address at = addr.type() == m_group.getType() ? addr : Address.of(m_group.getType(), addr.val());
		MemoryCell mc = m_group.findByAddress(at);
		return mc == null ? -1 : mc.getListingLineNr();
	}

	@Override
	public String toString() {
		return "Macro11Listing[" + m_lines.size() + " lines, " + getWordCount() + " words, "
			+ m_problems.size() + " problems]";
	}
}
