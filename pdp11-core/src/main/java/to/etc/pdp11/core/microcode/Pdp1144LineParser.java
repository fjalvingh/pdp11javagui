package to.etc.pdp11.core.microcode;

import to.etc.pdp11.core.util.Octal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns one line of the PDP-11/44's microcode listing into a {@link MicroInstruction}.
 *
 * <p>{@code TPDP1144MicroInstruction.Parse} and {@code BuildFields} together
 * ({@code Pdp1144MicroCodeU.pas:387-546}), as one step: a microword that cannot be decoded is
 * not a microword, and the Pascal's two-step version can leave a parsed-but-not-decoded object
 * in the list when the second step throws.</p>
 */
final class Pdp1144LineParser {
	/** A line that begins like a microword but is not one. Carries a sentence, not a stack. */
	static final class BadLineException extends RuntimeException {
		BadLineException(String message) {
			super(message);
		}
	}

	private Pdp1144LineParser() {
	}

	/**
	 * Where each listing word's bits start in the microword, and how many of them count.
	 *
	 * <p>{@code wordidx2bitpos} / {@code wordidx2bitlen} ({@code Pdp1144MicroCodeU.pas:505-509}).
	 * Word 0 is nine bits wide and the other eight are twelve, which is what makes the
	 * arithmetic below worth writing down rather than deriving: the boundaries are not
	 * regular.</p>
	 */
	private static final int[] WORD_BIT_POS = {0, 9, 21, 33, 45, 57, 69, 81, 93};

	private static final int[] WORD_BIT_LEN = {9, 12, 12, 12, 12, 12, 12, 12, 12};

	/** How many listing words a microword is printed as. */
	private static final int WORD_COUNT = 9;

	static MicroInstruction parse(String sourceName, int fileLine, String line) {
		if(line.length() < 80)
			throw new BadLineException("Line is only " + line.length() + " characters; a microword needs 80");
		if(line.charAt(0) != 'U')
			throw new BadLineException("A microword line starts with 'U'");

		//-- Field 2 of the line when commas separate as well as blanks: "U 0461, 0000,..." .
		List<String> commaWords = words(line, " \t,");
		List<String> blankWords = words(line, " \t");
		if(commaWords.size() < 2 || blankWords.size() < 7)
			throw new BadLineException("Too few fields on the line");

		int address = octal(commaWords.get(1), "microword address");

		//-- The nine octal words, printed most significant first and held least significant
		//-- first, which is the order the bit map in MicroInstruction is written in.
		String[] printed = blankWords.get(2).split(",", -1);
		if(printed.length != WORD_COUNT)
			throw new BadLineException("Expected " + WORD_COUNT + " code words, found " + printed.length
				+ ": \"" + blankWords.get(2) + "\"");
		int[] raw = new int[WORD_COUNT];
		for(int i = 0; i < WORD_COUNT; i++)
			raw[i] = octal(printed[WORD_COUNT - 1 - i], "code word #" + (WORD_COUNT - 1 - i));

		//-- ";1062", the listing's own line number.
		String s = blankWords.get(3);
		if(s.length() < 2 || s.charAt(0) != ';')
			throw new BadLineException("Expected the listing line number after a ';', found \"" + s + "\"");
		int lineNumber;
		try {
			lineNumber = Integer.parseInt(s.substring(1));
		} catch(NumberFormatException x) {
			throw new BadLineException("Listing line number \"" + s.substring(1) + "\" is not a number");
		}

		//-- "461:", the address again. The listing prints it twice and they must agree: this is
		//-- the cheapest check there is that the octal above was read correctly.
		s = blankWords.get(4);
		if(s.length() < 2 || !s.endsWith(":"))
			throw new BadLineException("Expected the address again as \"nnn:\", found \"" + s + "\"");
		int repeated = octal(s.substring(0, s.length() - 1), "address in the comment");
		if(repeated != address)
			throw new BadLineException("Address " + Octal.format(address, 4) + " is repeated as "
				+ Octal.format(repeated, 4));

		//-- "2-I:", the symbolic tag.
		s = blankWords.get(5);
		if(s.length() < 2 || !s.endsWith(":"))
			throw new BadLineException("Expected a symbolic tag ending in ':', found \"" + s + "\"");
		String tag = s.substring(0, s.length() - 1);

		//-- Everything left is the microassembler source, comma-separated.
		StringBuilder source = new StringBuilder();
		for(int i = 6; i < blankWords.size(); i++) {
			if(source.length() > 0)
				source.append(' ');
			source.append(blankWords.get(i));
		}
		if(source.length() == 0)
			throw new BadLineException("The microword has no source text");
		List<String> operations = new ArrayList<>();
		for(String op : words(source.toString(), ","))
			operations.add(op.replace("_", ":="));

		MicrocodeArchitecture arch = Pdp1144Fields.ARCHITECTURE;
		int[] values = decode(arch, raw);
		int next = values[arch.indexOf(arch.getNextAddressField())];
		return new MicroInstruction(arch, address, next, tag, sortableTag(tag),
			lineNumber, sourceName, fileLine, line, raw, values, operations);
	}

	/**
	 * Cut the 104-bit microword back into its fields.
	 *
	 * <p>{@code BuildFields} ({@code Pdp1144MicroCodeU.pas:504-546}). A field never spans more
	 * than two listing words - the widest is ten bits and the words are twelve - so joining the
	 * word a field starts in with the one above it is always enough.</p>
	 */
	private static int[] decode(MicrocodeArchitecture arch, int[] raw) {
		int[] values = new int[arch.size()];
		for(MicrocodeField f : arch.getFields()) {
			int w = 0;
			while(f.lsb() >= WORD_BIT_POS[w] + WORD_BIT_LEN[w])
				w++;
			long vector = raw[w] & mask(WORD_BIT_LEN[w]);
			if(w < WORD_COUNT - 1)
				vector |= ((long) (raw[w + 1] & mask(WORD_BIT_LEN[w + 1]))) << WORD_BIT_LEN[w];
			vector >>>= f.lsb() - WORD_BIT_POS[w];
			values[arch.indexOf(f)] = (int) (vector & f.mask());
		}
		return values;
	}

	private static int mask(int bits) {
		return (1 << bits) - 1;
	}

	/**
	 * The tag rewritten so that plain text sorting puts the microcode in flow order.
	 *
	 * <p>{@code Pdp1144MicroCodeU.pas:443-465}: two-digit page number so {@code 2-I} sorts before
	 * {@code 20-Z}, and {@code AA} where there is no {@code FP} prefix so the processor's own
	 * pages come before the floating point ones. {@code FP-33AA} is a tag that does not fit the
	 * pattern and is in the listing anyway - the Pascal names it and so does this.</p>
	 *
	 * <p>A tag that fits no pattern at all sorts as itself rather than making the line
	 * unreadable: the order of one tag matters less than the microword it names.</p>
	 */
	static String sortableTag(String tag) {
		if("FP-33AA".equals(tag))
			return "FP33-AA";
		int dash = tag.indexOf('-');
		if(dash < 0)
			return tag;
		String page = tag.substring(0, dash);
		String block = tag.substring(dash + 1);
		String prefix = "AA";
		if(page.startsWith("FP")) {
			prefix = "FP";
			page = page.substring(2);
		}
		if(page.isEmpty() || block.isEmpty())
			return tag;
		for(int i = 0; i < page.length(); i++) {
			if(!Character.isDigit(page.charAt(i)))
				return tag;
		}
		for(int i = 0; i < block.length(); i++) {
			char c = block.charAt(i);
			if(c < 'A' || c > 'Z')
				return tag;
		}
		return prefix + String.format(Locale.ROOT, "%02d", Integer.parseInt(page)) + "-" + block;
	}

	private static int octal(String text, String what) {
		try {
			return (int) Octal.parse(text);
		} catch(NumberFormatException x) {
			throw new BadLineException("The " + what + " \"" + text + "\" is not an octal number");
		}
	}

	/**
	 * Split on any of these characters, dropping empty runs.
	 *
	 * <p>{@code ExtractWord} ({@code JH_Utilities.pas}), which is what the Pascal picks the
	 * line apart with: several separators in a row count as one, and the words are numbered
	 * from 1. They are numbered from 0 here.</p>
	 */
	private static List<String> words(String s, String separators) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if(separators.indexOf(c) >= 0) {
				if(cur.length() > 0) {
					out.add(cur.toString());
					cur.setLength(0);
				}
			} else {
				cur.append(c);
			}
		}
		if(cur.length() > 0)
			out.add(cur.toString());
		return out;
	}
}
