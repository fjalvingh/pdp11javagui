package to.etc.pdp11.core.microcode;

import to.etc.pdp11.core.util.Octal;

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
 * Reads the PDP-11/05's microcode out of the transcribed engineering drawings.
 *
 * <p>The source is not a PROM dump and not DEC's microassembler output: it is the <i>microcode
 * listing pages</i> of the PDP-11/05 engineering drawings, transcribed - symbolic tag, octal
 * control store address and all 40 bits, one per printed column under named field headings. That
 * matters for what this class can and cannot check. A dump has no tags, no field names and no
 * internal redundancy, and ten 4-bit PROM slices concatenated in the wrong order give 256
 * entirely plausible microwords with no symptom at all; a listing prints the assembled word with
 * its field boundaries drawn, so there is no slice map to get wrong. But it also carries none of
 * the 11/44 listing's {@code J/<tag>} text, so the one cross-check that document supports is not
 * available here - see {@link #verify}.</p>
 *
 * <h2>Two revisions, both shipped</h2>
 *
 * <p>The 1973 drawing set is M7261 <b>revision E</b> and the 1976 set <b>revision F</b>, and they
 * are different microcode: 20 bits across 14 microwords, all of them in {@code AUX} or
 * {@code CKO}. Either board can be in the machine, so both are packaged and the caller says which
 * one it is looking at. They are not two architectures - the field table is identical and only
 * the bits differ - so the revision is a label on the {@link Microcode}.</p>
 *
 * <p>Which one is in a given machine can be read off the board without powering it: of the ten
 * control store PROMs exactly two change part number between the revisions, and they are exactly
 * the two holding {@code AUX} and {@code CKO}. {@code 23A14A2} + {@code 23A15A2} is rev E,
 * {@code 23A19A2} + {@code 23A20A2} is rev F.</p>
 *
 * <h2>NXT is stored active low</h2>
 *
 * <p>The next microaddress is <b>{@code NXT XOR 0377}</b>: the signals are {@code MPC-7-L} down
 * to {@code MPC-0-L}. Taken as printed, 83.2% of the 214 next-addresses land on a listed
 * location - which is chance level, because 214 of 256 locations are listed - and complemented,
 * 213 of 214 do. That is the one silent error this format can carry, and
 * {@code Kd11bMicrocodeTest} holds it down with exactly that coverage assertion rather than with
 * a comment.</p>
 *
 * <h2>The control store is sparse</h2>
 *
 * <p>214 of 256 locations are printed; the other 42 are not. So a next-address can point at a
 * location that has no microword, and one does: {@code A145 @145 -> 377}, whose own 40 bits are
 * almost entirely zero and which looks like a filler or diagnostic entry. That is a
 * {@link Microcode.Problem}, not an exception, and not a reason to reject the listing.</p>
 */
public final class Kd11bMicrocode {
	/**
	 * Which M7261 board revision, which is to say which drawing set.
	 *
	 * @param label    how it is named where somebody has to choose one
	 * @param source   which drawing set it was transcribed from
	 * @param resource the packaged transcription
	 * @param promsE   the two control store PROMs that identify this revision on the board
	 */
	public enum Revision {
		E("M7261 rev E", "PDP-11/05 engineering drawings, October 1973",
			"kd11b-microcode-1973.tsv", "23A14A2 + 23A15A2"),
		F("M7261 rev F", "PDP-11/05 engineering drawings, July 1976",
			"kd11b-microcode-1976.tsv", "23A19A2 + 23A20A2");

		private final String m_label;

		private final String m_source;

		private final String m_resource;

		private final String m_promPartNumbers;

		Revision(String label, String source, String resource, String promPartNumbers) {
			m_label = label;
			m_source = source;
			m_resource = resource;
			m_promPartNumbers = promPartNumbers;
		}

		public String getLabel() {
			return m_label;
		}

		/** Which drawing set this was transcribed from. */
		public String getSourceDocument() {
			return m_source;
		}

		/** The file name of the packaged transcription, which is also its {@code sourceName}. */
		public String getResourceName() {
			return m_resource;
		}

		/** The part numbers of the two PROMs that say, on the board itself, that this is it. */
		public String getPromPartNumbers() {
			return m_promPartNumbers;
		}

		@Override
		public String toString() {
			return m_label;
		}
	}

	private static final String RESOURCE_DIR = "/microcode/kd11b/";

	/** The columns of the transcription this reads. The rest are derived and are not read. */
	private static final String COL_TAG = "NAM";

	private static final String COL_ADDRESS = "LOC";

	private static final String COL_BITS = "WORD40";

	private Kd11bMicrocode() {
	}

	// -----------------------------------------------------------------------------------------
	// Loading
	// -----------------------------------------------------------------------------------------

	/** One of the two packaged transcriptions. */
	public static Microcode load(Revision revision) {
		String resource = RESOURCE_DIR + revision.getResourceName();
		try(InputStream is = Kd11bMicrocode.class.getResourceAsStream(resource)) {
			if(is == null)
				throw new IllegalStateException("The packaged KD11-B transcription is missing: " + resource);
			try(BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				return parse(revision.getResourceName(), revision, r.lines().toList());
			}
		} catch(IOException x) {
			throw new UncheckedIOException("Cannot read the packaged KD11-B transcription", x);
		}
	}

	/** A transcription from a file, in the same format: a re-transcription, or another scan. */
	public static Microcode load(Path file, Revision revision) throws IOException {
		return parse(file.getFileName().toString(), revision, Files.readAllLines(file, StandardCharsets.UTF_8));
	}

	/**
	 * Read a transcription that is already in memory.
	 *
	 * <p>Tab separated, {@code #} for a comment, one header row naming the columns and one row per
	 * microword. Only {@link #COL_TAG}, {@link #COL_ADDRESS} and {@link #COL_BITS} are read: every
	 * other column in the file is <i>derived</i> from those 40 bits by the transcription pipeline,
	 * and taking a decode from the file rather than doing it here would mean the field table in
	 * {@link Kd11bFields} was never exercised. The test does the comparison the other way round,
	 * which is the useful direction.</p>
	 */
	public static Microcode parse(String sourceName, Revision revision, List<String> lines) {
		List<MicroInstruction> instructions = new ArrayList<>();
		List<Microcode.Problem> problems = new ArrayList<>();
		MicrocodeArchitecture arch = Kd11bFields.ARCHITECTURE;
		int[] columns = null;
		int row = 0;

		for(int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int fileLine = i + 1;
			if(line.isBlank() || line.charAt(0) == '#')
				continue;
			String[] cells = line.split("\t", -1);
			if(columns == null) {
				columns = headerColumns(cells);
				if(columns == null)
					problems.add(new Microcode.Problem(Microcode.ProblemKind.MALFORMED_LINE, sourceName, fileLine,
						"The header row does not name " + COL_TAG + ", " + COL_ADDRESS + " and " + COL_BITS));
				continue;
			}
			try {
				instructions.add(microword(arch, sourceName, fileLine, ++row, line, cells, columns));
			} catch(BadRowException x) {
				row--;
				problems.add(new Microcode.Problem(x.m_kind, sourceName, fileLine, x.getMessage()));
			}
		}
		if(columns != null && row != Kd11bFields.LISTED_MICROWORDS)
			problems.add(new Microcode.Problem(Microcode.ProblemKind.WRONG_MICROWORD_COUNT, sourceName, 0,
				"The listing has " + row + " microwords, where both drawing sets print "
					+ Kd11bFields.LISTED_MICROWORDS));
		return new Microcode(arch, sourceName, revision == null ? null : revision.getLabel(),
			instructions, problems);
	}

	/** Where the three columns this reads are, or {@code null} when the row is not the header. */
	private static int[] headerColumns(String[] cells) {
		int[] out = {-1, -1, -1};
		for(int i = 0; i < cells.length; i++) {
			String name = cells[i].strip();
			if(COL_TAG.equals(name))
				out[0] = i;
			else if(COL_ADDRESS.equals(name))
				out[1] = i;
			else if(COL_BITS.equals(name))
				out[2] = i;
		}
		return out[0] < 0 || out[1] < 0 || out[2] < 0 ? null : out;
	}

	/** A row that is not a microword. Carries the problem kind it should be reported as. */
	private static final class BadRowException extends RuntimeException {
		private final Microcode.ProblemKind m_kind;

		BadRowException(Microcode.ProblemKind kind, String message) {
			super(message);
			m_kind = kind;
		}
	}

	private static MicroInstruction microword(MicrocodeArchitecture arch, String sourceName, int fileLine,
		int row, String line, String[] cells, int[] columns) {
		String tag = cell(cells, columns[0], COL_TAG).strip();
		if(tag.isEmpty())
			throw new BadRowException(Microcode.ProblemKind.MALFORMED_LINE, "The microword has no symbolic tag");

		String text = cell(cells, columns[1], COL_ADDRESS).strip();
		int address;
		try {
			address = (int) Octal.parse(text);
		} catch(NumberFormatException x) {
			throw new BadRowException(Microcode.ProblemKind.MALFORMED_LINE,
				tag + " has \"" + text + "\" as its address, which is not an octal number");
		}

		String bits = cell(cells, columns[2], COL_BITS).strip();
		if(bits.length() != Kd11bFields.WORD_BITS)
			throw new BadRowException(Microcode.ProblemKind.WRONG_BIT_COUNT,
				tag + " has " + bits.length() + " bits, where a KD11-B microword has "
					+ Kd11bFields.WORD_BITS);

		//-- The listing prints schematic bit 39 leftmost and bit 0 rightmost, which is what puts
		//-- the bit numbers here on the drawings and on the KM11's connector.
		long word = 0;
		for(int i = 0; i < bits.length(); i++) {
			char c = bits.charAt(i);
			if(c != '0' && c != '1')
				throw new BadRowException(Microcode.ProblemKind.ILLEGAL_CHARACTER,
					tag + " has '" + c + "' in bit position " + i + ", where a microword has only 0 and 1");
			if(c == '1')
				word |= 1L << (Kd11bFields.WORD_BITS - 1 - i);
		}

		int[] values = new int[arch.size()];
		for(MicrocodeField f : arch.getFields())
			values[arch.indexOf(f)] = f.valueFrom(word);

		//-- MPC-7-L .. MPC-0-L: the bits are burned complemented, so the address they name is not
		//-- the number they read as.
		int next = values[arch.indexOf(arch.getNextAddressField())] ^ arch.getNextAddressField().mask();

		return new MicroInstruction(arch, address, next, tag, tag, row, sourceName, fileLine, line,
			promSlices(word), values, List.of());
	}

	private static String cell(String[] cells, int index, String what) {
		if(index >= cells.length)
			throw new BadRowException(Microcode.ProblemKind.MALFORMED_LINE, "The row has no " + what + " column");
		return cells[index];
	}

	/**
	 * The microword as the ten 256x4 PROMs hold it, least significant slice first.
	 *
	 * <p>Not how the listing prints it - the listing prints one assembled 40-bit word - but it is
	 * how the machine holds it, and it is the form to compare against a PROM read off a board.
	 * Slice 0 is bits 3:0, which is the {@code BUT} nibble.</p>
	 */
	private static int[] promSlices(long word) {
		int[] out = new int[Kd11bFields.WORD_BITS / Kd11bFields.PROM_SLICE_BITS];
		for(int i = 0; i < out.length; i++)
			out[i] = (int) ((word >>> (i * Kd11bFields.PROM_SLICE_BITS)) & 0xF);
		return out;
	}

	// -----------------------------------------------------------------------------------------
	// Checking
	// -----------------------------------------------------------------------------------------

	/**
	 * The cross-checks, over microwords that parsed.
	 *
	 * <p>Weaker than the 11/44's, and unavoidably so: that listing prints each microword's jump
	 * target twice, once as bits and once as text, and nothing decoded through a wrong bit map
	 * survives the comparison. Here there is no text. What is left is the two properties the
	 * transcription's own address solver was built on - the addresses are distinct and they fit
	 * in the control store - and the observation that a next-address should name a microword that
	 * exists.</p>
	 *
	 * <p>The last of those is a complaint and not a verdict, because the control store is sparse:
	 * 42 of its 256 locations are not printed and one microword genuinely points into them.</p>
	 */
	static List<Microcode.Problem> verify(String sourceName, List<MicroInstruction> all,
		Map<Integer, MicroInstruction> byAddress) {
		List<Microcode.Problem> problems = new ArrayList<>();
		int limit = 1 << Kd11bFields.ARCHITECTURE.getAddressBits();
		for(MicroInstruction mi : all) {
			if(mi.getAddress() < 0 || mi.getAddress() >= limit) {
				problems.add(new Microcode.Problem(Microcode.ProblemKind.ADDRESS_OUT_OF_RANGE, sourceName,
					mi.getFileLine(), mi.getSymbolicTag() + " is at " + Octal.format(mi.getAddress(), 4)
					+ ", outside a " + Kd11bFields.ARCHITECTURE.getAddressBits() + " bit control store"));
				continue;
			}
			if(byAddress.get(mi.getNextAddress()) == null)
				problems.add(new Microcode.Problem(Microcode.ProblemKind.MISSING_NEXT, sourceName, mi.getFileLine(),
					mi.getSymbolicTag() + " goes to " + mi.getNextAddressOctal()
						+ ", which is one of the 42 control store locations the listing does not print"));
		}
		return problems;
	}
}
