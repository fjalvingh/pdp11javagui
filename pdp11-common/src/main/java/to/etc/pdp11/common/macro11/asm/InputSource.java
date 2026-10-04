package to.etc.pdp11.common.macro11.asm;

/**
 * Something lines are being read from: the source file, an included file, or the text a macro
 * call or repeat block expanded to.
 *
 * @param reader           the lines
 * @param kind             what it is
 * @param conditionalDepth how many conditionals were open when it started; an expansion must
 *                         close the ones it opens, and {@code .MEXIT} closes them for it
 * @param argumentCount    for a macro expansion, how many arguments the call gave - {@code .NARG}
 */
record InputSource(TokenReader reader, Kind kind, int conditionalDepth, int argumentCount) {
	enum Kind {
		FILE,
		MACRO,
		REPEAT
	}

	boolean isExpansion() {
		return kind != Kind.FILE;
	}
}
