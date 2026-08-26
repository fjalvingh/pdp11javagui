package to.etc.pdp11.core.microcode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A whole microprogram: every microword of one control store, indexed the ways somebody reading
 * it wants to get at them.
 *
 * <p>Ported from {@code TPDP1144MicroCode} ({@code Pdp1144MicroCodeU.pas}), and no longer about
 * one machine: what a microword is made of is {@link MicrocodeArchitecture}, where it was read
 * from is the loader that built this ({@link Pdp1144Microcode} for the 11/44), and this is what
 * they produce. Everything here - the three sort orders, the address, tag and line indexes, the
 * fall-through map and the problem list - is arithmetic over microwords and cares about neither.
 * </p>
 *
 * <h2>Nothing here throws because the source is wrong</h2>
 *
 * <p>The Pascal raises on the first thing it does not like, whether that is a mangled line or a
 * failed cross-check, and abandons the whole load ({@code LoadListingPages}, and {@code Verify}
 * at {@code :788-846}). For a document that mostly arrives as somebody's scan that is the wrong
 * shape: one broken line in four thousand should cost you that line, not the microcode. Every
 * complaint is collected as a {@link Problem} instead, the rest of the document loads, and
 * whoever asked can decide what to do about {@link #getProblems()}. Only being unable to read
 * the file at all is an exception.</p>
 */
public final class Microcode {
	/** What sort of thing the source document, or the loader, is unhappy about. */
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
		 * <p>The strongest of the checks, and one only a listing that prints both can be held to:
		 * the address comes out of the bit fields and the tag out of the text beside them, so
		 * they can only agree if both were read correctly.</p>
		 */
		JUMP_NOT_IN_SOURCE,

		/** The listing's own line numbers went backwards, which means pages are out of order. */
		LINE_NUMBERS_OUT_OF_SEQUENCE,

		/** The document holds a different number of microwords than that document should. */
		WRONG_MICROWORD_COUNT,

		/** A microword sits at an address wider than the control store. */
		ADDRESS_OUT_OF_RANGE,

		/** A microword is printed with the wrong number of bits. */
		WRONG_BIT_COUNT
	}

	/**
	 * One complaint about the source document.
	 *
	 * @param kind     what sort
	 * @param source   which document
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

	private final MicrocodeArchitecture m_architecture;

	private final String m_sourceName;

	private final String m_revision;

	private final List<MicroInstruction> m_byAddress;

	private final List<MicroInstruction> m_byTag;

	private final List<MicroInstruction> m_byLineNumber;

	private final Map<Integer, MicroInstruction> m_addressIndex;

	private final Map<String, MicroInstruction> m_tagIndex;

	private final Map<Integer, MicroInstruction> m_lineIndex;

	private final Map<Integer, List<MicroInstruction>> m_predecessors;

	private final List<Problem> m_problems;

	/**
	 * @param architecture what the microwords were decoded against
	 * @param sourceName   which document they came out of
	 * @param revision     which revision of the board this microcode is, or {@code null} where
	 *                     the question does not arise; two revisions of one board share a field
	 *                     table and differ only in bits, so this is a label and not a second
	 *                     architecture
	 */
	Microcode(MicrocodeArchitecture architecture, String sourceName, String revision,
		List<MicroInstruction> instructions, List<Problem> problems) {
		m_architecture = architecture;
		m_sourceName = sourceName;
		m_revision = revision;

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

		all.addAll(architecture.check(sourceName, m_byAddress, m_addressIndex));
		m_problems = List.copyOf(all);
	}

	// -----------------------------------------------------------------------------------------
	// Reading it
	// -----------------------------------------------------------------------------------------

	/** Which processor's microcode this is, and so what its microwords are made of. */
	public MicrocodeArchitecture getArchitecture() {
		return m_architecture;
	}

	/** Which document this is, for showing beside what came out of it. */
	public String getSourceName() {
		return m_sourceName;
	}

	/** Which revision of the board this is, or {@code null} where the question does not arise. */
	public String getRevision() {
		return m_revision;
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

	/** Everything, in the order the tags run through the flow pages. */
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

	/** Everything the loader was unhappy about. Empty for a sound document. */
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
			+ (isOk() ? ", verified"
			: ", " + m_problems.size() + (m_problems.size() == 1 ? " problem" : " problems"));
	}

	@Override
	public String toString() {
		return describe();
	}
}
