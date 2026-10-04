package to.etc.pdp11.common.macro11.asm;

/**
 * A parsed MACRO-11 expression.
 *
 * <p>MACRO-11 has no operator precedence: {@code 1+2*3} is 9, evaluated left to right, and
 * angle brackets are the only way to group. The tree reflects that - a {@link Binary} always has
 * everything to its left as its left operand.</p>
 */
sealed interface Expression permits Expression.Constant, Expression.SymbolReference, Expression.Unary,
	Expression.Binary {

	/** Where the expression starts, for an error about it. */
	Location location();

	/** A value known when the expression was read: a number, or a symbol already defined. */
	record Constant(Value value, Location location) implements Expression {
	}

	/**
	 * A reference to a symbol that was not defined yet when it was read.
	 *
	 * @param key  the name in the symbol table; for a local label, qualified with its block
	 * @param name the name as written, for messages
	 */
	record SymbolReference(String key, String name, Location location) implements Expression {
	}

	record Unary(UnaryOperator operator, Expression operand, Location location) implements Expression {
	}

	record Binary(BinaryOperator operator, Expression left, Expression right, Location location)
		implements Expression {
	}

	enum UnaryOperator {
		/** {@code -x} */
		NEGATE,

		/** {@code ^Cx}, the one's complement. */
		COMPLEMENT,

		/** {@code %x}: the register numbered {@code x}. */
		REGISTER
	}

	enum BinaryOperator {
		ADD("+"),
		SUBTRACT("-"),
		MULTIPLY("*"),
		DIVIDE("/"),
		AND("&"),
		OR("!");

		private final String m_symbol;

		BinaryOperator(String symbol) {
			m_symbol = symbol;
		}

		String symbol() {
			return m_symbol;
		}
	}

	/** Whether this expression is completely known: nothing in it waits for a later definition. */
	default boolean isConstant() {
		return switch(this) {
			case Constant c -> true;
			case SymbolReference s -> false;
			case Unary u -> u.operand().isConstant();
			case Binary b -> b.left().isConstant() && b.right().isConstant();
		};
	}
}
