package to.etc.pdp11.core.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The m4 macro processor, to the extent the PDP-11 machine descriptions use it.
 *
 * <p>Machine descriptions are m4 sources that expand into Windows {@code .ini} files: a
 * machine is a list of module instances, and each {@code Module_XXX(baseaddr)} call expands to
 * the register definitions of one device jumpered at that address. The Pascal shells out to
 * {@code m4.bat} for this ({@code MemoryCellU.pas:576-655}), which is a Windows batch file, so
 * <b>the whole feature is dead on Linux</b> - bitfields, the I/O page scanner and the
 * register-group windows cannot work there at all.</p>
 *
 * <p>Reimplementing rather than shelling out also removes a process launch, a temp file, a
 * 5 second timeout, two {@code Application.ProcessMessages} spin loops and a "network delay"
 * retry loop from the load path.</p>
 *
 * <h2>What is implemented, and why only that</h2>
 *
 * <p>PLAN.md §0 measured the m4 surface across all nine machine description files:
 * {@code define} x15, {@code include} x11, {@code eval(expr,8)} x3, and {@code $1}/{@code $2}
 * x46. Nothing else. So this implements the real m4 <i>architecture</i> - quoting, argument
 * collection, and pushback so that a macro's expansion is rescanned - with only those three
 * builtins on top.</p>
 *
 * <p>The architecture is not optional. {@code Module_SLU}'s body contains
 * {@code _offset($1,0)}, so after {@code $1} is substituted the result has to be scanned
 * <i>again</i> for {@code _offset} to expand. A regex pass cannot do that.</p>
 *
 * <p>Nor are comments optional. The module library documents its own macros inside
 * {@code ;#} comment blocks, and those comments contain a {@code define(Module_SLU,`} with no
 * closing quote ({@code pdp11.modules:20-24}). m4 copies comment text through without looking
 * at it; anything that did not would swallow the rest of the file into a quoted string.</p>
 *
 * <p>Builtins that are <b>not</b> implemented are named in {@link #UNSUPPORTED_BUILTINS} and
 * throw. m4's own behaviour for an unknown name is to pass it through as text, which for a
 * machine description would mean a silently wrong I/O page rather than an error.</p>
 */
public final class M4Preprocessor {
	/** m4's default quote characters. The machine descriptions never change them. */
	private static final char QUOTE_OPEN = '`';

	private static final char QUOTE_CLOSE = '\'';

	/** m4's default comment delimiters: to end of line, copied through unexpanded. */
	private static final char COMMENT_START = '#';

	/**
	 * Real m4 builtins this does not implement. Named so that a machine description growing a
	 * new construct fails loudly instead of emitting the macro call as literal text.
	 */
	private static final Set<String> UNSUPPORTED_BUILTINS = Set.of(
		"ifelse", "ifdef", "dnl", "divert", "undivert", "divnum", "changequote", "changecom",
		"shift", "incr", "decr", "len", "substr", "translit", "index", "regexp", "patsubst",
		"format", "sinclude", "undefine", "pushdef", "popdef", "defn", "errprint", "m4exit",
		"m4wrap", "maketemp", "mkstemp", "syscmd", "esyscmd", "sysval", "traceon", "traceoff",
		"builtin", "indir");

	/** How deep argument collection may nest before it is called a runaway. */
	private static final int MAX_DEPTH = 100;

	/**
	 * How many macros one run may expand.
	 *
	 * <p>A depth limit alone does not catch a runaway, and finding that out cost a hung test
	 * and then an out-of-memory one. An expansion is <i>pushed back onto the input</i> rather
	 * than expanded recursively - which is the whole reason {@code Module_SLU}'s body gets
	 * rescanned for {@code _offset} - so a macro that expands to a call on itself loops
	 * forever inside a single {@code scan()} with the Java stack never growing. Counting
	 * expansions is the only thing that sees it.</p>
	 *
	 * <p>Expanding the shipped {@code pdp11.ini} costs 215 expansions, so this leaves nearly
	 * three orders of magnitude of headroom and still fails in well under a second.</p>
	 */
	private static final int MAX_EXPANSIONS = 100_000;

	private final List<Path> m_includePath = new ArrayList<>();

	private final Map<String, String> m_macros = new HashMap<>();

	private int m_depth;

	private int m_expansions;

	/**
	 * @param includeDirs where {@code include()} looks, in order. The Pascal passes
	 *                    {@code --include=%PDP11GUIAPPDATADIR%\machines} plus m4's own default
	 *                    of the input file's directory ({@code m4.bat:10}).
	 */
	public M4Preprocessor(Path... includeDirs) {
		for(Path p : includeDirs) {
			if(p != null)
				m_includePath.add(p);
		}
	}

	/**
	 * Preprocess a file.
	 *
	 * <p>Machine descriptions are ISO-8859-1 - the only non-ASCII byte in them is {@code 0xB5}
	 * for the micro sign in "&#181;Code" - and decoding them as UTF-8 fails. See
	 * {@code machines/README.md}.</p>
	 */
	public String processFile(Path file) {
		Path dir = file.toAbsolutePath().getParent();
		if(dir != null && !m_includePath.contains(dir))
			m_includePath.add(0, dir);
		return process(read(file), file.toString());
	}

	/** Preprocess text. {@code origin} only names the source in error messages. */
	public String process(String text, String origin) {
		//-- Per run, not cumulative: the count calibrates MAX_EXPANSIONS and reports what the
		//-- last run cost, and a reused instance was adding every earlier run to both.
		m_expansions = 0;
		try {
			return scan(text);
		} catch(M4Exception x) {
			throw new M4Exception(origin + ": " + x.getMessage(), x.getCause());
		}
	}

	/** How many macros the last run expanded; calibrates {@link #MAX_EXPANSIONS}. */
	public int getExpansionCount() {
		return m_expansions;
	}

	/** Define a macro from outside, the equivalent of m4's {@code -D}. */
	public void define(String name, String body) {
		m_macros.put(name, body);
	}

	// ---------------------------------------------------------------------------------------
	// The scanner
	// ---------------------------------------------------------------------------------------

	/** One string being read, with a position. The input is a stack of these so that a
	 *  macro's expansion can be pushed back in front of whatever follows it. */
	private static final class Src {
		private final String text;

		private int pos;

		private Src(String text) {
			this.text = text;
		}
	}

	/**
	 * Expand {@code text} completely. Used for the whole input and, recursively, for each
	 * macro argument - m4 expands arguments as it collects them.
	 */
	private String scan(String text) {
		if(++m_depth > MAX_DEPTH)
			throw new M4Exception("macro expansion is " + MAX_DEPTH + " deep; a macro is probably recursive");
		try {
			Deque<Src> stack = new ArrayDeque<>();
			stack.push(new Src(text));
			StringBuilder out = new StringBuilder(text.length() + 64);

			int c;
			while((c = read(stack)) >= 0) {
				if(c == QUOTE_OPEN) {
					//-- Quoted text loses one level of quotes and is NOT rescanned.
					out.append(readQuoted(stack));
				} else if(c == COMMENT_START) {
					out.append((char) c).append(readToEndOfLine(stack));
				} else if(isNameStart(c)) {
					String name = readName(stack, (char) c);
					String body = m_macros.get(name);
					boolean called = peekChar(stack) == '(';
					//-- A user macro expands with or without parentheses; every m4 builtin here
					//-- requires arguments, and GNU m4 leaves such a name alone when it is not
					//-- actually called. That is not a nicety: the machine descriptions are full
					//-- of English prose like "does not include UNIBUS addresses" and "within 3
					//-- index pulses", sitting in register info strings that reach the scanner
					//-- unquoted. Expanding those words would corrupt the descriptions.
					if(body == null && !(called && isBuiltin(name))) {
						out.append(name);
						continue;
					}
					List<String> args = called ? collectArgs(stack, name) : List.of();
					if(++m_expansions > MAX_EXPANSIONS)
						throw new M4Exception("gave up after " + MAX_EXPANSIONS
							+ " macro expansions at '" + name + "'; a macro or an include expands to a call on itself");
					//-- Push the expansion back so it is rescanned, which is the whole point.
					pushBack(stack, expandMacro(name, body, args));
				} else {
					out.append((char) c);
				}
			}
			return out.toString();
		} finally {
			m_depth--;
		}
	}

	private String expandMacro(String name, String body, List<String> args) {
		if(body != null)
			return substituteParameters(name, body, args);

		return switch(name) {
			case "define" -> {
				if(args.isEmpty())
					throw new M4Exception("define needs at least a name");
				m_macros.put(args.get(0), args.size() > 1 ? args.get(1) : "");
				yield "";
			}
			case "include" -> {
				if(args.size() != 1)
					throw new M4Exception("include takes exactly one file name, got " + args.size() + " arguments");
				yield includeFile(args.get(0));
			}
			case "eval" -> {
				if(args.isEmpty() || args.size() > 3)
					throw new M4Exception("eval takes 1 to 3 arguments, got " + args.size());
				long v = M4Evaluator.evaluate(args.get(0));
				//-- The third argument is a minimum width, zero-padded. Accepting it and
				//-- ignoring it is how an address that was written to be six digits long comes
				//-- out as three (FABLE-ISSUES #57).
				yield M4Evaluator.format(v, intArg(args, 1, 10, "radix"), intArg(args, 2, 1, "width"));
			}
			default -> throw new M4Exception("m4 builtin '" + name + "' is used but not implemented; "
				+ "see machines/README.md for the subset the machine descriptions were measured to need");
		};
	}

	/**
	 * One of {@code eval}'s trailing numeric arguments, or {@code fallback} when it is absent
	 * or empty.
	 *
	 * <p>A bare {@code NumberFormatException} out of here says "For input string" and names
	 * neither the file nor the macro; every other way of writing a machine description wrongly
	 * produces an {@link M4Exception}, which {@code process()} then prefixes with the source.</p>
	 */
	private static int intArg(List<String> args, int index, int fallback, String what) {
		if(args.size() <= index || args.get(index).isBlank())
			return fallback;
		String text = args.get(index).trim();
		try {
			return Integer.parseInt(text);
		} catch(NumberFormatException x) {
			throw new M4Exception("eval: " + what + " must be a number, got \"" + text + "\"");
		}
	}

	/** Substitute {@code $0}..{@code $9} in a macro body. */
	private static String substituteParameters(String name, String body, List<String> args) {
		StringBuilder sb = new StringBuilder(body.length() + 32);
		for(int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if(c != '$' || i + 1 >= body.length()) {
				sb.append(c);
				continue;
			}
			char n = body.charAt(i + 1);
			if(n >= '0' && n <= '9') {
				int idx = n - '0';
				if(idx == 0)
					sb.append(name);
				else if(idx <= args.size())
					sb.append(args.get(idx - 1));
				//-- A parameter the call did not supply expands to nothing, as in m4.
				i++;
			} else if(n == '#' || n == '*' || n == '@') {
				throw new M4Exception("macro '" + name + "' uses $" + n
					+ ", which is not implemented; the machine descriptions use only $1..$9");
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * Collect a macro call's arguments. The opening {@code (} is still unread. Arguments are
	 * split on commas at paren depth zero and outside quotes, leading unquoted whitespace is
	 * dropped, and each argument is expanded - which is what strips its quotes.
	 */
	private List<String> collectArgs(Deque<Src> stack, String macroName) {
		read(stack);                                            // the '('
		List<String> args = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int parens = 0;
		boolean atArgStart = true;

		while(true) {
			int c = read(stack);
			if(c < 0)
				throw new M4Exception("end of input inside the argument list of '" + macroName + "'");

			if(c == QUOTE_OPEN) {
				//-- Keep the quotes: the argument is expanded afterwards, and that is what
				//-- removes exactly one level of them.
				cur.append(QUOTE_OPEN).append(readQuotedRaw(stack)).append(QUOTE_CLOSE);
				atArgStart = false;
				continue;
			}
			if(c == COMMENT_START) {
				cur.append((char) c).append(readToEndOfLine(stack));
				atArgStart = false;
				continue;
			}
			if(atArgStart && Character.isWhitespace(c))
				continue;                                       // m4 drops leading whitespace
			if(c == '(') {
				parens++;
			} else if(c == ')') {
				if(parens == 0) {
					args.add(scan(cur.toString()));
					return args;
				}
				parens--;
			} else if(c == ',' && parens == 0) {
				args.add(scan(cur.toString()));
				cur.setLength(0);
				atArgStart = true;
				continue;
			}
			cur.append((char) c);
			atArgStart = false;
		}
	}

	/** Read a quoted string, returning it with the outermost quote pair removed. */
	private String readQuoted(Deque<Src> stack) {
		return readQuotedRaw(stack);
	}

	/**
	 * The body of a quoted string, outermost quotes stripped and nested pairs kept. The
	 * opening quote has been read.
	 */
	private String readQuotedRaw(Deque<Src> stack) {
		StringBuilder sb = new StringBuilder();
		int level = 1;
		while(true) {
			int c = read(stack);
			if(c < 0)
				throw new M4Exception("end of input inside a quoted string; a `' pair is unbalanced");
			if(c == QUOTE_OPEN) {
				level++;
			} else if(c == QUOTE_CLOSE) {
				if(--level == 0)
					return sb.toString();
			}
			sb.append((char) c);
		}
	}

	/** A comment: everything up to and including the newline, taken literally. */
	private String readToEndOfLine(Deque<Src> stack) {
		StringBuilder sb = new StringBuilder();
		int c;
		while((c = read(stack)) >= 0) {
			sb.append((char) c);
			if(c == '\n')
				break;
		}
		return sb.toString();
	}

	private String readName(Deque<Src> stack, char first) {
		StringBuilder sb = new StringBuilder().append(first);
		while(true) {
			int c = peekChar(stack);
			if(c < 0 || !isNamePart(c))
				return sb.toString();
			sb.append((char) read(stack));
		}
	}

	private String includeFile(String name) {
		Path found = null;
		for(Path dir : m_includePath) {
			Path p = dir.resolve(name);
			if(Files.isReadable(p)) {
				found = p;
				break;
			}
		}
		if(found == null)
			throw new M4Exception("include: cannot find '" + name + "' in " + m_includePath);

		//-- No include-loop check here on purpose: an include is pushed back onto the input
		//-- like any other expansion, so by the time its contents are scanned this call has
		//-- already returned and there is no nesting for a stack to detect. A file that
		//-- includes itself is caught by MAX_EXPANSIONS instead.
		return read(found);
	}

	private static String read(Path file) {
		try {
			//-- ISO-8859-1, not UTF-8: see the class comment.
			return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
		} catch(IOException x) {
			throw new M4Exception("cannot read " + file + ": " + x.getMessage(), x);
		}
	}

	private static int read(Deque<Src> stack) {
		while(!stack.isEmpty()) {
			Src s = stack.peek();
			if(s.pos < s.text.length())
				return s.text.charAt(s.pos++);
			stack.pop();
		}
		return -1;
	}

	private static int peekChar(Deque<Src> stack) {
		for(Src s : stack) {
			if(s.pos < s.text.length())
				return s.text.charAt(s.pos);
		}
		return -1;
	}

	/**
	 * Push an expansion in front of the remaining input.
	 *
	 * <p>Exhausted sources are dropped first. Without that the stack grows by one entry per
	 * expansion - {@code read()} returns from the new top and never reaches the spent entry
	 * below it to pop it - so a self-recursive macro runs out of heap long before it runs out
	 * of the expansion budget, and even an ordinary run leaves one dead entry per macro call.</p>
	 */
	private static void pushBack(Deque<Src> stack, String text) {
		while(!stack.isEmpty() && stack.peek().pos >= stack.peek().text.length()) {
			stack.pop();
		}
		if(!text.isEmpty())
			stack.push(new Src(text));
	}

	private static boolean isBuiltin(String name) {
		return "define".equals(name) || "include".equals(name) || "eval".equals(name)
			|| UNSUPPORTED_BUILTINS.contains(name);
	}

	private static boolean isNameStart(int c) {
		return Character.isLetter(c) || c == '_';
	}

	private static boolean isNamePart(int c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}
}
