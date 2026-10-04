package to.etc.pdp11.common.macro11.asm;

/**
 * A user symbol: a label, or a name given a value with {@code =}.
 *
 * <p>A symbol may be referred to before it is defined, and a symbol defined with {@code =} may
 * be defined in terms of symbols that come later still. Such a symbol is "deferred": its
 * {@link #getExpression() expression} is kept and evaluated once the whole source has been read.
 * Everything else has its value from the moment it is defined.</p>
 */
final class Symbol {
	enum Kind {
		/** {@code name:} - a location, which cannot be redefined. */
		LABEL,

		/** {@code name = value} - may be given a new value later. */
		ASSIGNED
	}

	private final String m_key;

	private final String m_name;

	private final boolean m_local;

	private Kind m_kind;

	private Value m_value;

	private Expression m_expression;

	private Location m_definedAt;

	private boolean m_global;

	private Location m_declaredGlobalAt;

	private boolean m_referenced;

	/** For deferred symbols: being evaluated right now, so meeting it again is a cycle. */
	private boolean m_resolving;

	/** Defined by text a macro or repeat block produced, rather than by the user directly. */
	private boolean m_definedInExpansion;

	Symbol(String key, String name, boolean local) {
		m_key = key;
		m_name = name;
		m_local = local;
	}

	String getKey() {
		return m_key;
	}

	/** The name as the user wrote it. */
	String getName() {
		return m_name;
	}

	boolean isLocal() {
		return m_local;
	}

	Kind getKind() {
		return m_kind;
	}

	boolean isDefined() {
		return m_kind != null;
	}

	/** Defined, with a value known now. */
	boolean hasValue() {
		return m_value != null;
	}

	Value getValue() {
		return m_value;
	}

	/** The expression of a deferred symbol, or null. */
	Expression getExpression() {
		return m_expression;
	}

	Location getDefinedAt() {
		return m_definedAt;
	}

	void define(Kind kind, Value value, Location at) {
		m_kind = kind;
		m_value = value;
		m_expression = null;
		m_definedAt = at;
	}

	void defineDeferred(Expression expression, Location at) {
		m_kind = Kind.ASSIGNED;
		m_value = null;
		m_expression = expression;
		m_definedAt = at;
	}

	/** Give a deferred symbol the value its expression came to. */
	void resolve(Value value) {
		m_value = value;
	}

	boolean isGlobal() {
		return m_global;
	}

	Location getDeclaredGlobalAt() {
		return m_declaredGlobalAt;
	}

	void makeGlobal(Location at) {
		if(!m_global)
			m_declaredGlobalAt = at;
		m_global = true;
	}

	boolean isReferenced() {
		return m_referenced;
	}

	void markReferenced() {
		m_referenced = true;
	}

	boolean isDefinedInExpansion() {
		return m_definedInExpansion;
	}

	void setDefinedInExpansion(boolean inExpansion) {
		m_definedInExpansion = inExpansion;
	}

	boolean isResolving() {
		return m_resolving;
	}

	void setResolving(boolean resolving) {
		m_resolving = resolving;
	}

	@Override
	public String toString() {
		return m_name + (m_value != null ? "=" + m_value : m_expression != null ? "=<deferred>" : " (undefined)");
	}
}
