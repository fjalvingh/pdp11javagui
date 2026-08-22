package to.etc.pdp11.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.machine.MachineDescription;
import to.etc.pdp11.core.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting the shipped machine descriptions onto disk, where m4 can resolve their includes.
 *
 * <p>They are resources, and there is no directory inside a jar to include from - so they are
 * written into the data directory on the way up, which is where the Pascal keeps them too.</p>
 */
class MachineDescriptionStoreTest {
	private static final Path RESOURCES = Path.of("src/main/resources/machines");

	/**
	 * The index has to list exactly what is packaged.
	 *
	 * <p>A hand-maintained list of files is a trap - add a {@code .modules} file, forget the
	 * index, and the machine description fails to load for everyone but the developer, whose
	 * copy is already on disk. This is what stops that being possible.
	 */
	@Test
	void theIndexListsExactlyWhatIsShipped() throws IOException {
		Set<String> indexed = Set.copyOf(MachineDescriptionStore.shippedNames(Logger.NULL));
		Set<String> present;
		try(Stream<Path> files = Files.list(RESOURCES)) {
			present = files.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(".ini") || n.endsWith(".modules"))
				.collect(Collectors.toSet());
		}
		assertEquals(present, indexed, "machines/index.txt must list every .ini and .modules file");
	}

	@Test
	void installingWritesThemAllAndLeavesEditsAlone(@TempDir Path dir) throws IOException {
		Path machines = MachineDescriptionStore.install(dir, Logger.NULL);
		assertNotNull(machines);
		for(String name : MachineDescriptionStore.shippedNames(Logger.NULL)) {
			assertTrue(Files.isReadable(machines.resolve(name)), name + " should have been installed");
		}

		//-- A user who has edited a description keeps their edit across a restart. They lose the
		//-- improvements to that file, which is the right way round for a file we invite them to
		//-- edit.
		Path edited = machines.resolve(MachineDescriptionStore.DEFAULT_NAME);
		Files.writeString(edited, "; mine\n", StandardCharsets.ISO_8859_1);
		MachineDescriptionStore.install(dir, Logger.NULL);
		assertEquals("; mine\n", Files.readString(edited, StandardCharsets.ISO_8859_1));
	}

	@Test
	void theInstalledDefaultLoadsIntoRegisterGroups(@TempDir Path dir) {
		Path machines = MachineDescriptionStore.install(dir, Logger.NULL);
		AppTestContext ctx = AppTestContext.create(dir);
		MachineDescription md = MachineDescriptionStore.load(ctx.context(),
			machines.resolve(MachineDescriptionStore.DEFAULT_NAME));

		assertNotNull(md);
		assertEquals(List.of(), md.getWarnings());
		assertEquals(17, ctx.context().getMemoryCellGroups().size());
		assertEquals(62, ctx.context().getBitfieldDefs().getDefinitions().size());
		assertNotNull(ctx.context().getMemoryCellGroups().findByName("CPU"));
		assertEquals(MachineDescription.USAGE_TAG,
			ctx.context().getMemoryCellGroups().findByName("CPU").getUsageTag());
	}

	@Test
	void loadingASecondDescriptionReplacesTheFirst(@TempDir Path dir) throws IOException {
		Path machines = MachineDescriptionStore.install(dir, Logger.NULL);
		AppTestContext ctx = AppTestContext.create(dir);
		MachineDescriptionStore.load(ctx.context(), machines.resolve(MachineDescriptionStore.DEFAULT_NAME));
		int first = ctx.context().getMemoryCellGroups().size();
		assertTrue(first > 1);

		//-- A description with one device in it. The previous seventeen groups have to go, or
		//-- the application shows two machines at once.
		Path small = machines.resolve("small.ini");
		Files.writeString(small, "[TINY]\nInfo=\"A tiny device\"\nREG=177560;\"the register\"\n",
			StandardCharsets.ISO_8859_1);
		MachineDescriptionStore.load(ctx.context(), small);

		assertEquals(1, ctx.context().getMemoryCellGroups().size());
		assertNotNull(ctx.context().getMemoryCellGroups().findByName("TINY"));
		assertNull(ctx.context().getMemoryCellGroups().findByName("CPU"));
	}

	/** A description is a file the user may have edited, so a broken one costs the register windows and nothing else. */
	@Test
	void aMissingDescriptionIsReportedRatherThanFatal(@TempDir Path dir) {
		AppTestContext ctx = AppTestContext.create(dir);
		assertNull(MachineDescriptionStore.load(ctx.context(), dir.resolve("not-there.ini")));
		assertNull(ctx.context().getMachineDescription());
		assertEquals(0, ctx.context().getMemoryCellGroups().size());
	}
}
