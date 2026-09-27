package to.etc.pdp11.core.macro11;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.macro11.Macro11Listing.Problem;
import to.etc.pdp11.core.macro11.Macro11Listing.ProblemKind;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Octal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a MACRO-11 listing and turns its code column into memory cells.
 *
 * <p>Ported from {@code TFormMacro11Listing.ParseCode}
 * ({@code FormMacro11ListingU.pas:392-537}), which lives inside a form and can therefore only
 * be checked by looking at a window. It is arithmetic over text, so per CLAUDE.md it is a class
 * here with a test, and the window shows what it produced.</p>
 *
 * <h2>What a listing looks like</h2>
 *
 * <pre>
 *        1                                	.asect
 *        2 173000                         	.=173000
 *        3 173000    104     104          start:	.ascii	"DD"
 *        4 173002 000022                  	.word	last-.
 *        5 173004    110     145     154  tst0:	.ascii	"Hello"
 *          173007    154     157
 *        8 173020 004767  000000G         	jsr	pc,undefsym
 * t1.mac:7: ***ERROR Instruction on odd address
 * </pre>
 *
 * <p>Columns 1-8 are the source line number, 10-15 the address, and everything from 17 on is the
 * code column followed by the source text. Both the line number and the address are optional; a
 * line with neither produced nothing. The Pascal's own comment is honest about the approach -
 * strip the fixed left columns, then parse the rest by whitespace rather than by counting - and
 * that is kept, because the code column's width depends on how many values fit.</p>
 *
 * <h2>Four things that are easy to get wrong</h2>
 *
 * <ul>
 *   <li><b>Where the code column stops.</b> There is no marker between the last value and the
 *       source text, so scanning stops at the first word that is not octal, or that ends in
 *       {@code :} and is therefore a label - including {@code 2$:}, which starts with a digit.</li>
 *   <li><b>Three digits mean a byte, six mean a word.</b> A {@code .ascii} emits one byte per
 *       column, and two consecutive bytes are the two halves of one word - the odd address being
 *       the <i>high</i> byte of the even one below it. A five-character string therefore makes
 *       three cells across two listing lines, the last one half filled.</li>
 *   <li><b>Suffixes.</b> {@code 000000G} is a value with an unresolved global in it, which the
 *       assembler does not call an error but which means the program will not run;
 *       {@code 000000'} marks a relocatable value and is fine.</li>
 *   <li><b>An error line is not indented.</b> Every listing line starts with spaces, so a line
 *       that does not is MACRO-11 talking. Its format is {@code file:line: message}, and the
 *       file may contain colons of its own - a Windows drive letter, which is why the search for
 *       the separator skips one.</li>
 * </ul>
 *
 * <h2>Divergences from the Pascal, deliberate</h2>
 *
 * <ul>
 *   <li><b>Every problem is collected, not just the first.</b> The original keeps one and stops
 *       looking; a listing with twelve errors reports one, and the eleventh is found only after
 *       eleven more assemblies.</li>
 *   <li><b>An unknown suffix is recorded, not thrown.</b> {@code ParseCode} raises on one
 *       ({@code :516-517}), which abandons the parse and loses every cell already built,
 *       including all the code before the oddity. Here the word is skipped, the rest of the
 *       program survives, and the user is told which value was not understood.</li>
 *   <li><b>The high byte of an unset word is written as if the low byte were zero.</b> The
 *       Pascal ORs into {@code MEMORYCELL_ILLEGALVAL}, its all-ones sentinel, so a listing whose
 *       first byte lands on an odd address produces {@code 177777} with the byte in it rather
 *       than the byte.</li>
 * </ul>
 *
 * <h2>Parsing and installing are two steps</h2>
 *
 * <p>{@link #parse(List, MemoryAddressType)} reads a listing and touches no
 * {@link MemoryCellGroup} at all; {@link Parsed#installInto} puts the result into one. That
 * split is a threading rule, not a tidiness one. A code group is registered on the
 * {@code MemoryCellGroups} propagation bus, a grid paints from it on the event thread, and the
 * command thread walks the bus index during an examine - and none of the memory-cell types is
 * synchronised. An assembly runs the tool on a worker, so the worker parses and the event thread
 * installs.</p>
 *
 * <p>The two-argument {@code parse} overloads still do both halves on the caller's thread, which
 * is what anything already on the event thread wants.</p>
 */
public final class Macro11ListingParser {
	/** Columns 1-8 of the listing, 1-based, hold the source line number. */
	private static final int LINENO_END = 8;

	/** Columns 10-15 hold the address. */
	private static final int ADDR_START = 9;

	private static final int ADDR_END = 15;

	/** The code column starts at column 17. */
	private static final int CODE_START = 16;

	/** Below this length a line cannot be one of MACRO-11's diagnostics. */
	private static final int MIN_ERROR_LENGTH = 10;

	private Macro11ListingParser() {
	}

	// -------------------------------------------------------------------------------------
	// Parsing, which never touches a live group
	// -------------------------------------------------------------------------------------

	/**
	 * One word of code, as the parse builds it: everything a {@link MemoryCell} would hold,
	 * without being one.
	 *
	 * <p>Mutable because a byte at an odd address goes into the <i>high</i> half of the word
	 * below it, so a word already made has to be reached back into. See {@link #fillValue}.</p>
	 */
	private static final class Word {
		private final long m_addrValue;

		private CellValue m_value = CellValue.UNKNOWN;

		private int m_listingLine = -1;

		private Word(long addrValue) {
			m_addrValue = addrValue;
		}
	}

	/**
	 * What one listing parsed to, holding no reference to any {@link MemoryCellGroup}.
	 *
	 * <p>This split exists because parsing is slow enough to want off the event thread while the
	 * code group is not something a third thread may touch: it is on the propagation bus, a grid
	 * paints from it, and the command thread walks the bus index during an examine. None of
	 * {@link MemoryCell}, {@link MemoryCellGroup} or {@code MemoryCellGroups} is synchronised, so
	 * the worker builds this and the event thread calls {@link #installInto} - which is the only
	 * step that writes to the group.</p>
	 */
	public static final class Parsed {
		private final List<String> m_lines;

		private final int[] m_sourceLineOf;

		private final List<Problem> m_problems;

		private final List<Word> m_words;

		private final MemoryAddressType m_type;

		private Parsed(List<String> lines, int[] sourceLineOf, List<Problem> problems,
			List<Word> words, MemoryAddressType type) {
			m_lines = lines;
			m_sourceLineOf = sourceLineOf;
			m_problems = problems;
			m_words = words;
			m_type = type;
		}

		/** The address width this was parsed at; {@link #installInto} needs a group of the same. */
		public MemoryAddressType getType() {
			return m_type;
		}

		/** What MACRO-11 and this parser objected to, known before anything is installed. */
		public List<Problem> getProblems() {
			return m_problems;
		}

		/** How many words of code came out. */
		public int getWordCount() {
			return m_words.size();
		}

		/**
		 * Empty {@code group} and put this listing's words in it.
		 *
		 * <p><b>Event thread only</b>, and the whole of the mutation: the group is emptied and
		 * refilled in one go, so nothing else ever sees it half built.</p>
		 */
		public Macro11Listing installInto(MemoryCellGroup group) {
			if(group.getType() != m_type)
				throw new IllegalArgumentException("Listing parsed at " + m_type
					+ " cannot be installed into a " + group.getType() + " group");
			group.clear();
			for(Word w : m_words) {
				MemoryCell mc = group.add(Address.of(m_type, w.m_addrValue));
				mc.setEditValue(w.m_value);
				mc.setListingLineNr(w.m_listingLine);
				//-- What the machine holds at this address is still unknown; the file says what it
				//-- *should* hold. That difference is what makes every word show as changed until
				//-- it has been deposited, and it is the same rule the Memory Loader follows.
				mc.setPdpValue(CellValue.UNKNOWN);
			}
			return new Macro11Listing(m_lines, m_sourceLineOf, m_problems, group);
		}
	}

	/** The words made so far, with the same "first one wins" lookup a group has. */
	private static final class Words {
		private final List<Word> m_list = new ArrayList<>();

		private final Map<Long, Word> m_byAddress = new HashMap<>();

		private Word add(long addrValue) {
			Word w = new Word(addrValue);
			m_list.add(w);
			//-- putIfAbsent, matching MemoryCellGroup.add: several cells may share an address and
			//-- the lookup answers with the first one declared there.
			m_byAddress.putIfAbsent(addrValue, w);
			return w;
		}

		private Word find(long addrValue) {
			return m_byAddress.get(addrValue);
		}
	}

	/** Read a listing file and parse it at {@code type}, touching no group. */
	public static Parsed parse(Path listingFile, MemoryAddressType type) throws IOException {
		//-- ISO-8859-1 rather than the platform default: a listing is bytes from an assembler
		//-- that predates the concept, and a stray high byte must not fail the read.
		return parse(Files.readAllLines(listingFile, StandardCharsets.ISO_8859_1), type);
	}

	/**
	 * Parse {@code lines} at {@code type}, touching no group.
	 *
	 * @param type the address width the cells will get. The Pascal uses {@code matVirtual},
	 *             because a listing's addresses are what the program will see, not where a
	 *             console will put them.
	 */
	public static Parsed parse(List<String> lines, MemoryAddressType type) {
		Words words = new Words();
		int[] sourceLineOf = new int[lines.size()];
		List<Problem> problems = new ArrayList<>();
		int currentSourceLine = 0;

		for(int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if(line.length() > MIN_ERROR_LENGTH && line.charAt(0) != ' ') {
				//-- Not indented, so MACRO-11 is complaining rather than listing.
				Problem p = parseDiagnostic(line, i);
				if(p != null)
					problems.add(p);
				//-- A diagnostic belongs to the source line it names, so that highlighting the
				//-- source line highlights this too.
				sourceLineOf[i] = p == null ? currentSourceLine : Math.max(p.sourceLine(), 0);
				continue;
			}

			String lineNo = trimmedColumns(line, 0, LINENO_END);
			String addrText = trimmedColumns(line, ADDR_START, ADDR_END);
			String code = line.length() > CODE_START ? line.substring(CODE_START) : "";

			int parsed = parseDecimal(lineNo);
			if(parsed >= 0)
				currentSourceLine = parsed;
			sourceLineOf[i] = currentSourceLine;

			if(addrText.isEmpty())
				continue;
			long addrValue = Octal.parseOr(addrText, -1);
			if(addrValue < 0)
				continue;                                    // not an address: the hex listing, or junk
			Address addr = addressOrNull(type, addrValue);
			if(addr == null)
				continue;                                    // wider than this group's machine
			scanCodeColumn(code, addr, words, type, i, currentSourceLine, problems);
		}
		return new Parsed(List.copyOf(lines), sourceLineOf, List.copyOf(problems), words.m_list, type);
	}

	// -------------------------------------------------------------------------------------
	// Parse and install in one step
	// -------------------------------------------------------------------------------------

	/** Parse a listing file straight into {@code group}. Caller's thread does both halves. */
	public static Macro11Listing parse(Path listingFile, MemoryCellGroup group) throws IOException {
		return parse(listingFile, group.getType()).installInto(group);
	}

	/**
	 * Parse {@code lines} into {@code group}, which is cleared first.
	 *
	 * <p>Both halves on the caller's thread, which is right for anything already on the event
	 * thread. A worker parses with {@link #parse(List, MemoryAddressType)} and installs from the
	 * event thread instead.</p>
	 */
	public static Macro11Listing parse(List<String> lines, MemoryCellGroup group) {
		return parse(lines, group.getType()).installInto(group);
	}

	// -------------------------------------------------------------------------------------
	// The code column
	// -------------------------------------------------------------------------------------

	/** An address at this width, or null when the value is too wide for it. */
	private static Address addressOrNull(MemoryAddressType type, long value) {
		try {
			return Address.of(type, value);
		} catch(IllegalArgumentException x) {
			return null;
		}
	}

	/**
	 * Read octal values off the front of {@code code} until something is not one.
	 */
	private static void scanCodeColumn(String code, Address addr, Words words, MemoryAddressType type,
		int listingLine, int sourceLine, List<Problem> problems) {
		for(String word : code.split("[ \t]+")) {
			if(word.isEmpty())
				continue;
			if(word.endsWith(":"))
				break;                                       // a label, so the code column ended
			String digits = octalPrefixOf(word);
			if(digits.isEmpty())
				break;                                       // the source text has begun
			String suffix = word.substring(digits.length());
			addr = fillValue(words, type, addr, digits, listingLine);
			switch(suffix) {
				case "" -> {
				}
				case "G" -> {
					//-- Not an assembler error, but the emitted word is a hole where an address
					//-- should be. Once per source line: an unresolved symbol used four times in
					//-- one instruction is one mistake.
					if(problems.stream().noneMatch(p -> p.kind() == ProblemKind.UNRESOLVED_GLOBAL
						&& p.sourceLine() == sourceLine)) {
						problems.add(new Problem(ProblemKind.UNRESOLVED_GLOBAL, "", sourceLine, listingLine,
							"Unresolved global symbol"));
					}
				}
				case "'" -> {
					//-- A relocatable value. Nothing to do: the listing's own address is what
					//-- this program will be loaded at.
				}
				default -> problems.add(new Problem(ProblemKind.UNKNOWN_SUFFIX, "", sourceLine, listingLine,
					"Unknown suffix \"" + suffix + "\" in value \"" + word + "\""));
			}
			if(addr == null)
				break;                                       // ran off the top of the address space
		}
	}

	/**
	 * Put one value into the parse and step the address past it.
	 *
	 * <p>Ported from {@code FillVal2MemoryCell} ({@code :447-495}). The byte case is the whole
	 * of the interest: an odd address is the <b>high</b> half of the word below it, so it must
	 * find that word rather than make a second one at the same place.</p>
	 *
	 * @return where the next value in this line would go, or null at the top of memory
	 */
	private static Address fillValue(Words words, MemoryAddressType type, Address addr, String digits,
		int listingLine) {
		int byteCount = digits.length() <= 3 ? 1 : 2;
		int value = (int) (Octal.parseOr(digits, 0) & 0xFFFF);
		boolean odd = (addr.val() & 1) != 0;

		Word w = null;
		if(byteCount == 1 && odd)
			w = words.find(addr.val() - 1);
		if(w == null)
			w = words.add(addr.val() & ~1L);

		if(byteCount == 1) {
			if(!odd) {
				w.m_value = CellValue.of(value & 0xFF);
			} else {
				//-- Unknown counts as zero here, unlike in the Pascal, where ORing into the
				//-- all-ones sentinel turns a single byte into 177777.
				int low = w.m_value.wordOr(0) & 0xFF;
				w.m_value = CellValue.of(low | ((value & 0xFF) << 8));
			}
		} else {
			w.m_value = CellValue.of(value);
		}
		w.m_listingLine = listingLine;
		//-- Null at the very top of memory, where there is no next address to step to.
		return addressOrNull(type, addr.val() + byteCount);
	}

	/** The leading run of octal digits, which may be empty. */
	private static String octalPrefixOf(String word) {
		int i = 0;
		while(i < word.length() && word.charAt(i) >= '0' && word.charAt(i) <= '7') {
			i++;
		}
		return word.substring(0, i);
	}

	// -------------------------------------------------------------------------------------
	// Diagnostics
	// -------------------------------------------------------------------------------------

	/**
	 * {@code file:line: message}, or null if the line is not that shape.
	 *
	 * <p>The colon search skips a drive letter, because {@code D:\pdp11\progs\x.mac:11: ...} is
	 * what this looked like on the machine the original was written on and listings from then
	 * are still around.</p>
	 */
	private static Problem parseDiagnostic(String line, int listingLine) {
		int colon = line.length() > 1 && line.charAt(1) == ':'
			? line.indexOf(':', 2)
			: line.indexOf(':');
		if(colon < 0)
			return null;
		String file = line.substring(0, colon);
		String rest = line.substring(colon + 1);
		int second = rest.indexOf(':');
		if(second < 0)
			return null;
		int sourceLine = parseDecimal(rest.substring(0, second).trim());
		if(sourceLine < 0)
			return null;
		String message = rest.substring(second + 1).trim();
		return new Problem(ProblemKind.ERROR, file, sourceLine, listingLine, message);
	}

	// -------------------------------------------------------------------------------------
	// Small change
	// -------------------------------------------------------------------------------------

	/** Columns {@code [from, to)} of a line that may be shorter than that, trimmed. */
	private static String trimmedColumns(String line, int from, int to) {
		if(line.length() <= from)
			return "";
		return line.substring(from, Math.min(to, line.length())).trim();
	}

	/** A non-negative decimal, or -1. */
	private static int parseDecimal(String text) {
		if(text.isEmpty())
			return -1;
		int value = 0;
		for(int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if(c < '0' || c > '9')
				return -1;
			value = value * 10 + (c - '0');
			if(value > 10_000_000)
				return -1;
		}
		return value;
	}
}
