package to.etc.pdp11.ui.microcode;

import to.etc.pdp11.core.microcode.Kd11bFields;
import to.etc.pdp11.core.microcode.Kd11bMicrocode;
import to.etc.pdp11.core.microcode.Microcode;
import to.etc.pdp11.core.microcode.MicrocodeArchitecture;
import to.etc.pdp11.core.microcode.Pdp1144Fields;
import to.etc.pdp11.core.microcode.Pdp1144Microcode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What the microcode window can be asked to show, which is one entry in its combo.
 *
 * <p>Three, not two lists. A Machine combo plus a Revision combo would leave the second showing
 * a single value, or disabled, whenever the 11/44 is chosen, and a control that is meaningless
 * half the time is worse than a slightly longer list. Revisit only if a machine with several
 * revisions arrives.</p>
 *
 * <p>Note what is <i>not</i> here: a revision is not an architecture. Both KD11-B entries share
 * one {@link MicrocodeArchitecture} and differ only in the bits they load, which is why
 * {@link #getOther()} can compare them field by field.</p>
 */
public enum MicrocodeSource {
	/** DEC's printed listing, EY-C3012-RB-001, April 1981. */
	PDP1144("PDP-11/44", null),

	/** The October 1973 drawing set, and the board that takes it. */
	PDP1105_E("PDP-11/05 (M7261 rev E)", Kd11bMicrocode.Revision.E),

	/** The July 1976 drawing set. The default, being the later board and the better scan. */
	PDP1105_F("PDP-11/05 (M7261 rev F)", Kd11bMicrocode.Revision.F);

	/**
	 * What the window opens on the first time anybody runs it.
	 *
	 * <p>Nothing depends much on getting this right, because the setting overrides it from the
	 * second run onwards - and which of the two KD11-B revisions is in the rack is a question
	 * only somebody looking at the board can answer.</p>
	 */
	public static final MicrocodeSource DEFAULT = PDP1105_F;

	private final String m_label;

	private final Kd11bMicrocode.Revision m_revision;

	MicrocodeSource(String label, Kd11bMicrocode.Revision revision) {
		m_label = label;
		m_revision = revision;
	}

	public String getLabel() {
		return m_label;
	}

	/** Which KD11-B board revision this is, or {@code null} for the 11/44. */
	public Kd11bMicrocode.Revision getRevision() {
		return m_revision;
	}

	public MicrocodeArchitecture getArchitecture() {
		return m_revision == null ? Pdp1144Fields.ARCHITECTURE : Kd11bFields.ARCHITECTURE;
	}

	/**
	 * The other revision of the same board, or {@code null} where there is no such thing.
	 *
	 * <p>What makes it worth loading: the two KD11-B revisions have <i>identical</i> addresses and
	 * next-addresses and differ in 20 bits across 14 microwords, so a wrongly chosen revision does
	 * not look wrong - every address resolves, every chain walks, and the microword on screen is
	 * simply incorrect in {@code AUX} or {@code CKO} with nothing to show for it.</p>
	 */
	public MicrocodeSource getOther() {
		return switch(this) {
			case PDP1144 -> null;
			case PDP1105_E -> PDP1105_F;
			case PDP1105_F -> PDP1105_E;
		};
	}

	/** The document packaged with the application. */
	public Microcode load() {
		return m_revision == null ? Pdp1144Microcode.builtin() : Kd11bMicrocode.load(m_revision);
	}

	/** Another copy of it: a re-transcription, another scan, or a listing split per page. */
	public Microcode load(List<Path> files) throws IOException {
		if(files.isEmpty())
			throw new IllegalArgumentException("No files to load");
		if(m_revision != null)
			return Kd11bMicrocode.load(files.get(0), m_revision);
		return files.size() == 1 ? Pdp1144Microcode.load(files.get(0)) : Pdp1144Microcode.load(files);
	}

	/** Whether choosing several files at once means anything for this document. */
	public boolean isSplitAcrossFiles() {
		return m_revision == null;
	}

	/** What the file chooser should say it is asking for. */
	public String getOpenPrompt() {
		return m_revision == null
			? "Open a PDP-11/44 microcode listing (or every page of one)"
			: "Open a KD11-B control store transcription for " + m_revision.getLabel();
	}

	/**
	 * Whether the listing carries line numbers of its own, so that searching by one means
	 * something.
	 *
	 * <p>The 11/44's listing prints its own line number beside every microword. The KD11-B
	 * transcription has no such thing - it is a bit table, and a row number in a printed drawing
	 * is not what anybody is looking for - so the window offers two ways of searching there
	 * rather than three with one that never helps.</p>
	 */
	public boolean hasListingLineNumbers() {
		return m_revision == null;
	}

	/** The entry with this label, or {@code null} for one this version does not know. */
	public static MicrocodeSource byLabel(String label) {
		for(MicrocodeSource s : values()) {
			if(s.getLabel().equals(label))
				return s;
		}
		return null;
	}

	@Override
	public String toString() {
		return m_label;
	}
}
