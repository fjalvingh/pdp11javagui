package to.etc.pdp11.core.microcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One named bit field inside a microword, and what its values mean.
 *
 * <p>A field on its own says nothing about which machine it belongs to; the table it is part of
 * does, and that is {@link MicrocodeArchitecture}. This used to hold the 11/44's table as static
 * state, which worked until there was a second machine to show - see that class.</p>
 *
 * <h2>The default matters as much as the value</h2>
 *
 * <p>Most fields spend most of their life at one value, which is the encoding for "this part of
 * the machine is not involved this cycle". Knowing that value is what turns 37 numbers into the
 * two or three that are actually doing something, and it is why {@link #defaultValue()} is part
 * of the table rather than a display detail: the window shows a microword by highlighting the
 * fields that differ from it.</p>
 *
 * <p>On a machine whose control lines are mostly active low - the KD11-B - the resting value of
 * a one-bit field is 1 rather than 0, and there is no way to guess that from the bits. It comes
 * from the signal name, and getting it backwards makes every microword look busy.</p>
 *
 * <h2>A field is a list of bits, not a range</h2>
 *
 * <p>Most fields are a run of adjacent bits and {@link #of} builds those. Some are not, and they
 * are not an oddity to be worked around somewhere else: the KD11-B prints its four-bit scratchpad
 * address as four <i>non-adjacent and out-of-order</i> single-bit columns, and its {@code BUT}
 * nibble in the order {@code BUT-1, BUT-0, BUT-2, BUT-3}. Decoded as if they were ranges both
 * produce a plausible wrong answer for every microword and there is nothing to notice. So the
 * bits a field is made of are listed, most significant first, and the order is part of the
 * table.</p>
 *
 * @param tag          the field's number in the original table, kept because the value names are
 *                     keyed by it there and it makes the two tables checkable against each other
 * @param name         the field's name, as the print set spells it
 * @param defaultValue the value meaning "nothing to do", or -1 for a field that has no such
 *                     value - only the next-address field, which is different every time
 * @param bits         which bits of the microword the field is made of, most significant first
 * @param values       what each value means, where the print set names them; a value not in here
 *                     is shown as a number, and an empty name means the value is worth no words
 */
public record MicrocodeField(int tag, String name, int defaultValue, List<Integer> bits,
	Map<Integer, String> values) {

	public MicrocodeField {
		if(bits.isEmpty())
			throw new IllegalArgumentException(name + " has no bits");
		for(int b : bits) {
			if(b < 0)
				throw new IllegalArgumentException(name + " has a bit at " + b);
		}
		if(bits.size() != Set.copyOf(bits).size())
			throw new IllegalArgumentException(name + " uses a bit twice: " + bits);
		bits = List.copyOf(bits);
		values = Map.copyOf(values);
	}

	/**
	 * A field made of a run of adjacent bits, which is most of them.
	 *
	 * @param lsb    the position of the field's least significant bit in the microword
	 * @param length how many bits
	 */
	public static MicrocodeField of(int tag, String name, int defaultValue, int lsb, int length,
		Map<Integer, String> values) {
		if(length < 1)
			throw new IllegalArgumentException(name + " is " + length + " bits wide");
		List<Integer> bits = new ArrayList<>(length);
		for(int b = lsb + length - 1; b >= lsb; b--)
			bits.add(b);
		return new MicrocodeField(tag, name, defaultValue, bits, values);
	}

	/** How many bits. */
	public int length() {
		return bits.size();
	}

	/** The lowest bit position the field touches. */
	public int lsb() {
		int lowest = bits.get(0);
		for(int b : bits)
			lowest = Math.min(lowest, b);
		return lowest;
	}

	/** Highest bit position, so a field reads as {@code msb:lsb} the way a print set writes it. */
	public int msb() {
		int highest = bits.get(0);
		for(int b : bits)
			highest = Math.max(highest, b);
		return highest;
	}

	/** Whether the field is a plain descending run, which is what {@link #of} builds. */
	public boolean isContiguous() {
		for(int i = 0; i < bits.size(); i++) {
			if(bits.get(i) != msb() - i)
				return false;
		}
		return true;
	}

	/**
	 * How the print set names this field's bits: {@code "87"} for one bit, {@code "102:93"} for a
	 * run, and every bit in value order for a field that is neither.
	 */
	public String bitRange() {
		if(isContiguous())
			return length() == 1 ? String.valueOf(lsb()) : msb() + ":" + lsb();
		StringBuilder sb = new StringBuilder();
		for(int b : bits)
			sb.append(sb.length() == 0 ? "" : ",").append(b);
		return sb.toString();
	}

	/**
	 * This field's value out of a microword that fits in a long.
	 *
	 * <p>Bit numbers are the machine's own - the schematic's, so that what this reads is what a
	 * maintenance panel's lights show. A word too wide for a long, which the 11/44's 104 bits
	 * are, is cut up by its own parser instead.</p>
	 */
	public int valueFrom(long word) {
		int value = 0;
		for(int b : bits)
			value = (value << 1) | (int) ((word >>> b) & 1);
		return value;
	}

	/** Whether there is a value that means "not this cycle". */
	public boolean hasDefault() {
		return defaultValue >= 0;
	}

	/**
	 * What this value means, or {@code null} when the print set does not name it.
	 *
	 * <p>An empty string is a third case and not the same as {@code null}: it means the value is
	 * named and the name is nothing - "load BA" versus "do not load BA". Those show as the
	 * number alone, which is also what an unnamed value shows as, but the distinction is real
	 * and the FP11 fields rely on it: none of their values are named at all.</p>
	 */
	public String text(int value) {
		return values.get(value);
	}

	/** The largest value that fits. */
	public int mask() {
		return (1 << length()) - 1;
	}

	@Override
	public String toString() {
		return name + " <" + bitRange() + ">";
	}
}
