package to.etc.pdp11.common.macro11;

import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.macro11.asm.AssemblerOptions;
import to.etc.pdp11.common.macro11.asm.AssemblyResult;
import to.etc.pdp11.common.macro11.asm.FileSourceResolver;
import to.etc.pdp11.common.macro11.asm.Macro11Assembler;
import to.etc.pdp11.common.macro11.asm.MacroLibrary;
import to.etc.pdp11.common.macro11.asm.SourceResolver;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Assembles MACRO-11 for the application: source text in, detached memory cells out.
 *
 * <p>This used to run the external C {@code macro11} as a process and parse the listing it left
 * on disk. The assembler is now part of the application ({@link Macro11Assembler}), so nothing
 * has to be installed and nothing has to be written to run it, and errors, warnings and the
 * relation between source lines and words come from the assembler rather than from reading its
 * listing back. The result has the detached form a listing file parses to,
 * {@link Macro11ListingParser.Parsed}, so whoever installs one installs the other.</p>
 *
 * <p>An assembly never throws for a mistake in the source: everything wrong with it is in the
 * result.</p>
 */
public final class Macro11 {
	/**
	 * What an assembly produced.
	 *
	 * @param assembly everything the assembler knows: the program, the listing, every diagnostic
	 * @param parsed   the same, detached, ready for {@link Macro11ListingParser.Parsed#installInto}
	 */
	public record Result(AssemblyResult assembly, Macro11ListingParser.Parsed parsed) {
		/** The listing, a line per entry. */
		public List<String> listing() {
			return assembly.getListing();
		}
	}

	private Macro11() {
	}

	/**
	 * Assemble a source that lives in a file.
	 *
	 * <p>{@code .INCLUDE} names are relative to the file's directory, and {@code .MCALL} looks
	 * for {@code NAME.MAC} there.</p>
	 *
	 * @param source where the source is; its text is not read from there, but the name is used in
	 *               messages and the directory for {@code .INCLUDE} and {@code .MCALL}
	 * @param text   the source text, which may be newer than what is on disk
	 * @param type   the address width the cells get
	 */
	public static Result assemble(Path source, String text, MemoryAddressType type) {
		return assemble(source, text, type, List.of());
	}

	/**
	 * Assemble a source that lives in a file, with macro libraries.
	 *
	 * @param libraries searched by {@code .MCALL}, in order, after any the source names with
	 *                  {@code .LIBRARY} and before {@code NAME.MAC} files beside the source
	 */
	public static Result assemble(Path source, String text, MemoryAddressType type, List<MacroLibrary> libraries) {
		Path dir = source.toAbsolutePath().getParent();
		SourceResolver resolver = new FileSourceResolver(dir, List.of(dir));
		return assemble(source.getFileName().toString(), text, resolver, type, libraries);
	}

	/**
	 * Assemble text.
	 *
	 * @param name     what to call the source in messages and the listing
	 * @param resolver where {@code .INCLUDE} and {@code .MCALL} look
	 */
	public static Result assemble(String name, String text, SourceResolver resolver, MemoryAddressType type) {
		return assemble(name, text, resolver, type, List.of());
	}

	public static Result assemble(String name, String text, SourceResolver resolver, MemoryAddressType type,
		List<MacroLibrary> libraries) {
		AssemblyResult r = new Macro11Assembler(AssemblerOptions.DEFAULT, resolver, libraries).assemble(name, text);
		return new Result(r, Macro11ListingParser.Parsed.fromAssembly(r, type));
	}

	/** Where the listing for a source file goes: beside it, with a {@code .lst} extension. */
	public static Path listingFileFor(Path source) {
		String name = source.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = dot <= 0 ? name : name.substring(0, dot);
		Path dir = source.toAbsolutePath().getParent();
		return dir == null ? Paths.get(base + ".lst") : dir.resolve(base + ".lst");
	}
}
