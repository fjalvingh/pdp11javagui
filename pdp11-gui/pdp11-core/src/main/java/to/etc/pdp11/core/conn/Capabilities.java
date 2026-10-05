package to.etc.pdp11.core.conn;

import to.etc.pdp11.core.console.ConsoleFeature;

import java.util.EnumSet;
import java.util.Set;

/**
 * What can be done with this machine over this console - the one place a window asks.
 *
 * <p>{@link ConsoleFeature} is the driver's statement of what it can send. Windows used to read
 * it each their own way: the Execution window decided whether a machine could be stopped from two
 * features, the run-mode check from a third, the I/O page scanner and unasked reads from a
 * fourth, and the rule "Halt is always enabled" lived in one window and was wrong on a boot ROM
 * console. This is those decisions, made once, as a function with a test (PLAN.md §2,
 * "Capabilities").</p>
 *
 * <p>Three inputs. The protocol and the CPU come from the {@link ConnectionProfile}; the features
 * come from the live console, because ODT's change with the RUN/HALT switch. The CPU narrows
 * nothing yet: no capability is known to differ by CPU behind the same console, and a guess
 * would either offer something that kills the console or hide something that works. When one is
 * known it goes in {@link #of} as a row, with a test and with where the fact came from.</p>
 */
public final class Capabilities {
	/** What a window sees with no connection: nothing. */
	public static final Capabilities NONE = new Capabilities(EnumSet.noneOf(Capability.class));

	private final EnumSet<Capability> m_set;

	private Capabilities(EnumSet<Capability> set) {
		m_set = set;
	}

	/**
	 * What can be done, given what is on the other end.
	 *
	 * @param protocol which console; part of the key for the CPU rows to come
	 * @param cpu      which machine it is in front of, {@link CpuModel#UNKNOWN} when not said
	 * @param features what the live console says it can do now
	 */
	public static Capabilities of(ConsoleProtocol protocol, CpuModel cpu, Set<ConsoleFeature> features) {
		if(protocol == null || features == null)
			throw new IllegalArgumentException("protocol and features are required");
		EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
		if(features.contains(ConsoleFeature.ACTION_RESET_AND_START_CPU))
			set.add(Capability.START);
		if(features.contains(ConsoleFeature.ACTION_RESET_MACHINE))
			set.add(Capability.RESET);
		if(features.contains(ConsoleFeature.CPU_REGISTERS))
			set.add(Capability.SET_PC);
		if(features.contains(ConsoleFeature.ACTION_CONTINUE_CPU))
			set.add(Capability.CONTINUE);
		if(features.contains(ConsoleFeature.ACTION_SINGLE_STEP))
			set.add(Capability.SINGLE_STEP);
		if(features.contains(ConsoleFeature.ACTION_HALT_CPU))
			set.add(Capability.HALT);
		if(features.contains(ConsoleFeature.SWITCH_ENABLE_OR_HALT))
			set.add(Capability.RUN_HALT_SWITCH);
		if(features.contains(ConsoleFeature.NON_FATAL_UNIBUS_TIMEOUT))
			set.add(Capability.SURVIVES_BUS_TIMEOUT);
		//-- Rows by CPU go here. There are none: see the class comment.
		return new Capabilities(set);
	}

	public boolean can(Capability c) {
		return m_set.contains(c);
	}

	/**
	 * Whether this window can do anything about a running program: halt it, or say which way
	 * to move the switch. Where it cannot - a boot ROM console - a started program is out of
	 * sight until the console emulator comes back, and "running" is a guess that must not lock
	 * anything.
	 */
	public boolean canStopProgram() {
		return can(Capability.HALT) || can(Capability.RUN_HALT_SWITCH);
	}

	public Set<Capability> asSet() {
		return Set.copyOf(m_set);
	}

	@Override
	public String toString() {
		return m_set.toString();
	}
}
