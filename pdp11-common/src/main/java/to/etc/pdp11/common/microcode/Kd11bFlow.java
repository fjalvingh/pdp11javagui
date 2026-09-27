package to.etc.pdp11.common.microcode;

import to.etc.pdp11.common.util.Octal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The KD11-B microprogram flow: DEC's commentary on the 11/05's microcode, saying what each
 * microword is for.
 *
 * <p>The source is drawing {@code K-MP-KD11-B-1}, "MICROPROGRAM FLOW REV. A, 27-JUL-72", 22 pages
 * of the October 1973 engineering drawing set. Despite its name it is not a chart but a commented
 * listing: a heading per routine ({@code * EMT TRAP (VECTOR LOC=30)}), comments
 * ({@code / GET TO ET-1 FROM F-5 VIA BUT IR DECODE}), and one line per microword with its address,
 * next address, tag and register transfers ({@code 245 246 ET-2 R[12]←B}). It is transcribed in
 * {@code kd11b-flow.tsv}, a resource of its own, so that nothing in it can reach the bits.</p>
 *
 * <h2>It checks the listing, and the listing checks it</h2>
 *
 * <p>Every microword line carries the address, next address and tag the listing also prints, and
 * the two documents were transcribed independently - the listing by a classifier over the bit
 * table, the flow by eye. They agree for all 201 microwords the flow shows. The three tags where
 * they first did not, {@code SBE-1}, {@code DBE-1} and {@code DE-1}, were the listing's OCR
 * reading E as F, which the scans confirm; see {@code kd11b-corrections.txt}. The flow's own
 * page 22 names the 13 microwords it does not show, {@code A145} among them.</p>
 *
 * <h2>Which comment belongs to which microword</h2>
 *
 * <p>The document does not say, but its layout does, and consistently: a {@code GET TO X FROM Y}
 * comment is printed above the microword it is about, and everything else - the {@code IF ...
 * GOTO} list after a branch, "J2-1 MUST BE A NOP BECAUSE..." - below it. Comments before a
 * routine's first microword are about the routine and go with that first microword.</p>
 *
 * <p>The flow is dated July 1972 and describes rev E's microcode; rev F changed bits in 14
 * microwords but no address and no tag, so it describes rev F just as well.</p>
 */
public final class Kd11bFlow {
	/** The drawing this is a transcription of, as it is shown to a user. */
	public static final String DRAWING = "K-MP-KD11-B-1";

	private static final String RESOURCE = "/microcode/kd11b/kd11b-flow.tsv";

	/** The flow's own record of what it leaves out, page 22: "ERT1A NOT EXPLICITLY SHOWN IN FLOW". */
	private static final Pattern NOT_SHOWN = Pattern.compile("^(\\S+) NOT EXPLICITLY SHOWN IN FLOW$");

	/** A comment about how the microword below it is reached; the document's own typos included. */
	private static final Pattern REACHED = Pattern.compile("^GETE? [TD]O ");

	/**
	 * What sort of routine. Not printed in the document: this project's grouping of its 59
	 * routine headings, given per routine in the resource.
	 */
	public enum Category {
		FETCH("Instruction fetch"),
		SOURCE("Source operand"),
		DESTINATION("Destination operand"),
		EXECUTE("Instruction"),
		TRAP("Trap"),
		CONSOLE("Console"),
		POWER("Power fail"),
		SERVICE("Interrupts and service");

		private final String m_label;

		Category(String label) {
			m_label = label;
		}

		public String getLabel() {
			return m_label;
		}
	}

	/** One routine: a heading printed with a leading {@code *}. */
	public record Routine(String heading, Category category, int page) {
	}

	/** One microword line, with the comments that belong to it. */
	public record Step(int address, int nextAddress, String tag, String action, Routine routine, int page,
		List<String> notes) {
		public Step {
			notes = List.copyOf(notes);
		}
	}

	private final List<Routine> m_routines;

	private final Map<String, Step> m_steps;

	private final Set<String> m_notShown;

	/** Every comment with the routine it is printed under, for finding where a tag is mentioned. */
	private final List<Mention> m_comments;

	private record Mention(String text, Routine routine, int page) {
	}

	private Kd11bFlow(List<Routine> routines, Map<String, Step> steps, Set<String> notShown, List<Mention> comments) {
		m_routines = List.copyOf(routines);
		m_steps = steps;
		m_notShown = notShown;
		m_comments = List.copyOf(comments);
	}

	private static volatile Kd11bFlow s_builtin;

	/** The packaged transcription, read once. */
	public static Kd11bFlow builtin() {
		Kd11bFlow flow = s_builtin;
		if(flow == null) {
			try(InputStream is = Kd11bFlow.class.getResourceAsStream(RESOURCE)) {
				if(is == null)
					throw new IllegalStateException("The packaged KD11-B microprogram flow is missing: " + RESOURCE);
				try(BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
					flow = parse(r.lines().toList());
				}
			} catch(IOException x) {
				throw new UncheckedIOException("Cannot read the packaged KD11-B microprogram flow", x);
			}
			s_builtin = flow;
		}
		return flow;
	}

	/**
	 * Read a transcription. It is packaged and checked by a test, so anything malformed is a
	 * mistake in the resource and is thrown, with its line.
	 */
	public static Kd11bFlow parse(List<String> lines) {
		List<Routine> routines = new ArrayList<>();
		Map<String, Step> steps = new LinkedHashMap<>();
		Set<String> notShown = new LinkedHashSet<>();
		List<Mention> comments = new ArrayList<>();

		Routine routine = null;
		int page = 0;
		//-- The microword being built, and the comments waiting for the next one.
		StepBuilder current = null;
		List<String> pending = new ArrayList<>();

		for(int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if(line.isBlank() || line.charAt(0) == '#')
				continue;
			String[] c = line.split("\t", -1);
			try {
				switch(c[0]) {
					case "PAGE" -> {
						expect(c, 2);
						page = Integer.parseInt(c[1]);
					}
					case "ROUTINE" -> {
						expect(c, 3);
						flush(current, pending, steps);
						current = null;
						pending = new ArrayList<>();
						routine = new Routine(c[1], Category.valueOf(c[2]), page);
						routines.add(routine);
					}
					case "NOTE" -> {
						expect(c, 2);
						String text = c[1];
						Matcher m = NOT_SHOWN.matcher(text);
						if(m.matches()) {
							notShown.add(m.group(1));
							continue;
						}
						comments.add(new Mention(text, routine, page));
						if(routine == null)
							continue;							// The notation notes on page 2.
						else if(current == null || REACHED.matcher(text).find())
							pending.add(text);
						else
							current.notes.add(text);
					}
					case "WORD" -> {
						expect(c, 5);
						if(routine == null)
							throw new IllegalArgumentException("A microword before any routine");
						flush(current, List.of(), steps);
						current = new StepBuilder((int) Octal.parseOr(c[1], -1), (int) Octal.parseOr(c[2], -1), c[3],
							c[4], routine, page);
						if(current.address < 0 || current.next < 0)
							throw new IllegalArgumentException("Not an octal address: " + c[1] + " " + c[2]);
						current.notes.addAll(pending);
						pending = new ArrayList<>();
					}
					default -> throw new IllegalArgumentException("Unknown line type " + c[0]);
				}
			} catch(IllegalArgumentException x) {
				throw new IllegalArgumentException("kd11b-flow.tsv:" + (i + 1) + ": " + x.getMessage(), x);
			}
		}
		flush(current, pending, steps);
		return new Kd11bFlow(routines, Map.copyOf(steps), Set.copyOf(notShown), comments);
	}

	private static void expect(String[] cells, int count) {
		if(cells.length != count)
			throw new IllegalArgumentException(cells[0] + " has " + cells.length + " columns, not " + count);
	}

	/**
	 * Finish a microword. At the end of a routine, comments still waiting for a microword below
	 * them go to its last one instead, since there is no other.
	 */
	private static void flush(StepBuilder b, List<String> trailing, Map<String, Step> steps) {
		if(b == null)
			return;
		b.notes.addAll(trailing);
		Step clash = steps.put(b.tag, new Step(b.address, b.next, b.tag, b.action, b.routine, b.page, b.notes));
		if(clash != null)
			throw new IllegalArgumentException(b.tag + " is shown twice");
	}

	private static final class StepBuilder {
		final int address;

		final int next;

		final String tag;

		final String action;

		final Routine routine;

		final int page;

		final List<String> notes = new ArrayList<>();

		StepBuilder(int address, int next, String tag, String action, Routine routine, int page) {
			this.address = address;
			this.next = next;
			this.tag = tag;
			this.action = action;
			this.routine = routine;
			this.page = page;
		}
	}

	// -----------------------------------------------------------------------------------------
	// Reading it
	// -----------------------------------------------------------------------------------------

	/** Every routine, in printed order. */
	public List<Routine> getRoutines() {
		return m_routines;
	}

	/** The microword line with this tag, or {@code null} when the flow does not show one. */
	public Step step(String tag) {
		return m_steps.get(tag);
	}

	/** Every microword line, in no particular order. */
	public List<Step> getSteps() {
		return List.copyOf(m_steps.values());
	}

	/** The tags the flow itself says it leaves out, on its last page. */
	public Set<String> getNotShown() {
		return m_notShown;
	}

	/**
	 * Where the flow and a listing disagree about a microword they both name: its address or its
	 * next address. Empty for the two shipped listings, which is the point of asking.
	 */
	public List<String> disagreements(List<MicroInstruction> listing) {
		List<String> out = new ArrayList<>();
		for(MicroInstruction mi : listing) {
			Step s = m_steps.get(mi.getSymbolicTag());
			if(s == null)
				continue;
			if(s.address() != mi.getAddress() || s.nextAddress() != mi.getNextAddress())
				out.add(mi.getSymbolicTag() + ": the listing has " + mi.getAddressOctal() + " -> "
					+ mi.getNextAddressOctal() + ", the flow " + octal(s.address()) + " -> " + octal(s.nextAddress()));
		}
		return out;
	}

	/**
	 * What each of these microwords is for, by address.
	 *
	 * <p>Matched by tag, which both documents print. Where the flow puts a microword at another
	 * address or has it go elsewhere, that is said in its notes rather than trusted silently: the
	 * flow is a year older than the listing. A microword the flow does not show gets the places
	 * its comments mention it, which for all but one of them says what it is for.</p>
	 */
	public Map<Integer, MicrowordRole> annotate(List<MicroInstruction> listing) {
		Map<Integer, MicrowordRole> out = new HashMap<>();
		for(MicroInstruction mi : listing) {
			Step s = m_steps.get(mi.getSymbolicTag());
			if(s != null) {
				List<String> notes = new ArrayList<>(s.notes());
				if(s.address() != mi.getAddress() || s.nextAddress() != mi.getNextAddress())
					notes.add(0, "The flow has this microword at " + octal(s.address()) + " going to "
						+ octal(s.nextAddress()) + "; the listing is what the machine has");
				out.put(mi.getAddress(), new MicrowordRole(s.routine().category().getLabel(), s.routine().heading(),
					s.action(), notes, DRAWING + " page " + s.page()));
				continue;
			}
			List<String> notes = new ArrayList<>();
			notes.add(m_notShown.contains(mi.getSymbolicTag())
				? "Not shown in the flow, which says so on its last page"
				: "Not shown in the flow");
			Pattern tag = Pattern.compile("(?<![A-Z0-9-])" + Pattern.quote(mi.getSymbolicTag()) + "(?![A-Z0-9-])");
			//-- Grouped by what is said: the priority list after every BUT SERVICE names ERT1A,
			//-- and eight copies of one line say less than one line and a count.
			Map<String, List<String>> where = new LinkedHashMap<>();
			for(Mention m : m_comments) {
				if(m.routine() != null && tag.matcher(m.text()).find())
					where.computeIfAbsent(m.text(), k -> new ArrayList<>()).add(m.routine().heading());
			}
			where.forEach((text, routines) -> notes.add(text + " (" + (routines.size() == 1
				? "in " + routines.get(0)
				: "in " + routines.size() + " routines") + ")"));
			out.put(mi.getAddress(), new MicrowordRole(null, null, null, notes, DRAWING));
		}
		return out;
	}

	private static String octal(int value) {
		return Octal.format(value, 3);
	}
}
