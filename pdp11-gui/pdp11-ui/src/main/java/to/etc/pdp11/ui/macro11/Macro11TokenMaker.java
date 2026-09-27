package to.etc.pdp11.ui.macro11;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.text.Segment;

/**
 * Syntax highlighting for MACRO-11 assembler source.
 *
 * <p>Hand-written rather than generated. PLAN.md §5 says "MACRO-11 JFlex mode", and JFlex is the
 * usual way to make one of these - but it needs a code-generation plugin in the build for a
 * language whose whole grammar is "a comment starts at a semicolon and everything else is a
 * word". {@link AbstractTokenMaker} is the supported way to write one directly, the result is
 * the same set of tokens, and the build stays a plain compile.</p>
 *
 * <h2>What MACRO-11 lines look like</h2>
 *
 * <pre>
 * loop:   mov     #400,sp         ; a comment runs to the end of the line
 *         .word   last-.          ; "." is the current location counter
 *         .ascii  "Hello"
 * 2$:     bne     loop            ; a local label may start with a digit
 * </pre>
 *
 * <p>Four things are worth colouring differently and are what this recognises: the comment,
 * because half of a listing is comment; the directives, which all begin with {@code .}; the
 * registers, because {@code r0} and a symbol named {@code r0} being the same colour is
 * confusing; and numbers, which are octal and easy to mistake for labels.</p>
 *
 * <p>Everything is case-insensitive - {@code MOV} and {@code mov} are one instruction, and real
 * DEC listings shout.</p>
 */
public final class Macro11TokenMaker extends AbstractTokenMaker {
	/** The MIME-ish name this language is registered under. */
	public static final String SYNTAX_STYLE = "text/macro11";

	/**
	 * The PDP-11 instruction set, as MACRO-11 spells it.
	 *
	 * <p>Deliberately the mnemonics rather than the opcodes: this is a text editor and it knows
	 * nothing about encoding. {@link to.etc.pdp11.core.disas.Disassembler} is the other half of
	 * that and is a different problem.</p>
	 */
	private static final String[] INSTRUCTIONS = {
		//-- Double operand
		"mov", "movb", "cmp", "cmpb", "bit", "bitb", "bic", "bicb", "bis", "bisb", "add", "sub",
		//-- Register/operand
		"mul", "div", "ash", "ashc", "xor",
		//-- Single operand
		"clr", "clrb", "com", "comb", "inc", "incb", "dec", "decb", "neg", "negb", "adc", "adcb",
		"sbc", "sbcb", "tst", "tstb", "ror", "rorb", "rol", "rolb", "asr", "asrb", "asl", "aslb",
		"swab", "sxt", "mfps", "mtps", "mfpi", "mfpd", "mtpi", "mtpd",
		//-- Branches
		"br", "bne", "beq", "bpl", "bmi", "bvc", "bvs", "bcc", "bcs", "bge", "blt", "bgt", "ble",
		"bhi", "blos", "bhis", "blo", "sob",
		//-- Jumps and subroutines
		"jmp", "jsr", "rts", "mark", "trap", "emt", "bpt", "iot", "rti", "rtt",
		//-- Condition codes and control
		"halt", "wait", "reset", "nop", "clc", "clv", "clz", "cln", "ccc", "sec", "sev", "sez",
		"sen", "scc", "spl",
		//-- Floating point (FP11)
		"fadd", "fsub", "fmul", "fdiv", "setf", "setd", "seti", "setl", "ldf", "ldd", "stf", "std",
		"addf", "addd", "subf", "subd", "mulf", "muld", "divf", "divd", "cmpf", "cmpd", "tstf",
		"tstd", "absf", "absd", "negf", "negd", "clrf", "clrd", "ldcif", "ldcid", "ldclf", "ldcld",
		"stcfi", "stcdi", "stcfl", "stcdl", "ldcfd", "ldcdf", "stcfd", "stcdf", "ldexp", "stexp",
		"ldfps", "stfps", "cfcc"
	};

	/** Assembler directives. All start with a dot, which is how they are recognised anyway. */
	private static final String[] DIRECTIVES = {
		".ascii", ".asciz", ".asect", ".blkb", ".blkw", ".byte", ".csect", ".dsabl", ".enabl",
		".end", ".endc", ".endm", ".endr", ".error", ".even", ".flt2", ".flt4", ".globl", ".iden",
		".if", ".ifdf", ".iff", ".ift", ".iftf", ".ifz", ".iif", ".include", ".irp", ".irpc",
		".limit", ".list", ".macro", ".mcall", ".mexit", ".narg", ".nchr", ".nlist", ".ntype",
		".odd", ".page", ".print", ".psect", ".radix", ".rad50", ".rem", ".rept", ".restore",
		".sbttl", ".save", ".title", ".word"
	};

	private static final String[] REGISTERS = {
		"r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "sp", "pc",
		"ac0", "ac1", "ac2", "ac3", "ac4", "ac5"
	};

	/**
	 * Teach RSyntaxTextArea about this language. Idempotent; call before building an editor.
	 *
	 * <p>The default factory maps a style name to a class name and instantiates it reflectively,
	 * so the class has to be public with a no-argument constructor - which is why this one is.</p>
	 */
	public static void register() {
		TokenMakerFactory factory = TokenMakerFactory.getDefaultInstance();
		if(factory instanceof AbstractTokenMakerFactory f)
			f.putMapping(SYNTAX_STYLE, Macro11TokenMaker.class.getName(), Macro11TokenMaker.class.getClassLoader());
	}

	@Override
	public TokenMap getWordsToHighlight() {
		//-- Case-insensitive: MACRO-11 is, and old DEC listings are entirely upper case.
		TokenMap map = new TokenMap(true);
		for(String s : INSTRUCTIONS) {
			map.put(s, TokenTypes.RESERVED_WORD);
		}
		for(String s : DIRECTIVES) {
			//-- DATA_TYPE rather than FUNCTION, which is what a directive most resembles: the
			//-- stock dark theme paints FUNCTION near-white, so directives would be the same
			//-- colour as ordinary text and the distinction would exist only in the code.
			map.put(s, TokenTypes.DATA_TYPE);
		}
		for(String s : REGISTERS) {
			map.put(s, TokenTypes.VARIABLE);
		}
		return map;
	}

	@Override
	public String[] getLineCommentStartAndEnd(int languageIndex) {
		return new String[] {";", null};
	}

	/**
	 * Split one line into tokens.
	 *
	 * <p>Called for every visible line on every repaint, so it walks the segment once and makes
	 * no garbage. There is no multi-line state to carry: MACRO-11 has no block comment and no
	 * multi-line string, so {@code initialTokenType} is always {@code NULL} and is ignored.</p>
	 */
	@Override
	public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
		resetTokenList();
		char[] array = text.array;
		int offset = text.offset;
		int end = offset + text.count;
		//-- The document offset of array[offset], so a token's document position is
		//-- documentOffsetOfFirstChar + (index - offset).
		int base = startOffset - offset;

		int i = offset;
		while(i < end) {
			char c = array[i];
			if(c == ';') {
				//-- Everything to the end of the line, and there is no way to escape out of it.
				addToken(text, i, end - 1, TokenTypes.COMMENT_EOL, base + i);
				i = end;
			} else if(c == ' ' || c == '\t') {
				int start = i;
				while(i < end && (array[i] == ' ' || array[i] == '\t')) {
					i++;
				}
				addToken(text, start, i - 1, TokenTypes.WHITESPACE, base + start);
			} else if(c == '"' || c == '/') {
				//-- .ascii takes either quotes or a pair of delimiters; an unterminated one is
				//-- coloured to the end of the line, which is what it looks like to the assembler.
				int start = i;
				i++;
				while(i < end && array[i] != c) {
					i++;
				}
				if(i < end)
					i++;
				addToken(text, start, i - 1, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, base + start);
			} else if(isWordStart(c)) {
				int start = i;
				while(i < end && isWordPart(array[i])) {
					i++;
				}
				//-- A trailing colon makes it a label definition, whatever the word is - including
				//-- "2$:", which is a local label and starts with a digit.
				if(i < end && array[i] == ':') {
					i++;
					addToken(text, start, i - 1, TokenTypes.PREPROCESSOR, base + start);
				} else if(isNumber(array, start, i)) {
					addToken(text, start, i - 1, TokenTypes.LITERAL_NUMBER_DECIMAL_INT, base + start);
				} else {
					//-- The word map is consulted here rather than in addToken: despite holding the
					//-- map, AbstractTokenMaker does not look anything up in it, and a maker that
					//-- only emits IDENTIFIER gets no highlighting at all.
					int known = wordsToHighlight.get(text, start, i - 1);
					addToken(text, start, i - 1, known == -1 ? TokenTypes.IDENTIFIER : known, base + start);
				}
			} else {
				addToken(text, i, i, TokenTypes.OPERATOR, base + i);
				i++;
			}
		}
		addNullToken();
		return firstToken;
	}

	/**
	 * A word may start with a letter, a digit, a dot or a dollar.
	 *
	 * <p>The dot because every directive does and because a bare {@code .} is the location
	 * counter; the digit because {@code 2$} is a local label and because a number is lexically a
	 * word here.</p>
	 */
	private static boolean isWordStart(char c) {
		return Character.isLetterOrDigit(c) || c == '.' || c == '$' || c == '_';
	}

	private static boolean isWordPart(char c) {
		return Character.isLetterOrDigit(c) || c == '.' || c == '$' || c == '_';
	}

	/** All digits, so it is a number rather than a symbol. A trailing {@code .} means decimal. */
	private static boolean isNumber(char[] array, int start, int end) {
		boolean any = false;
		for(int i = start; i < end; i++) {
			char c = array[i];
			if(c >= '0' && c <= '9') {
				any = true;
			} else if(c == '.' && i == end - 1) {
				return any;                                  // "10." - a decimal literal
			} else {
				return false;
			}
		}
		return any;
	}
}
