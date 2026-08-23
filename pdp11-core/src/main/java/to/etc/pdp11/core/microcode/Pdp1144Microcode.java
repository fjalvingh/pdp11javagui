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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PDP-11/44's microcode, read out of DEC's printed listing.
 *
 * <p>Ported from {@code TPDP1144MicroCode} ({@code Pdp1144MicroCodeU.pas}). The listing is
 * <i>EY-C3012-RB-001 PDP-11/44 Processor Maintenance Supplementary Listings (microcode),
 * April 1981</i>, as text; a copy is packaged with this class and {@link #builtin()} reads it.
 * The 11/44 is a microcoded machine, so this is the actual behaviour of the instruction set -
 * 1018 microwords, each one cycle of the data path - and being able to read it beside a
 * misbehaving processor is what the window that shows it is for.</p>
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
 * <h2>Nothing here throws because the listing is wrong</h2>
 *
 * <p>The Pascal raises on the first thing it does not like, whether that is a mangled line or a
 * failed cross-check, and abandons the whole load ({@code LoadListingPages}, and {@code Verify}
 * at {@code :788-846}). For a document that mostly arrives as somebody's scan that is the wrong
 * shape: one broken line in four thousand should cost you that line, not the microcode. Every
 * complaint is collected as a {@link Problem} instead, the rest of the listing loads, and
 * whoever asked can decide what to do about {@link #getProblems()}. Only being unable to read
 * the file at all is an exception.</p>
 *
 * <h2>The cross-checks are what make a scan trustworthy</h2>
 *
 * <p>{@link #verify()} is not decoration. Each line prints its address twice, and each
 * microword's next-address field must agree with the {@code J/<tag>} written in its own source
 * text - so a digit misread anywhere in the octal soup shows up as a contradiction rather than
 * as a plausible wrong answer. That is a property of the listing's format, and it is the only
 * reason a transcription of a 1981 line printer output can be believed.</p>
 */
public final class Pdp1144Microcode {
	/** The listing packaged with this class. */
	public static final String BUILTIN_NAME = "EY-C3012-RB-001_Microcode_Listing_Apr81.txt";

	private static final String BUILTIN_RESOURCE = "/microcode/" + BUILTIN_NAME;

	/** What sort of thing the listing, or this parser, is unhappy about. */
	public enum ProblemKind {
		/** A line beginning with {@code U } that could not be read as a microword. */
		MALFORMED_LINE,

		/** A character no microcode listing contains, which means the text is damaged. */
		ILLEGAL_CHARACTER,

		/** Two microwords claim the same control store address. */
		DUPLICATE_ADDRESS,

		/** Two microwords claim the same symbolic tag. */
		DUPLICATE_TAG,

		/** A microword's next address is not an address any microword has. */
		MISSING_NEXT,

		/**
		 * A microword's decoded next address does not match the {@code J/<tag>} in its own source.
		 *
		 * <p>The strongest of the checks: the address comes out of the bit fields and the tag out
		 * of the text beside them, so they can only agree if both were read correctly.</p>
		 */
		JUMP_NOT_IN_SOURCE,

		/** The listing's own line numbers went backwards, which means pages are out of order. */
		LINE_NUMBERS_OUT_OF_SEQUENCE
	}

	/**
	 * One complaint about the listing.
	 *
	 * @param kind     what sort
	 * @param source   which listing
	 * @param fileLine the 1-based physical line it is about, or 0 when it is about no one line
	 * @param message  what to show
	 */
	public record Problem(ProblemKind kind, String source, int fileLine, String message) {
		public String describe() {
			return fileLine > 0 ? source + ":" + fileLine + ": " + message : source + ": " + message;
		}

		@Override
		public String toString() {
			return describe();
		}
	}

	private final String m_sourceName;

	private final List<MicroInstruction> m_byAddress;

	private final List<MicroInstruction> m_byTag;

	private final List<MicroInstruction> m_byLineNumber;

	private final Map<Integer, MicroInstruction> m_addressIndex;

	private final Map<String, MicroInstruction> m_tagIndex;

	private final Map<Integer, MicroInstruction> m_lineIndex;

	private final Map<Integer, List<MicroInstruction>> m_predecessors;

	private final List<Problem> m_problems;

	private Pdp1144Microcode(String sourceName, List<MicroInstruction> instructions, List<Problem> problems) {
		m_sourceName = sourceName;

		//-- Three orders, computed once. The Pascal re-sorts one shared list every time the
		//-- window's "search by" changes, so the model's order is a property of the UI.
		List<MicroInstruction> byAddress = new ArrayList<>(instructions);
		byAddress.sort(Comparator.comparingInt(MicroInstruction::getAddress));
		List<MicroInstruction> byTag = new ArrayList<>(instructions);
		byTag.sort(Comparator.comparing(MicroInstruction::getSortableTag));
		List<MicroInstruction> byLine = new ArrayList<>(instructions);
		byLine.sort(Comparator.comparingInt(MicroInstruction::getLineNumber));
		m_byAddress = List.copyOf(byAddress);
		m_byTag = List.copyOf(byTag);
		m_byLineNumber = List.copyOf(byLine);

		List<Problem> all = new ArrayList<>(problems);
		Map<Integer, MicroInstruction> addresses = new LinkedHashMap<>();
		Map<String, MicroInstruction> tags = new LinkedHashMap<>();
		Map<Integer, MicroInstruction> lines = new LinkedHashMap<>();
		for(MicroInstruction mi : m_byAddress) {
			MicroInstruction clash = addresses.putIfAbsent(mi.getAddress(), mi);
			if(clash != null)
				all.add(new Problem(ProblemKind.DUPLICATE_ADDRESS, sourceName, mi.getFileLine(),
					"Address " + mi.getAddressOctal() + " is also used by " + clash.getSymbolicTag()
						+ " on line " + clash.getFileLine()));
			clash = tags.putIfAbsent(mi.getSymbolicTag(), mi);
			if(clash != null)
				all.add(new Problem(ProblemKind.DUPLICATE_TAG, sourceName, mi.getFileLine(),
					"Symbolic tag " + mi.getSymbolicTag() + " is also used at "
						+ clash.getAddressOctal() + " on line " + clash.getFileLine()));
			//-- The listing's line numbers are unique in a sound listing, but a duplicate one is
			//-- already reported as pages out of sequence; first wins here.
			lines.putIfAbsent(mi.getLineNumber(), mi);
		}
		m_addressIndex = Map.copyOf(addresses);
		m_tagIndex = Map.copyOf(tags);
		m_lineIndex = Map.copyOf(lines);

		//-- Who falls through to whom. The Pascal has no way back from a microword to the ones
		//-- that reach it, and reading microcode is mostly done backwards from the state you
		//-- ended up in.
		Map<Integer, List<MicroInstruction>> predecessors = new HashMap<>();
		for(MicroInstruction mi : m_byAddress)
			predecessors.computeIfAbsent(mi.getNextAddress(), k -> new ArrayList<>()).add(mi);
		m_predecessors = predecessors;

		all.addAll(verify(sourceName, m_byAddress, m_addressIndex));
		m_problems = List.copyOf(all);
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
	public static Pdp1144Microcode builtin() {
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
	 * left behind, where here that byte becomes one {@link ProblemKind#ILLEGAL_CHARACTER} and
	 * the rest of the file still loads.</p>
	 */
	public static Pdp1144Microcode load(Path file) throws IOException {
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
	public static Pdp1144Microcode load(List<Path> files) throws IOException {
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
	public static Pdp1144Microcode parse(String sourceName, List<String> lines) {
		List<MicroInstruction> instructions = new ArrayList<>();
		List<Problem> problems = new ArrayList<>();

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
					problems.add(new Problem(ProblemKind.ILLEGAL_CHARACTER, sourceName, fileLine,
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
					MicroInstruction mi = MicrocodeLineParser.parse(sourceName, currentStart, current.toString());
					if(lastLineNumber >= 0 && mi.getLineNumber() < lastLineNumber)
						problems.add(new Problem(ProblemKind.LINE_NUMBERS_OUT_OF_SEQUENCE, sourceName,
							mi.getFileLine(), "Listing line number " + mi.getLineNumber()
							+ " comes after " + lastLineNumber));
					lastLineNumber = mi.getLineNumber();
					instructions.add(mi);
				} catch(MicrocodeLineParser.BadLineException x) {
					problems.add(new Problem(ProblemKind.MALFORMED_LINE, sourceName, currentStart, x.getMessage()));
				}
			}
			current = eof ? null : new StringBuilder(line);
			currentStart = fileLine;
		}
		return new Pdp1144Microcode(sourceName, instructions, problems);
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
	private static List<Problem> verify(String sourceName, List<MicroInstruction> all,
		Map<Integer, MicroInstruction> byAddress) {
		List<Problem> problems = new ArrayList<>();
		for(MicroInstruction mi : all) {
			MicroInstruction next = byAddress.get(mi.getNextAddress());
			if(next == null) {
				problems.add(new Problem(ProblemKind.MISSING_NEXT, sourceName, mi.getFileLine(),
					mi.getSymbolicTag() + " goes to " + mi.getNextAddressOctal() + ", where there is no microword"));
				continue;
			}
			String jump = "J/" + next.getSymbolicTag();
			if(!mi.getOperations().contains(jump))
				problems.add(new Problem(ProblemKind.JUMP_NOT_IN_SOURCE, sourceName, mi.getFileLine(),
					mi.getSymbolicTag() + " decodes to " + mi.getNextAddressOctal() + " (" + jump
						+ ") but its source says " + String.join(",", mi.getOperations())));
		}
		return problems;
	}

	// -----------------------------------------------------------------------------------------
	// Reading it
	// -----------------------------------------------------------------------------------------

	/** Which listing this is, for showing beside what came out of it. */
	public String getSourceName() {
		return m_sourceName;
	}

	public int size() {
		return m_byAddress.size();
	}

	public boolean isEmpty() {
		return m_byAddress.isEmpty();
	}

	/** Everything, in control store order. */
	public List<MicroInstruction> byAddress() {
		return m_byAddress;
	}

	/** Everything, in the order the tags run through the listing's flow pages. */
	public List<MicroInstruction> byTag() {
		return m_byTag;
	}

	/** Everything, in listing order. */
	public List<MicroInstruction> byLineNumber() {
		return m_byLineNumber;
	}

	/** The microword at this control store address, or {@code null}. */
	public MicroInstruction atAddress(int address) {
		return m_addressIndex.get(address);
	}

	/** The microword with this symbolic tag, or {@code null}. */
	public MicroInstruction withTag(String tag) {
		return tag == null ? null : m_tagIndex.get(tag.strip());
	}

	/** The microword the listing prints on this line number of its own, or {@code null}. */
	public MicroInstruction atLineNumber(int lineNumber) {
		return m_lineIndex.get(lineNumber);
	}

	/**
	 * The microwords that fall through to this one.
	 *
	 * <p>Fall-through only, and that is the whole truth available from a listing: a microword
	 * reached by a branch is reached because hardware replaced some of the next-address bits
	 * with a condition, and which microwords can do that is a property of the {@code BUT}
	 * fields and the branch logic, not of the printed addresses. So an empty list here does not
	 * mean nothing reaches this microword.</p>
	 */
	public List<MicroInstruction> predecessorsOf(MicroInstruction mi) {
		List<MicroInstruction> list = m_predecessors.get(mi.getAddress());
		return list == null ? List.of() : List.copyOf(list);
	}

	/** Everything the listing was unhappy about. Empty for a sound one. */
	public List<Problem> getProblems() {
		return m_problems;
	}

	public boolean isOk() {
		return m_problems.isEmpty();
	}

	/** How this reads in a status line: what was loaded, and whether it hangs together. */
	public String describe() {
		if(isEmpty())
			return m_sourceName + ": no microcode found";
		String range = " (" + m_byAddress.get(0).getAddressOctal() + ".."
			+ m_byAddress.get(m_byAddress.size() - 1).getAddressOctal() + ")";
		return m_sourceName + ": " + size() + " microwords" + range
			+ (isOk() ? ", verified" : ", " + m_problems.size() + " problems");
	}

	@Override
	public String toString() {
		return describe();
	}
}
