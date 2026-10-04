package to.etc.pdp11.common.macro11.asm;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Assembling in tests: source as lines, results as octal words and diagnostics.
 */
final class Asm {
	private Asm() {
	}

	/** Assemble lines, each a statement; {@code .END} is not added. */
	static AssemblyResult assemble(String... lines) {
		return assemble(AssemblerOptions.DEFAULT, SourceResolver.NONE, lines);
	}

	static AssemblyResult assemble(AssemblerOptions options, SourceResolver resolver, String... lines) {
		return new Macro11Assembler(options, resolver).assemble("t.mac", String.join("\n", lines) + "\n");
	}

	/** Assemble lines in the absolute section from address 1000, ended by {@code .END}. */
	static AssemblyResult at1000(String... lines) {
		List<String> all = new ArrayList<>();
		all.add("\t.ASECT");
		all.add("\t.=1000");
		all.addAll(List.of(lines));
		all.add("\t.END");
		return assemble(all.toArray(String[]::new));
	}

	/** The program's words in address order, as octal strings. */
	static List<String> octal(AssemblyResult r) {
		List<String> l = new ArrayList<>();
		r.getWords().stream()
			.sorted((a, b) -> Integer.compare(a.address(), b.address()))
			.forEach(w -> l.add(Integer.toOctalString(w.value())));
		return l;
	}

	/** Address to value. */
	static Map<Integer, Integer> memory(AssemblyResult r) {
		Map<Integer, Integer> m = new HashMap<>();
		for(AssemblyResult.Word w : r.getWords())
			m.put(w.address(), w.value());
		return m;
	}

	/** The word at an address, which must be there. */
	static int at(AssemblyResult r, int address) {
		Integer v = memory(r).get(address);
		if(v == null)
			fail("Nothing at " + Integer.toOctalString(address) + " in " + r.getWords());
		return v;
	}

	/** Assert the words of a program, as octal, and that it had no errors. */
	static void assertWords(AssemblyResult r, String... expected) {
		assertNoErrors(r);
		assertEquals(List.of(expected), octal(r));
	}

	static void assertNoErrors(AssemblyResult r) {
		assertTrue(r.getErrors().isEmpty(), () -> "Unexpected errors: " + r.getErrors());
	}

	/** Assert there is an error whose message contains {@code text}, on the given line. */
	static Diagnostic assertError(AssemblyResult r, int line, String text) {
		for(Diagnostic d : r.getErrors()) {
			if(d.location().root().line() == line && d.message().contains(text))
				return d;
		}
		fail("No error containing \"" + text + "\" on line " + line + "; there are " + r.getDiagnostics());
		return null;
	}

	/** Assert there is a warning of this kind on the given line. */
	static Diagnostic assertWarning(AssemblyResult r, WarningKind kind, int line) {
		for(Diagnostic d : r.getWarnings()) {
			if(d.warning() == kind && d.location().root().line() == line)
				return d;
		}
		fail("No " + kind + " warning on line " + line + "; there are " + r.getDiagnostics());
		return null;
	}

	static void assertNoWarning(AssemblyResult r, WarningKind kind) {
		for(Diagnostic d : r.getWarnings()) {
			if(d.warning() == kind)
				fail("Unexpected " + kind + ": " + d);
		}
	}

	/** A resolver holding sources in a map: name to text, for includes and macros alike. */
	static SourceResolver resolver(Map<String, String> files) {
		return resolver(files, Map.of());
	}

	/** The same, with macro libraries: name to bytes. */
	static SourceResolver resolver(Map<String, String> files, Map<String, byte[]> libraries) {
		return new SourceResolver() {
			@Override
			public Optional<LibraryFile> library(String name) {
				byte[] data = libraries.get(name);
				return data == null ? Optional.empty() : Optional.of(new LibraryFile(name, data));
			}

			@Override
			public Optional<Source> include(String name) {
				return open(name);
			}

			@Override
			public Optional<Source> macro(String name) {
				return open(name + ".MAC");
			}

			private Optional<Source> open(String name) {
				String text = files.get(name);
				if(text == null)
					return Optional.empty();
				Reader r = new StringReader(text);
				return Optional.of(new Source(name, r));
			}
		};
	}
}
