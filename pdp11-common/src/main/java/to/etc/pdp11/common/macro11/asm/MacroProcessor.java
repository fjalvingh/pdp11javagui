package to.etc.pdp11.common.macro11.asm;

import to.etc.pdp11.common.macro11.asm.InputSource.Kind;
import to.etc.pdp11.common.macro11.asm.Macro.Parameter;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Macros and repeat blocks: reading their bodies, and expanding them into text that is then
 * read like any other source.
 *
 * <h2>Substitution</h2>
 *
 * <p>Expansion is textual, as in DEC's assembler. Every symbol in the body whose name is a
 * parameter is replaced by the argument's text, wherever it is - in an operand, a string or a
 * comment. An apostrophe right before or after a parameter name glues it to its neighbour and
 * disappears: {@code A'B} with {@code A=X} becomes {@code XB}.</p>
 */
final class MacroProcessor {
	/** The first label {@code ?} parameters are given, as in DEC's assembler. */
	private static final int FIRST_CREATED_LOCAL = 30000;

	/** More lines than this from one repeat block is taken to be a mistake. */
	private static final long MAX_EXPANSION_LINES = 1_000_000;

	private final AssemblyRun m_run;

	private final Map<String, Macro> m_macros = new LinkedHashMap<>();

	private int m_createdLocalBlock = -1;

	private int m_nextCreatedLocal;

	MacroProcessor(AssemblyRun run) {
		m_run = run;
	}

	Macro find(String name) {
		return m_macros.get(name);
	}

	Collection<Macro> all() {
		return Collections.unmodifiableCollection(m_macros.values());
	}

	// -------------------------------------------------------------------------------------
	// Definitions
	// -------------------------------------------------------------------------------------

	/** {@code .MACRO name params}: read the body from the same input. */
	void define(TokenReader r, Token at) throws AsmException {
		Macro m = readDefinition(r, at.location(), false, true);
		register(m);
	}

	private void register(Macro m) {
		Macro old = m_macros.get(m.getName());
		if(old != null && !old.isFromLibrary() && !m.isFromLibrary())
			m_run.getDiagnostics().warning(WarningKind.MACRO_REDEFINED, m.getDefinedAt(),
				"This replaces the macro " + m.getName() + " defined at " + old.getDefinedAt().root());
		m_macros.put(m.getName(), m);
	}

	/**
	 * The name, parameters and body of a definition whose {@code .MACRO} has just been read.
	 *
	 * @param list whether the body lines go into the listing - not for a library's
	 */
	private Macro readDefinition(TokenReader r, Location at, boolean fromLibrary, boolean list) throws AsmException {
		Token name = r.next();
		if(!name.is(TokenKind.SYMBOL))
			throw new AsmException(name.location(), ".MACRO needs a name");

		List<Parameter> parameters = new ArrayList<>();
		AsmException bad = null;
		try {
			r.accept(TokenKind.COMMA);
			while(!r.atEndOfStatement()) {
				boolean createsLocal = false;
				Token t = r.next();
				if(t.is(TokenKind.OTHER) && t.text().equals("?")) {
					createsLocal = true;
					t = r.next();
				}
				if(!t.is(TokenKind.SYMBOL))
					throw new AsmException(t.location(), "Expected a parameter name but found " + t.describe());
				String defaultValue = null;
				if(r.accept(TokenKind.EQUALS)) {
					TokenReader.Argument a = r.readArgument();
					defaultValue = a == null ? "" : a.text();
				}
				for(Parameter p : parameters) {
					if(p.name().equals(t.text()))
						throw new AsmException(t.location(), "The macro already has a parameter " + t.text());
				}
				parameters.add(new Parameter(t.text(), defaultValue, createsLocal));
				r.accept(TokenKind.COMMA);
			}
		} catch(AsmException x) {
			//-- The body still has to be read, or its lines would be assembled.
			bad = x;
			r.skipRestOfLine();
		}
		List<String> body = readBody(r, at, true, name.text(), list);
		if(bad != null)
			throw bad;
		return new Macro(name.text(), parameters, body, at, fromLibrary);
	}

	/**
	 * The lines up to the {@code .ENDM} or {@code .ENDR} that closes this body.
	 *
	 * <p>Nested definitions and repeat blocks are counted, so a macro can define macros. Either
	 * {@code .ENDM} or {@code .ENDR} closes either kind of block: DEC's MACRO-11 accepts
	 * {@code .ENDM} at the end of a repeat block, and sources rely on it. For a macro,
	 * {@code .ENDM name} closes the definition of {@code name} whatever is still open inside
	 * it.</p>
	 */
	private List<String> readBody(TokenReader r, Location openedAt, boolean macro, String name, boolean list) throws AsmException {
		List<String> lines = new ArrayList<>();
		int depth = 1;
		for(;;) {
			if(!r.nextLine())
				throw new AsmException(openedAt, macro
					? "The macro " + name + " has no .ENDM"
					: "This repeat block has no .ENDR");
			if(list)
				m_run.listLine(r);
			Directive d = directiveOf(r);
			if(d != null) {
				if(d.isMacroDefinition() || d.isRepeat()) {
					depth++;
				} else if(d == Directive.ENDM || d == Directive.ENDR) {
					depth--;
					Token after = r.peek();
					if(macro && d == Directive.ENDM && after.is(TokenKind.SYMBOL)) {
						if(after.text().equals(name))
							depth = 0;
						else if(depth == 0)
							throw new AsmException(after.location(), ".ENDM " + after.text() + " ends the definition of " + name);
					}
				}
			}
			if(depth == 0)
				return lines;
			lines.add(r.lineText());
		}
	}

	/** The directive on a line, after any labels; null if there is none. */
	private static Directive directiveOf(TokenReader r) {
		while((r.peek().is(TokenKind.SYMBOL) || r.peek().is(TokenKind.LOCAL_LABEL)) && r.peek(1).is(TokenKind.COLON)) {
			r.next();
			r.next();
			r.accept(TokenKind.COLON);
		}
		Token t = r.peek();
		if(!t.is(TokenKind.SYMBOL))
			return null;
		Directive d = Directive.find(t.text());
		if(d != null)
			r.next();
		return d;
	}

	// -------------------------------------------------------------------------------------
	// Libraries
	// -------------------------------------------------------------------------------------

	/** {@code .MCALL a,b,c}: find each macro not already known. */
	void mcall(TokenReader r, Token at) throws AsmException {
		if(r.atEndOfStatement())
			throw new AsmException(at.location(), ".MCALL needs the names of macros");
		do {
			Token t = r.next();
			if(!t.is(TokenKind.SYMBOL))
				throw new AsmException(t.location(), "Expected a macro name but found " + t.describe());
			if(find(t.text()) == null && loadFromLibrary(t.text(), t.location()) == null)
				m_run.getDiagnostics().error(t.location(), "There is no macro " + t.text() + " to .MCALL");
		} while(r.accept(TokenKind.COMMA) || r.peek().is(TokenKind.SYMBOL));
		m_run.expectEnd(r);
	}

	/**
	 * Find a macro through the {@link SourceResolver} and define it.
	 *
	 * @return the macro, or null when there is none
	 */
	Macro loadFromLibrary(String name, Location at) throws AsmException {
		Optional<SourceResolver.Source> source;
		try {
			source = m_run.getResolver().macro(name);
		} catch(IOException x) {
			throw new AsmException(at, "Cannot read the macro " + name + ": " + x.getMessage());
		}
		if(source.isEmpty())
			return null;
		TokenReader lr = new TokenReader(source.get().reader(), source.get().name(), at);
		try {
			while(lr.nextLine()) {
				Directive d = directiveOf(lr);
				if(d != null && d.isMacroDefinition() && lr.peek().isSymbol(name)) {
					Macro m = readDefinition(lr, lr.lineLocation(), true, false);
					register(m);
					return m;
				}
			}
		} finally {
			lr.close();
		}
		throw new AsmException(at, source.get().name() + " has no .MACRO " + name);
	}

	/** {@code .MDELETE a,b}: forget macros. */
	void delete(TokenReader r) throws AsmException {
		do {
			Token t = r.next();
			if(!t.is(TokenKind.SYMBOL))
				throw new AsmException(t.location(), "Expected a macro name but found " + t.describe());
			m_macros.remove(t.text());
		} while(r.accept(TokenKind.COMMA) || r.peek().is(TokenKind.SYMBOL));
		m_run.expectEnd(r);
	}

	// -------------------------------------------------------------------------------------
	// Calls
	// -------------------------------------------------------------------------------------

	/**
	 * A macro call: read the arguments, substitute, and read the result as source.
	 *
	 * <p>Arguments are positional, or {@code name=value} for a parameter by name. A value
	 * written {@code \expr} is the value of the expression as a number in the current radix.</p>
	 */
	void expand(Macro m, TokenReader r, Location callAt) throws AsmException {
		m.markCalled();
		Map<String, String> values = new HashMap<>();
		int given = 0;
		int next = 0;
		List<Parameter> parameters = m.getParameters();
		for(;;) {
			Token t = r.peek();
			if(t.isEndOfLine())
				break;
			TokenReader.Argument a;
			if(t.is(TokenKind.SYMBOL) && r.peek(1).is(TokenKind.EQUALS)) {
				r.next();
				r.next();
				a = r.readArgument();
				Parameter p = m.findParameter(t.text());
				if(p == null)
					throw new AsmException(t.location(), "The macro " + m.getName() + " has no parameter " + t.text());
				if(values.containsKey(p.name()))
					throw new AsmException(t.location(), "The argument " + p.name() + " is given twice");
				values.put(p.name(), argumentValue(a, t.location()));
			} else {
				a = r.readArgument();
				if(a == null)
					break;
				while(next < parameters.size() && values.containsKey(parameters.get(next).name()))
					next++;
				if(next < parameters.size()) {
					values.put(parameters.get(next).name(), argumentValue(a, a.location()));
					next++;
				} else {
					m_run.getDiagnostics().warning(WarningKind.EXCESS_MACRO_ARGUMENTS, a.location(),
						"The macro " + m.getName() + " takes " + parameters.size() + " argument"
							+ (parameters.size() == 1 ? "" : "s") + "; this one is ignored");
				}
			}
			if(a != null && !a.terminated())
				throw new AsmException(a.location(), "The argument is not closed: there is no '>'");
			given++;
			r.skipArgumentSeparator();
		}

		int block = m_run.getState().getSymbols().getLocalBlock();
		if(block != m_createdLocalBlock) {
			m_createdLocalBlock = block;
			m_nextCreatedLocal = FIRST_CREATED_LOCAL;
		}
		for(Parameter p : parameters) {
			if(values.containsKey(p.name()))
				continue;
			if(p.createsLocal())
				values.put(p.name(), (m_nextCreatedLocal++) + "$");
			else
				values.put(p.name(), p.defaultValue() == null ? "" : p.defaultValue());
		}

		String text = substitute(m.getBody(), values);
		m_run.pushSource(new TokenReader(new StringReader(text), m.getName(), callAt), Kind.MACRO, given);
	}

	/** The text an argument stands for: as written, or for {@code \expr} the value. */
	private String argumentValue(TokenReader.Argument a, Location at) throws AsmException {
		if(a == null)
			return "";
		String text = a.text();
		if(a.bracketed() || !text.startsWith("\\"))
			return text;
		TokenReader er = new TokenReader(new StringReader(text.substring(1)), at.source(), at.expandedFrom());
		er.nextLine();
		Expression e = m_run.parser(er).parseExpression();
		m_run.expectEnd(er);
		int n = m_run.numberNow(e, "A \\ argument");
		return Integer.toString(n, m_run.getState().getRadix()).toUpperCase(Locale.ROOT);
	}

	/** The body with every parameter replaced by its value. */
	static String substitute(List<String> body, Map<String, String> values) {
		StringBuilder out = new StringBuilder();
		for(String line : body) {
			int i = 0;
			while(i < line.length()) {
				char c = line.charAt(i);
				if(!TokenReader.isSymbolChar(c) || (i > 0 && TokenReader.isSymbolChar(line.charAt(i - 1)))) {
					out.append(c);
					i++;
					continue;
				}
				int end = i;
				while(end < line.length() && TokenReader.isSymbolChar(line.charAt(end)))
					end++;
				String word = line.substring(i, end);
				String value = TokenReader.isDigit(c) ? null : values.get(word.toUpperCase(Locale.ROOT));
				if(value == null) {
					out.append(word);
				} else {
					if(out.length() > 0 && out.charAt(out.length() - 1) == '\'')
						out.setLength(out.length() - 1);
					out.append(value);
					if(end < line.length() && line.charAt(end) == '\'')
						end++;
				}
				i = end;
			}
			out.append('\n');
		}
		return out.toString();
	}

	// -------------------------------------------------------------------------------------
	// Repeat blocks
	// -------------------------------------------------------------------------------------

	/** {@code .REPT n}, {@code .IRP sym,<list>}, {@code .IRPC sym,<string>}. */
	void repeat(Directive d, TokenReader r, Token at) throws AsmException {
		switch(d) {
			case REPT -> {
				if(r.atEndOfStatement()) {
					readBody(r, at.location(), false, null, true);
					throw new AsmException(at.location(), ".REPT needs a count");
				}
				int count;
				try {
					Expression e = m_run.parser(r).parseExpression();
					m_run.expectEnd(r);
					count = (short) m_run.numberNow(e, "The repeat count");
				} catch(AsmException x) {
					r.skipRestOfLine();
					readBody(r, at.location(), false, null, true);
					throw x;
				}
				List<String> body = readBody(r, at.location(), false, null, true);
				checkSize(count, body.size(), at);
				StringBuilder text = new StringBuilder();
				for(int i = 0; i < count; i++)
					text.append(substitute(body, Map.of()));
				push(text, ".REPT", at);
			}
			case IRP, IRPC -> {
				Token symbol = r.next();
				AsmException bad = null;
				TokenReader.Argument list = null;
				if(!symbol.is(TokenKind.SYMBOL)) {
					bad = new AsmException(symbol.location(), d.getName() + " needs a symbol to substitute");
				} else {
					r.skipArgumentSeparator();
					list = r.readArgument();
					if(list != null && !list.terminated())
						bad = new AsmException(list.location(), "The list is not closed: there is no '>'");
				}
				r.skipRestOfLine();
				List<String> body = readBody(r, at.location(), false, null, true);
				if(bad != null)
					throw bad;
				List<String> items = new ArrayList<>();
				if(list != null && d == Directive.IRP) {
					for(TokenReader.Argument item : splitList(list.text(), list.location()))
						items.add(argumentValue(item, item.location()));
				} else if(list != null) {
					items.addAll(characters(list.text()));
				}
				checkSize(items.size(), body.size(), at);
				StringBuilder text = new StringBuilder();
				for(String item : items)
					text.append(substitute(body, Map.of(symbol.text(), item)));
				push(text, d.getName(), at);
			}
			default -> throw new IllegalArgumentException(d + " is not a repeat block");
		}
	}

	private static void checkSize(long count, int lines, Token at) throws AsmException {
		if(count * lines > MAX_EXPANSION_LINES)
			throw new AsmException(at.location(), "This repeats to more than " + MAX_EXPANSION_LINES + " lines");
	}

	private void push(StringBuilder text, String name, Token at) {
		if(text.isEmpty())
			return;
		m_run.pushSource(new TokenReader(new StringReader(text.toString()), name, at.location()), Kind.REPEAT, 0);
	}

	/**
	 * The items of an {@code .IRP} list: arguments, separated as a macro call's are, and like a
	 * macro call's evaluated when written {@code \expr}.
	 */
	private static List<TokenReader.Argument> splitList(String list, Location at) {
		TokenReader lr = new TokenReader(new StringReader(list), at.source(), at.expandedFrom());
		List<TokenReader.Argument> items = new ArrayList<>();
		if(!lr.nextLine())
			return items;
		for(;;) {
			TokenReader.Argument a = lr.readArgument();
			if(a == null)
				break;
			items.add(a);
			lr.skipArgumentSeparator();
		}
		return items;
	}

	private static List<String> characters(String s) {
		List<String> l = new ArrayList<>();
		for(int i = 0; i < s.length(); i++)
			l.add(String.valueOf(s.charAt(i)));
		return l;
	}
}
