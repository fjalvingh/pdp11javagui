package to.etc.pdp11.app;

import to.etc.pdp11.core.machine.MachineDescription;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.ui.AppContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The machine descriptions on disk: putting the shipped ones there, and loading one.
 *
 * <p>The descriptions are resources in this module, but {@code m4} include resolution works over
 * a directory and there is no directory inside a jar. So the shipped set is written into the
 * data directory on the way up, which is also what the Pascal does - it passes
 * {@code --include=%PDP11GUIAPPDATADIR%\machines} to m4 ({@code MemoryCellU.pas:599}) - and it
 * gives the user somewhere to put a description of their own machine, which is the whole point
 * of these files being data rather than code.</p>
 *
 * <p><b>An existing file is never overwritten.</b> A user who has edited a description keeps
 * their edit across an upgrade; what they lose is the improvements to that file, which is the
 * right way round for a file the program invites them to edit.</p>
 */
public final class MachineDescriptionStore {
	/** Lists what to install. Kept honest by {@code MachineDescriptionStoreTest}. */
	private static final String INDEX = "/machines/index.txt";

	private static final String RESOURCE_DIR = "/machines/";

	/** What a fresh installation loads. */
	public static final String DEFAULT_NAME = "pdp11.ini";

	private MachineDescriptionStore() {
	}

	/**
	 * Make sure {@code dataDir/machines} exists and holds the shipped descriptions.
	 *
	 * @return the directory, or {@code null} if it could not be created - which is not fatal:
	 *         the application runs perfectly well without a machine description, it just has no
	 *         register windows.
	 */
	public static Path install(Path dataDir, Logger logger) {
		Path dir = dataDir.resolve("machines");
		try {
			Files.createDirectories(dir);
		} catch(IOException x) {
			logger.log(LogChannel.OTHER, "Cannot create %s: %s", dir, x);
			return null;
		}
		int written = 0;
		for(String name : shippedNames(logger)) {
			Path target = dir.resolve(name);
			if(Files.exists(target))
				continue;
			try(InputStream is = MachineDescriptionStore.class.getResourceAsStream(RESOURCE_DIR + name)) {
				if(is == null) {
					logger.log(LogChannel.OTHER, "Machine description %s is listed but not packaged", name);
					continue;
				}
				Files.copy(is, target);
				written++;
			} catch(IOException x) {
				logger.log(LogChannel.OTHER, "Cannot write %s: %s", target, x);
			}
		}
		if(written > 0)
			logger.log(LogChannel.OTHER, "Installed %d machine description files in %s", written, dir);
		return dir;
	}

	/** The files the index names. Empty, with a complaint, if the index is missing. */
	public static List<String> shippedNames(Logger logger) {
		List<String> names = new ArrayList<>();
		try(InputStream is = MachineDescriptionStore.class.getResourceAsStream(INDEX)) {
			if(is == null) {
				logger.log(LogChannel.OTHER, "No machine description index at %s", INDEX);
				return names;
			}
			for(String line : new String(is.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String name = line.strip();
				//-- No path separators: this list names files in one directory and nothing else.
				if(!name.isEmpty() && !name.startsWith("#") && name.indexOf('/') < 0 && name.indexOf('\\') < 0)
					names.add(name);
			}
		} catch(IOException x) {
			logger.log(LogChannel.OTHER, "Cannot read the machine description index: %s", x);
		}
		return names;
	}

	/**
	 * Load a description into the application, replacing whatever was loaded before.
	 *
	 * <p>Dropping the previous description's groups first is {@code UnloadMachineDescription}
	 * ({@code FormMainU.pas:648-675}); here it is one call, because the groups carry the usage
	 * tag that says where they came from and the windows showing them are found by key rather
	 * than held in a field.</p>
	 *
	 * @return the description, or {@code null} if it could not be loaded - which is reported and
	 *         is not fatal
	 */
	public static MachineDescription load(AppContext context, Path file) {
		Logger logger = context.getLogger();
		//-- Windows first: a window holding a group that is about to be freed would be showing
		//-- cells that belong to nothing.
		context.getWindowManager().closeAll(to.etc.pdp11.ui.window.WindowType.REGISTER_GROUP);
		context.getMemoryCellGroups().removeGroupsByUsageTag(MachineDescription.USAGE_TAG);
		context.getBitfieldDefs().clear();
		context.setMachineDescription(null);
		if(file == null || !Files.isReadable(file)) {
			logger.log(LogChannel.OTHER, "No machine description at %s", file);
			return null;
		}
		try {
			MachineDescription md = MachineDescription.load(file, context.getMemoryCellGroups(),
				context.getBitfieldDefs(), logger);
			for(String warning : md.getWarnings()) {
				logger.log(LogChannel.OTHER, "%s: %s", file.getFileName(), warning);
			}
			context.setMachineDescription(md);
			logger.log(LogChannel.OTHER, "Machine description %s: %d register groups", md.getName(),
				context.getMemoryCellGroups().getGroups().stream()
					.filter(g -> MachineDescription.USAGE_TAG.equals(g.getUsageTag())).count());
			return md;
		} catch(RuntimeException x) {
			//-- A description is a file the user may have edited. A broken one costs the register
			//-- windows and nothing else; it must not stop the application.
			context.reportFailure("Could not load the machine description " + file.getFileName(), x);
			return null;
		}
	}

	/** Install the shipped descriptions and load the default one. Called once, on the way up. */
	public static MachineDescription installAndLoad(AppContext context) {
		Path dir = install(context.getDataDir(), context.getLogger());
		return dir == null ? null : load(context, dir.resolve(DEFAULT_NAME));
	}
}
