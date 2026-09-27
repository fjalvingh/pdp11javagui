package to.etc.pdp11.core.console;

/**
 * What a particular console can do. Examine and deposit are not in here: every console can do
 * those, and one that could not would not be a console.
 *
 * <p>Ported from {@code TConsoleFeatures} ({@code ConsoleGenericU.pas:141-155}). The Pascal set
 * becomes an {@code EnumSet}. It drives button enablement in the UI, which is why a console
 * with a physical ENABLE/HALT switch reports a set that depends on where that switch is - see
 * {@link #SWITCH_ENABLE_OR_HALT}.</p>
 *
 * <p>A console may also "implement" an action by telling the user which switch to operate; from
 * the UI's point of view that still counts as having the feature.</p>
 */
public enum ConsoleFeature {
	/** The console survives a HALT and keeps talking - the machine does not stop dead (M9312). */
	NON_FATAL_HALT,

	/** The console survives a UNIBUS timeout and keeps talking. */
	NON_FATAL_UNIBUS_TIMEOUT,

	/**
	 * There is a physical ENABLE/HALT switch, so the feature set depends on the run mode.
	 * Named after the 11/70 front panel.
	 */
	SWITCH_ENABLE_OR_HALT,

	/** Can reset the machine without starting it, and set the PC. */
	ACTION_RESET_MACHINE,

	/** Can reset and then start the CPU. */
	ACTION_RESET_AND_START_CPU,

	/** Can continue a stopped CPU without resetting it. */
	ACTION_CONTINUE_CPU,

	/** Can stop a running program. */
	ACTION_HALT_CPU,

	/** Can execute one instruction. */
	ACTION_SINGLE_STEP,

	/** A reset also sets the PC. SimH's does not, which is why this is a separate flag. */
	RESET_CPU_SETS_PC
}
