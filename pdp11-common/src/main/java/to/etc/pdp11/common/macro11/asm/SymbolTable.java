package to.etc.pdp11.common.macro11.asm;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The user's symbols, and the registers.
 *
 * <h2>Local labels</h2>
 *
 * <p>A local label such as {@code 10$} only means something inside its local symbol block. A
 * block ends at the next ordinary label, at a change of program section, and at
 * {@code .ENABL LSB}/{@code .DSABL LSB}; {@code .ENABL LSB} also stops ordinary labels from
 * ending one. So the table keys a local label by its name and the number of the block it is in,
 * and the name the user sees stays {@code 10$}.</p>
 */
final class SymbolTable {
	/** The register names, which are not user symbols and cannot be redefined. */
	private static final Map<String, Integer> REGISTERS = Map.of(
		"R0", 0, "R1", 1, "R2", 2, "R3", 3, "R4", 4, "R5", 5, "SP", 6, "PC", 7);

	private final Map<String, Symbol> m_symbols = new LinkedHashMap<>();

	private int m_localBlock;

	/** The register a name is, or -1. */
	static int registerNumber(String name) {
		Integer n = REGISTERS.get(name);
		return n == null ? -1 : n;
	}

	static boolean isRegisterName(String name) {
		return REGISTERS.containsKey(name);
	}

	int getLocalBlock() {
		return m_localBlock;
	}

	/** Start a new local symbol block. */
	void newLocalBlock() {
		m_localBlock++;
	}

	/** The table key for a name as written: a local label is qualified by its block. */
	String keyOf(String name) {
		return isLocalName(name) ? name + "@" + m_localBlock : name;
	}

	static boolean isLocalName(String name) {
		return !name.isEmpty() && TokenReader.isDigit(name.charAt(0)) && name.endsWith("$");
	}

	/** The symbol with this name, as written, in the current block; null if never seen. */
	Symbol find(String name) {
		return m_symbols.get(keyOf(name));
	}

	Symbol findByKey(String key) {
		return m_symbols.get(key);
	}

	/** The symbol with this name, made (undefined) if it was never seen. */
	Symbol get(String name) {
		return m_symbols.computeIfAbsent(keyOf(name), k -> new Symbol(k, name, isLocalName(name)));
	}

	Collection<Symbol> all() {
		return Collections.unmodifiableCollection(m_symbols.values());
	}
}
