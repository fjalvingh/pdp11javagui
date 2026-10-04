package to.etc.pdp11.common.macro11.asm;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * What an assembly produced: the program, the listing, and every error and warning.
 *
 * <p>The listing is laid out as the C {@code macro11} lays it out, so that a saved listing can
 * be read back by {@code Macro11ListingParser} and compared with one the C assembler made. The
 * relation between it and the source and the program is kept here too, so nobody has to parse
 * it to find out which line produced which word.</p>
 */
public final class AssemblyResult {
	/**
	 * One word of the program.
	 *
	 * @param address     where it goes, always even
	 * @param value       the 16-bit value
	 * @param listingLine the 0-based listing line it is shown on
	 */
	public record Word(int address, int value, int listingLine) {
	}

	private final List<Diagnostic> m_diagnostics;

	private final Map<Diagnostic, Integer> m_diagnosticLines;

	private final List<String> m_listing;

	private final int[] m_sourceLineOf;

	private final List<Word> m_words;

	private final Integer m_transferAddress;

	private final Map<String, Integer> m_symbols;

	private final String m_title;

	AssemblyResult(List<Diagnostic> diagnostics, Map<Diagnostic, Integer> diagnosticLines, List<String> listing,
		int[] sourceLineOf, List<Word> words, Integer transferAddress, Map<String, Integer> symbols, String title) {
		m_diagnostics = List.copyOf(diagnostics);
		m_diagnosticLines = new IdentityHashMap<>(diagnosticLines);
		m_listing = List.copyOf(listing);
		m_sourceLineOf = sourceLineOf.clone();
		m_words = List.copyOf(words);
		m_transferAddress = transferAddress;
		m_symbols = Map.copyOf(symbols);
		m_title = title;
	}

	/** Every error and warning, in the order of the source. */
	public List<Diagnostic> getDiagnostics() {
		return m_diagnostics;
	}

	public List<Diagnostic> getErrors() {
		return m_diagnostics.stream().filter(Diagnostic::isError).toList();
	}

	public List<Diagnostic> getWarnings() {
		return m_diagnostics.stream().filter(d -> !d.isError()).toList();
	}

	public boolean hasErrors() {
		return m_diagnostics.stream().anyMatch(Diagnostic::isError);
	}

	/** The 0-based listing line a diagnostic is printed on. */
	public int listingLineOf(Diagnostic d) {
		Integer i = m_diagnosticLines.get(d);
		return i == null ? -1 : i;
	}

	/** The listing, one entry per line. */
	public List<String> getListing() {
		return m_listing;
	}

	/**
	 * The 1-based line of the source file that a listing line comes from; 0 for none.
	 *
	 * <p>A line a macro produced belongs to the line that called the macro, and a line of an
	 * included file to the {@code .INCLUDE}: those are the lines an editor of the source has.</p>
	 */
	public int sourceLineOf(int listingLine) {
		if(listingLine < 0 || listingLine >= m_sourceLineOf.length)
			return 0;
		return m_sourceLineOf[listingLine];
	}

	/** The program, a word per address, in the order it was assembled. */
	public List<Word> getWords() {
		return m_words;
	}

	/** The start address {@code .END} gave, if it gave one. */
	public OptionalInt getTransferAddress() {
		return m_transferAddress == null ? OptionalInt.empty() : OptionalInt.of(m_transferAddress);
	}

	/** The values of the user's symbols, local labels excluded. */
	public Map<String, Integer> getSymbols() {
		return m_symbols;
	}

	/** The name {@code .TITLE} gave, or empty. */
	public String getTitle() {
		return m_title;
	}

	@Override
	public String toString() {
		return "AssemblyResult[" + m_words.size() + " words, " + getErrors().size() + " errors, "
			+ getWarnings().size() + " warnings]";
	}
}
