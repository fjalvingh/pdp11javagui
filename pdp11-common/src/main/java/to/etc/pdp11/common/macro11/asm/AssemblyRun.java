package to.etc.pdp11.common.macro11.asm;

import to.etc.pdp11.common.macro11.asm.AssemblyState.Option;
import to.etc.pdp11.common.macro11.asm.CodeWord.Check;
import to.etc.pdp11.common.macro11.asm.CodeWord.Encoding;
import to.etc.pdp11.common.macro11.asm.Evaluator.NotYetKnownException;
import to.etc.pdp11.common.macro11.asm.Expression.Constant;
import to.etc.pdp11.common.macro11.asm.InputSource.Kind;
import to.etc.pdp11.common.macro11.asm.Value.Absolute;
import to.etc.pdp11.common.macro11.asm.Value.Register;
import to.etc.pdp11.common.macro11.asm.Value.Relocatable;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One assembly: reads the source once, statement by statement, and keeps everything that the
 * end of the assembly needs to finish the program.
 *
 * <h2>One pass</h2>
 *
 * <p>DEC's MACRO-11 reads its source twice, the first time only to learn where every label is.
 * This assembler reads it once. That works for the PDP-11 because the size of an instruction
 * depends only on how its operands are written - {@code X(R0)} has an extra word whatever
 * {@code X} is - and never on their values. So the location counter is always known, and a
 * value that is not known yet is stored as a {@link CodeWord} with its expression and patched at
 * the end.</p>
 *
 * <p>What this cannot do is decide space or layout on something defined later: {@code .=X},
 * {@code .BLKW N}, {@code .REPT N} and {@code .IF} need their values when they are read. That
 * is an error here with a message saying so; DEC's assembler gets such code wrong in quieter
 * ways, so nothing that worked there stops working.</p>
 */
final class AssemblyRun {
	/** How deep {@code .INCLUDE}s and expansions may nest before it is taken to be a loop. */
	private static final int MAX_NESTING = 64;

	/** How many {@code .SAVE}s may be outstanding. */
	private static final int MAX_SAVED_SECTIONS = 16;

	private final AssemblerOptions m_options;

	private final SourceResolver m_resolver;

	private final Diagnostics m_diagnostics;

	private final AssemblyState m_state;

	private final InstructionAssembler m_instructions;

	private final MacroProcessor m_macros;

	private final ConditionalStack m_conditionals = new ConditionalStack();

	private final Deque<InputSource> m_inputs = new ArrayDeque<>();

	private final Map<String, Section> m_sections = new LinkedHashMap<>();

	private final Deque<Section> m_savedSections = new ArrayDeque<>();

	private final List<ListingEntry> m_listing = new ArrayList<>();

	private final List<CodeWord> m_words = new ArrayList<>();

	/** Symbols tested with {@code .IF DF}/{@code NDF} while undefined, and where. */
	private final Map<String, Location> m_undefinedWhenTested = new LinkedHashMap<>();

	private final Section m_absolute;

	private Section m_section;

	private ListingEntry m_entry;

	private String m_mainSource;

	private Location m_lastMainLine;

	private boolean m_ended;

	private Location m_endedAt;

	private Expression m_transferAddress;

	private String m_title = "";

	/** The last instruction always transferred control, so the next one needs a label. */
	private boolean m_afterTransfer;

	AssemblyRun(AssemblerOptions options, SourceResolver resolver) {
		m_options = options;
		m_resolver = resolver;
		m_diagnostics = new Diagnostics(options.disabledWarnings());
		m_state = new AssemblyState(m_diagnostics);
		m_instructions = new InstructionAssembler(this);
		m_macros = new MacroProcessor(this);
		m_absolute = new Section(". ABS.", Section.Attributes.ABSOLUTE, null);
		m_sections.put(m_absolute.getName(), m_absolute);
		Section blank = new Section("", Section.Attributes.PSECT_DEFAULT, null);
		m_sections.put(blank.getName(), blank);
		m_section = blank;
	}

	// -------------------------------------------------------------------------------------
	// Running
	// -------------------------------------------------------------------------------------

	AssemblyResult run(String name, Reader text) {
		m_mainSource = name;
		pushSource(new TokenReader(text, name, null), Kind.FILE, 0);
		while(!m_ended) {
			InputSource in = m_inputs.peek();
			if(in == null)
				break;
			if(!in.reader().nextLine()) {
				endOfSource(in);
				continue;
			}
			if(in.kind() == Kind.FILE && in.reader().getExpandedFrom() == null)
				m_lastMainLine = in.reader().lineLocation();
			processLine(in);
		}

		if(m_ended)
			checkForTextAfterEnd();
		else
			m_diagnostics.warning(WarningKind.MISSING_END, m_lastMainLine == null ? Location.inFile(name, 1, 0) : m_lastMainLine,
				"The source does not end with .END");
		for(Location open : m_conditionals.closeTo(0))
			m_diagnostics.error(open, "This conditional is not closed by .ENDC");
		closeAllSources();

		return new Finisher(this).finish();
	}

	private void processLine(InputSource in) {
		TokenReader r = in.reader();
		m_entry = new ListingEntry(r.lineLocation(), r.lineText(), r.lineNumber());
		m_listing.add(m_entry);
		try {
			if(m_conditionals.isActive())
				processStatement(r);
			else
				skipInactive(r);
		} catch(AsmException x) {
			m_diagnostics.error(x);
			r.skipRestOfLine();
		}
	}

	/**
	 * A macro, repeat block or included file must close the conditionals it opens. The main
	 * file's are reported at the end of the run.
	 */
	private void endOfSource(InputSource in) {
		boolean main = in.kind() == Kind.FILE && in.reader().getExpandedFrom() == null;
		if(!main) {
			String what = switch(in.kind()) {
				case MACRO -> "the macro " + in.reader().getSource();
				case REPEAT -> "the repeat block";
				case FILE -> "the included file " + in.reader().getSource();
			};
			for(Location l : m_conditionals.closeTo(in.conditionalDepth()))
				m_diagnostics.error(l, "This conditional is not closed before the end of " + what);
		}
		popSource();
	}

	/** Statements after {@code .END} are not assembled; say so if there are any. */
	private void checkForTextAfterEnd() {
		for(InputSource in : m_inputs) {
			if(in.kind() != Kind.FILE)
				continue;
			TokenReader r = in.reader();
			while(r.nextLine()) {
				if(!r.atEndOfStatement()) {
					m_diagnostics.warning(WarningKind.TEXT_AFTER_END, r.lineLocation(),
						"This is after .END at " + m_endedAt.root() + " and is not assembled");
					return;
				}
			}
		}
	}

	// -------------------------------------------------------------------------------------
	// Statements
	// -------------------------------------------------------------------------------------

	/**
	 * One statement: labels, then an assignment, a macro call, a directive, an instruction, or
	 * data.
	 */
	void processStatement(TokenReader r) throws AsmException {
		boolean labelled = false;
		for(;;) {
			Token t = r.peek();
			if((t.is(TokenKind.SYMBOL) || t.is(TokenKind.LOCAL_LABEL)) && r.peek(1).is(TokenKind.COLON)) {
				r.next();
				r.next();
				boolean global = r.accept(TokenKind.COLON);
				defineLabel(t, global);
				labelled = true;
				continue;
			}
			break;
		}

		Token t = r.peek();
		if(t.isEndOfLine())
			return;

		if(t.is(TokenKind.SYMBOL) && r.peek(1).is(TokenKind.EQUALS)) {
			r.next();
			r.next();
			boolean global = r.accept(TokenKind.EQUALS);
			r.accept(TokenKind.COLON);
			assign(t, global, labelled, r);
			return;
		}

		if(t.is(TokenKind.SYMBOL)) {
			Macro m = m_macros.find(t.text());
			if(m != null) {
				r.next();
				m_macros.expand(m, r, t.location());
				return;
			}
			Directive d = Directive.find(t.text());
			if(d != null) {
				r.next();
				directive(d, t, r, labelled);
				return;
			}
			Instruction i = Instruction.find(t.text());
			if(i != null) {
				r.next();
				m_instructions.assemble(i, t, r);
				expectEnd(r);
				return;
			}
			if(m_state.isEnabled(Option.MCL) && !t.text().startsWith(".")) {
				m = m_macros.loadFromLibrary(t.text(), t.location());
				if(m != null) {
					r.next();
					m_macros.expand(m, r, t.location());
					return;
				}
			}
			if(t.text().length() > 1 && t.text().startsWith(".")) {
				//-- A name starting with a point is almost always a directive that does not exist,
				//-- rather than a value to store.
				Symbol s = m_state.getSymbols().find(t.text());
				if(s == null || !s.isDefined())
					throw new AsmException(t.location(), t.text() + " is not a directive");
			}
		}

		//-- Nothing else: the operands are data, as if .WORD had been written.
		m_diagnostics.warning(WarningKind.IMPLICIT_WORD, t.location(), "No operation here, so this is assembled as .WORD");
		data(r, 2, t);
	}

	/** Everything on the line must have been used. */
	void expectEnd(TokenReader r) throws AsmException {
		Token t = r.peek();
		if(t.isEndOfLine())
			return;
		String hint = t.spaceBefore() && t.is(TokenKind.SYMBOL) ? "; is a ';' missing before a comment?" : "";
		throw new AsmException(t.location(), "Unexpected " + t.describe() + " after the operands" + hint);
	}

	private void defineLabel(Token t, boolean global) throws AsmException {
		String name = t.text();
		if(SymbolTable.isRegisterName(name))
			throw new AsmException(t.location(), name + " is a register and cannot be a label");
		SymbolTable symbols = m_state.getSymbols();
		boolean local = t.is(TokenKind.LOCAL_LABEL);
		if(local && global)
			throw new AsmException(t.location(), "A local label cannot be global");
		if(!local && !m_state.isEnabled(Option.LSB))
			symbols.newLocalBlock();
		Symbol s = symbols.get(name);
		if(s.isDefined()) {
			if(s.getKind() == Symbol.Kind.LABEL)
				throw new AsmException(t.location(), name + " is already a label, defined at " + s.getDefinedAt().root());
			throw new AsmException(t.location(), name + " already has a value, given at " + s.getDefinedAt().root()
				+ ", and cannot also be a label");
		}
		s.define(Symbol.Kind.LABEL, currentLocation(), t.location());
		s.setDefinedInExpansion(currentInput().isExpansion());
		if(global)
			s.makeGlobal(t.location());
		m_afterTransfer = false;
	}

	/** {@code name = value}, {@code name == value}, and {@code . = value}. */
	private void assign(Token nameToken, boolean global, boolean labelled, TokenReader r) throws AsmException {
		String name = nameToken.text();
		Expression e = parser(r).parseExpression();
		expectEnd(r);

		if(name.equals(".")) {
			if(labelled)
				m_diagnostics.warning(WarningKind.LABEL_BEFORE_LOCATION_CHANGE, nameToken.location(),
					"The label on this line gets the location before the change");
			setLocation(e, nameToken.location());
			return;
		}
		if(SymbolTable.isRegisterName(name)) {
			//-- Old sources define the registers themselves: R0=%0. That is what they are anyway.
			Value v = valueNow(e, "The value of a register name");
			if(v instanceof Register reg && reg.number() == SymbolTable.registerNumber(name))
				return;
			throw new AsmException(nameToken.location(), name + " is a register and cannot be given another value");
		}

		Symbol s = m_state.getSymbols().get(name);
		if(s.isDefined() && s.getKind() == Symbol.Kind.LABEL)
			throw new AsmException(nameToken.location(), name + " is a label, defined at " + s.getDefinedAt().root()
				+ ", and cannot be given another value");
		try {
			Value v = m_state.getEvaluator().evaluateNow(e);
			s.define(Symbol.Kind.ASSIGNED, v, nameToken.location());
			m_entry.showValue(v);
		} catch(NotYetKnownException x) {
			//-- Defined in terms of something further down: worked out at the end.
			s.defineDeferred(e, nameToken.location());
			m_entry.showValue(e);
		}
		s.setDefinedInExpansion(currentInput().isExpansion());
		if(global)
			s.makeGlobal(nameToken.location());
	}

	private void setLocation(Expression e, Location at) throws AsmException {
		Value v = valueNow(e, "The location counter");
		int offset;
		if(m_section.isRelocatable()) {
			if(!(v instanceof Relocatable rel) || rel.section() != m_section)
				throw new AsmException(e.location(), "In " + m_section.describe()
					+ " the location counter can only be set to a location in that section");
			offset = rel.offset();
		} else {
			if(!(v instanceof Absolute abs))
				throw new AsmException(e.location(), "In " + m_section.describe() + " the location counter must be set to an absolute address");
			offset = abs.value();
		}
		m_section.setLocation(offset);
		m_entry.showValue(v);
		m_afterTransfer = false;
	}

	/**
	 * A value that is needed now, to decide where things go.
	 *
	 * @param what what needs it, for the message: "The repeat count"
	 */
	Value valueNow(Expression e, String what) throws AsmException {
		try {
			return m_state.getEvaluator().evaluateNow(e);
		} catch(NotYetKnownException x) {
			String why = x.getReference() != null
				? x.getReference().name() + " is not defined yet"
				: x.getMessage();
			throw new AsmException(x.getLocation(), what + " must be known where it is used, and " + why);
		}
	}

	/** An absolute number that is needed now. */
	int numberNow(Expression e, String what) throws AsmException {
		Value v = valueNow(e, what);
		return switch(v) {
			case Absolute a -> a.value();
			case Register r -> throw new AsmException(e.location(), what + " must be a number, not a register");
			case Relocatable r -> throw new AsmException(e.location(), what + " must be an absolute number, not a location in "
				+ r.section().describe());
		};
	}

	// -------------------------------------------------------------------------------------
	// Conditionals that are not being assembled
	// -------------------------------------------------------------------------------------

	/** In a block that is not assembled, only the conditional directives count. */
	private void skipInactive(TokenReader r) {
		while((r.peek().is(TokenKind.SYMBOL) || r.peek().is(TokenKind.LOCAL_LABEL)) && r.peek(1).is(TokenKind.COLON)) {
			r.next();
			r.next();
			r.accept(TokenKind.COLON);
		}
		Token t = r.peek();
		if(!t.is(TokenKind.SYMBOL))
			return;
		Directive d = Directive.find(t.text());
		if(d == null)
			return;
		if(d.isConditional() && d != Directive.IIF)
			m_conditionals.open(false, t.location());
		else if(d.isSubconditional())
			m_conditionals.subconditional(d);
		else if(d == Directive.ENDC)
			m_conditionals.close();
	}

	// -------------------------------------------------------------------------------------
	// Directives
	// -------------------------------------------------------------------------------------

	private void directive(Directive d, Token at, TokenReader r, boolean labelled) throws AsmException {
		switch(d) {
			case ASCII, ASCIZ -> ascii(r, at, d == Directive.ASCIZ);
			case RAD50 -> rad50(r, at);
			case BYTE -> data(r, 1, at);
			case WORD -> data(r, 2, at);
			case BLKB -> reserve(r, at, 1);
			case BLKW -> reserve(r, at, 2);
			case EVEN -> {
				if((m_section.getLocation() & 1) != 0)
					emitConstant(1, 0, at.location());
				expectEnd(r);
			}
			case ODD -> {
				if((m_section.getLocation() & 1) == 0)
					emitConstant(1, 0, at.location());
				expectEnd(r);
			}
			case FLT2 -> floats(r, at, 2);
			case FLT4 -> floats(r, at, 4);
			case PACKED -> packed(r, at);
			case LIMIT -> {
				alignWord(at.location(), ".LIMIT");
				emit(2, Encoding.LIMIT, 0, 0, null, Check.NONE, at.location());
				emit(2, Encoding.LIMIT, 1, 0, null, Check.NONE, at.location());
				expectEnd(r);
			}
			case ASECT -> {
				switchTo(m_absolute);
				expectEnd(r);
			}
			case PSECT, CSECT -> section(r, at, d);
			case SAVE -> {
				if(m_savedSections.size() >= MAX_SAVED_SECTIONS)
					throw new AsmException(at.location(), "Too many .SAVEs without a .RESTORE");
				m_savedSections.push(m_section);
				expectEnd(r);
			}
			case RESTORE -> {
				Section s = m_savedSections.poll();
				if(s == null)
					throw new AsmException(at.location(), ".RESTORE without a .SAVE");
				switchTo(s);
				expectEnd(r);
			}
			case GLOBL, WEAK -> globals(r);
			case TITLE -> {
				Token name = r.peek();
				if(name.is(TokenKind.SYMBOL))
					m_title = name.text();
				r.readRestOfLine();
			}
			case IDENT -> ident(r, at);
			case SBTTL, PAGE, EOT, CROSS, NOCROSS, LIST, NLIST, PRINT -> r.readRestOfLine();
			case ENABL -> options(r, at, true);
			case DSABL -> options(r, at, false);
			case RADIX -> radix(r, at);
			case END -> end(r, at);
			case ERROR -> {
				String text = r.readRestOfLine();
				m_diagnostics.error(at.location(), text.isEmpty() ? ".ERROR" : ".ERROR: " + text);
			}
			case NARG -> narg(r, at);
			case NCHR -> nchr(r, at);
			case NTYPE -> ntype(r, at);
			case INCLUDE -> include(r, at);
			case LIBRARY -> throw new AsmException(at.location(), "Macro libraries (.LIBRARY) are not supported yet; "
				+ "put the macros in a directory and use .MCALL");
			case MCALL -> m_macros.mcall(r, at);
			case MDELETE -> m_macros.delete(r);
			case MACRO, MACR -> m_macros.define(r, at);
			case MEXIT -> {
				expectEnd(r);
				mexit(at);
			}
			case REPT, IRP, IRPC -> m_macros.repeat(d, r, at);
			case ENDM -> throw new AsmException(at.location(), ".ENDM without a .MACRO");
			case ENDR -> throw new AsmException(at.location(), ".ENDR without a .REPT, .IRP or .IRPC");
			case IFF, IFT, IFTF -> {
				if(!m_conditionals.subconditional(d))
					throw new AsmException(at.location(), d.getName() + " outside a conditional block");
				expectEnd(r);
			}
			case ENDC -> {
				if(!m_conditionals.close())
					throw new AsmException(at.location(), ".ENDC without a .IF");
				expectEnd(r);
			}
			case REM -> remark(r, at);
			default -> {
				if(d.isConditional())
					conditional(d, r, at);
				else
					throw new IllegalStateException("Directive " + d + " is not handled");
			}
		}
	}

	// -------------------------------------------------------------------------------------
	// Data
	// -------------------------------------------------------------------------------------

	/**
	 * {@code .WORD} and {@code .BYTE}: expressions separated by commas or blanks.
	 *
	 * <p>{@code .WORD} with nothing is one zero word. A comma with nothing before it is a zero,
	 * as in DEC's assembler.</p>
	 */
	private void data(TokenReader r, int size, Token at) throws AsmException {
		if(size == 2)
			alignWord(at.location(), ".WORD");
		m_afterTransfer = false;
		if(r.atEndOfStatement()) {
			emitConstant(size, 0, at.location());
			return;
		}
		ExpressionParser p = parser(r);
		for(;;) {
			if(r.peek().is(TokenKind.COMMA)) {
				emitConstant(size, 0, r.peek().location());
			} else {
				Expression e;
				try {
					e = p.parseExpression();
				} catch(AsmException x) {
					//-- Keep the space, so that what follows is where it would have been.
					emitConstant(size, 0, x.getLocation());
					throw x;
				}
				emit(size, Encoding.VALUE, 0, 0, e, size == 1 ? Check.BYTE_DATA : Check.DATA_WORD, e.location());
			}
			Token sep = r.peek();
			if(sep.isEndOfLine())
				return;
			if(sep.is(TokenKind.COMMA)) {
				r.next();
				if(r.atEndOfStatement()) {
					emitConstant(size, 0, sep.location());
					return;
				}
				continue;
			}
			if(!sep.spaceBefore())
				throw new AsmException(sep.location(), "Unexpected " + sep.describe() + " after the value");
		}
	}

	/** {@code .ASCII} and {@code .ASCIZ}: delimited strings, and byte values in angle brackets. */
	private void ascii(TokenReader r, Token at, boolean zero) throws AsmException {
		m_afterTransfer = false;
		boolean any = false;
		for(;;) {
			int c = r.peekCharacter();
			if(c < 0 || c == ';')
				break;
			any = true;
			if(c == '<' || c == '^') {
				Expression e = parser(r).parseTerm();
				emit(1, Encoding.VALUE, 0, 0, e, Check.BYTE_DATA, e.location());
				continue;
			}
			TokenReader.Delimited d = r.readDelimited();
			for(int i = 0; i < d.text().length(); i++)
				emitConstant(1, d.text().charAt(i), d.location());
			if(!d.terminated())
				throw new AsmException(d.location(), "The string is not closed: there is no second '" + d.delimiter() + "'");
		}
		if(!any)
			throw new AsmException(at.location(), at.text() + " needs a string");
		if(zero)
			emitConstant(1, 0, at.location());
	}

	/**
	 * {@code .RAD50}: strings packed three characters to a word, and character codes in angle
	 * brackets.
	 */
	private void rad50(TokenReader r, Token at) throws AsmException {
		alignWord(at.location(), ".RAD50");
		m_afterTransfer = false;
		List<Integer> codes = new ArrayList<>();
		for(;;) {
			int c = r.peekCharacter();
			if(c < 0 || c == ';')
				break;
			if(c == '<') {
				Expression e = parser(r).parseTerm();
				int code = numberNow(e, "A RAD50 character code");
				if(code > 047)
					throw new AsmException(e.location(), "A RAD50 character code is 0 to 47, not " + Integer.toOctalString(code));
				codes.add(code);
				continue;
			}
			TokenReader.Delimited d = r.readDelimited();
			for(int i = 0; i < d.text().length(); i++) {
				int code = Rad50.code(d.text().charAt(i));
				if(code < 0) {
					m_diagnostics.error(d.location().atColumn(d.location().column() + 1 + i),
						"'" + d.text().charAt(i) + "' cannot be written in RAD50; it is stored as a blank");
					code = 0;
				}
				codes.add(code);
			}
			if(!d.terminated())
				throw new AsmException(d.location(), "The string is not closed: there is no second '" + d.delimiter() + "'");
		}
		if(codes.isEmpty())
			throw new AsmException(at.location(), ".RAD50 needs a string");
		for(int i = 0; i < codes.size(); i += 3) {
			int w = 0;
			for(int j = 0; j < 3; j++)
				w = w * 050 + (i + j < codes.size() ? codes.get(i + j) : 0);
			emitConstant(2, w, at.location());
		}
	}

	/** {@code .BLKB}, {@code .BLKW}: space, by default one unit. */
	private void reserve(TokenReader r, Token at, int unit) throws AsmException {
		if(unit == 2)
			alignWord(at.location(), at.text());
		int count = 1;
		if(!r.atEndOfStatement())
			count = numberNow(parser(r).parseExpression(), "The size of " + at.text());
		expectEnd(r);
		m_entry.showValue(currentLocation());
		m_section.advance(count * unit);
		m_afterTransfer = false;
	}

	/** {@code .FLT2}, {@code .FLT4}: floating-point numbers. */
	private void floats(TokenReader r, Token at, int words) throws AsmException {
		alignWord(at.location(), at.text());
		m_afterTransfer = false;
		boolean any = false;
		while(!r.atEndOfStatement()) {
			Location here = r.here();
			String text = r.readWord();
			if(text.isEmpty())
				throw new AsmException(here, "Expected a floating-point number");
			for(int w : PdpFloat.parse(text, words, here))
				emitConstant(2, w, here);
			any = true;
			r.skipArgumentSeparator();
		}
		if(!any)
			throw new AsmException(at.location(), at.text() + " needs a number");
	}

	/**
	 * {@code .PACKED digits[,symbol]}: packed decimal, two digits to a byte and a sign in the
	 * last half byte, which is 14 for plus, 15 for minus, and 15 for no sign as well.
	 */
	private void packed(TokenReader r, Token at) throws AsmException {
		TokenReader.Argument a = r.readArgument();
		if(a == null)
			throw new AsmException(at.location(), ".PACKED needs a decimal number");
		String text = a.text().trim();
		int sign = 0xF;
		if(text.startsWith("+")) {
			sign = 0xC;
			text = text.substring(1);
		} else if(text.startsWith("-")) {
			sign = 0xD;
			text = text.substring(1);
		}
		if(text.isEmpty() || text.length() > 31 || !text.chars().allMatch(ch -> ch >= '0' && ch <= '9'))
			throw new AsmException(a.location(), "'" + a.text() + "' is not a decimal number of at most 31 digits");
		String digits = (text.length() % 2 == 0 ? "0" : "") + text;
		for(int i = 0; i < digits.length(); i += 2) {
			int hi = digits.charAt(i) - '0';
			int lo = i + 1 < digits.length() ? digits.charAt(i + 1) - '0' : sign;
			emitConstant(1, (hi << 4) | lo, at.location());
		}
		if(digits.length() % 2 == 0)
			emitConstant(1, sign, at.location());
		r.skipArgumentSeparator();
		if(!r.atEndOfStatement()) {
			Token name = r.next();
			if(!name.is(TokenKind.SYMBOL))
				throw new AsmException(name.location(), "Expected a symbol for the number of digits");
			defineAssigned(name, new Absolute(text.length()));
		}
		expectEnd(r);
	}

	// -------------------------------------------------------------------------------------
	// Sections and symbols
	// -------------------------------------------------------------------------------------

	private void section(TokenReader r, Token at, Directive d) throws AsmException {
		String name = "";
		if(r.peek().is(TokenKind.SYMBOL))
			name = r.next().text();
		boolean psect = d == Directive.PSECT;

		Section.Attributes given = null;
		if(psect) {
			boolean relocatable = true;
			boolean readOnly = false;
			boolean data = false;
			boolean global = false;
			boolean overlay = false;
			boolean saved = false;
			boolean any = false;
			r.accept(TokenKind.COMMA);
			while(!r.atEndOfStatement()) {
				Token t = r.next();
				if(!t.is(TokenKind.SYMBOL))
					throw new AsmException(t.location(), "Expected a .PSECT attribute but found " + t.describe());
				any = true;
				switch(t.text()) {
					case "ABS" -> {
						relocatable = false;
						overlay = true;
					}
					case "REL" -> relocatable = true;
					case "RO" -> readOnly = true;
					case "RW" -> readOnly = false;
					case "I" -> data = false;
					case "D" -> data = true;
					case "GBL" -> global = true;
					case "LCL" -> global = false;
					case "OVR" -> overlay = true;
					case "CON" -> overlay = false;
					case "SAV" -> saved = true;
					case "LOW", "HGH" -> {
					}
					default -> throw new AsmException(t.location(), t.text() + " is not a .PSECT attribute; "
						+ "they are ABS, REL, RO, RW, I, D, GBL, LCL, OVR, CON, SAV, LOW and HGH");
				}
				r.accept(TokenKind.COMMA);
			}
			if(any)
				given = new Section.Attributes(relocatable, readOnly, data, global, overlay, saved);
		} else {
			expectEnd(r);
		}

		Section s = m_sections.get(name);
		if(s == null) {
			Section.Attributes attributes = given != null ? given
				: psect ? Section.Attributes.PSECT_DEFAULT : Section.Attributes.CSECT_DEFAULT;
			s = new Section(name, attributes, at.location());
			m_sections.put(name, s);
		} else if(s == m_absolute) {
			throw new AsmException(at.location(), ". ABS. is the absolute section; use .ASECT");
		} else if(given != null && !given.equals(s.getAttributes())) {
			m_diagnostics.warning(WarningKind.PSECT_ATTRIBUTE_CHANGE, at.location(),
				"These attributes differ from those " + s.describe() + " was given"
					+ (s.getDeclaredAt() == null ? "" : " at " + s.getDeclaredAt().root()) + "; the new ones are used");
			s.setAttributes(given);
		}
		switchTo(s);
	}

	private void switchTo(Section s) {
		if(s != m_section) {
			m_section = s;
			m_afterTransfer = false;
			if(!m_state.isEnabled(Option.LSB))
				m_state.getSymbols().newLocalBlock();
		}
	}

	private void globals(TokenReader r) throws AsmException {
		do {
			Token t = r.next();
			if(!t.is(TokenKind.SYMBOL))
				throw new AsmException(t.location(), "Expected a symbol but found " + t.describe());
			if(SymbolTable.isRegisterName(t.text()))
				throw new AsmException(t.location(), t.text() + " is a register and cannot be global");
			m_state.getSymbols().get(t.text()).makeGlobal(t.location());
		} while(r.accept(TokenKind.COMMA) || r.peek().is(TokenKind.SYMBOL));
		expectEnd(r);
	}

	/** Give a symbol a value from a directive such as {@code .NARG}. */
	private void defineAssigned(Token name, Value v) throws AsmException {
		if(SymbolTable.isRegisterName(name.text()))
			throw new AsmException(name.location(), name.text() + " is a register and cannot be given a value");
		Symbol s = m_state.getSymbols().get(name.text());
		if(s.isDefined() && s.getKind() == Symbol.Kind.LABEL)
			throw new AsmException(name.location(), name.text() + " is a label and cannot be given a value");
		s.define(Symbol.Kind.ASSIGNED, v, name.location());
		s.setDefinedInExpansion(currentInput().isExpansion());
		m_entry.showValue(v);
	}

	private Token symbolOperand(TokenReader r, Token at) throws AsmException {
		Token name = r.next();
		if(!name.is(TokenKind.SYMBOL))
			throw new AsmException(name.location(), at.text() + " needs a symbol to set");
		r.accept(TokenKind.COMMA);
		return name;
	}

	private void narg(TokenReader r, Token at) throws AsmException {
		Token name = symbolOperand(r, at);
		expectEnd(r);
		InputSource macro = null;
		for(InputSource in : m_inputs) {
			if(in.kind() == Kind.MACRO) {
				macro = in;
				break;
			}
		}
		if(macro == null)
			throw new AsmException(at.location(), ".NARG outside a macro");
		defineAssigned(name, new Absolute(macro.argumentCount()));
	}

	private void nchr(TokenReader r, Token at) throws AsmException {
		Token name = symbolOperand(r, at);
		TokenReader.Argument a = r.readArgument();
		expectEnd(r);
		defineAssigned(name, new Absolute(a == null ? 0 : a.text().length()));
	}

	private void ntype(TokenReader r, Token at) throws AsmException {
		Token name = symbolOperand(r, at);
		Operand o = parser(r).parseOperand();
		expectEnd(r);
		defineAssigned(name, new Absolute(o.bits()));
	}

	private void ident(TokenReader r, Token at) throws AsmException {
		TokenReader.Delimited d = r.readDelimited();
		if(d == null || !d.terminated())
			throw new AsmException(at.location(), ".IDENT needs a delimited string, as in .IDENT /V01/");
		if(d.text().length() > 6 || !Rad50.canEncode(d.text()))
			m_diagnostics.error(d.location(), "An .IDENT is at most six RAD50 characters");
		expectEnd(r);
	}

	private void options(TokenReader r, Token at, boolean on) throws AsmException {
		if(r.atEndOfStatement())
			throw new AsmException(at.location(), at.text() + " needs an option");
		do {
			Token t = r.next();
			if(!t.is(TokenKind.SYMBOL))
				throw new AsmException(t.location(), "Expected an option but found " + t.describe());
			Option o = Option.find(t.text());
			if(o == null)
				throw new AsmException(t.location(), t.text() + " is not an option of " + at.text());
			if(!o.isSupported())
				m_diagnostics.warning(WarningKind.UNSUPPORTED_OPTION, t.location(), t.text() + " is not supported and is ignored");
			if(o == Option.LSB)
				m_state.getSymbols().newLocalBlock();
			m_state.setEnabled(o, on);
		} while(r.accept(TokenKind.COMMA) || r.peek().is(TokenKind.SYMBOL));
		expectEnd(r);
	}

	/** {@code .RADIX n}: n is always decimal, and 8 when left out. */
	private void radix(TokenReader r, Token at) throws AsmException {
		int radix = 8;
		if(!r.atEndOfStatement()) {
			Token t = r.next();
			if(!t.is(TokenKind.NUMBER))
				throw new AsmException(t.location(), ".RADIX needs a number");
			radix = ExpressionParser.parseNumber(t, 10);
		}
		expectEnd(r);
		if(radix != 2 && radix != 8 && radix != 10 && radix != 16)
			throw new AsmException(at.location(), "The radix can be 2, 8, 10 or 16, not " + radix);
		m_state.setRadix(radix);
	}

	private void end(TokenReader r, Token at) throws AsmException {
		if(!r.atEndOfStatement())
			m_transferAddress = parser(r).parseExpression();
		expectEnd(r);
		m_ended = true;
		m_endedAt = at.location();
	}

	private void remark(TokenReader r, Token at) throws AsmException {
		int c = r.peekCharacter();
		if(c < 0)
			throw new AsmException(at.location(), ".REM needs a delimiter, as in .REM /text/");
		r.readCharacters(1);
		InputSource in = currentInput();
		while(!r.skipPast((char) c)) {
			if(!r.nextLine())
				throw new AsmException(at.location(), "The .REM is not closed: there is no second '" + (char) c + "'");
			ListingEntry e = new ListingEntry(r.lineLocation(), r.lineText(), r.lineNumber());
			m_listing.add(e);
			if(in.kind() == Kind.FILE && in.reader().getExpandedFrom() == null)
				m_lastMainLine = r.lineLocation();
		}
		r.skipRestOfLine();
	}

	// -------------------------------------------------------------------------------------
	// Conditionals
	// -------------------------------------------------------------------------------------

	/** {@code .IF}, {@code .IFxx} and {@code .IIF}. */
	private void conditional(Directive d, TokenReader r, Token at) throws AsmException {
		String condition = d.getCondition();
		if(condition == null) {
			Token c = r.next();
			if(!c.is(TokenKind.SYMBOL))
				throw new AsmException(c.location(), d.getName() + " needs a condition: EQ, NE, GT, GE, LT, LE, DF, NDF, B, NB, IDN or DIF");
			condition = c.text();
			r.accept(TokenKind.COMMA);
		}

		boolean result;
		try {
			result = evaluateCondition(condition, r, at);
		} catch(AsmException x) {
			if(d == Directive.IIF)
				throw x;
			//-- Still open the block, or its .ENDC would be an error too.
			m_conditionals.open(false, at.location());
			throw x;
		}

		if(d == Directive.IIF) {
			Token sep = r.peek();
			if(sep.is(TokenKind.COMMA))
				r.next();
			if(result)
				processStatement(r);
			else
				r.skipRestOfLine();
			return;
		}
		expectEnd(r);
		m_conditionals.open(result, at.location());
	}

	private boolean evaluateCondition(String condition, TokenReader r, Token at) throws AsmException {
		return switch(condition) {
			case "EQ", "Z" -> signedNumber(r, at) == 0;
			case "NE", "NZ" -> signedNumber(r, at) != 0;
			case "GT", "G" -> signedNumber(r, at) > 0;
			case "GE" -> signedNumber(r, at) >= 0;
			case "LT", "L" -> signedNumber(r, at) < 0;
			case "LE" -> signedNumber(r, at) <= 0;
			case "DF" -> definedness(r);
			case "NDF" -> !definedness(r);
			case "B" -> isBlank(r.readArgument());
			case "NB" -> !isBlank(r.readArgument());
			case "IDN" -> identical(r, at);
			case "DIF" -> !identical(r, at);
			default -> throw new AsmException(at.location(), condition + " is not a condition: "
				+ "EQ, NE, GT, GE, LT, LE, DF, NDF, B, NB, IDN or DIF");
		};
	}

	private int signedNumber(TokenReader r, Token at) throws AsmException {
		if(r.atEndOfStatement())
			throw new AsmException(at.location(), "The condition needs a value");
		Expression e = parser(r).parseExpression();
		int n = numberNow(e, "The value of a condition");
		m_entry.showValue(new Absolute(n));
		return (short) n;
	}

	/**
	 * {@code DF a&b!c}: symbols joined by {@code &} (and) and {@code !} (or), left to right.
	 */
	private boolean definedness(TokenReader r) throws AsmException {
		boolean result = isDefined(r);
		for(;;) {
			if(r.accept(TokenKind.AMPERSAND))
				result = isDefined(r) && result;
			else if(r.accept(TokenKind.EXCLAMATION))
				result = isDefined(r) || result;
			else
				return result;
		}
	}

	private boolean isDefined(TokenReader r) throws AsmException {
		Token t = r.next();
		if(!t.is(TokenKind.SYMBOL) && !t.is(TokenKind.LOCAL_LABEL))
			throw new AsmException(t.location(), "Expected a symbol but found " + t.describe());
		if(SymbolTable.isRegisterName(t.text()) || t.text().equals("."))
			return true;
		Symbol s = m_state.getSymbols().get(t.text());
		s.markReferenced();
		if(s.isDefined())
			return true;
		if(m_macros.find(t.text()) != null)
			return true;
		m_undefinedWhenTested.putIfAbsent(s.getKey(), t.location());
		return false;
	}

	private static boolean isBlank(TokenReader.Argument a) {
		return a == null || a.text().isBlank();
	}

	private boolean identical(TokenReader r, Token at) throws AsmException {
		TokenReader.Argument a = r.readArgument();
		r.skipArgumentSeparator();
		TokenReader.Argument b = r.readArgument();
		if(a == null)
			throw new AsmException(at.location(), "IDN and DIF need two arguments");
		return a.text().equals(b == null ? "" : b.text());
	}

	// -------------------------------------------------------------------------------------
	// Input
	// -------------------------------------------------------------------------------------

	private void include(TokenReader r, Token at) throws AsmException {
		TokenReader.Delimited d = r.readDelimited();
		if(d == null || !d.terminated() || d.text().isBlank())
			throw new AsmException(at.location(), ".INCLUDE needs a file name between delimiters, as in .INCLUDE /DEFS.MAC/");
		expectEnd(r);
		Optional<SourceResolver.Source> source;
		try {
			source = m_resolver.include(d.text());
		} catch(IOException x) {
			throw new AsmException(d.location(), "Cannot read " + d.text() + ": " + x.getMessage());
		}
		if(source.isEmpty())
			throw new AsmException(d.location(), "There is no file " + d.text() + " to include");
		pushSource(new TokenReader(source.get().reader(), source.get().name(), at.location()), Kind.FILE, 0);
	}

	/** Leave the innermost macro or repeat expansion. */
	private void mexit(Token at) throws AsmException {
		InputSource target = null;
		for(InputSource in : m_inputs) {
			if(in.isExpansion()) {
				target = in;
				break;
			}
		}
		if(target == null)
			throw new AsmException(at.location(), ".MEXIT outside a macro or repeat block");
		while(m_inputs.peek() != target)
			popSource();
		m_conditionals.closeTo(target.conditionalDepth());
		popSource();
	}

	void pushSource(TokenReader reader, Kind kind, int argumentCount) {
		if(m_inputs.size() >= MAX_NESTING) {
			m_diagnostics.error(reader.getExpandedFrom() == null ? Location.inFile(reader.getSource(), 1, 0) : reader.getExpandedFrom(),
				"Macros, repeat blocks and included files are nested more than " + MAX_NESTING + " deep; is something calling itself?");
			return;
		}
		m_inputs.push(new InputSource(reader, kind, m_conditionals.depth(), argumentCount));
	}

	private void popSource() {
		m_inputs.pop().reader().close();
	}

	private void closeAllSources() {
		while(!m_inputs.isEmpty())
			popSource();
	}

	InputSource currentInput() {
		return m_inputs.peek();
	}

	/** Add a listing line for a line that was read but not assembled: a macro body. */
	void listLine(TokenReader r) {
		m_listing.add(new ListingEntry(r.lineLocation(), r.lineText(), r.lineNumber()));
	}

	// -------------------------------------------------------------------------------------
	// Emitting
	// -------------------------------------------------------------------------------------

	/** The location counter: the value of {@code .}. */
	Value currentLocation() {
		if(m_section.isRelocatable())
			return new Relocatable(m_section, m_section.getLocation());
		return new Absolute(m_section.getLocation());
	}

	ExpressionParser parser(TokenReader r) {
		return new ExpressionParser(r, m_state, currentLocation());
	}

	/** Put a word on an even address, saying so if it was not. */
	void alignWord(Location at, String what) {
		if((m_section.getLocation() & 1) != 0) {
			m_diagnostics.error(at, what + " at an odd address; it is moved to the next even one. Is a .EVEN missing?");
			m_section.advance(1);
		}
	}

	CodeWord emit(int size, Encoding encoding, int bits, int fieldMax, Expression e, Check check, Location at) {
		CodeWord w = new CodeWord(m_entry, m_section, m_section.getLocation(), size, encoding, bits, fieldMax, e, check, at);
		m_words.add(w);
		m_entry.addWord(w);
		m_section.advance(size);
		return w;
	}

	CodeWord emitConstant(int size, int value, Location at) {
		CodeWord w = CodeWord.constant(m_entry, m_section, m_section.getLocation(), size, value, at);
		m_words.add(w);
		m_entry.addWord(w);
		m_section.advance(size);
		return w;
	}

	/** An expression that is a constant now, or a word to be patched later. */
	CodeWord emitValue(int size, Expression e, Check check) {
		if(e instanceof Constant c && c.value() instanceof Absolute a && check == Check.NONE)
			return emitConstant(size, a.value(), e.location());
		return emit(size, Encoding.VALUE, 0, 0, e, check, e.location());
	}

	// -------------------------------------------------------------------------------------
	// Reachability
	// -------------------------------------------------------------------------------------

	/** An instruction is about to be stored: warn if nothing can get to it. */
	void checkReachable(Location at) {
		if(m_afterTransfer)
			m_diagnostics.warning(WarningKind.UNREACHABLE_CODE, at,
				"This instruction follows an unconditional transfer and has no label, so it cannot be reached");
		m_afterTransfer = false;
	}

	void markTransfer() {
		m_afterTransfer = true;
	}

	// -------------------------------------------------------------------------------------
	// For the end
	// -------------------------------------------------------------------------------------

	AssemblerOptions getOptions() {
		return m_options;
	}

	SourceResolver getResolver() {
		return m_resolver;
	}

	AssemblyState getState() {
		return m_state;
	}

	Diagnostics getDiagnostics() {
		return m_diagnostics;
	}

	ConditionalStack getConditionals() {
		return m_conditionals;
	}

	Collection<Section> getSections() {
		return m_sections.values();
	}

	List<ListingEntry> getListing() {
		return m_listing;
	}

	List<CodeWord> getWords() {
		return m_words;
	}

	Map<String, Location> getUndefinedWhenTested() {
		return m_undefinedWhenTested;
	}

	Collection<Macro> getMacros() {
		return m_macros.all();
	}

	Expression getTransferAddress() {
		return m_transferAddress;
	}

	Location getEndedAt() {
		return m_endedAt;
	}

	String getMainSource() {
		return m_mainSource;
	}

	String getTitle() {
		return m_title;
	}
}
