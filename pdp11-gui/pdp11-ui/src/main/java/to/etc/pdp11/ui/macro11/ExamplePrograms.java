package to.etc.pdp11.ui.macro11;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The example programs packaged in the jar, and putting one where MACRO-11 can assemble it.
 *
 * <p>The examples are resources beside this class's package, listed by {@code examples/index.txt}
 * in menu order. A resource cannot be assembled where it is: MACRO-11 reads a file and writes its
 * listing beside it, and there is no directory inside a jar. So loading an example copies it into
 * {@code examples/} in the data directory first, which also gives the user a copy of their own to
 * change and run - the same reason the machine descriptions are installed there.</p>
 *
 * <h2>A changed copy is never overwritten without asking</h2>
 *
 * <p>Somebody who loaded the tic-tac-toe, improved it and saved it has a file in that directory
 * that is theirs. Loading the example again must not quietly put the original back over it. So
 * {@link #prepare} reports a copy that differs from the packaged one rather than replacing it, and
 * the caller asks; only {@code replaceChanged} overwrites. Whatever the answer, nothing outside
 * {@code dataDir/examples} is ever written.</p>
 *
 * <p>Swing-free and tested on its own; the menu in the main window only shows what this says.</p>
 */
public final class ExamplePrograms {
	/** Where the examples are packaged, as a class-path resource directory. */
	static final String RESOURCE_DIR = "/to/etc/pdp11/ui/examples/";

	static final String INDEX = RESOURCE_DIR + "index.txt";

	/** The directory under the data directory that examples are copied into. */
	public static final String DIRECTORY = "examples";

	/**
	 * One packaged example.
	 *
	 * @param fileName the resource's name, which is also the name its copy gets on disk
	 * @param title    what the menu calls it
	 */
	public record Example(String fileName, String title) {
	}

	/** What {@link #prepare} found on disk. */
	public enum CopyState {
		/** There was no copy, or the caller asked for it to be replaced: the original was written. */
		WRITTEN,
		/** The copy on disk is the original, byte for byte. */
		UNCHANGED,
		/** The copy on disk has been changed. It was left alone, and it is what was read. */
		CHANGED
	}

	/**
	 * An example on disk, ready to be loaded.
	 *
	 * @param file  where it is - the file the assembler will read and write the listing beside
	 * @param text  what that file holds now
	 * @param state whether that is the original
	 */
	public record Prepared(Path file, String text, CopyState state) {
	}

	private ExamplePrograms() {
	}

	/**
	 * The packaged examples, in menu order. Empty if the index is missing - which only a broken
	 * build can cause, and which the menu shows as "no examples" rather than failing.
	 */
	public static List<Example> list() {
		try(InputStream is = ExamplePrograms.class.getResourceAsStream(INDEX)) {
			if(is == null)
				return List.of();
			return parseIndex(new String(is.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
		} catch(IOException x) {
			return List.of();
		}
	}

	/**
	 * Read the index: a file name, a tab, a title. Blank lines and {@code #} comments are skipped,
	 * and so is a line with no title - an example the menu could not name is not offered.
	 */
	static List<Example> parseIndex(List<String> lines) {
		List<Example> list = new ArrayList<>();
		for(String line : lines) {
			String s = line.strip();
			if(s.isEmpty() || s.startsWith("#"))
				continue;
			int tab = s.indexOf('\t');
			if(tab <= 0)
				continue;
			String name = s.substring(0, tab).strip();
			String title = s.substring(tab + 1).strip();
			if(name.isEmpty() || title.isEmpty() || name.contains("/") || name.contains("\\"))
				continue;
			list.add(new Example(name, title));
		}
		return List.copyOf(list);
	}

	/** The example as packaged. ISO-8859-1, as every MACRO-11 source is read here. */
	public static String packagedText(Example example) throws IOException {
		try(InputStream is = ExamplePrograms.class.getResourceAsStream(RESOURCE_DIR + example.fileName())) {
			if(is == null)
				throw new IOException("The example " + example.fileName() + " is listed but not packaged");
			return new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}

	/**
	 * Make sure {@code dataDir/examples} holds a copy of the example, and read it.
	 *
	 * <p>File work: call it off the event thread.</p>
	 *
	 * @param replaceChanged true to put the original back over a copy that has been changed
	 */
	public static Prepared prepare(Example example, Path dataDir, boolean replaceChanged) throws IOException {
		String original = packagedText(example);
		Path dir = dataDir.resolve(DIRECTORY);
		Files.createDirectories(dir);
		Path file = dir.resolve(example.fileName()).toAbsolutePath();
		if(Files.isRegularFile(file)) {
			String onDisk = Files.readString(file, StandardCharsets.ISO_8859_1);
			if(onDisk.equals(original))
				return new Prepared(file, onDisk, CopyState.UNCHANGED);
			if(!replaceChanged)
				return new Prepared(file, onDisk, CopyState.CHANGED);
		}
		Files.writeString(file, original, StandardCharsets.ISO_8859_1);
		return new Prepared(file, original, CopyState.WRITTEN);
	}
}
