package to.etc.pdp11.core.conn;

import java.util.EnumSet;
import java.util.List;

/**
 * Which PDP-11 is behind the console - the second axis of {@link Capabilities}.
 *
 * <p>A console protocol says how to talk; it does not say to what. The M9312 console emulator is
 * the same driver on an 11/04, an 11/05 and an 11/34, and what can be done with the machine is
 * not necessarily the same. {@link #UNKNOWN} is always allowed, and is what every profile saved
 * before this existed reads as: it gets only what is true whichever CPU it turns out to be.</p>
 *
 * <p>The list is the machines the consoles here are named after or have been used on, not every
 * PDP-11 there was. Add one when a console is used on it.</p>
 */
public enum CpuModel {
	UNKNOWN("Not specified"),

	LSI11("LSI-11 (11/03)"),

	PDP11_04("PDP-11/04"),

	PDP11_05("PDP-11/05"),

	PDP11_23("PDP-11/23"),

	PDP11_34("PDP-11/34"),

	PDP11_44("PDP-11/44"),

	PDP11_73("PDP-11/73"),

	PDP11_93("PDP-11/93");

	private final String m_label;

	CpuModel(String label) {
		m_label = label;
	}

	public String getLabel() {
		return m_label;
	}

	/**
	 * The CPUs a console of this protocol can be in front of, {@link #UNKNOWN} first.
	 *
	 * <p>SimH can be set to be any of them. The ODT widths follow the labels of
	 * {@link ConsoleProtocol}; the boot ROM consoles are on UNIBUS machines without ODT, the 11/05
	 * among them; the K1630 is a machine of its own and offers no choice.</p>
	 */
	public static List<CpuModel> forProtocol(ConsoleProtocol protocol) {
		EnumSet<CpuModel> set = switch(protocol) {
			case SIMH -> EnumSet.allOf(CpuModel.class);
			case ODT_16 -> EnumSet.of(UNKNOWN, LSI11);
			case ODT_18 -> EnumSet.of(UNKNOWN, PDP11_23);
			case ODT_22 -> EnumSet.of(UNKNOWN, PDP11_73, PDP11_93);
			case ODT_K1630 -> EnumSet.of(UNKNOWN);
			case PDP1144, PDP1144_V340C -> EnumSet.of(UNKNOWN, PDP11_44);
			case M9312, M9301 -> EnumSet.of(UNKNOWN, PDP11_04, PDP11_05, PDP11_34);
		};
		return List.copyOf(set);
	}

	@Override
	public String toString() {
		return m_label;
	}
}
