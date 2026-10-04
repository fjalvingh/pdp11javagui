package to.etc.pdp11.common.macro11.asm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects the diagnostics of one assembly.
 *
 * <p>The same complaint about the same place is kept once: a statement can be looked at more
 * than once - an expression is checked when it is read and again when its value is finally
 * known - and the user should not see the same mistake twice.</p>
 */
final class Diagnostics {
	private final List<Diagnostic> m_list = new ArrayList<>();

	private final Set<Diagnostic> m_seen = new HashSet<>();

	private final Set<WarningKind> m_disabled;

	Diagnostics(Set<WarningKind> disabled) {
		m_disabled = Set.copyOf(disabled);
	}

	void error(Location location, String message) {
		add(new Diagnostic(Severity.ERROR, null, location, message));
	}

	void error(AsmException x) {
		error(x.getLocation(), x.getMessage());
	}

	void warning(WarningKind kind, Location location, String message) {
		if(m_disabled.contains(kind))
			return;
		add(new Diagnostic(Severity.WARNING, kind, location, message));
	}

	private void add(Diagnostic d) {
		if(m_seen.add(d))
			m_list.add(d);
	}

	List<Diagnostic> list() {
		return List.copyOf(m_list);
	}
}
