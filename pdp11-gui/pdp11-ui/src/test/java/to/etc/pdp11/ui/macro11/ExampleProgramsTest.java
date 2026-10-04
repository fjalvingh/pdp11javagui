package to.etc.pdp11.ui.macro11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.macro11.Macro11;
import to.etc.pdp11.common.macro11.Macro11Listing;
import to.etc.pdp11.common.macro11.Macro11ListingParser;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.ui.macro11.ExamplePrograms.CopyState;
import to.etc.pdp11.ui.macro11.ExamplePrograms.Example;
import to.etc.pdp11.ui.macro11.ExamplePrograms.Prepared;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The packaged example programs: that the index and the files agree, that loading one never
 * tramples a copy the user has changed, and that every one of them assembles.
 */
class ExampleProgramsTest {
	private static Example tictac() {
		return ExamplePrograms.list().stream()
			.filter(e -> e.fileName().equals("tictac.mac"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("tic-tac-toe is not in the index"));
	}

	@Test
	void ticTacToeIsTheFirstExample() {
		List<Example> list = ExamplePrograms.list();
		assertFalse(list.isEmpty());
		assertEquals(new Example("tictac.mac", "Tic-tac-toe"), list.get(0));
	}

	/**
	 * Add a file and forget the index, and the menu never offers it; list a file and forget to
	 * add it, and the menu offers something that fails. Same rule as the machine descriptions.
	 */
	@Test
	void theIndexNamesExactlyTheExamplesThatArePackaged() throws Exception {
		Path dir = Path.of(ExamplePrograms.class.getResource(ExamplePrograms.RESOURCE_DIR).toURI());
		Set<String> packaged;
		try(Stream<Path> files = Files.list(dir)) {
			packaged = files.map(p -> p.getFileName().toString())
				.filter(n -> !n.equals("index.txt"))
				.collect(Collectors.toCollection(TreeSet::new));
		}
		Set<String> listed = ExamplePrograms.list().stream().map(Example::fileName)
			.collect(Collectors.toCollection(TreeSet::new));
		assertEquals(packaged, listed);
	}

	@Test
	void anIndexLineWithoutATitleOrWithAPathIsNotOffered() {
		List<Example> list = ExamplePrograms.parseIndex(List.of(
			"# a comment",
			"",
			"good.mac\tGood",
			"untitled.mac",
			"../escape.mac\tEscape",
			"sub/dir.mac\tSubdirectory"));
		assertEquals(List.of(new Example("good.mac", "Good")), list);
	}

	@Test
	void theFirstLoadWritesTheOriginalAndTheSecondFindsItUnchanged(@TempDir Path data) throws Exception {
		Prepared first = ExamplePrograms.prepare(tictac(), data, false);
		assertEquals(CopyState.WRITTEN, first.state());
		assertEquals(data.resolve("examples").resolve("tictac.mac").toAbsolutePath(), first.file());
		assertEquals(ExamplePrograms.packagedText(tictac()), first.text());
		assertEquals(first.text(), Files.readString(first.file(), StandardCharsets.ISO_8859_1));

		assertEquals(CopyState.UNCHANGED, ExamplePrograms.prepare(tictac(), data, false).state());
	}

	/** Somebody improved the example and saved it. Loading it again must not undo that unasked. */
	@Test
	void aChangedCopyIsLeftAloneUnlessReplacingIsAskedFor(@TempDir Path data) throws Exception {
		Path file = ExamplePrograms.prepare(tictac(), data, false).file();
		String mine = "; my own version\n" + Files.readString(file, StandardCharsets.ISO_8859_1);
		Files.writeString(file, mine, StandardCharsets.ISO_8859_1);

		Prepared kept = ExamplePrograms.prepare(tictac(), data, false);
		assertEquals(CopyState.CHANGED, kept.state());
		assertEquals(mine, kept.text(), "what is loaded is the user's copy");
		assertEquals(mine, Files.readString(file, StandardCharsets.ISO_8859_1), "and it is still on disk");

		Prepared replaced = ExamplePrograms.prepare(tictac(), data, true);
		assertEquals(CopyState.WRITTEN, replaced.state());
		assertEquals(ExamplePrograms.packagedText(tictac()), Files.readString(file, StandardCharsets.ISO_8859_1));
	}

	/**
	 * An example that does not assemble is worse than none. Skipped where {@code macro11} is not
	 * installed, which includes CI.
	 */
	@Test
	void everyExampleAssemblesWithoutAProblem(@TempDir Path data) throws Exception {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		for(Example example : ExamplePrograms.list()) {
			Path file = ExamplePrograms.prepare(example, data, false).file();
			MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, example.fileName());
			Macro11Listing listing = Macro11ListingParser.parse(Macro11.assemble(file, Logger.NULL).listing(), g);
			assertTrue(listing.isOk(), () -> example.fileName() + ": " + listing.getProblems());
			assertTrue(listing.getWordCount() > 0, example.fileName() + " assembled to nothing");
			assertEquals("001000", listing.getStartAddress().toOctal(), example.fileName() + " should start at 1000");
		}
	}
}
