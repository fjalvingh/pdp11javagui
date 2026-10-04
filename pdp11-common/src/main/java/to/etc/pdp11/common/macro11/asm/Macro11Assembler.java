package to.etc.pdp11.common.macro11.asm;

import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;

/**
 * A MACRO-11 assembler.
 *
 * <p>Assembles PDP-11 MACRO-11 source into a program, a listing in the layout of the C
 * {@code macro11}, and a list of errors and warnings. There is no linker behind it, so the
 * program is absolute: relocatable sections are placed at {@link AssemblerOptions#relocationBase()}
 * and an external symbol is an error.</p>
 *
 * <p>An assembly always completes. Errors in the source are reported in the
 * {@link AssemblyResult}, never thrown; a statement with an error is skipped and the next one
 * assembled, so one assembly finds every mistake rather than the first. An instance holds no
 * state between assemblies and can be used for any number of them, from any thread.</p>
 */
public final class Macro11Assembler {
	private final AssemblerOptions m_options;

	private final SourceResolver m_resolver;

	private final List<MacroLibrary> m_libraries;

	/**
	 * @param libraries macro libraries {@code .MCALL} searches, in this order, after any the
	 *                  source names with {@code .LIBRARY}. Read once and used for any number of
	 *                  assemblies, as DEC's system library is.
	 */
	public Macro11Assembler(AssemblerOptions options, SourceResolver resolver, List<MacroLibrary> libraries) {
		m_options = Objects.requireNonNull(options, "options");
		m_resolver = Objects.requireNonNull(resolver, "resolver");
		m_libraries = List.copyOf(libraries);
	}

	public Macro11Assembler(AssemblerOptions options, SourceResolver resolver) {
		this(options, resolver, List.of());
	}

	/** Default options, and no {@code .INCLUDE} or {@code .MCALL}. */
	public Macro11Assembler() {
		this(AssemblerOptions.DEFAULT, SourceResolver.NONE);
	}

	/**
	 * Assemble the text a reader holds.
	 *
	 * @param name   what to call the source in locations and the listing
	 * @param source the source; read to the end and closed
	 */
	public AssemblyResult assemble(String name, Reader source) {
		return new AssemblyRun(m_options, m_resolver, m_libraries).run(name, source);
	}

	public AssemblyResult assemble(String name, String source) {
		return assemble(name, new StringReader(source));
	}
}
