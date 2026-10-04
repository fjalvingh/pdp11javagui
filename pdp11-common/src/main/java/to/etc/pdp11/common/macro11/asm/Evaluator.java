package to.etc.pdp11.common.macro11.asm;

import to.etc.pdp11.common.macro11.asm.Expression.Binary;
import to.etc.pdp11.common.macro11.asm.Expression.BinaryOperator;
import to.etc.pdp11.common.macro11.asm.Expression.Constant;
import to.etc.pdp11.common.macro11.asm.Expression.SymbolReference;
import to.etc.pdp11.common.macro11.asm.Expression.Unary;
import to.etc.pdp11.common.macro11.asm.Value.Absolute;
import to.etc.pdp11.common.macro11.asm.Value.Register;
import to.etc.pdp11.common.macro11.asm.Value.Relocatable;

import java.util.function.IntBinaryOperator;

/**
 * Works out the value of an {@link Expression}.
 *
 * <p>All arithmetic is on 16-bit words. A result that fits in neither the signed nor the
 * unsigned reading of 16 bits is kept to its low 16 bits and warned about; {@code -1+1} is
 * fine, {@code 100000*2} is not. Division is unsigned and division by zero is an error.</p>
 *
 * <p>Relocatable values can be added to and subtracted from absolute ones, and two in the same
 * section subtracted from each other, before anything has an address. Anything else needs the
 * sections placed, and is only possible in the final evaluation, once the whole source has been
 * read.</p>
 */
final class Evaluator {
	/**
	 * The value cannot be known yet: a symbol in it has no value, or it needs the address of
	 * something in a section that has not been placed.
	 *
	 * <p>Not an error by itself. Where the value is only needed at the end it is simply worked
	 * out then; where it is needed now - to decide how much space something takes - the caller
	 * turns this into an error.</p>
	 */
	static final class NotYetKnownException extends Exception {
		private final Location m_location;

		private final SymbolReference m_reference;

		NotYetKnownException(SymbolReference reference) {
			super(reference.name() + " is not defined", null, false, false);
			m_location = reference.location();
			m_reference = reference;
		}

		NotYetKnownException(Location location, String message) {
			super(message, null, false, false);
			m_location = location;
			m_reference = null;
		}

		Location getLocation() {
			return m_location;
		}

		/** The symbol that has no value, or null when the problem is placement. */
		SymbolReference getReference() {
			return m_reference;
		}
	}

	private final SymbolTable m_symbols;

	private final Diagnostics m_diagnostics;

	Evaluator(SymbolTable symbols, Diagnostics diagnostics) {
		m_symbols = symbols;
		m_diagnostics = diagnostics;
	}

	/**
	 * The value now, while the source is still being read.
	 *
	 * @throws NotYetKnownException when a symbol in it has no value yet
	 * @throws AsmException             when it is not a valid expression
	 */
	Value evaluateNow(Expression e) throws NotYetKnownException, AsmException {
		return evaluate(e, false);
	}

	/** The value once everything has been read and the sections placed. */
	Value evaluateFinal(Expression e) throws NotYetKnownException, AsmException {
		return evaluate(e, true);
	}

	private Value evaluate(Expression e, boolean last) throws NotYetKnownException, AsmException {
		return switch(e) {
			case Constant c -> c.value();
			case SymbolReference s -> symbolValue(s, last);
			case Unary u -> unary(u, evaluate(u.operand(), last), last);
			case Binary b -> binary(b, evaluate(b.left(), last), evaluate(b.right(), last), last);
		};
	}

	private Value symbolValue(SymbolReference ref, boolean last) throws NotYetKnownException, AsmException {
		Symbol s = m_symbols.findByKey(ref.key());
		if(s == null || !s.isDefined()) {
			//-- An opcode used as a value means its instruction word, as in DEC's permanent
			//-- symbol table; only at the end, when it is certain no user symbol has the name.
			Instruction instruction = last ? Instruction.find(ref.name()) : null;
			if(instruction != null)
				return new Absolute(instruction.getOpcode());
			throw new NotYetKnownException(ref);
		}
		if(s.hasValue())
			return s.getValue();
		if(!last)
			throw new NotYetKnownException(ref);

		//-- A symbol assigned in terms of something defined later: work it out now.
		if(s.isResolving())
			throw new AsmException(ref.location(), "The definition of " + s.getName() + " refers to itself");
		s.setResolving(true);
		try {
			Value v = evaluate(s.getExpression(), true);
			s.resolve(v);
			return v;
		} finally {
			s.setResolving(false);
		}
	}

	private Value unary(Unary u, Value v, boolean last) throws AsmException, NotYetKnownException {
		return switch(u.operator()) {
			case NEGATE -> new Absolute(-number(v, u, last));
			case COMPLEMENT -> new Absolute(~number(v, u, last));
			case REGISTER -> {
				int n = number(v, u, last);
				if(n > 7)
					throw new AsmException(u.location(), "%" + Integer.toOctalString(n) + " is not a register: they are %0 to %7");
				yield new Register(n);
			}
		};
	}

	private Value binary(Binary b, Value l, Value r, boolean last) throws AsmException, NotYetKnownException {
		BinaryOperator op = b.operator();

		//-- What can be done before anything has an address.
		if(op == BinaryOperator.ADD) {
			if(l instanceof Relocatable rl && r instanceof Absolute ar)
				return new Relocatable(rl.section(), rl.offset() + ar.value());
			if(l instanceof Absolute al && r instanceof Relocatable rr)
				return new Relocatable(rr.section(), rr.offset() + al.value());
			if(l instanceof Register || r instanceof Register)
				return registerArithmetic(b, l, r);
		} else if(op == BinaryOperator.SUBTRACT) {
			if(l instanceof Relocatable rl && r instanceof Absolute ar)
				return new Relocatable(rl.section(), rl.offset() - ar.value());
			if(l instanceof Relocatable rl && r instanceof Relocatable rr && rl.section() == rr.section())
				return new Absolute(rl.offset() - rr.offset());
			if(l instanceof Register || r instanceof Register)
				return registerArithmetic(b, l, r);
		} else if(l instanceof Register || r instanceof Register) {
			throw new AsmException(b.location(), "A register cannot be used with '" + op.symbol() + "'");
		}

		int a = number(l, b, last);
		int c = number(r, b, last);
		return switch(op) {
			case ADD -> arithmetic(a, c, Integer::sum, b);
			case SUBTRACT -> arithmetic(a, c, (x, y) -> x - y, b);
			case MULTIPLY -> arithmetic(a, c, (x, y) -> x * y, b);
			case DIVIDE -> {
				if(c == 0)
					throw new AsmException(b.right().location(), "Division by zero");
				yield new Absolute(a / c);
			}
			case AND -> new Absolute(a & c);
			case OR -> new Absolute(a | c);
		};
	}

	/**
	 * {@code R0+1} and {@code %2-1}: a register plus or minus a number is another register, as
	 * MACRO-11 allows. Anything else with a register in it is an error.
	 */
	private static Value registerArithmetic(Binary b, Value l, Value r) throws AsmException {
		if(l instanceof Register rl && r instanceof Absolute ar) {
			int n = b.operator() == BinaryOperator.ADD ? rl.number() + ar.value() : rl.number() - ar.value();
			if(n >= 0 && n <= 7)
				return new Register(n);
			throw new AsmException(b.location(), "The result is not a register");
		}
		if(l instanceof Absolute al && r instanceof Register rr && b.operator() == BinaryOperator.ADD) {
			int n = al.value() + rr.number();
			if(n >= 0 && n <= 7)
				return new Register(n);
			throw new AsmException(b.location(), "The result is not a register");
		}
		throw new AsmException(b.location(), "A register cannot be used with '" + b.operator().symbol() + "' here");
	}

	private Value arithmetic(int a, int b, IntBinaryOperator f, Binary at) {
		int unsigned = f.applyAsInt(a, b);
		int signed = f.applyAsInt((short) a, (short) b);
		if((unsigned < 0 || unsigned > 0xFFFF) && (signed < Short.MIN_VALUE || signed > Short.MAX_VALUE)) {
			m_diagnostics.warning(WarningKind.ARITHMETIC_OVERFLOW, at.location(),
				"The result of '" + at.operator().symbol() + "' does not fit in 16 bits");
		}
		return new Absolute(unsigned);
	}

	/** The number a value stands for, where only a number will do. */
	private static int number(Value v, Expression at, boolean last) throws AsmException, NotYetKnownException {
		return switch(v) {
			case Absolute a -> a.value();
			case Register r -> throw new AsmException(at.location(), "A register cannot be used in this expression");
			case Relocatable r -> {
				if(!last || !r.section().isPlaced())
					throw new NotYetKnownException(at.location(),
						"This needs the address of something in " + r.section().describe()
							+ ", which is only known at the end of the assembly");
				yield r.section().addressOf(r.offset());
			}
		};
	}
}
