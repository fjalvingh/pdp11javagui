package to.etc.pdp11.common.macro11.asm;

import java.util.ArrayList;
import java.util.List;

/**
 * One source line as it will appear in the listing: its text, and what it produced.
 *
 * <p>A line can show a value instead of code - the value given to a symbol, the new location
 * after {@code .=}, the condition of a {@code .IF} - and that value can depend on things only
 * known at the end, so it is kept as an expression and worked out when the listing is
 * written.</p>
 */
final class ListingEntry {
	private final Location m_location;

	private final String m_text;

	private final int m_lineNumber;

	private final List<CodeWord> m_words = new ArrayList<>();

	private Expression m_shownValue;

	ListingEntry(Location location, String text, int lineNumber) {
		m_location = location;
		m_text = text;
		m_lineNumber = lineNumber;
	}

	Location getLocation() {
		return m_location;
	}

	String getText() {
		return m_text;
	}

	/** The number in the listing's first column: the line within its file or expansion. */
	int getLineNumber() {
		return m_lineNumber;
	}

	List<CodeWord> getWords() {
		return m_words;
	}

	void addWord(CodeWord w) {
		m_words.add(w);
	}

	Expression getShownValue() {
		return m_shownValue;
	}

	void showValue(Expression value) {
		m_shownValue = value;
	}

	void showValue(Value value) {
		m_shownValue = new Expression.Constant(value, m_location);
	}
}
