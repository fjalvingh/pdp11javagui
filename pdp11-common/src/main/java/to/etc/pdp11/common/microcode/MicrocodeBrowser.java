package to.etc.pdp11.common.microcode;

import to.etc.pdp11.common.util.LogChannel;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.Octal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Getting about a processor's microcode: which document is being read, which microword is being
 * looked at, how you got there and how to get back.
 *
 * <p>This is what the desktop's Microcode window and the web application's microcode page both
 * show; each is a view over one of these. Ported from {@code TFormMicroCode}
 * ({@code FormMicroCodeU.pas}), whose navigation lived in the form: reading the document and
 * cutting the microwords into fields is {@link Microcode} and the loaders beside it, and what is
 * here is the search, the walk and the comparison against the other board revision.</p>
 *
 * <p><b>You can go back.</b> {@link #next()} follows the fall-through, as the Pascal's does. But a
 * microword also lists what falls through to <i>it</i>, and {@link #back()} returns along the way
 * you came, because microcode is mostly read backwards from the state you ended up in.</p>
 *
 * <p>Not thread-safe: one belongs to one view, and is used on that view's thread.</p>
 */
public final class MicrocodeBrowser {
	/** How what is typed into the search box is read. */
	public enum SearchBy {
		ADDRESS("µPC"),
		TAG("Symbolic tag"),
		LINE("Listing line");

		/** The ways the loaded document can actually be searched, which is not always all three. */
		public static SearchBy[] availableFor(MicrocodeSource source) {
			return source.hasListingLineNumbers() ? values() : new SearchBy[]{ADDRESS, TAG};
		}

		private final String m_label;

		SearchBy(String label) {
			m_label = label;
		}

		public String getLabel() {
			return m_label;
		}

		@Override
		public String toString() {
			return m_label;
		}
	}

	private final Logger m_logger;

	/** What has been read so far, so that switching back and forth does not re-read anything. */
	private final Map<MicrocodeSource, Microcode> m_loaded = new EnumMap<>(MicrocodeSource.class);

	/** Where the user has been, most recent first, so {@link #back()} walks it. */
	private final Deque<Integer> m_history = new ArrayDeque<>();

	private MicrocodeSource m_source = MicrocodeSource.DEFAULT;

	private Microcode m_code;

	/** The same microwords off the other revision of the same board, or null when there is none. */
	private Microcode m_otherRevision;

	private MicroInstruction m_current;

	private SearchBy m_searchBy = SearchBy.ADDRESS;

	/**
	 * @param logger where a document's problems, and a failure to read the other revision to
	 *               compare against, are written
	 */
	public MicrocodeBrowser(Logger logger) {
		m_logger = logger;
	}

	// -------------------------------------------------------------------------------------
	// Which document
	// -------------------------------------------------------------------------------------

	/** Show one of the documents packaged with the application, reading it if it has not been. */
	public void choose(MicrocodeSource source) {
		Microcode code = m_loaded.get(source);
		show(source, code == null ? source.load() : code);
	}

	/**
	 * Read another copy of a document - a re-transcription, another scan, a listing split per page
	 * - and show it, staying on the microword being looked at if the new copy has it.
	 *
	 * <p>Nothing changes when the read fails.</p>
	 */
	public void open(MicrocodeSource source, List<Path> files) throws IOException {
		Microcode code = source.load(files);
		int keep = m_current == null ? -1 : m_current.getAddress();
		show(source, code);
		if(keep >= 0 && code.atAddress(keep) != null)
			select(code.atAddress(keep), false);
	}

	/** Take a document, saying in the log what is wrong with it if anything is. */
	private void show(MicrocodeSource source, Microcode code) {
		m_source = source;
		m_code = code;
		m_loaded.put(source, code);
		m_history.clear();
		m_logger.log(LogChannel.OTHER, "Microcode: %s", code.describe());
		for(Microcode.Problem p : code.getProblems())
			m_logger.log(LogChannel.OTHER, "Microcode: %s", p.describe());
		m_otherRevision = otherRevision();
		if(!isAvailable(m_searchBy))
			m_searchBy = SearchBy.ADDRESS;
		select(code.isEmpty() ? null : code.byAddress().get(0), false);
	}

	/**
	 * The same board's other revision, read if it has not been already.
	 *
	 * <p>Worth the second read: it is what turns "you may have the wrong revision selected" into
	 * fourteen microwords with two coloured rows in them. Failing to read it costs the colouring
	 * and nothing else, so it is not allowed to stop anything working.</p>
	 */
	private Microcode otherRevision() {
		MicrocodeSource other = m_source.getOther();
		if(other == null)
			return null;
		try {
			return m_loaded.computeIfAbsent(other, MicrocodeSource::load);
		} catch(RuntimeException x) {
			m_logger.log(LogChannel.OTHER,
				"Microcode: cannot read %s to compare against: %s", other.getLabel(), x);
			return null;
		}
	}

	// -------------------------------------------------------------------------------------
	// Searching
	// -------------------------------------------------------------------------------------

	public void setSearchBy(SearchBy by) {
		m_searchBy = by == null ? SearchBy.ADDRESS : by;
	}

	public SearchBy getSearchBy() {
		return m_searchBy;
	}

	private boolean isAvailable(SearchBy by) {
		for(SearchBy s : SearchBy.availableFor(m_source)) {
			if(s == by)
				return true;
		}
		return false;
	}

	/**
	 * Every value that can be searched for, in the current search order.
	 *
	 * <p>The list is the index: with all 1018 in it, a list ordered by symbolic tag is the
	 * listing's table of contents.</p>
	 */
	public List<String> searchIndex() {
		List<String> out = new ArrayList<>();
		if(m_code == null)
			return out;
		List<MicroInstruction> order = switch(m_searchBy) {
			case ADDRESS -> m_code.byAddress();
			case TAG -> m_code.byTag();
			case LINE -> m_code.byLineNumber();
		};
		for(MicroInstruction mi : order)
			out.add(searchTextOf(mi));
		return out;
	}

	/** How a microword is written in the current search order: its address, tag or line. */
	public String searchTextOf(MicroInstruction mi) {
		return switch(m_searchBy) {
			case ADDRESS -> mi.getAddressOctal();
			case TAG -> mi.getSymbolicTag();
			case LINE -> String.valueOf(mi.getLineNumber());
		};
	}

	/**
	 * Go to whatever was typed or picked, read in the current search order.
	 *
	 * @return {@code null} when it was found and is now current; otherwise why it was not, and
	 * nothing has moved. Nothing is worse here than jumping somewhere else: the Pascal's address
	 * mode reads an unparseable address as 0 and silently shows the first microword instead.
	 */
	public String searchFor(String text) {
		if(m_code == null || text == null)
			return null;
		String s = text.strip();
		if(s.isEmpty())
			return null;
		MicroInstruction found = switch(m_searchBy) {
			case ADDRESS -> m_code.atAddress((int) Octal.parseOr(s, -1));
			case TAG -> m_code.withTag(s);
			case LINE -> {
				try {
					yield m_code.atLineNumber(Integer.parseInt(s));
				} catch(NumberFormatException x) {
					yield null;
				}
			}
		};
		if(found == null)
			return notFound(s);
		select(found, true);
		return null;
	}

	/**
	 * Why what was asked for is not there.
	 *
	 * <p>The µPC case is the one that needs saying. A KD11-B address typed off the KM11's lights
	 * can be a perfectly good control store location that the listing does not print - 42 of the
	 * 256 are not - and "no microword at 377" on its own reads like a typo when it is not.</p>
	 */
	private String notFound(String s) {
		if(m_searchBy == SearchBy.TAG)
			return "No microword tagged " + s;
		if(m_searchBy == SearchBy.LINE)
			return "No microword on listing line " + s;
		long address = Octal.parseOr(s, -1);
		if(address < 0)
			return "No microword: \"" + s + "\" is not an octal address";
		int bits = m_code.getArchitecture().getAddressBits();
		if(address >= (1L << bits))
			return "No microword at " + s + ": there is no such address in a " + bits
				+ " bit control store";
		return "No microword at " + s + ": it is one of the "
			+ ((1 << bits) - m_code.size()) + " control store locations this document does not print";
	}

	// -------------------------------------------------------------------------------------
	// Walking
	// -------------------------------------------------------------------------------------

	/**
	 * Follow the fall-through, which is what the microword's next-address field says.
	 *
	 * @return {@code null} when it moved; otherwise why it could not
	 */
	public String next() {
		if(m_current == null || m_code == null)
			return null;
		MicroInstruction to = m_code.atAddress(m_current.getNextAddress());
		if(to == null)
			return "This microword goes to " + m_current.getNextAddressOctal()
				+ ", which this document does not print";
		select(to, true);
		return null;
	}

	/** Go straight to a microword - one a predecessor link names, say - remembering where we were. */
	public boolean goTo(int address) {
		MicroInstruction to = m_code == null ? null : m_code.atAddress(address);
		if(to == null)
			return false;
		select(to, true);
		return true;
	}

	/** Back the way we came. */
	public void back() {
		if(m_history.isEmpty() || m_code == null)
			return;
		MicroInstruction to = m_code.atAddress(m_history.pop());
		if(to != null)
			select(to, false);
	}

	private void select(MicroInstruction mi, boolean remember) {
		if(remember && m_current != null && m_current != mi)
			m_history.push(m_current.getAddress());
		m_current = mi;
	}

	public boolean canGoBack() {
		return !m_history.isEmpty();
	}

	public boolean canGoNext() {
		return isLoaded() && m_current != null;
	}

	/** Whether there is anything to search or walk. */
	public boolean isLoaded() {
		return m_code != null && !m_code.isEmpty();
	}

	// -------------------------------------------------------------------------------------
	// What is on screen
	// -------------------------------------------------------------------------------------

	public MicrocodeSource getSource() {
		return m_source;
	}

	/** The document being read, or {@code null} before anything has been chosen. */
	public Microcode getMicrocode() {
		return m_code;
	}

	/** The microword being looked at, or {@code null} for none. */
	public MicroInstruction getCurrent() {
		return m_current;
	}

	/** What falls through to the current microword. */
	public List<MicroInstruction> predecessors() {
		return m_current == null || m_code == null ? List.of() : m_code.predecessorsOf(m_current);
	}

	/**
	 * Which fields the other revision of this board has something else in, for the current
	 * microword. Empty for a machine with one revision, and for the 200 microwords that are the
	 * same in both.
	 */
	public Set<MicrocodeField> differingFields() {
		MicroInstruction mi = m_current;
		if(mi == null || m_otherRevision == null)
			return Set.of();
		MicroInstruction other = m_otherRevision.atAddress(mi.getAddress());
		if(other == null || other.getArchitecture() != mi.getArchitecture())
			return Set.of();
		Set<MicrocodeField> out = new LinkedHashSet<>();
		for(MicrocodeField f : mi.getFields()) {
			if(mi.getValue(f) != other.getValue(f))
				out.add(f);
		}
		return out;
	}

	/** The current microword's fields, each with what it means and whether it is doing anything. */
	public List<MicrowordFieldValue> fieldValues() {
		return m_current == null ? List.of() : MicrowordFieldValue.of(m_current, differingFields());
	}

	/** What the machine's documentation says the current microword is for, or {@code null}. */
	public MicrowordRole role() {
		return m_current == null || m_code == null ? null : m_code.roleOf(m_current);
	}

	/**
	 * What the other revision of this board has in one field of the current microword, or
	 * {@code null} where there is no other revision or no such microword on it.
	 *
	 * <p>Saying the other value, not only that there is one, is what lets someone holding the
	 * board decide which revision it is from what the machine does.</p>
	 */
	public MicrowordFieldValue otherRevisionField(MicrocodeField field) {
		if(m_current == null || m_otherRevision == null)
			return null;
		MicroInstruction other = m_otherRevision.atAddress(m_current.getAddress());
		if(other == null || other.getArchitecture() != m_current.getArchitecture())
			return null;
		for(MicrowordFieldValue v : MicrowordFieldValue.of(other, Set.of())) {
			if(v.field().equals(field))
				return v;
		}
		return null;
	}

	/** The current microword as display rows, or none. */
	public List<MicrowordRow> rows() {
		if(m_current == null || m_code == null)
			return List.of();
		return MicrowordRow.of(m_current, predecessors(), differingFields(), m_code.roleOf(m_current));
	}

	/**
	 * Whether the status line has something wrong to say: the document did not read cleanly,
	 * or the current microword goes somewhere it does not print.
	 */
	public boolean isStatusTroubled() {
		return m_code == null || !m_code.isOk() || (m_current != null && m_code.nextNotInDocument(m_current));
	}

	/** What is on screen, where it came from, and whether the listing hangs together. */
	public String statusText() {
		if(m_code == null)
			return "No microcode loaded";
		StringBuilder sb = new StringBuilder();
		if(m_current != null) {
			sb.append("µPC = ").append(m_current.getAddressOctal())
				.append("  ·  ").append(m_current.getSymbolicTag());
			//-- What it is part of, which is the first thing anybody looking at a µPC wants to know.
			MicrowordRole role = m_code.roleOf(m_current);
			if(role != null && role.summary() != null)
				sb.append("  ·  ").append(role.summary());
			sb.append("  ·  next ").append(m_current.getNextAddressOctal());
			//-- Said here, on the microword, and not as a problem with the whole document: the
			//-- 11/05's listing has one microword that genuinely leaves it, and a status line that
			//-- said so permanently read as though the load had failed.
			if(m_code.nextNotInDocument(m_current))
				sb.append(" (not in this document)");
			//-- Where a microtest is selected the hardware ORs its result into the next address,
			//-- so what is printed is a branch base and not the successor. Saying "next 147" flat
			//-- would be stating as fact something that depends on the state of the machine.
			if(m_current.isBranching())
				sb.append(" if ").append(m_current.getMicrotestName()).append(" is zero (a branch base)");
			sb.append("  ·  ");
		}
		sb.append(m_code.describe());
		return sb.toString();
	}
}
