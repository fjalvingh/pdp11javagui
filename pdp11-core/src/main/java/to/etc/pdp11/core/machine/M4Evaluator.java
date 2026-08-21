package to.etc.pdp11.core.machine;

/**
 * The integer expression evaluator behind m4's {@code eval}.
 *
 * <p>Only what the machine descriptions actually use, which PLAN.md §7 measured: {@code +},
 * {@code -}, {@code *}, parentheses and unary minus, over C-style integer literals. All three
 * call sites are address arithmetic:</p>
 *
 * <pre>
 * define(_offset,`eval(0$1+0$2,8)')
 * Boot ROM $1=eval(`0173000+($1-1)*128',8)
 * CSR=eval(0172100+ (($1 - 1) * 2), 8)
 * </pre>
 *
 * <p>Division, modulo and the bitwise and comparison operators are implemented anyway - they
 * are three lines each and their absence would be a puzzling failure rather than an obvious
 * one - but the shift, logical and ternary operators are not. See
 * {@code machines/README.md}.</p>
 *
 * <p><b>The leading zero matters.</b> {@code _offset} is written as
 * {@code eval(0$1+0$2,8)} precisely so that its arguments, which are octal PDP-11 addresses
 * written without a prefix, get the C convention that {@code 0} means octal put back in front
 * of them. Getting this wrong turns {@code 0177560} into decimal 177560 and every device
 * register lands somewhere else.</p>
 */
final class M4Evaluator {
	private final String m_text;

	private int m_pos;

	private M4Evaluator(String text) {
		m_text = text;
	}

	/** Evaluate an m4 {@code eval} expression. */
	static long evaluate(String expression) {
		M4Evaluator e = new M4Evaluator(expression);
		long v = e.parseExpression();
		e.skipSpace();
		if(e.m_pos < e.m_text.length())
			throw new M4Exception("eval: unexpected '" + e.m_text.charAt(e.m_pos)
				+ "' in expression \"" + expression + "\"");
		return v;
	}

	/** Format for m4's {@code eval(expr, radix)}. Radix 8 is the only one used here. */
	static String format(long value, int radix) {
		if(radix < 2 || radix > 36)
			throw new M4Exception("eval: radix must be 2..36, got " + radix);
		return Long.toString(value, radix);
	}

	private long parseExpression() {
		return parseAdditive();
	}

	private long parseAdditive() {
		long v = parseMultiplicative();
		while(true) {
			skipSpace();
			char c = peek();
			if(c == '+') {
				m_pos++;
				v += parseMultiplicative();
			} else if(c == '-') {
				m_pos++;
				v -= parseMultiplicative();
			} else {
				return v;
			}
		}
	}

	private long parseMultiplicative() {
		long v = parseUnary();
		while(true) {
			skipSpace();
			char c = peek();
			if(c == '*') {
				m_pos++;
				v *= parseUnary();
			} else if(c == '/') {
				m_pos++;
				long d = parseUnary();
				if(d == 0)
					throw new M4Exception("eval: division by zero in \"" + m_text + "\"");
				v /= d;
			} else if(c == '%') {
				m_pos++;
				long d = parseUnary();
				if(d == 0)
					throw new M4Exception("eval: modulo by zero in \"" + m_text + "\"");
				v %= d;
			} else {
				return v;
			}
		}
	}

	private long parseUnary() {
		skipSpace();
		char c = peek();
		if(c == '-') {
			m_pos++;
			return -parseUnary();
		}
		if(c == '+') {
			m_pos++;
			return parseUnary();
		}
		if(c == '~') {
			m_pos++;
			return ~parseUnary();
		}
		return parsePrimary();
	}

	private long parsePrimary() {
		skipSpace();
		if(peek() == '(') {
			m_pos++;
			long v = parseExpression();
			skipSpace();
			if(peek() != ')')
				throw new M4Exception("eval: missing ')' in \"" + m_text + "\"");
			m_pos++;
			return v;
		}
		return parseNumber();
	}

	/**
	 * A C-style integer literal: {@code 0x} hex, a leading {@code 0} octal, anything else
	 * decimal.
	 */
	private long parseNumber() {
		skipSpace();
		int start = m_pos;
		int radix = 10;
		if(peek() == '0') {
			m_pos++;
			char c = peek();
			if(c == 'x' || c == 'X') {
				m_pos++;
				start = m_pos;
				radix = 16;
			} else {
				start = m_pos - 1;
				radix = 8;
			}
		}
		int digitsStart = m_pos;
		while(m_pos < m_text.length() && Character.digit(m_text.charAt(m_pos), radix) >= 0) {
			m_pos++;
		}
		if(m_pos == digitsStart) {
			if(radix == 8)
				return 0;                                   // a bare "0"
			throw new M4Exception("eval: expected a number at position " + start
				+ " of \"" + m_text + "\"");
		}
		String digits = m_text.substring(radix == 8 ? digitsStart : start, m_pos);
		try {
			return Long.parseLong(digits, radix);
		} catch(NumberFormatException x) {
			throw new M4Exception("eval: '" + digits + "' is not a base-" + radix + " number", x);
		}
	}

	private void skipSpace() {
		while(m_pos < m_text.length() && Character.isWhitespace(m_text.charAt(m_pos))) {
			m_pos++;
		}
	}

	private char peek() {
		return m_pos < m_text.length() ? m_text.charAt(m_pos) : '\0';
	}
}
