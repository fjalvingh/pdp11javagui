package to.etc.pdp11.common.macro11.asm;

import to.etc.pdp11.common.macro11.asm.CodeWord.Check;
import to.etc.pdp11.common.macro11.asm.CodeWord.Encoding;
import to.etc.pdp11.common.macro11.asm.Expression.Constant;
import to.etc.pdp11.common.macro11.asm.Instruction.Flag;
import to.etc.pdp11.common.macro11.asm.Value.Absolute;

/**
 * Turns an instruction and its operands into words.
 *
 * <p>The instruction word comes first, then the extra word of the source operand, then that of
 * the destination - which is the order the CPU fetches them in, and so the order the
 * PC-relative distances are measured from.</p>
 */
final class InstructionAssembler {
	private final AssemblyRun m_run;

	InstructionAssembler(AssemblyRun run) {
		m_run = run;
	}

	void assemble(Instruction instruction, Token at, TokenReader r) throws AsmException {
		m_run.alignWord(at.location(), "An instruction");
		m_run.checkReachable(at.location());
		int wordsBefore = m_run.getWords().size();
		try {
			encode(instruction, at, r);
		} catch(AsmException x) {
			//-- Keep at least the instruction word's place, so that what follows - and every
			//-- branch over this - is where it would have been.
			if(m_run.getWords().size() == wordsBefore)
				m_run.emitConstant(2, instruction.getOpcode(), at.location());
			throw x;
		}
		if(instruction.has(Flag.TRANSFER))
			m_run.markTransfer();
	}

	private void encode(Instruction instruction, Token at, TokenReader r) throws AsmException {
		ExpressionParser p = m_run.parser(r);
		int opcode = instruction.getOpcode();

		switch(instruction.getFormat()) {
			case NONE -> m_run.emitConstant(2, opcode, at.location());
			case SINGLE -> {
				Operand d = operand(p, r, instruction, false);
				checkDestination(instruction, d);
				m_run.emitConstant(2, opcode | d.bits(), at.location());
				extraWord(d, instruction, false);
			}
			case DOUBLE -> {
				Operand s = operand(p, r, instruction, true);
				comma(r, instruction);
				Operand d = operand(p, r, instruction, false);
				checkDestination(instruction, d);
				if(instruction == Instruction.MOV && s.isRegister() && d.isRegister() && s.register() == d.register())
					warning(WarningKind.NO_EFFECT, at, "MOV of a register to itself does nothing but set the condition codes; TST does that");
				m_run.emitConstant(2, opcode | (s.bits() << 6) | d.bits(), at.location());
				extraWord(s, instruction, true);
				extraWord(d, instruction, false);
			}
			case BRANCH -> {
				Expression target = target(p, r, instruction);
				m_run.emit(2, Encoding.BRANCH, opcode, 0, target, Check.NONE, target.location());
			}
			case REGISTER_DESTINATION -> {
				int register = p.parseRegister();
				comma(r, instruction);
				Operand d = operand(p, r, instruction, false);
				checkDestination(instruction, d);
				m_run.emitConstant(2, opcode | (register << 6) | d.bits(), at.location());
				extraWord(d, instruction, false);
			}
			case SOURCE_REGISTER -> {
				Operand s = operand(p, r, instruction, true);
				comma(r, instruction);
				Location regAt = r.here();
				int register = p.parseRegister();
				if(instruction.has(Flag.EVEN_REGISTER) && (register & 1) != 0)
					warning(WarningKind.ODD_REGISTER_PAIR, regAt, instruction + " works on a pair of registers starting with an even one; "
						+ "with R" + register + " the result is unpredictable");
				m_run.emitConstant(2, opcode | (register << 6) | s.bits(), at.location());
				extraWord(s, instruction, true);
			}
			case REGISTER -> {
				int register = p.parseRegister();
				m_run.emitConstant(2, opcode | register, at.location());
			}
			case SOB -> {
				int register = p.parseRegister();
				comma(r, instruction);
				Expression target = target(p, r, instruction);
				m_run.emit(2, Encoding.SOB, opcode | (register << 6), 0, target, Check.NONE, target.location());
			}
			case TRAP -> field(p, r, at, opcode, 0377, false);
			case MARK -> field(p, r, at, opcode, 077, true);
			case SPL -> field(p, r, at, opcode, 07, true);
			case FP_SOURCE -> {
				Operand s = floatingOperand(p, r, instruction);
				comma(r, instruction);
				int accumulator = accumulator(p, r);
				m_run.emitConstant(2, opcode | (accumulator << 6) | s.bits(), at.location());
				extraWord(s, instruction, true);
			}
			case FP_DESTINATION -> {
				int accumulator = accumulator(p, r);
				comma(r, instruction);
				Operand d = operand(p, r, instruction, false);
				checkDestination(instruction, d);
				m_run.emitConstant(2, opcode | (accumulator << 6) | d.bits(), at.location());
				extraWord(d, instruction, false);
			}
		}
	}

	private Operand operand(ExpressionParser p, TokenReader r, Instruction instruction, boolean source) throws AsmException {
		if(r.atEndOfStatement() || r.peek().is(TokenKind.COMMA))
			throw new AsmException(r.here(), instruction + " needs " + (source ? "a source" : "a destination") + " operand here");
		Operand o = p.parseOperand();
		if(o.register() == 7 && (o.mode() == 4 || o.mode() == 5))
			warning(WarningKind.PC_AUTO_DECREMENT, o.location(), "Auto-decrement of the PC makes the CPU run backwards over its own code");
		return o;
	}

	private Expression target(ExpressionParser p, TokenReader r, Instruction instruction) throws AsmException {
		if(r.atEndOfStatement())
			throw new AsmException(r.here(), instruction + " needs a target");
		return p.parseExpression();
	}

	private void comma(TokenReader r, Instruction instruction) throws AsmException {
		Token t = r.peek();
		if(!t.is(TokenKind.COMMA))
			throw new AsmException(t.location(), instruction + " needs a comma between its operands"
				+ (t.isEndOfLine() ? ", and a second operand" : ", but found " + t.describe()));
		r.next();
	}

	/** What goes wrong with a destination, given what the instruction does with it. */
	private void checkDestination(Instruction instruction, Operand d) throws AsmException {
		if(instruction.has(Flag.JUMP) && d.isRegister())
			throw new AsmException(d.location(), instruction + " to a register is illegal: a register is not an address to go to");
		if(instruction.has(Flag.WRITES) && d.isImmediate())
			warning(WarningKind.IMMEDIATE_DESTINATION, d.location(),
				"The destination is an immediate operand, so " + instruction + " writes into its own instruction");
	}

	/**
	 * The word after the instruction for an operand that has one.
	 *
	 * @param source whether it is the source operand, which an instruction reads rather than writes
	 */
	private void extraWord(Operand o, Instruction instruction, boolean source) {
		if(o.extra() == null)
			return;
		boolean wordSized = !instruction.isByte();
		Check addressCheck = instruction.has(Flag.JUMP) && !source ? Check.JUMP_TARGET
			: wordSized ? Check.WORD_ADDRESS : Check.NONE;
		switch(o.use()) {
			case NONE -> throw new IllegalStateException("An operand without an extra word has an expression");
			case IMMEDIATE_OR_ABSOLUTE -> {
				if(o.mode() == 2) {
					Check check = instruction.isByte() ? Check.BYTE_IMMEDIATE : Check.DATA_WORD;
					m_run.emit(2, Encoding.VALUE, 0, 0, o.extra(), check, o.extra().location());
				} else {
					m_run.emit(2, Encoding.VALUE, 0, 0, o.extra(), addressCheck, o.extra().location());
				}
			}
			case INDEX -> m_run.emitValue(2, o.extra(), Check.NONE);
			case PC_RELATIVE -> {
				//-- @X: X holds a pointer, which is a word whatever the instruction is.
				Check check = o.mode() == 7 ? Check.WORD_ADDRESS : addressCheck;
				m_run.emit(2, Encoding.DISPLACEMENT, 0, 0, o.extra(), check, o.extra().location());
			}
		}
	}

	/** EMT, TRAP, MARK, SPL: a number in the instruction word. */
	private void field(ExpressionParser p, TokenReader r, Token at, int opcode, int max, boolean required) throws AsmException {
		if(r.atEndOfStatement()) {
			if(required)
				throw new AsmException(r.here(), at.text() + " needs a number from 0 to " + Integer.toOctalString(max));
			m_run.emitConstant(2, opcode, at.location());
			return;
		}
		r.accept(TokenKind.HASH);
		Expression e = p.parseExpression();
		m_run.emit(2, Encoding.FIELD, opcode, max, e, Check.NONE, e.location());
	}

	private int accumulator(ExpressionParser p, TokenReader r) throws AsmException {
		Location at = r.here();
		int register = p.parseAccumulator();
		if(register > 3)
			throw new AsmException(at, "A floating-point accumulator here must be AC0 to AC3 (R0-R3 or %0-%3), not " + register);
		return register;
	}

	/**
	 * The source of a floating-point instruction, where {@code #} means a floating-point
	 * number: the first word of one, which is all the instruction reads.
	 */
	private Operand floatingOperand(ExpressionParser p, TokenReader r, Instruction instruction) throws AsmException {
		Token t = r.peek();
		if(t.is(TokenKind.HASH)) {
			r.next();
			Location at = r.here();
			String word = r.peekWord();
			if(looksFloating(word)) {
				r.readWord();
				int[] w = PdpFloat.parse(word, 1, at);
				return new Operand(2, 7, new Constant(new Absolute(w[0]), at), Operand.Use.IMMEDIATE_OR_ABSOLUTE, t.location());
			}
			r.pushBack(t);
		}
		return operand(p, r, instruction, true);
	}

	/** A number with a point or an exponent, which can only be floating point. */
	private static boolean looksFloating(String word) {
		if(word.isEmpty())
			return false;
		char first = word.charAt(0);
		if(!(Character.isDigit(first) || first == '-' || first == '+' || first == '.'))
			return false;
		for(int i = 0; i < word.length(); i++) {
			char c = word.charAt(i);
			if(!(Character.isDigit(c) || c == '.' || c == 'E' || c == 'e' || c == '-' || c == '+'))
				return false;
		}
		return word.indexOf('.') >= 0 || word.indexOf('E') >= 0 || word.indexOf('e') >= 0;
	}

	private void warning(WarningKind kind, Token at, String message) {
		m_run.getDiagnostics().warning(kind, at.location(), message);
	}

	private void warning(WarningKind kind, Location at, String message) {
		m_run.getDiagnostics().warning(kind, at, message);
	}
}
