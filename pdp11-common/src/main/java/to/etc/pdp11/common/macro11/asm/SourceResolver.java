package to.etc.pdp11.common.macro11.asm;

import java.io.IOException;
import java.io.Reader;
import java.util.Optional;

/**
 * Finds the text that {@code .INCLUDE} and {@code .MCALL} ask for.
 *
 * <p>An interface rather than a directory, because where the text lives is the caller's
 * business: the IDE may hold a file in an editor that has not been saved, and a test keeps its
 * sources in strings.</p>
 */
public interface SourceResolver {
	/**
	 * Text found by name.
	 *
	 * @param name   how it is called in locations and messages
	 * @param reader the text; the assembler closes it
	 */
	record Source(String name, Reader reader) {
	}

	/**
	 * A macro library file, as bytes: it is binary.
	 *
	 * @param name how it is called in messages
	 */
	record LibraryFile(String name, byte[] data) {
	}

	/** A resolver that finds nothing. */
	SourceResolver NONE = new SourceResolver() {
		@Override
		public Optional<Source> include(String name) {
			return Optional.empty();
		}

		@Override
		public Optional<Source> macro(String name) {
			return Optional.empty();
		}
	};

	/** The file {@code .INCLUDE} names, or empty when there is none. */
	Optional<Source> include(String name) throws IOException;

	/**
	 * Text holding the definition of macro {@code name}, for {@code .MCALL}; empty when there is
	 * none. The text may hold other things too: the assembler looks for {@code .MACRO name}.
	 */
	Optional<Source> macro(String name) throws IOException;

	/**
	 * The macro library {@code .LIBRARY} names, or empty when there is none.
	 *
	 * <p>Finding none by default, so that a resolver that has no libraries need not say so.</p>
	 */
	default Optional<LibraryFile> library(String name) throws IOException {
		return Optional.empty();
	}
}
