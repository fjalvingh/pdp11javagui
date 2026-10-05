package to.etc.pdp11.core.conn;

/**
 * Something the user can do with the machine over this connection, named as the user's action
 * rather than as the console command that does it. See {@link Capabilities}.
 */
public enum Capability {
	/** Start a program at an address. A bus reset comes with it wherever the console does one. */
	START,

	/** Reset the machine and leave it stopped. */
	RESET,

	/** Write the PC with the machine stopped, without starting anything. */
	SET_PC,

	/** Carry on from where the machine stopped. */
	CONTINUE,

	/** Execute one instruction. */
	SINGLE_STEP,

	/** Stop a running program from here. */
	HALT,

	/**
	 * The machine has a RUN/HALT switch which the operator tells us the position of. It decides
	 * what the console's commands mean, and it is how a program is stopped when {@link #HALT} is
	 * not there.
	 */
	RUN_HALT_SWITCH,

	/**
	 * Reading an address with nothing behind it leaves the console talking. Without it a window
	 * may not read the machine on its own, and the I/O page cannot be scanned.
	 */
	SURVIVES_BUS_TIMEOUT
}
