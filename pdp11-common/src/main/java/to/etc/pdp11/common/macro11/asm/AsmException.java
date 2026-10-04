package to.etc.pdp11.common.macro11.asm;

/**
 * A statement cannot be assembled.
 *
 * <p>Thrown from deep inside the parsing of a statement and caught at the statement, which
 * records it as an error and goes on with the next line. It never escapes the assembler: an
 * assembly always finishes, and reports every problem it found.</p>
 */
final class AsmException extends Exception {
	private final Location m_location;

	AsmException(Location location, String message) {
		super(message, null, false, false);
		m_location = location;
	}

	Location getLocation() {
		return m_location;
	}
}
