package to.etc.pdp11.core.microcode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a microword looks like on one processor: the fields it is cut into, which of them says
 * where to go next, and what can be checked about a microprogram written for it.
 *
 * <p>The 11/44's table used to be static state on {@link MicrocodeField} itself, which worked for
 * exactly as long as there was one machine. There is more than one: the PDP-11/05's KD11-B is 40
 * bits where this is 104, and its listing is a different document in a different format. So the
 * table becomes an object, one instance per processor ({@link Pdp1144Fields}), and everything
 * that decodes or displays a microword is handed the instance the microword was decoded
 * against.</p>
 *
 * <h2>A revision is not an architecture</h2>
 *
 * <p>Two revisions of the same board have the same field table and differ only in the bits burned
 * into the PROMs - the KD11-B's M7261 rev E and rev F differ in 20 bits across 14 microwords and
 * in nothing else. So a revision is a property of the {@link Microcode} that was loaded, which
 * carries it as a label; it is not a second architecture and must not become one, or the field
 * table gets copied and the two copies drift.</p>
 */
public final class MicrocodeArchitecture {
	/**
	 * Cross-checks a microprogram for this architecture can be held to.
	 *
	 * <p>These are properties of the <i>source document</i> as much as of the machine: the 11/44
	 * listing prints each microword's jump target as text beside the bits that encode it, so the
	 * two can be made to agree, and nothing decoded through a wrong bit map survives that. A
	 * transcription of a printed bit table has no such redundancy and gets different checks. An
	 * architecture with nothing to check supplies {@link #NONE}.</p>
	 */
	@FunctionalInterface
	public interface Checks {
		/** Everything wrong with this set of microwords, or an empty list. */
		List<Microcode.Problem> check(String sourceName, List<MicroInstruction> all,
			Map<Integer, MicroInstruction> byAddress);

		/** Nothing to check. */
		Checks NONE = (sourceName, all, byAddress) -> List.of();
	}

	private final String m_name;

	private final List<MicrocodeField> m_fields;

	private final MicrocodeField m_nextAddress;

	private final MicrocodeField m_microtest;

	private final Map<String, MicrocodeField> m_byName;

	private final Map<MicrocodeField, Integer> m_index = new IdentityHashMap<>();

	private final Checks m_checks;

	private final DontCare m_dontCare;

	/**
	 * Whether a field's printed value is not what the machine does in this microword.
	 *
	 * <p>Not a display detail and not a nicety. The KD11-B's {@code AUX} line selects whether the
	 * ALU control comes from the microword or is decoded from the instruction register, so in the
	 * microwords that assert it the printed {@code ALU} field is a don't-care - and showing it as
	 * an operation says the machine does something it does not. Which fields those are is
	 * arithmetic over the microword, so it belongs here and not in a window.</p>
	 */
	@FunctionalInterface
	public interface DontCare {
		/** Why this field means nothing in this microword, or {@code null} when it means what it says. */
		String reason(MicroInstruction mi, MicrocodeField field);

		/** A machine where every field says what it does. */
		DontCare NONE = (mi, field) -> null;
	}

	/**
	 * @param name              how this processor is named where a user has to choose one
	 * @param fields            every field, in the order a window lists them
	 * @param nextAddressField  the name of the field holding the fall-through address
	 * @param checks            what can be checked about a microprogram for it
	 */
	public MicrocodeArchitecture(String name, List<MicrocodeField> fields, String nextAddressField, Checks checks) {
		this(name, fields, nextAddressField, null, checks, DontCare.NONE);
	}

	/**
	 * @param microtestField the field selecting a branch microtest, whose resting value means "no
	 *                       branch" and whose every other value means the hardware replaces some
	 *                       of the next-address bits with what was tested; null for a machine
	 *                       without one
	 */
	public MicrocodeArchitecture(String name, List<MicrocodeField> fields, String nextAddressField,
		String microtestField, Checks checks, DontCare dontCare) {
		m_name = name;
		m_dontCare = dontCare;
		m_fields = List.copyOf(fields);
		m_checks = checks;
		Map<String, MicrocodeField> byName = new LinkedHashMap<>();
		for(int i = 0; i < m_fields.size(); i++) {
			MicrocodeField f = m_fields.get(i);
			MicrocodeField clash = byName.putIfAbsent(f.name(), f);
			if(clash != null)
				throw new IllegalArgumentException(name + " has two fields called " + f.name());
			m_index.put(f, i);
		}
		m_byName = Map.copyOf(byName);
		MicrocodeField next = m_byName.get(nextAddressField);
		if(next == null)
			throw new IllegalArgumentException(name + " has no field called " + nextAddressField);
		m_nextAddress = next;
		if(microtestField == null)
			m_microtest = null;
		else {
			MicrocodeField test = m_byName.get(microtestField);
			if(test == null)
				throw new IllegalArgumentException(name + " has no field called " + microtestField);
			if(!test.hasDefault())
				throw new IllegalArgumentException(microtestField + " has no resting value, so nothing"
					+ " says which of its values means \"no branch\"");
			m_microtest = test;
		}
	}

	/** How this processor is named where a user has to choose one: {@code "PDP-11/44"}. */
	public String getName() {
		return m_name;
	}

	/** Every field, in the order the window lists them. */
	public List<MicrocodeField> getFields() {
		return m_fields;
	}

	public int size() {
		return m_fields.size();
	}

	/**
	 * Where this microword goes next when nothing branches.
	 *
	 * <p>The one field with behaviour rather than just a value: it is what
	 * {@link MicroInstruction#getNextAddress()} reads and what the fall-through index in
	 * {@link Microcode} is built on. Both machines carry a full explicit next address in every
	 * microword and neither has a microprogram counter that counts, which is why this works the
	 * same way for both.</p>
	 */
	public MicrocodeField getNextAddressField() {
		return m_nextAddress;
	}

	/**
	 * How wide a control store address is, in bits, which is how wide the next-address field is.
	 *
	 * <p>Not a separate number: a microword names its successor in full, so the field that does
	 * the naming is exactly as wide as the store it points into. 10 bits on the 11/44, 8 on the
	 * KD11-B.</p>
	 */
	public int getAddressBits() {
		return m_nextAddress.length();
	}

	/**
	 * The field selecting a branch microtest, or {@code null} for a machine without one.
	 *
	 * <p>Where this is at its resting value the printed next address is the whole story. Where it
	 * is not, the hardware ORs the result of a test into some of those bits, so the printed value
	 * is a branch <i>base</i> and the real successor depends on what the machine finds - which is
	 * true of 73 of the KD11-B's 214 microwords. A window that follows the printed address in
	 * those has to say that is what it is doing.</p>
	 */
	public MicrocodeField getMicrotestField() {
		return m_microtest;
	}

	/** The field with this name, or {@code null}. For tests and for looking one up by hand. */
	public MicrocodeField byName(String name) {
		return m_byName.get(name);
	}

	/**
	 * Where this field sits in {@link #getFields()}, which is the index its value has in a
	 * microword.
	 *
	 * <p>Identity, not equality: the fields are the singletons in the list and a microword holds
	 * its values in an array parallel to it, so this is a lookup that a record's
	 * component-by-component {@code equals} - which would compare the whole value-name map on
	 * every call - is not needed for.</p>
	 */
	public int indexOf(MicrocodeField field) {
		Integer i = m_index.get(field);
		if(i == null)
			throw new IllegalArgumentException(field + " is not one of " + m_name + "'s fields");
		return i;
	}

	/** Why this field means nothing in this microword, or {@code null} when it means what it says. */
	public String dontCareReason(MicroInstruction mi, MicrocodeField field) {
		return m_dontCare.reason(mi, field);
	}

	/** Everything wrong with this set of microwords, by this architecture's own rules. */
	List<Microcode.Problem> check(String sourceName, List<MicroInstruction> all,
		Map<Integer, MicroInstruction> byAddress) {
		return m_checks.check(sourceName, all, byAddress);
	}

	/**
	 * The bits no field claims, which a test wants to be able to name.
	 *
	 * @param width how wide the microword is, since a field table does not say
	 */
	public List<Integer> unusedBits(int width) {
		boolean[] used = new boolean[width];
		for(MicrocodeField f : m_fields) {
			//-- Every bit the field is made of, not the range it spans: a scattered field spans
			//-- bits that belong to other fields, and walking the range says so wrongly.
			for(int b : f.bits()) {
				if(b >= width)
					throw new IllegalStateException(f + " reaches bit " + b + " of a " + width + " bit word");
				if(used[b])
					throw new IllegalStateException("Bit " + b + " is in two fields, the second being " + f);
				used[b] = true;
			}
		}
		List<Integer> out = new ArrayList<>();
		for(int b = 0; b < width; b++) {
			if(!used[b])
				out.add(b);
		}
		return List.copyOf(out);
	}

	@Override
	public String toString() {
		return m_name + ", " + m_fields.size() + " fields";
	}
}
