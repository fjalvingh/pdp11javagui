package to.etc.pdp11.core.macro11;

import to.etc.pdp11.core.addr.Address;
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
import java.util.List;

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

	public static Macro11Listing parse(Path listingFile, MemoryCellGroup group) throws IOException {
		//-- ISO-8859-1 rather than the platform default: a listing is bytes from an assembler
		//-- that predates the concept, and a stray high byte must not fail the read.
		List<String> lines = Files.readAllLines(listingFile, StandardCharsets.ISO_8859_1);
		return parse(lines, group);
	}

	/**
	 * Parse {@code lines} into {@code group}, which is cleared first.
	 *
	 * @param group where the code goes. Its address type is what the cells get; the Pascal uses
	 *              {@code matVirtual}, because a listing's addresses are what the program will
	 *              see, not where a console will put them.
	 */
	public static Macro11Listing parse(List<String> lines, MemoryCellGroup group) {
		group.clear();
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
			Address addr = addressOrNull(group, addrValue);
			if(addr == null)
				continue;                                    // wider than this group's machine
			scanCodeColumn(code, addr, group, i, currentSourceLine, problems);
		}
		return new Macro11Listing(lines, sourceLineOf, problems, group);
	}

	// -------------------------------------------------------------------------------------
	// The code column
	// -------------------------------------------------------------------------------------

	/** An address at this group's width, or null when the value is too wide for it. */
	private static Address addressOrNull(MemoryCellGroup group, long value) {
		try {
			return Address.of(group.getType(), value);
		} catch(IllegalArgumentException x) {
			return null;
		}
	}

	/**
	 * Read octal values off the front of {@code code} until something is not one.
	 */
	private static void scanCodeColumn(String code, Address addr, MemoryCellGroup group,
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
			addr = fillValue(group, addr, digits, listingLine);
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
	 * Put one value into the group and step the address past it.
	 *
	 * <p>Ported from {@code FillVal2MemoryCell} ({@code :447-495}). The byte case is the whole
	 * of the interest: an odd address is the <b>high</b> half of the word below it, so it must
	 * find that word rather than make a second cell at the same place.</p>
	 *
	 * @return where the next value in this line would go, or null at the top of memory
	 */
	private static Address fillValue(MemoryCellGroup group, Address addr, String digits, int listingLine) {
		int byteCount = digits.length() <= 3 ? 1 : 2;
		int value = (int) (Octal.parseOr(digits, 0) & 0xFFFF);
		boolean odd = (addr.val() & 1) != 0;

		MemoryCell mc = null;
		if(byteCount == 1 && odd)
			mc = group.findByAddress(Address.of(group.getType(), addr.val() - 1));
		if(mc == null)
			mc = group.add(Address.of(group.getType(), addr.val() & ~1L));

		if(byteCount == 1) {
			if(!odd) {
				mc.setEditValue(CellValue.of(value & 0xFF));
			} else {
				//-- Unknown counts as zero here, unlike in the Pascal, where ORing into the
				//-- all-ones sentinel turns a single byte into 177777.
				int low = mc.getEditValue().wordOr(0) & 0xFF;
				mc.setEditValue(CellValue.of(low | ((value & 0xFF) << 8)));
			}
		} else {
			mc.setEditValue(CellValue.of(value));
		}
		mc.setListingLineNr(listingLine);
		//-- What the machine holds at this address is still unknown; the file says what it
		//-- *should* hold. That difference is what makes every word show as changed until it has
		//-- been deposited, and it is the same rule the Memory Loader follows.
		mc.setPdpValue(CellValue.UNKNOWN);
		//-- Null at the very top of memory, where there is no next address to step to.
		return addressOrNull(group, addr.val() + byteCount);
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
