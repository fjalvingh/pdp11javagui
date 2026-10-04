package to.etc.pdp11.common.macro11.asm;

/**
 * A byte or word the program will hold, whose value may not be known yet.
 *
 * <p>The assembler reads the source once. Where a value depends on something defined further
 * down - a forward branch, a call to a subroutine below - the word is stored with its expression
 * and how the value goes into it, and patched once everything has been read and the sections
 * have addresses. A word whose value is known at once is simply one with nothing left to do.</p>
 */
final class CodeWord {
	/** How the expression's value becomes the stored value. */
	enum Encoding {
		/** The value itself, or {@link #getBits()} when there is no expression. */
		VALUE,

		/** Bits ORed with a small number that must fit in {@link #getFieldMax()}: EMT, TRAP, MARK, SPL. */
		FIELD,

		/** The distance from the end of this word to the target: PC-relative addressing. */
		DISPLACEMENT,

		/** A branch: bits ORed with a signed 8-bit word offset from the next instruction. */
		BRANCH,

		/** SOB: bits ORed with a 6-bit backward word offset from the next instruction. */
		SOB,

		/** {@code .LIMIT}: the lowest (bits 0) or highest (bits 1) address of the program. */
		LIMIT
	}

	/** What else the value must be checked for. */
	enum Check {
		NONE,

		/** A data byte: must fit in a byte. */
		BYTE_DATA,

		/** A data word: a register here is suspect. */
		DATA_WORD,

		/** The immediate operand of a byte instruction: only the low byte is used. */
		BYTE_IMMEDIATE,

		/** The address of a word operand: must be even. */
		WORD_ADDRESS,

		/** Where a jump goes: must be even. */
		JUMP_TARGET
	}

	private final ListingEntry m_entry;

	private final Section m_section;

	private final int m_offset;

	private final int m_size;

	private final Encoding m_encoding;

	private final int m_bits;

	private final int m_fieldMax;

	private final Expression m_expression;

	private final Check m_check;

	private final Location m_location;

	private int m_value;

	CodeWord(ListingEntry entry, Section section, int offset, int size, Encoding encoding, int bits, int fieldMax,
		Expression expression, Check check, Location location) {
		if(size != 1 && size != 2)
			throw new IllegalArgumentException("A code word is 1 or 2 bytes, not " + size);
		m_entry = entry;
		m_section = section;
		m_offset = offset;
		m_size = size;
		m_encoding = encoding;
		m_bits = bits;
		m_fieldMax = fieldMax;
		m_expression = expression;
		m_check = check;
		m_location = location;
		m_value = bits;
	}

	/** A byte or word whose value is known now. */
	static CodeWord constant(ListingEntry entry, Section section, int offset, int size, int value, Location location) {
		int v = size == 1 ? value & 0xFF : value & 0xFFFF;
		return new CodeWord(entry, section, offset, size, Encoding.VALUE, v, 0, null, Check.NONE, location);
	}

	ListingEntry getEntry() {
		return m_entry;
	}

	Section getSection() {
		return m_section;
	}

	int getOffset() {
		return m_offset;
	}

	int getSize() {
		return m_size;
	}

	Encoding getEncoding() {
		return m_encoding;
	}

	int getBits() {
		return m_bits;
	}

	int getFieldMax() {
		return m_fieldMax;
	}

	Expression getExpression() {
		return m_expression;
	}

	Check getCheck() {
		return m_check;
	}

	Location getLocation() {
		return m_location;
	}

	/** Where it is, once its section has been placed. */
	int getAddress() {
		return m_section.addressOf(m_offset);
	}

	/** The value, once resolved; until then the constant bits. */
	int getValue() {
		return m_value;
	}

	void setValue(int value) {
		m_value = m_size == 1 ? value & 0xFF : value & 0xFFFF;
	}
}
