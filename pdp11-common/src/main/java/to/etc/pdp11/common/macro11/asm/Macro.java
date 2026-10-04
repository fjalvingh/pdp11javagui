package to.etc.pdp11.common.macro11.asm;

import java.util.List;

/**
 * A macro definition: its parameters and the text of its body.
 */
final class Macro {
	/**
	 * One parameter.
	 *
	 * @param name          the name, upper case
	 * @param defaultValue  the text used when the call gives none, or null
	 * @param createsLocal  written {@code ?NAME}: when the call gives no value, a new local label
	 *                      is made up for it
	 */
	record Parameter(String name, String defaultValue, boolean createsLocal) {
	}

	private final String m_name;

	private final List<Parameter> m_parameters;

	private final List<String> m_body;

	private final Location m_definedAt;

	private final boolean m_fromLibrary;

	private boolean m_called;

	Macro(String name, List<Parameter> parameters, List<String> body, Location definedAt, boolean fromLibrary) {
		m_name = name;
		m_parameters = List.copyOf(parameters);
		m_body = List.copyOf(body);
		m_definedAt = definedAt;
		m_fromLibrary = fromLibrary;
	}

	String getName() {
		return m_name;
	}

	List<Parameter> getParameters() {
		return m_parameters;
	}

	Parameter findParameter(String name) {
		for(Parameter p : m_parameters) {
			if(p.name().equals(name))
				return p;
		}
		return null;
	}

	List<String> getBody() {
		return m_body;
	}

	Location getDefinedAt() {
		return m_definedAt;
	}

	/** Brought in by {@code .MCALL} rather than defined in the source. */
	boolean isFromLibrary() {
		return m_fromLibrary;
	}

	boolean isCalled() {
		return m_called;
	}

	void markCalled() {
		m_called = true;
	}
}
