package to.etc.pdp11.common.macro11.asm;

/**
 * Every warning the assembler can give.
 *
 * <p>A warning is something DEC's MACRO-11 would accept, and this assembler accepts too, but
 * which is more often a mistake than an intention. Each one has a kind so that it can be switched
 * off by {@link AssemblerOptions#withoutWarning}, and so that a test can ask for exactly the
 * warning it is about rather than matching message text.</p>
 */
public enum WarningKind {
	/** A label that nothing refers to. */
	UNUSED_LABEL("A label that is never referred to"),

	/** A symbol given a value with {@code =} that nothing refers to. */
	UNUSED_SYMBOL("A symbol that is assigned but never used"),

	/** A macro defined in the source and never called. */
	UNUSED_MACRO("A macro that is defined but never called"),

	/** An instruction straight after an unconditional jump, with no label to reach it by. */
	UNREACHABLE_CODE("An instruction that follows an unconditional transfer and has no label"),

	/**
	 * {@code .IF DF} or {@code .IF NDF} on a symbol that is only defined further down.
	 *
	 * <p>The test sees the symbol as undefined, because at that point it is. DEC's two-pass
	 * MACRO-11 would see it undefined in one pass and defined in the other, which is worse.</p>
	 */
	CONDITION_ON_LATER_DEFINITION("A .IF DF/NDF on a symbol that is defined later in the source"),

	/** The source has no {@code .END}. */
	MISSING_END("The source does not end with .END"),

	/** Statements after {@code .END}, which are ignored. */
	TEXT_AFTER_END("Statements after .END, which are ignored"),

	/** Two statements put something at the same address. */
	OVERLAPPING_CODE("Two statements store at the same address"),

	/** A word instruction whose operand address is odd, which traps on a real machine. */
	ODD_WORD_ADDRESS("A word operand at an odd address"),

	/** A register used where a number is expected, as in {@code .WORD R0} or {@code #R0}. */
	REGISTER_AS_VALUE("A register used as a number"),

	/** An immediate operand as a destination: the instruction writes into its own code. */
	IMMEDIATE_DESTINATION("An instruction whose destination is an immediate operand"),

	/** A byte instruction's immediate operand does not fit in a byte. */
	BYTE_IMMEDIATE_TRUNCATED("A byte instruction's immediate value does not fit in a byte"),

	/** Expression arithmetic that does not fit in 16 bits. */
	ARITHMETIC_OVERFLOW("Expression arithmetic that overflows 16 bits"),

	/** A label on a line that moves the location counter, which gets the old location. */
	LABEL_BEFORE_LOCATION_CHANGE("A label on a line that changes the location counter"),

	/** A macro defined a second time, replacing the first definition. */
	MACRO_REDEFINED("A macro that is defined again"),

	/** More arguments in a macro call than the macro has parameters. */
	EXCESS_MACRO_ARGUMENTS("A macro call with more arguments than the macro takes"),

	/** {@code DIV} or {@code ASHC} with an odd register, whose result is unpredictable. */
	ODD_REGISTER_PAIR("DIV or ASHC with an odd register"),

	/** An option of {@code .ENABL}/{@code .DSABL} that this assembler accepts but ignores. */
	UNSUPPORTED_OPTION("An .ENABL or .DSABL option that is ignored"),

	/** A statement with no operation, which is assembled as {@code .WORD}. */
	IMPLICIT_WORD("A statement without an operation, assembled as .WORD"),

	/**
	 * Two different symbols with the same first six characters.
	 *
	 * <p>DEC's MACRO-11 only looks at six, so it would see one symbol where this assembler sees
	 * two. Only a warning, because here they are kept apart.</p>
	 */
	SYMBOL_NOT_UNIQUE_IN_SIX("Two symbols that are the same in their first six characters"),

	/** A {@code .PSECT} reopened with attributes that differ from the first time. */
	PSECT_ATTRIBUTE_CHANGE("A .PSECT reopened with different attributes"),

	/** {@code -(PC)} or {@code @-(PC)}, which runs the program backwards over its own code. */
	PC_AUTO_DECREMENT("Auto-decrement of the PC"),

	/** The program's start address is odd. */
	ODD_TRANSFER_ADDRESS("An odd start address on .END"),

	/** An instruction that does nothing, such as {@code MOV R0,R0} or {@code BR .+2}. */
	NO_EFFECT("An instruction that has no effect"),

	/**
	 * A plain number where a register belongs, as in {@code -(6)}. Old sources do this, and it
	 * means the register, but {@code R6} or {@code %6} says so.
	 */
	NUMBER_AS_REGISTER("A plain number used as a register");

	private final String m_description;

	WarningKind(String description) {
		m_description = description;
	}

	/** What this warning is about, for a settings screen or a tooltip. */
	public String getDescription() {
		return m_description;
	}
}
