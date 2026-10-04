package to.etc.pdp11.common.macro11.asm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Writes the listing in the layout of the C {@code macro11}.
 *
 * <pre>
 *        3 001000 000001  000002  000003  A:	.WORD	1,2,3,4,5
 *          001006 000004  000005
 *        4 001012    001     002     003  	.BYTE	1,2,3
 *        7 000005                         B=5
 * x.mac:6: ***ERROR Instruction at an odd address
 * </pre>
 *
 * <p>Columns 1-8 are the line number, 10-15 the address or a value, and from 17 come up to three
 * items of eight columns - a word as six octal digits, a byte as three, right-aligned - after
 * which the source starts at column 41. More items go on continuation lines that have only an
 * address. A diagnostic is a line of its own, before the line it is about, and is not indented,
 * which is how a reader of the listing tells it from the rest.</p>
 */
final class ListingWriter {
	/** Where the items start. */
	private static final int ITEMS_COLUMN = 16;

	/** Where the source text starts. */
	private static final int SOURCE_COLUMN = 40;

	private static final int ITEMS_PER_LINE = 3;

	/** What was written, and where each thing ended up. */
	record Written(List<String> lines, int[] sourceLineOf, Map<CodeWord, Integer> wordLines,
		Map<Diagnostic, Integer> diagnosticLines) {
	}

	private final List<String> m_lines = new ArrayList<>();

	private final List<Integer> m_sourceLines = new ArrayList<>();

	private final Map<CodeWord, Integer> m_wordLines = new IdentityHashMap<>();

	private final Map<Diagnostic, Integer> m_diagnosticLines = new IdentityHashMap<>();

	/**
	 * @param shownValue the number a line shows instead of code, or null when it cannot be known
	 */
	Written write(List<ListingEntry> entries, List<Diagnostic> diagnostics, Function<ListingEntry, Integer> shownValue) {
		Map<Location, List<Diagnostic>> byLine = new HashMap<>();
		for(Diagnostic d : diagnostics)
			byLine.computeIfAbsent(d.location().lineOnly(), k -> new ArrayList<>()).add(d);

		for(ListingEntry e : entries) {
			int sourceLine = e.getLocation().root().line();
			List<Diagnostic> ds = byLine.remove(e.getLocation().lineOnly());
			if(ds != null) {
				for(Diagnostic d : ds)
					diagnosticLine(d);
			}
			entry(e, sourceLine, shownValue.apply(e));
		}

		//-- Anything not about a listed line: the end of the source, a library's macro.
		for(Diagnostic d : diagnostics) {
			if(!m_diagnosticLines.containsKey(d))
				diagnosticLine(d);
		}

		int[] sourceLineOf = new int[m_sourceLines.size()];
		for(int i = 0; i < sourceLineOf.length; i++)
			sourceLineOf[i] = m_sourceLines.get(i);
		return new Written(List.copyOf(m_lines), sourceLineOf, m_wordLines, m_diagnosticLines);
	}

	private void diagnosticLine(Diagnostic d) {
		Location root = d.location().root();
		m_diagnosticLines.put(d, m_lines.size());
		add(root.source() + ":" + root.line() + ": ***" + d.severity() + " " + d.describe(), root.line());
	}

	private void entry(ListingEntry e, int sourceLine, Integer shown) {
		String number = "  " + pad(Integer.toString(e.getLineNumber()), 6);
		List<CodeWord> words = e.getWords();

		if(words.isEmpty() || shown != null) {
			StringBuilder sb = new StringBuilder(number);
			if(shown != null)
				sb.append(' ').append(octal(shown, 6));
			add(padTo(sb, SOURCE_COLUMN).append(e.getText()).toString(), sourceLine);
			if(!words.isEmpty())
				items(words, null, sourceLine);
			return;
		}
		int first = m_lines.size();
		items(words, number, sourceLine);
		m_lines.set(first, m_lines.get(first) + e.getText());
	}

	/**
	 * The items, three to a line. The first line has {@code number} in front of it, or nothing
	 * when it is null; a line also ends where the addresses are not consecutive.
	 */
	private void items(List<CodeWord> words, String number, int sourceLine) {
		StringBuilder sb = null;
		int inLine = 0;
		int next = -1;
		String prefix = number;
		for(CodeWord w : words) {
			if(sb == null || inLine == ITEMS_PER_LINE || w.getAddress() != next) {
				if(sb != null)
					add(padTo(sb, SOURCE_COLUMN).toString(), sourceLine);
				sb = new StringBuilder(prefix == null ? " ".repeat(8) : prefix);
				prefix = null;
				sb.append(' ').append(octal(w.getAddress(), 6));
				padTo(sb, ITEMS_COLUMN);
				inLine = 0;
			}
			m_wordLines.put(w, m_lines.size());
			if(w.getSize() == 2)
				sb.append(octal(w.getValue(), 6)).append("  ");
			else
				sb.append("   ").append(octal(w.getValue(), 3)).append("  ");
			inLine++;
			next = w.getAddress() + w.getSize();
		}
		if(sb != null)
			add(padTo(sb, SOURCE_COLUMN).toString(), sourceLine);
	}

	private void add(String line, int sourceLine) {
		m_lines.add(line);
		m_sourceLines.add(sourceLine);
	}

	private static StringBuilder padTo(StringBuilder sb, int column) {
		while(sb.length() < column)
			sb.append(' ');
		return sb;
	}

	private static String pad(String s, int width) {
		return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
	}

	private static String octal(int value, int digits) {
		String s = Integer.toOctalString(value & (digits == 3 ? 0xFF : 0xFFFF));
		return s.length() >= digits ? s : "0".repeat(digits - s.length()) + s;
	}
}
