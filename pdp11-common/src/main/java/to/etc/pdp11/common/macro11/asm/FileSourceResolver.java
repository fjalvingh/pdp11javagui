package to.etc.pdp11.common.macro11.asm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds included files and macros in directories.
 *
 * <p>An {@code .INCLUDE} name is taken relative to the directory of the source being assembled,
 * unless it is absolute. A macro {@code NAME} is the file {@code NAME.MAC} in one of the macro
 * directories, the first one that has it; the name is matched without regard to case, because
 * these files come from systems that had none.</p>
 *
 * <p>A {@code .LIBRARY} name is found like an {@code .INCLUDE} one, and when it has no
 * extension and no such file exists, with {@code .MLB} added, as DEC's assembler does.</p>
 *
 * <p>Files are read as ISO-8859-1: they are bytes from a time before character sets, and a stray
 * high byte must not stop an assembly.</p>
 */
public final class FileSourceResolver implements SourceResolver {
	private final Path m_baseDirectory;

	private final List<Path> m_macroDirectories;

	/**
	 * @param baseDirectory    where relative {@code .INCLUDE} names are looked for
	 * @param macroDirectories where {@code .MCALL} looks, in order
	 */
	public FileSourceResolver(Path baseDirectory, List<Path> macroDirectories) {
		m_baseDirectory = baseDirectory;
		m_macroDirectories = List.copyOf(macroDirectories);
	}

	@Override
	public Optional<Source> include(String name) throws IOException {
		Path p = m_baseDirectory.resolve(name);
		if(!Files.isRegularFile(p))
			return Optional.empty();
		return Optional.of(new Source(name, Files.newBufferedReader(p, StandardCharsets.ISO_8859_1)));
	}

	@Override
	public Optional<LibraryFile> library(String name) throws IOException {
		Path p = m_baseDirectory.resolve(name);
		if(!Files.isRegularFile(p) && !p.getFileName().toString().contains("."))
			p = m_baseDirectory.resolve(name + ".MLB");
		if(!Files.isRegularFile(p))
			return Optional.empty();
		return Optional.of(new LibraryFile(name, Files.readAllBytes(p)));
	}

	@Override
	public Optional<Source> macro(String name) throws IOException {
		String wanted = (name + ".MAC").toUpperCase(Locale.ROOT);
		for(Path dir : m_macroDirectories) {
			if(!Files.isDirectory(dir))
				continue;
			Path found;
			try(Stream<Path> files = Files.list(dir)) {
				found = files.filter(p -> p.getFileName().toString().toUpperCase(Locale.ROOT).equals(wanted))
					.filter(Files::isRegularFile)
					.findFirst()
					.orElse(null);
			}
			if(found != null)
				return Optional.of(new Source(found.getFileName().toString(),
					Files.newBufferedReader(found, StandardCharsets.ISO_8859_1)));
		}
		return Optional.empty();
	}
}
