package to.etc.pdp11.common.microcode;

import to.etc.pdp11.common.util.Octal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One field of one microword, with everything a display might say about it kept apart.
 *
 * <p>{@link MicrowordRow} flattens this into the three columns the desktop's table has. A view
 * that can lay a field out more freely - the web page puts the value, what it means and why it
 * does not matter in columns of their own - reads it from here, so which fields count as
 * doing something, and which the other revision disagrees on, is decided once.</p>
 *
 * @param octal       the value in octal, padded to the digits the field's width needs
 * @param meaning     what the value means where the print set says - "DATO" - or {@code null}
 *                    where it names it as nothing or not at all
 * @param dontCare    why the value is not what the machine does, or {@code null}; the KD11-B's
 *                    ALU field is this in nine microwords
 * @param active      the field is doing something this cycle: it has a resting value and is not
 *                    at it, and it is not a don't-care
 * @param differs     the other revision of the same board has something else here
 * @param nextAddress this is the field that holds where the microword goes next
 */
public record MicrowordFieldValue(MicrocodeField field, int value, String octal, String meaning, String dontCare,
	boolean active, boolean differs, boolean nextAddress) {

	/** Every field of the microword, in the order its architecture lists them. */
	public static List<MicrowordFieldValue> of(MicroInstruction mi, Set<MicrocodeField> differing) {
		List<MicrowordFieldValue> out = new ArrayList<>();
		for(MicrocodeField f : mi.getFields()) {
			int value = mi.getValue(f);
			String text = mi.getText(f);
			String dontCare = mi.getDontCareReason(f);
			//-- Active when the field is doing something, which needs it to have a resting value
			//-- to differ from. The next-address field has none - it is different in every
			//-- microword - and the Pascal highlights it in every microword as a result
			//-- ({@code FormMicroCodeU.pas:341}, where the default is -1 and the comparison can
			//-- never be equal). A row that is always yellow says nothing; this one is not.
			out.add(new MicrowordFieldValue(f, value, Octal.format(value, Octal.digitsForBits(f.length())),
				text == null || text.isEmpty() ? null : text, dontCare,
				f.hasDefault() && !mi.isDefault(f) && dontCare == null, differing.contains(f),
				f == mi.getArchitecture().getNextAddressField()));
		}
		return List.copyOf(out);
	}

	/** "2 = DATO", with the don't-care reason after it: the one-column form the desktop shows. */
	public String info() {
		String info = meaning == null ? octal : octal + " = " + meaning;
		if(dontCare != null)
			info = info + "  -  don't care: " + dontCare;
		return info;
	}
}
