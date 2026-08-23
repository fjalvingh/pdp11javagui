package to.etc.pdp11.core.macro11;

import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs the external MACRO-11 assembler and hands back the listing it wrote.
 *
 * <p>Ported from {@code TFormMacro11Source.Compile} ({@code FormMacro11SourceU.pas:335-360}),
 * which is itself a port of the {@code macro11.bat} the Windows version shipped. Two runs, in
 * this order:</p>
 *
 * <ol>
 *   <li>{@code macro11 <source> -l <source>.lst} - the real one, listing in octal.</li>
 *   <li>{@code macro11 -e listhex <source> -l <source>.lst.hex} - the same listing with the code
 *       column in hex, for reading a logic analyser's capture against. <b>Allowed to fail.</b>
 *       {@code macro11.bat} does not check it either, and a missing hex listing is not a reason
 *       to refuse the program that assembled perfectly well.</li>
 * </ol>
 *
 * <p>{@code -e AMA} - absolute rather than PC-relative addressing - is commented out in
 * {@code macro11.bat} and so is not used here either.</p>
 *
 * <h2>The exit code says nothing</h2>
 *
 * <p>This is the thing worth knowing before touching anything here: <b>{@code macro11} exits 0
 * whether or not the source assembled.</b> A syntax error is reported as a line in the listing
 * and on stdout, and the process still succeeds. So errors are found by reading the listing -
 * {@link Macro11ListingParser} - and never by looking at the status. The Pascal reaches the same
 * conclusion by a different route: {@code AppControlU} cannot retrieve an exit code at all, so
 * it only ever asks whether the listing file appeared.</p>
 *
 * <h2>Which macro11</h2>
 *
 * <p>{@code https://github.com/rhefner1/macro11}, found on {@code PATH}. It is not shipped, it
 * is not needed to build or to test, and its absence is a sentence rather than a crash - see
 * {@link #findExecutable()}. CI has no {@code macro11}, so everything here that runs it lives in
 * a test that skips itself.</p>
 */
public final class Macro11 {
	/** The program's name on {@code PATH}. */
	public static final String EXECUTABLE = "macro11";

	/**
	 * How long the assembler is allowed to run.
	 *
	 * <p>Five seconds, from {@code timeout_millis} ({@code FormMacro11SourceU.pas:336}). It is
	 * an enormous allowance: the largest source in the project's own corpus - the 729-line
	 * CXCPAG processor test - assembles in about two milliseconds. What the timeout is really
	 * for is an assembler that has stopped rather than one that is slow.</p>
	 */
	public static final long DEFAULT_TIMEOUT_MS = 5000;

	/** How long {@link #findExecutable}'s answer is reused before the PATH is walked again. */
	private static final long LOOKUP_TTL_MS = 5000;

	private static final Object LOOKUP_LOCK = new Object();

	/** Guarded by {@link #LOOKUP_LOCK}. Zero means "never looked". */
	private static long m_lookupAt;

	/** Guarded by {@link #LOOKUP_LOCK}. */
	private static Path m_lookedUp;

	/**
	 * What one run of the assembler did.
	 *
	 * <p>There is no {@code timedOut} flag. There was, and it was always false: a run that
	 * exceeds {@link #DEFAULT_TIMEOUT_MS} is killed and reported as a {@link Macro11Exception},
	 * so no {@code Run} describing it is ever built (FABLE-ISSUES #55). A record of it would
	 * have to be reached through the exception, and the exception already says what happened.</p>
	 */
	public record Run(List<String> command, int exitCode, String output) {
		public boolean succeeded() {
			return exitCode == 0;
		}
	}

	/**
	 * A finished assembly.
	 *
	 * @param listing    the octal listing, which exists - {@link #assemble} throws if it does not
	 * @param hexListing the hex listing, or null when the second run failed
	 * @param runs       what was run, in order, for the log
	 */
	public record Result(Path listing, Path hexListing, List<Run> runs) {
	}

	private Macro11() {
	}

	// -------------------------------------------------------------------------------------
	// Finding it
	// -------------------------------------------------------------------------------------

	/**
	 * Where {@code macro11} is, or null if it is not on {@code PATH}.
	 *
	 * <p>Replaces {@code FindDefaultExecutablePath} ({@code FormMacro11SourceU.pas:343}). The
	 * Windows original ran a {@code macro11.bat} sitting beside the executable; that is gone,
	 * and with it the assumption that the tool travels with the application.</p>
	 */
	/**
	 * Where {@code macro11} is, or null.
	 *
	 * <p><b>Cached for {@link #LOOKUP_TTL_MS}</b>, because the answer decides whether a button is
	 * enabled and so is asked for on every {@code updateButtons()} - a repaint storm was walking
	 * the whole PATH doing two filesystem checks per entry, on the event thread, tens of times a
	 * second (FABLE-ISSUES #62). A short life rather than none at all: installing the assembler
	 * while the application is running still gets noticed, within a few seconds, without anything
	 * having to know to ask again.</p>
	 */
	public static Path findExecutable() {
		long now = System.nanoTime();
		synchronized(LOOKUP_LOCK) {
			if(m_lookupAt != 0 && now - m_lookupAt < LOOKUP_TTL_MS * 1_000_000L)
				return m_lookedUp;
		}
		Path found = searchPath();
		synchronized(LOOKUP_LOCK) {
			m_lookedUp = found;
			m_lookupAt = System.nanoTime();
		}
		return found;
	}

	/**
	 * Throw the cached answer away, so the next {@link #findExecutable} really looks.
	 *
	 * <p>For tests that change the PATH under it; nothing in the application needs this.</p>
	 */
	public static void forgetExecutable() {
		synchronized(LOOKUP_LOCK) {
			m_lookupAt = 0;
			m_lookedUp = null;
		}
	}

	private static Path searchPath() {
		String path = System.getenv("PATH");
		if(path == null || path.isEmpty())
			return null;
		for(String dir : path.split(java.io.File.pathSeparator)) {
			if(dir.isEmpty())
				continue;
			for(String name : candidateNames()) {
				Path p;
				try {
					p = Paths.get(dir, name);
				} catch(RuntimeException x) {
					//-- A PATH entry that is not a path at all. Skip it rather than fail the
					//-- whole lookup over somebody else's environment.
					continue;
				}
				if(Files.isRegularFile(p) && Files.isExecutable(p))
					return p;
			}
		}
		return null;
	}

	private static List<String> candidateNames() {
		if(!isWindows())
			return List.of(EXECUTABLE);
		//-- Windows will not execute an extensionless file, and the original shipped a .bat.
		return List.of(EXECUTABLE + ".exe", EXECUTABLE + ".bat", EXECUTABLE + ".cmd", EXECUTABLE);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	public static boolean isAvailable() {
		return findExecutable() != null;
	}

	/** The one sentence to show when it is not installed. */
	public static String notInstalledMessage() {
		return "\"" + EXECUTABLE + "\" was not found on the PATH. Install the MACRO-11 assembler"
			+ " (https://github.com/rhefner1/macro11) and make sure it is on the PATH.";
	}

	// -------------------------------------------------------------------------------------
	// Running it
	// -------------------------------------------------------------------------------------

	/** Where the listing for a source file goes: beside it, with a {@code .lst} extension. */
	public static Path listingFileFor(Path source) {
		String name = source.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = dot <= 0 ? name : name.substring(0, dot);
		Path dir = source.toAbsolutePath().getParent();
		return dir == null ? Paths.get(base + ".lst") : dir.resolve(base + ".lst");
	}

	/**
	 * Assemble {@code source}, leaving the listing beside it.
	 *
	 * <p>The source is not written here - whoever holds the editor saves it first, exactly as
	 * the Pascal does before calling this.</p>
	 *
	 * @throws Macro11Exception if the assembler is missing, the directory cannot be written to,
	 *                          the run timed out, or no listing appeared
	 */
	public static Result assemble(Path source, long timeoutMs, Logger logger) throws IOException {
		Path exe = findExecutable();
		if(exe == null)
			throw new Macro11Exception(notInstalledMessage());
		Path absSource = source.toAbsolutePath();
		if(!Files.isRegularFile(absSource))
			throw new Macro11Exception("There is no source file at " + absSource);
		Path dir = absSource.getParent();
		if(dir == null || !Files.isWritable(dir)) {
			//-- MACRO-11 writes its listing beside the source and has no option not to, so a
			//-- read-only directory stops the whole thing. Say which directory, since the usual
			//-- cause is assembling straight off a mounted disc image.
			throw new Macro11Exception("Cannot write to the directory \"" + dir
				+ "\", and MACRO-11 has to put its listing there. Copy the source somewhere writable.");
		}

		Path listing = listingFileFor(absSource);
		Path hexListing = listing.resolveSibling(listing.getFileName() + ".hex");
		//-- Deleted first: an old listing left behind by a run that failed would otherwise be
		//-- parsed as though it were this one's.
		Files.deleteIfExists(listing);
		Files.deleteIfExists(hexListing);

		String src = absSource.getFileName().toString();
		String lst = listing.getFileName().toString();
		String hex = hexListing.getFileName().toString();

		List<Run> runs = new ArrayList<>();
		runs.add(run(exe, dir, timeoutMs, logger, src, "-l", lst));
		//-- The hex listing is a convenience for reading logic-analyser captures. If it fails,
		//-- say so in the log and carry on: macro11.bat does not check it either.
		Run hexRun = null;
		try {
			hexRun = run(exe, dir, timeoutMs, logger, "-e", "listhex", src, "-l", hex);
			runs.add(hexRun);
		} catch(Macro11Exception | IOException x) {
			logger.log(LogChannel.OTHER, "MACRO11 hex listing failed: %s", x.getMessage());
		}

		if(!Files.isRegularFile(listing))
			throw new Macro11Exception("MACRO11 failure: list file " + listing + " not found");
		boolean haveHex = hexRun != null && Files.isRegularFile(hexListing);
		return new Result(listing, haveHex ? hexListing : null, List.copyOf(runs));
	}

	public static Result assemble(Path source, Logger logger) throws IOException {
		return assemble(source, DEFAULT_TIMEOUT_MS, logger);
	}

	/**
	 * One run of the assembler, drained and waited for.
	 *
	 * <p>Its output is drained on a thread of its own rather than read here. A process whose
	 * pipe fills up blocks in {@code write} and never exits, so reading after {@code waitFor}
	 * deadlocks on exactly the failure the timeout exists to catch - and reading before it means
	 * the timeout cannot fire at all.</p>
	 */
	private static Run run(Path exe, Path workDir, long timeoutMs, Logger logger, String... args)
		throws IOException {
		List<String> command = new ArrayList<>();
		command.add(exe.toString());
		command.addAll(List.of(args));

		logger.log(LogChannel.OTHER, "Starting MACRO11: %s (in %s)", String.join(" ", command), workDir);
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workDir.toFile());
		//-- One stream for both: macro11 reports errors on stdout and on stderr depending on
		//-- what went wrong, and which one it used is of no interest to anybody.
		pb.redirectErrorStream(true);
		Process process = pb.start();

		StringBuilder output = new StringBuilder();
		Thread drain = new Thread(() -> drainInto(process.getInputStream(), output), "macro11-output");
		drain.setDaemon(true);
		drain.start();

		boolean finished;
		try {
			finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new Macro11Exception("Interrupted while waiting for MACRO11", x);
		}
		if(!finished) {
			process.destroyForcibly();
			throw new Macro11Exception("MACRO11 timeout: it ran for longer than "
				+ (timeoutMs / 1000) + " seconds and was stopped");
		}
		//-- Bounded: the process is gone, so the pipe is at EOF and this returns at once.
		try {
			drain.join(1000);
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
		}
		String text;
		synchronized(output) {
			text = output.toString();
		}
		if(!text.isBlank())
			logger.log(LogChannel.OTHER, "MACRO11: %s", text.strip());
		return new Run(List.copyOf(command), process.exitValue(), text);
	}

	private static void drainInto(InputStream in, StringBuilder into) {
		byte[] buffer = new byte[4096];
		try(InputStream stream = in) {
			for(; ; ) {
				int count = stream.read(buffer);
				if(count < 0)
					return;
				synchronized(into) {
					into.append(new String(buffer, 0, count, StandardCharsets.ISO_8859_1));
				}
			}
		} catch(IOException x) {
			//-- The process died mid-write. Whatever it managed to say is still worth having.
		}
	}
}
