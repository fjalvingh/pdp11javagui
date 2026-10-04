package to.etc.pdp11.common.macro11.asm;

/**
 * A general operand: a mode, a register, and the word that follows the instruction when the
 * mode has one.
 *
 * @param mode     the addressing mode, 0-7
 * @param register the register, 0-7
 * @param extra    the expression for the extra word, or null when the mode has none
 * @param use      what the extra word holds
 * @param location where the operand starts
 */
record Operand(int mode, int register, Expression extra, Use use, Location location) {
	/** What the word after the instruction is. */
	enum Use {
		/** There is none. */
		NONE,

		/** {@code #X} or {@code @#X}: the value itself. */
		IMMEDIATE_OR_ABSOLUTE,

		/** {@code X(R)}: an offset added to the register. */
		INDEX,

		/** {@code X} or {@code @X}: the distance from the word after it to {@code X}. */
		PC_RELATIVE
	}

	/** The six bits that go into the instruction word. */
	int bits() {
		return (mode << 3) | register;
	}

	boolean isRegister() {
		return mode == 0;
	}

	/** {@code #X}: mode 2 on the PC, with a value. */
	boolean isImmediate() {
		return mode == 2 && register == 7 && extra != null;
	}
}
