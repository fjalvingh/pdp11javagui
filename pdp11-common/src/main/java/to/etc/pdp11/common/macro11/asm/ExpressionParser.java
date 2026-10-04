package to.etc.pdp11.common.macro11.asm;

import to.etc.pdp11.common.macro11.asm.Evaluator.NotYetKnownException;
import to.etc.pdp11.common.macro11.asm.Expression.Binary;
import to.etc.pdp11.common.macro11.asm.Expression.BinaryOperator;
import to.etc.pdp11.common.macro11.asm.Expression.Constant;
import to.etc.pdp11.common.macro11.asm.Expression.SymbolReference;
import to.etc.pdp11.common.macro11.asm.Expression.Unary;
import to.etc.pdp11.common.macro11.asm.Expression.UnaryOperator;
import to.etc.pdp11.common.macro11.asm.Value.Absolute;
import to.etc.pdp11.common.macro11.asm.Value.Register;

import java.util.OptionalInt;

/**
 * Parses expressions and addressing modes from a {@link TokenReader}.
 *
 * <p>A symbol that already has a value when it is read is replaced by that value at once. That
 * is not an optimisation: a symbol defined with {@code =} can be given a new value further down,
 * and an expression means what its symbols meant where it was written. Only symbols that have no
 * value yet stay in the tree as {@link SymbolReference}s, to be looked up once everything has
 * been read - when they have the last value they were given, as in DEC's second pass.</p>
 */
final class ExpressionParser {
	private final TokenReader m_reader;

	private final AssemblyState m_state;

	/** The value of {@code .}: where the statement being parsed starts. */
	private final Value m_dot;

	ExpressionParser(TokenReader reader, AssemblyState state, Value dot) {
		m_reader = reader;
		m_state = state;
		m_dot = dot;
	}

	// -------------------------------------------------------------------------------------
	// Expressions
	// -------------------------------------------------------------------------------------

	/** An expression: terms joined by operators, evaluated from left to right. */
	Expression parseExpression() throws AsmException {
		Expression left = parseTerm();
		for(;;) {
			BinaryOperator op = binaryOperator(m_reader.peek());
			if(op == null)
				return left;
			Token t = m_reader.next();
			Expression right = parseTerm();
			left = new Binary(op, left, right, t.location());
		}
	}

	private static BinaryOperator binaryOperator(Token t) {
		return switch(t.kind()) {
			case PLUS -> BinaryOperator.ADD;
			case MINUS -> BinaryOperator.SUBTRACT;
			case STAR -> BinaryOperator.MULTIPLY;
			case SLASH -> BinaryOperator.DIVIDE;
			case AMPERSAND -> BinaryOperator.AND;
			case EXCLAMATION -> BinaryOperator.OR;
			default -> null;
		};
	}

	/** One term: a number, a symbol, a unary operator and its term, or {@code <expression>}. */
	Expression parseTerm() throws AsmException {
		return parseTerm(m_state.getRadix());
	}

	private Expression parseTerm(int radix) throws AsmException {
		Token t = m_reader.next();
		return switch(t.kind()) {
			case PLUS -> parseTerm(radix);
			case MINUS -> new Unary(UnaryOperator.NEGATE, parseTerm(radix), t.location());
			case PERCENT -> new Unary(UnaryOperator.REGISTER, parseTerm(radix), t.location());
			case CIRCUMFLEX -> parseCircumflex(t);
			case LEFT_ANGLE -> {
				Expression inner = parseExpression();
				expect(TokenKind.RIGHT_ANGLE, "'>' to close the '<' at column " + t.location().column());
				yield inner;
			}
			case NUMBER -> new Constant(new Absolute(parseNumber(t, radix)), t.location());
			case CHARACTER -> new Constant(new Absolute(t.value()), t.location());
			case LOCAL_LABEL -> symbol(t);
			case SYMBOL -> {
				if(radix == 16 && isHexWord(t.text()))
					yield new Constant(new Absolute(parseNumber(t, 16)), t.location());
				yield symbol(t);
			}
			case ERROR -> throw new AsmException(t.location(), t.text());
			default -> throw new AsmException(t.location(), "Expected a value but found " + t.describe());
		};
	}

	private Expression parseCircumflex(Token t) throws AsmException {
		return switch(t.text()) {
			case "C" -> new Unary(UnaryOperator.COMPLEMENT, parseTerm(), t.location());
			case "B" -> parseTerm(2);
			case "O" -> parseTerm(8);
			case "D" -> parseTerm(10);
			case "X" -> parseTerm(16);
			case "R" -> {
				//-- Up to three characters, as they are; or a bracketed string.
				String text;
				if(m_reader.peekCharacter() == '<') {
					TokenReader.Argument a = m_reader.readArgument();
					text = a.text();
				} else {
					text = m_reader.readRad50Characters(3);
				}
				yield new Constant(new Absolute(rad50(text, t.location())), t.location());
			}
			case "F" -> {
				String text = m_reader.readWord();
				int[] words = PdpFloat.parse(text, 1, t.location());
				yield new Constant(new Absolute(words[0]), t.location());
			}
			default -> throw new AsmException(t.location(), "'^" + t.text() + "' is not an operator; "
				+ "MACRO-11 knows ^B, ^C, ^D, ^F, ^O, ^R and ^X");
		};
	}

	private static int rad50(String text, Location at) throws AsmException {
		String s = text.length() > 3 ? text.substring(0, 3) : text;
		return Rad50.encodeWord(s).orElseThrow(() -> new AsmException(at, "'" + text + "' cannot be written in RAD50"));
	}

	private Expression symbol(Token t) throws AsmException {
		String name = t.text();
		if(name.equals("."))
			return new Constant(m_dot, t.location());
		int register = SymbolTable.registerNumber(name);
		if(register >= 0)
			return new Constant(new Register(register), t.location());

		Symbol s = m_state.getSymbols().get(name);
		s.markReferenced();
		if(s.hasValue())
			return new Constant(s.getValue(), t.location());
		//-- Not known yet. That includes an opcode used as a value, which means its instruction
		//-- word - unless a label of that name follows, as RETURN: often does. So that is only
		//-- decided at the end, by the evaluator.
		return new SymbolReference(s.getKey(), name, t.location());
	}

	private Token expect(TokenKind kind, String what) throws AsmException {
		Token t = m_reader.peek();
		if(!t.is(kind))
			throw new AsmException(t.location(), "Expected " + what + " but found " + t.describe());
		return m_reader.next();
	}

	// -------------------------------------------------------------------------------------
	// Numbers
	// -------------------------------------------------------------------------------------

	/**
	 * A number in the given radix, or in decimal when it ends in a point.
	 *
	 * <p>The token is the whole run of letters and digits, so {@code 8.5} and {@code 128} in
	 * octal are one token each and one error each, not a number followed by another.</p>
	 */
	static int parseNumber(Token t, int radix) throws AsmException {
		String text = t.text();
		int r = radix;
		if(text.endsWith(".")) {
			text = text.substring(0, text.length() - 1);
			r = 10;
		}
		if(text.isEmpty())
			throw new AsmException(t.location(), "'" + t.text() + "' is not a number");
		if(text.indexOf('.') >= 0)
			throw new AsmException(t.location(), "'" + t.text() + "' is not an integer; "
				+ "a floating-point number is written with ^F, .FLT2 or .FLT4");
		long value = 0;
		for(int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			int d = Character.digit(c, r);
			if(d < 0) {
				String hint = r == 8 && (c == '8' || c == '9') ? "; write " + text + ". for a decimal number" : "";
				throw new AsmException(t.location(), "'" + t.text() + "' is not a valid " + radixName(r)
					+ " number: '" + c + "' is not a digit" + hint);
			}
			value = value * r + d;
			if(value > 0xFFFF)
				throw new AsmException(t.location(), "'" + t.text() + "' does not fit in 16 bits");
		}
		return (int) value;
	}

	private static String radixName(int radix) {
		return switch(radix) {
			case 2 -> "binary";
			case 8 -> "octal";
			case 10 -> "decimal";
			case 16 -> "hexadecimal";
			default -> "radix " + radix;
		};
	}

	private static boolean isHexWord(String s) {
		for(int i = 0; i < s.length(); i++) {
			if(Character.digit(s.charAt(i), 16) < 0)
				return false;
		}
		return true;
	}

	// -------------------------------------------------------------------------------------
	// Addressing modes
	// -------------------------------------------------------------------------------------

	/**
	 * One general operand.
	 *
	 * <pre>
	 *   R            register                    @R or (R)   register deferred
	 *   (R)+         autoincrement               @(R)+       autoincrement deferred
	 *   -(R)         autodecrement               @-(R)       autodecrement deferred
	 *   X(R)         index                       @X(R)       index deferred; @(R) is @0(R)
	 *   #X           immediate                   @#X         absolute
	 *   X            PC-relative                 @X          PC-relative deferred
	 * </pre>
	 */
	Operand parseOperand() throws AsmException {
		Location at = m_reader.here();
		boolean deferred = m_reader.accept(TokenKind.AT);
		Token t = m_reader.peek();

		if(t.is(TokenKind.HASH)) {
			m_reader.next();
			Expression e = parseExpression();
			return new Operand(deferred ? 3 : 2, 7, e, Operand.Use.IMMEDIATE_OR_ABSOLUTE, at);
		}

		if(t.is(TokenKind.MINUS) && m_reader.peek(1).is(TokenKind.LEFT_PAREN)) {
			m_reader.next();
			m_reader.next();
			int register = parseRegisterAndClose();
			return new Operand(deferred ? 5 : 4, register, null, Operand.Use.NONE, at);
		}

		if(t.is(TokenKind.LEFT_PAREN)) {
			m_reader.next();
			int register = parseRegisterAndClose();
			if(m_reader.accept(TokenKind.PLUS))
				return new Operand(deferred ? 3 : 2, register, null, Operand.Use.NONE, at);
			if(deferred)
				return new Operand(7, register, new Constant(new Absolute(0), at), Operand.Use.INDEX, at);
			return new Operand(1, register, null, Operand.Use.NONE, at);
		}

		Expression e = parseExpression();
		if(m_reader.peek().is(TokenKind.LEFT_PAREN)) {
			m_reader.next();
			int register = parseRegisterAndClose();
			return new Operand(deferred ? 7 : 6, register, e, Operand.Use.INDEX, at);
		}
		OptionalInt register = registerOf(e);
		if(register.isPresent())
			return new Operand(deferred ? 1 : 0, register.getAsInt(), null, Operand.Use.NONE, at);
		if(!deferred && m_state.isEnabled(AssemblyState.Option.AMA))
			return new Operand(3, 7, e, Operand.Use.IMMEDIATE_OR_ABSOLUTE, at);
		return new Operand(deferred ? 7 : 6, 7, e, Operand.Use.PC_RELATIVE, at);
	}

	/**
	 * The register an expression is, if it is one. Not only {@code R3}: {@code %3}, {@code %X}
	 * and {@code R2+1} are registers too, which only evaluating shows.
	 */
	private OptionalInt registerOf(Expression e) {
		if(!e.isConstant())
			return OptionalInt.empty();
		try {
			return m_state.getEvaluator().evaluateNow(e) instanceof Register r ? OptionalInt.of(r.number()) : OptionalInt.empty();
		} catch(NotYetKnownException | AsmException x) {
			//-- Not a register, and the error, if it is one, is reported where the value is used.
			return OptionalInt.empty();
		}
	}

	private int parseRegisterAndClose() throws AsmException {
		int register = parseRegister();
		expect(TokenKind.RIGHT_PAREN, "')' after the register");
		return register;
	}

	/**
	 * An expression that must be a register: {@code R3}, {@code SP}, {@code %3}, {@code %X+1};
	 * or, with a warning, a plain number from 0 to 7.
	 */
	int parseRegister() throws AsmException {
		return parseRegister(false);
	}

	/**
	 * A register, where a plain number 0-7 is the normal way to write it: the accumulator of a
	 * floating-point instruction, which sources define as {@code AC0=0}.
	 */
	int parseAccumulator() throws AsmException {
		return parseRegister(true);
	}

	private int parseRegister(boolean numberIsNormal) throws AsmException {
		Location at = m_reader.here();
		Expression e = parseExpression();
		Value v;
		try {
			v = m_state.getEvaluator().evaluateNow(e);
		} catch(NotYetKnownException x) {
			throw new AsmException(at, "A register is needed here, and " + x.getMessage());
		}
		if(v instanceof Register r)
			return r.number();
		if(v instanceof Absolute a && a.value() <= 7) {
			if(!numberIsNormal)
				m_state.getDiagnostics().warning(WarningKind.NUMBER_AS_REGISTER, at,
					"A plain number as a register; write R" + a.value() + " or %" + a.value());
			return a.value();
		}
		throw new AsmException(at, "A register is needed here (R0-R5, SP, PC or %n)");
	}
}
