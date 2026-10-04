package to.etc.pdp11.common.macro11.asm;

import to.etc.pdp11.common.macro11.asm.CodeWord.Encoding;
import to.etc.pdp11.common.macro11.asm.Evaluator.NotYetKnownException;
import to.etc.pdp11.common.macro11.asm.Expression.SymbolReference;
import to.etc.pdp11.common.macro11.asm.Value.Register;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything that has to wait until the whole source has been read.
 *
 * <p>In order: give the relocatable sections addresses; patch every word whose value was not
 * known when it was stored; look at the program as a whole for what no single statement shows -
 * undefined and unused symbols, code stored twice at one address, names DEC's assembler would
 * confuse; and finally write the listing and the program out.</p>
 */
final class Finisher {
	private final AssemblyRun m_run;

	private final Diagnostics m_diagnostics;

	private final Evaluator m_evaluator;

	private final SymbolTable m_symbols;

	Finisher(AssemblyRun run) {
		m_run = run;
		m_diagnostics = run.getDiagnostics();
		m_evaluator = run.getState().getEvaluator();
		m_symbols = run.getState().getSymbols();
	}

	AssemblyResult finish() {
		placeSections();
		resolveDeferredSymbols();
		for(CodeWord w : m_run.getWords())
			resolve(w);
		resolveLimits();
		Integer transfer = transferAddress();

		checkGlobals();
		checkUnused();
		checkTestedBeforeDefinition();
		checkSixCharacterNames();
		checkUnusedMacros();
		checkOverlaps();

		List<Diagnostic> diagnostics = m_diagnostics.list();
		ListingWriter.Written listing = new ListingWriter().write(m_run.getListing(), diagnostics, this::shownValue);

		//-- In the order of the listing, which is the order of the source; the first one in it is
		//-- the one worth showing first.
		List<Diagnostic> ordered = new ArrayList<>(diagnostics);
		ordered.sort(Comparator.comparingInt(d -> listing.diagnosticLines().getOrDefault(d, Integer.MAX_VALUE)));

		return new AssemblyResult(ordered, listing.diagnosticLines(), listing.lines(), listing.sourceLineOf(),
			words(listing), transfer, symbolValues(), m_run.getTitle());
	}

	// -------------------------------------------------------------------------------------
	// Placing
	// -------------------------------------------------------------------------------------

	/**
	 * The relocatable sections, one after the other from the relocation base, in the order the
	 * source opened them, each on an even address.
	 */
	private void placeSections() {
		int base = m_run.getOptions().relocationBase();
		for(Section s : m_run.getSections()) {
			if(!s.isRelocatable())
				continue;
			base = (base + 1) & ~1;
			s.place(base & 0xFFFF);
			base += s.getSize();
			if(base > 0x10000)
				m_diagnostics.error(s.getDeclaredAt() == null ? Location.inFile(m_run.getMainSource(), 1, 0) : s.getDeclaredAt(),
					"The program does not fit in 64K bytes: " + s.describe() + " ends at " + Integer.toOctalString(base));
		}
	}

	// -------------------------------------------------------------------------------------
	// Patching
	// -------------------------------------------------------------------------------------

	/** Work out every symbol that was defined in terms of something further down. */
	private void resolveDeferredSymbols() {
		for(Symbol s : m_symbols.all()) {
			if(s.isDefined() && !s.hasValue() && s.getExpression() != null) {
				try {
					m_evaluator.evaluateFinal(s.getExpression());
				} catch(NotYetKnownException x) {
					undefined(x, s.getDefinedAt(), " in the definition of " + s.getName());
				} catch(AsmException x) {
					m_diagnostics.error(x);
				}
			}
		}
	}

	private void resolve(CodeWord w) {
		Expression e = w.getExpression();
		if(e == null || w.getEncoding() == Encoding.LIMIT)
			return;
		Value v;
		try {
			v = m_evaluator.evaluateFinal(e);
		} catch(NotYetKnownException x) {
			undefined(x, x.getLocation(), "");
			return;
		} catch(AsmException x) {
			m_diagnostics.error(x);
			return;
		}

		int address = w.getAddress();
		switch(w.getEncoding()) {
			case VALUE -> {
				int n = number(v, w);
				check(w, n);
				w.setValue(n);
			}
			case FIELD -> {
				int n = number(v, w);
				if(n > w.getFieldMax()) {
					m_diagnostics.error(w.getLocation(), "The value " + Integer.toOctalString(n) + " is out of range: it must be 0 to "
						+ Integer.toOctalString(w.getFieldMax()));
				}
				w.setValue(w.getBits() | (n & w.getFieldMax()));
			}
			case DISPLACEMENT -> {
				if(v instanceof Register) {
					m_diagnostics.error(w.getLocation(), "A register is not an address");
					return;
				}
				int target = v.number();
				check(w, target);
				w.setValue(target - (address + 2));
			}
			case BRANCH -> branch(w, v, address);
			case SOB -> sob(w, v, address);
			case LIMIT -> throw new IllegalStateException("LIMIT has no expression");
		}
	}

	/** The number a value stands for in a word, warning when it is a register. */
	private int number(Value v, CodeWord w) {
		if(v instanceof Register r) {
			m_diagnostics.warning(WarningKind.REGISTER_AS_VALUE, w.getLocation(),
				"R" + r.number() + " is a register; here it is used as the number " + r.number());
		}
		return v.number();
	}

	private void check(CodeWord w, int n) {
		int word = n & 0xFFFF;
		switch(w.getCheck()) {
			case NONE, DATA_WORD -> {
			}
			case BYTE_DATA -> {
				if(word > 0377 && word < 0177400)
					m_diagnostics.error(w.getLocation(), "The value " + Integer.toOctalString(word) + " does not fit in a byte");
			}
			case BYTE_IMMEDIATE -> {
				if(word > 0377 && word < 0177400)
					m_diagnostics.warning(WarningKind.BYTE_IMMEDIATE_TRUNCATED, w.getLocation(),
						"A byte instruction only uses the low byte of " + Integer.toOctalString(word));
			}
			case WORD_ADDRESS -> {
				if((word & 1) != 0)
					m_diagnostics.warning(WarningKind.ODD_WORD_ADDRESS, w.getLocation(),
						"The word at " + Integer.toOctalString(word) + " is at an odd address, which traps");
			}
			case JUMP_TARGET -> {
				if((word & 1) != 0)
					m_diagnostics.error(w.getLocation(), "The jump target " + Integer.toOctalString(word) + " is at an odd address");
			}
		}
	}

	/**
	 * Whether a branch from {@code w} may go to {@code target} at all: only within its section,
	 * as in DEC's assembler, because a branch is a distance and sections move.
	 */
	private boolean sameSection(CodeWord w, Value target) {
		if(target instanceof Register) {
			m_diagnostics.error(w.getLocation(), "A register is not something to branch to");
			return false;
		}
		Section from = w.getSection().isRelocatable() ? w.getSection() : null;
		Section to = target.relocationSection();
		if(from == to)
			return true;
		String there = to == null ? "an absolute address" : to.describe();
		m_diagnostics.error(w.getLocation(), "A branch in " + w.getSection().describe() + " cannot go to " + there);
		return false;
	}

	private void branch(CodeWord w, Value target, int address) {
		if(!sameSection(w, target))
			return;
		int to = target.number();
		int offset = to - (address + 2);
		if((offset & 1) != 0) {
			m_diagnostics.error(w.getLocation(), "The branch target " + Integer.toOctalString(to) + " is at an odd address");
			return;
		}
		if(offset < -256 || offset > 254) {
			m_diagnostics.error(w.getLocation(), "The branch target is " + words(offset) + " away; "
				+ "a branch reaches 128 words back and 127 forward. Use JMP");
			return;
		}
		if(offset == 0 && w.getBits() == Instruction.BR.getOpcode())
			m_diagnostics.warning(WarningKind.NO_EFFECT, w.getLocation(), "BR to the next instruction does nothing");
		w.setValue(w.getBits() | ((offset >> 1) & 0377));
	}

	private void sob(CodeWord w, Value target, int address) {
		if(!sameSection(w, target))
			return;
		int to = target.number();
		int offset = (address + 2) - to;
		if((offset & 1) != 0) {
			m_diagnostics.error(w.getLocation(), "The SOB target " + Integer.toOctalString(to) + " is at an odd address");
			return;
		}
		if(offset < 0 || offset > 126) {
			m_diagnostics.error(w.getLocation(), offset < 0
				? "SOB can only go backwards"
				: "The SOB target is " + words(-offset) + " away; SOB reaches 63 words back");
			return;
		}
		w.setValue(w.getBits() | (offset >> 1));
	}

	private static String words(int byteOffset) {
		return Math.abs(byteOffset / 2) + " words";
	}

	/** {@code .LIMIT}: the lowest address of the program, and the one after its highest. */
	private void resolveLimits() {
		int low = Integer.MAX_VALUE;
		int high = 0;
		for(CodeWord w : m_run.getWords()) {
			low = Math.min(low, w.getAddress());
			high = Math.max(high, w.getAddress() + w.getSize());
		}
		for(CodeWord w : m_run.getWords()) {
			if(w.getEncoding() == Encoding.LIMIT)
				w.setValue(w.getBits() == 0 ? low : (high + 1) & ~1);
		}
	}

	private Integer transferAddress() {
		Expression e = m_run.getTransferAddress();
		if(e == null)
			return null;
		try {
			Value v = m_evaluator.evaluateFinal(e);
			if(v instanceof Register)
				throw new AsmException(e.location(), "The start address cannot be a register");
			int a = v.number();
			if((a & 1) != 0)
				m_diagnostics.warning(WarningKind.ODD_TRANSFER_ADDRESS, e.location(),
					"The start address " + Integer.toOctalString(a) + " is odd; the program cannot start there");
			return a;
		} catch(NotYetKnownException x) {
			undefined(x, x.getLocation(), " in the start address");
		} catch(AsmException x) {
			m_diagnostics.error(x);
		}
		return null;
	}

	/** Say that something is undefined, and as well as possible why. */
	private void undefined(NotYetKnownException x, Location at, String where) {
		SymbolReference ref = x.getReference();
		if(ref == null) {
			m_diagnostics.error(at, x.getMessage() + where);
			return;
		}
		Symbol s = m_symbols.findByKey(ref.key());
		String name = ref.name();
		if(s != null && s.isGlobal()) {
			m_diagnostics.error(ref.location(), name + " is declared global but not defined here, and there is no linker to supply it");
		} else if(SymbolTable.isLocalName(name) && hasLocalElsewhere(ref.key(), name)) {
			m_diagnostics.error(ref.location(), name + " is not defined in this local symbol block; there is one in another block. "
				+ "A label between them starts a new block (.ENABL LSB prevents that)");
		} else {
			m_diagnostics.error(ref.location(), "Undefined symbol " + name + where);
		}
	}

	private boolean hasLocalElsewhere(String key, String name) {
		String prefix = name + "@";
		for(Symbol s : m_symbols.all()) {
			if(s.isDefined() && s.getKey().startsWith(prefix) && !s.getKey().equals(key))
				return true;
		}
		return false;
	}

	// -------------------------------------------------------------------------------------
	// The program as a whole
	// -------------------------------------------------------------------------------------

	private void checkGlobals() {
		for(Symbol s : m_symbols.all()) {
			if(s.isGlobal() && !s.isDefined())
				m_diagnostics.error(s.getDeclaredGlobalAt(), s.getName() + " is declared global but never defined, "
					+ "and there is no linker to supply it");
		}
	}

	private void checkUnused() {
		for(Symbol s : m_symbols.all()) {
			if(!s.isDefined() || s.isReferenced() || s.isGlobal() || s.isDefinedInExpansion())
				continue;
			if(s.getKind() == Symbol.Kind.LABEL)
				m_diagnostics.warning(WarningKind.UNUSED_LABEL, s.getDefinedAt(), "The label " + s.getName() + " is not used");
			else
				m_diagnostics.warning(WarningKind.UNUSED_SYMBOL, s.getDefinedAt(), "The symbol " + s.getName() + " is not used");
		}
	}

	private void checkTestedBeforeDefinition() {
		for(Map.Entry<String, Location> e : m_run.getUndefinedWhenTested().entrySet()) {
			Symbol s = m_symbols.findByKey(e.getKey());
			if(s != null && s.isDefined())
				m_diagnostics.warning(WarningKind.CONDITION_ON_LATER_DEFINITION, e.getValue(),
					s.getName() + " is only defined further down, at " + s.getDefinedAt().root() + ", so here it counts as undefined");
		}
	}

	private void checkSixCharacterNames() {
		Map<String, Symbol> first = new HashMap<>();
		for(Symbol s : m_symbols.all()) {
			if(!s.isDefined() || s.isLocal() || s.getName().length() <= 6)
				continue;
			String six = s.getName().substring(0, 6).toUpperCase(Locale.ROOT);
			Symbol other = first.putIfAbsent(six, s);
			if(other != null)
				m_diagnostics.warning(WarningKind.SYMBOL_NOT_UNIQUE_IN_SIX, s.getDefinedAt(),
					s.getName() + " and " + other.getName() + " are the same in their first six characters; "
						+ "DEC's MACRO-11 would take them to be one symbol");
		}
		//-- A short name is its own first six characters.
		for(Symbol s : m_symbols.all()) {
			if(!s.isDefined() || s.isLocal() || s.getName().length() > 6)
				continue;
			Symbol other = first.get(s.getName());
			if(other != null)
				m_diagnostics.warning(WarningKind.SYMBOL_NOT_UNIQUE_IN_SIX, other.getDefinedAt(),
					other.getName() + " and " + s.getName() + " are the same in their first six characters; "
						+ "DEC's MACRO-11 would take them to be one symbol");
		}
	}

	private void checkUnusedMacros() {
		for(Macro m : m_run.getMacros()) {
			if(!m.isFromLibrary() && !m.isCalled())
				m_diagnostics.warning(WarningKind.UNUSED_MACRO, m.getDefinedAt(), "The macro " + m.getName() + " is never called");
		}
	}

	/** Two statements that store at the same address. */
	private void checkOverlaps() {
		Map<Integer, CodeWord> owner = new HashMap<>();
		for(CodeWord w : m_run.getWords()) {
			int a = w.getAddress();
			for(int i = 0; i < w.getSize(); i++) {
				CodeWord before = owner.put((a + i) & 0xFFFF, w);
				if(before != null && before.getEntry() != w.getEntry()) {
					m_diagnostics.warning(WarningKind.OVERLAPPING_CODE, w.getLocation(),
						"This stores at " + Integer.toOctalString(a + i) + ", where " + before.getLocation().root() + " already stored");
					break;
				}
			}
		}
	}

	// -------------------------------------------------------------------------------------
	// The result
	// -------------------------------------------------------------------------------------

	private Integer shownValue(ListingEntry e) {
		Expression x = e.getShownValue();
		if(x == null)
			return null;
		try {
			return m_evaluator.evaluateFinal(x).number();
		} catch(NotYetKnownException | AsmException ex) {
			//-- Already reported where it was used; the listing simply has no value to show.
			return null;
		}
	}

	/** A word of the program being put together from what was stored at its two bytes. */
	private static final class Cell {
		private final int m_listingLine;

		private int m_value;

		private Cell(int listingLine) {
			m_listingLine = listingLine;
		}

		private void store(CodeWord w) {
			if(w.getSize() == 2)
				m_value = w.getValue();
			else if((w.getAddress() & 1) == 0)
				m_value = (m_value & 0xFF00) | (w.getValue() & 0xFF);
			else
				m_value = (m_value & 0x00FF) | ((w.getValue() & 0xFF) << 8);
		}
	}

	/**
	 * The program as words: two bytes at an odd and the even address below it are one word, and
	 * a byte with no partner counts the other half as zero. Where two statements stored at one
	 * address, the later one wins, as it would in memory.
	 */
	private List<AssemblyResult.Word> words(ListingWriter.Written listing) {
		Map<Integer, Cell> byAddress = new LinkedHashMap<>();
		for(CodeWord w : m_run.getWords()) {
			int line = listing.wordLines().getOrDefault(w, -1);
			byAddress.computeIfAbsent(w.getAddress() & ~1, k -> new Cell(line)).store(w);
		}
		List<AssemblyResult.Word> words = new ArrayList<>();
		byAddress.forEach((address, cell) -> words.add(new AssemblyResult.Word(address, cell.m_value, cell.m_listingLine)));
		return words;
	}

	private Map<String, Integer> symbolValues() {
		Map<String, Integer> values = new TreeMap<>();
		for(Symbol s : m_symbols.all()) {
			if(!s.isDefined() || s.isLocal() || !s.hasValue())
				continue;
			Value v = s.getValue();
			if(v instanceof Register)
				continue;
			values.put(s.getName(), v.number());
		}
		return values;
	}
}
