package to.etc.pdp11.core.microcode;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the PDP-11/44's microcode out of DEC's printed listing.
 *
 * <p>Ported from {@code TPDP1144MicroCode} ({@code Pdp1144MicroCodeU.pas}). The listing is
 * <i>EY-C3012-RB-001 PDP-11/44 Processor Maintenance Supplementary Listings (microcode),
 * April 1981</i>, as text; a copy is packaged with this class and {@link #builtin()} reads it.
 * The 11/44 is a microcoded machine, so this is the actual behaviour of the instruction set -
 * 1018 microwords, each one cycle of the data path - and being able to read it beside a
 * misbehaving processor is what the window that shows it is for.</p>
 *
 * <p>What comes out is a {@link Microcode}, which is the same container whatever machine it
 * holds; what is here is this document's format and this document's cross-checks.</p>
 *
 * <h2>What a listing line looks like</h2>
 *
 * <pre>
 * U 0461, 0000,2042,0001,4140,0140,3033,4000,0422,017     ;1062   461:    2-I:    R0_R0+1,J/1-A
 * </pre>
 *
 * <p>In order: a {@code U}, the microword's address, the nine octal words the microword is
 * printed as, the listing's own line number, the address again, the symbolic tag, and the
 * microassembler source that produced it. Long source runs onto following lines, which carry no
 * line number of their own and are joined back on.</p>
 *
 * <h2>The cross-checks are what make a scan trustworthy</h2>
 *
 * <p>{@link #verify} is not decoration, and it is registered as this architecture's checks
 * ({@link Pdp1144Fields#ARCHITECTURE}) rather than being something a caller can forget. Each line
 * prints its address twice, and each microword's next-address field must agree with the
 * {@code J/<tag>} written in its own source text - so a digit misread anywhere in the octal soup
 * shows up as a contradiction rather than as a plausible wrong answer. That is a property of the
 * listing's format, and it is the only reason a transcription of a 1981 line printer output can
 * be believed. A machine whose microcode arrives as a bit table rather than as a listing has no
 * such redundancy and gets different checks.</p>
 */
public final class Pdp1144Microcode {
	/** The listing packaged with this class. */
	public static final String BUILTIN_NAME = "EY-C3012-RB-001_Microcode_Listing_Apr81.txt";

	private static final String BUILTIN_RESOURCE = "/microcode/" + BUILTIN_NAME;

	private Pdp1144Microcode() {
	}

	// -----------------------------------------------------------------------------------------
	// Loading
	// -----------------------------------------------------------------------------------------

	/**
	 * The listing packaged with this class.
	 *
	 * <p>Shipped rather than looked for, which is the one real departure from the Pascal: it
	 * remembers a path in the registry, defaults it into the data directory and opens the window
	 * with "code not loaded" when nobody put the file there ({@code FormMicroCodeU.pas:88-110}).
	 * The listing is 180 KB of text and it never changes.</p>
	 */
	public static Microcode builtin() {
		try(InputStream is = Pdp1144Microcode.class.getResourceAsStream(BUILTIN_RESOURCE)) {
			if(is == null)
				throw new IllegalStateException("The packaged microcode listing is missing: " + BUILTIN_RESOURCE);
			try(BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.ISO_8859_1))) {
				return parse(BUILTIN_NAME, r.lines().toList());
			}
		} catch(IOException x) {
			throw new UncheckedIOException("Cannot read the packaged microcode listing", x);
		}
	}

	/**
	 * Read a listing from a file: another scan, a different revision, or a listing split per page.
	 *
	 * <p>ISO-8859-1 deliberately. This is a transcription of line printer output and its bytes
	 * are ASCII; decoding it as the platform default would fail on whatever stray byte an OCR
	 * left behind, where here that byte becomes one
	 * {@link Microcode.ProblemKind#ILLEGAL_CHARACTER} and the rest of the file still loads.</p>
	 */
	public static Microcode load(Path file) throws IOException {
		return parse(file.getFileName().toString(), Files.readAllLines(file, StandardCharsets.ISO_8859_1));
	}

	/**
	 * Read several files as one listing, which is what a scan split into per-page files is.
	 *
	 * <p>The Pascal reaches this case by turning the chosen file name into a wildcard - it strips
	 * the trailing digits off the name and appends {@code *} ({@code FormMicroCodeU.pas:126-136})
	 * - so choosing one page silently loads whatever else in that directory has a similar name.
	 * Here the caller says which files.</p>
	 */
	public static Microcode load(List<Path> files) throws IOException {
		if(files.isEmpty())
			throw new IllegalArgumentException("No files to load");
		if(files.size() == 1)
			return load(files.get(0));
		List<String> lines = new ArrayList<>();
		StringBuilder name = new StringBuilder();
		for(Path p : files) {
			lines.addAll(Files.readAllLines(p, StandardCharsets.ISO_8859_1));
			if(name.length() > 0)
				name.append(", ");
			name.append(p.getFileName());
		}
		return parse(name.toString(), lines);
	}

	/**
	 * Read a listing that is already in memory.
	 *
	 * <p>The line rules, from {@code LoadListingPage} ({@code Pdp1144MicroCodeU.pas:644-698}):</p>
	 *
	 * <ul>
	 *   <li>a line starting with {@code ;} is a page header, and is skipped;</li>
	 *   <li>a line whose first non-blank character is {@code ;} is a comment, and is skipped;</li>
	 *   <li>a line starting with {@code U } that has a line number in columns 57-58 starts a
	 *       microword;</li>
	 *   <li>anything else non-blank is a continuation of the microword above it, and is joined
	 *       on.</li>
	 * </ul>
	 */
	public static Microcode parse(String sourceName, List<String> lines) {
		List<MicroInstruction> instructions = new ArrayList<>();
		List<Microcode.Problem> problems = new ArrayList<>();

		StringBuilder current = null;
		int currentStart = 0;
		int lastLineNumber = -1;

		//-- One past the end, so the last microword is finished by the same code as all the others.
		for(int i = 0; i <= lines.size(); i++) {
			boolean eof = i == lines.size();
			String line = eof ? "" : lines.get(i);
			int fileLine = i + 1;

			if(!eof) {
				if(line.isEmpty() || line.charAt(0) == ';')
					continue;
				int bad = illegalCharacter(line);
				if(bad >= 0) {
					problems.add(new Microcode.Problem(Microcode.ProblemKind.ILLEGAL_CHARACTER, sourceName, fileLine,
						"Illegal character '" + line.charAt(bad) + "' (#" + (int) line.charAt(bad)
							+ ") in column " + (bad + 1) + ", line skipped"));
					continue;
				}
				if(line.strip().startsWith(";"))
					continue;
			}

			//-- A line carrying the listing's own line number is a line of the listing in its own
			//-- right - a microword, or a comment beside one - and only a line without one is the
			//-- continuation of the line above it. Both halves matter: the listing contains a
			//-- comment line whose leading ';' was scanned as ':' (line 1020, ":1950"), which is
			//-- not a comment by the test above and would otherwise be joined onto the microword
			//-- before it. Its line number is what still says it is not.
			boolean numbered = !eof && hasLineNumber(line);
			boolean starts = !eof && line.startsWith("U ") && numbered;
			if(!starts && !eof) {
				//-- A continuation before any microword has begun belongs to nothing; the listing
				//-- opens with a page of revision history in exactly that shape.
				if(!numbered && current != null)
					current.append(line);
				continue;
			}

			//-- Either a new microword begins or the file ended: whatever was being collected is
			//-- complete.
			if(current != null) {
				try {
					MicroInstruction mi = Pdp1144LineParser.parse(sourceName, currentStart, current.toString());
					if(lastLineNumber >= 0 && mi.getLineNumber() < lastLineNumber)
						problems.add(new Microcode.Problem(Microcode.ProblemKind.LINE_NUMBERS_OUT_OF_SEQUENCE,
							sourceName, mi.getFileLine(), "Listing line number " + mi.getLineNumber()
							+ " comes after " + lastLineNumber));
					lastLineNumber = mi.getLineNumber();
					instructions.add(mi);
				} catch(Pdp1144LineParser.BadLineException x) {
					problems.add(new Microcode.Problem(Microcode.ProblemKind.MALFORMED_LINE, sourceName,
						currentStart, x.getMessage()));
				}
			}
			current = eof ? null : new StringBuilder(line);
			currentStart = fileLine;
		}
		return new Microcode(Pdp1144Fields.ARCHITECTURE, sourceName, null, instructions, problems);
	}

	/**
	 * Whether the line has the listing's own line number where the format puts it.
	 *
	 * <p>Columns 57 and 58, 1-based, which is where {@code Pdp1144MicroCodeU.pas:700-705} looks:
	 * a semicolon-and-digits there is what separates a microword from a line that is only its
	 * continuation. Written out as the two array indexes it is, rather than as the Pascal's
	 * 1-based subscripts, per the porting rule about string indexing.</p>
	 */
	private static boolean hasLineNumber(String line) {
		if(line.length() < 60)
			return false;
		return line.charAt(56) != ' ' && Character.isDigit(line.charAt(57));
	}

	/**
	 * Where the first character no microcode listing contains is, or -1.
	 *
	 * <p>The whitelist is the Pascal's ({@code Pdp1144MicroCodeU.pas:673-678}) and it is there to
	 * catch a damaged transcription: the listing is upper case ASCII and punctuation, so a
	 * lower case letter or a byte above 127 means something went wrong between the paper and
	 * the file.</p>
	 */
	private static int illegalCharacter(String line) {
		for(int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			boolean ok = c == ' ' || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| "!.:;,_-+*/=()\\&".indexOf(c) >= 0;
			if(!ok)
				return i;
		}
		return -1;
	}

	// -----------------------------------------------------------------------------------------
	// Checking
	// -----------------------------------------------------------------------------------------

	/**
	 * The cross-checks, over microwords that parsed.
	 *
	 * <p>{@code Verify} ({@code Pdp1144MicroCodeU.pas:788-846}), except that it collects rather
	 * than raising on the first, and that the lookups are indexed - the Pascal's
	 * {@code InstructionByAddr} is a linear scan called once per microword from inside this
	 * loop.</p>
	 */
	static List<Microcode.Problem> verify(String sourceName, List<MicroInstruction> all,
		Map<Integer, MicroInstruction> byAddress) {
		List<Microcode.Problem> problems = new ArrayList<>();
		for(MicroInstruction mi : all) {
			MicroInstruction next = byAddress.get(mi.getNextAddress());
			if(next == null) {
				problems.add(new Microcode.Problem(Microcode.ProblemKind.MISSING_NEXT, sourceName, mi.getFileLine(),
					mi.getSymbolicTag() + " goes to " + mi.getNextAddressOctal() + ", where there is no microword"));
				continue;
			}
			String jump = "J/" + next.getSymbolicTag();
			if(!mi.getOperations().contains(jump))
				problems.add(new Microcode.Problem(Microcode.ProblemKind.JUMP_NOT_IN_SOURCE, sourceName,
					mi.getFileLine(), mi.getSymbolicTag() + " decodes to " + mi.getNextAddressOctal() + " (" + jump
						+ ") but its source says " + String.join(",", mi.getOperations())));
		}
		return problems;
	}
}
