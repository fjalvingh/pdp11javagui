package to.etc.pdp11.common.diag;

import to.etc.pdp11.common.memfile.AbsoluteLoaderTape;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a program from the library can be run on its own - deposited into memory and started -
 * or needs something else loaded with it.
 *
 * <p>Only standalone programs are offered for running. The others are real, and worth keeping,
 * but they do not work without software this application does not load:</p>
 *
 * <ul>
 * <li><b>DRS programs</b> run under the XXDP+ Diagnostic Runtime Services supervisor
 *     ({@code DRSSM.SYS}/{@code DRSXM.SYS}). They are linked to load at 002000 and start with a
 *     program header there - the program's name in ASCII, then its revision (<i>PDP-11
 *     Diagnostic Design Guide</i>, 7.3.1 and 7.4.2) - and they call the supervisor for every
 *     message and every test. About a fifth of the XXDP V2 programs are like this.</li>
 * <li><b>XXDP itself</b> - monitors, drivers, the update and patch utilities - which reads and
 *     writes the load medium through the monitor.</li>
 * </ul>
 *
 * <p>Everything else that is an absolute loader image is the classic standalone MAINDEC program:
 * it carries its own vectors and console routines, and is started at its transfer address or,
 * when that is odd, at 200. 176 is its software switch register.</p>
 */
public final class ProgramCheck {
	/** Where a DRS program's header is. */
	static final int DRS_HEADER = 02000;

	/**
	 * Low-memory words a DRS image may still load: the ACT-11/XXDP hooks at 40 to 56 - location 52
	 * is where a program says what it needs, and DRS programs set it.
	 */
	private static final int HOOKS_LOW = 040;

	private static final int HOOKS_HIGH = 057;

	/** Program files; anything else (.TXT, .CCC, .LST ...) is not something to run. */
	private static final Set<String> PROGRAM_EXTENSIONS = Set.of("BIN", "BIC", "LDA", "SYS");

	/**
	 * XXDP utilities from before V2, which the index does not list under their names: they find
	 * their files through the monitor, as the V2 ones do.
	 */
	private static final Set<String> OLD_XXDP_UTILITIES = Set.of("COPY", "UPD1", "UPD2", "UPD3", "UPDATE", "XTECO", "SETUP", "PATCH", "UPDAT", "DXCL",
		"DTMON", "XXBOOT");

	/** The pre-V2 monitors, one per load device: RKDP, RXDP, TMDP and the rest. */
	private static final java.util.regex.Pattern OLD_MONITOR = java.util.regex.Pattern.compile("^[A-Z]{2}DP$");

	private ProgramCheck() {
	}

	public enum Kind {
		STANDALONE("standalone"),
		NEEDS_SUPERVISOR("needs DRS"),
		NEEDS_MONITOR("needs XXDP"),
		DAMAGED(""),
		NOT_A_PROGRAM("");

		private final String m_label;

		Kind(String label) {
			m_label = label;
		}

		/** A word or two for a table column. */
		public String getLabel() {
			return m_label;
		}
	}

	/**
	 * What the check found.
	 *
	 * @param kind   what it is
	 * @param reason one sentence, for the person deciding what to run
	 * @param tape   the parsed image when it is one, else null
	 */
	public record Verdict(Kind kind, String reason, AbsoluteLoaderTape tape) {
		public boolean isRunnable() {
			return kind == Kind.STANDALONE;
		}

		/** The verdict without the image, which is 128 KB and not worth keeping a thousand of. */
		public Brief brief() {
			return new Brief(kind, reason, tape == null ? -1 : tape.startAddress());
		}
	}

	/**
	 * A verdict to keep in a list.
	 *
	 * @param startAddress where the program starts, or -1 when it is not a program
	 */
	public record Brief(Kind kind, String reason, int startAddress) {
		public static final Brief NONE = new Brief(Kind.NOT_A_PROGRAM, "", -1);

		public boolean isRunnable() {
			return kind == Kind.STANDALONE;
		}
	}

	/**
	 * @param fileName       {@code NAME.EXT}
	 * @param damaged        whether the library knows the copy to be incomplete
	 * @param identification what the catalog makes of the name
	 */
	public static Verdict check(String fileName, byte[] data, boolean damaged, DiagnosticCatalog.Identification identification) {
		String name = fileName.toUpperCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		String base = dot < 0 ? name : name.substring(0, dot);
		String ext = dot < 0 ? "" : name.substring(dot + 1);
		if(!PROGRAM_EXTENSIONS.contains(ext))
			return new Verdict(Kind.NOT_A_PROGRAM, "A ." + ext + " file is not a program.", null);
		if(damaged)
			return new Verdict(Kind.DAMAGED, "This copy could not be read whole from its medium, so it is probably incomplete.", null);
		AbsoluteLoaderTape tape;
		try {
			tape = AbsoluteLoaderTape.parse(data, fileName);
		} catch(IOException x) {
			return new Verdict(Kind.NOT_A_PROGRAM, "Not a loadable image: " + x.getMessage(), null);
		}
		if(tape.loadedBytes() == 0)
			return new Verdict(Kind.NOT_A_PROGRAM, "Not an absolute loader image.", null);
		if(isDrsProgram(tape))
			return new Verdict(Kind.NEEDS_SUPERVISOR, "A DRS program: it runs under the XXDP Diagnostic Runtime Services "
				+ "supervisor, which must be loaded with it.", tape);
		if(ext.equals("SYS") || identification.family() == DiagnosticFamily.XXDP || OLD_XXDP_UTILITIES.contains(base)
			|| OLD_MONITOR.matcher(base).matches())
			return new Verdict(Kind.NEEDS_MONITOR, "Part of XXDP itself, which works through the XXDP monitor and its load medium.", tape);
		//-- Where it loads says how much memory it needs: an 11/05 with 8K words has none above
		//-- 037777, and nothing checks that but the person choosing.
		return new Verdict(Kind.STANDALONE, String.format(Locale.ROOT,
			"A standalone program: it loads from %06o to %06o and starts at %06o.",
			tape.lowestAddress(), tape.highestAddress(), tape.startAddress()), tape);
	}

	/**
	 * A DRS header at 002000 - an ASCII name and a revision - and nothing loaded below it but the
	 * hooks.
	 *
	 * <p>The name is up to eight characters, padded with blanks or NULs, sometimes with a blank in
	 * front ({@code " CZRCD"}); the revision is a letter and a digit. A standalone program that
	 * happens to load code at 2000 has instructions there, which are not that.</p>
	 */
	static boolean isDrsProgram(AbsoluteLoaderTape tape) {
		for(int a = 0; a < DRS_HEADER; a++) {
			if(tape.isLoaded(a) && (a < HOOKS_LOW || a > HOOKS_HIGH))
				return false;
		}
		int letters = 0;
		boolean ended = false;
		for(int i = 0; i < 8; i++) {
			if(!tape.isLoaded(DRS_HEADER + i))
				return false;
			int c = tape.byteAt(DRS_HEADER + i);
			if(c == 0 || c == ' ') {
				if(letters > 0)
					ended = true;
				continue;
			}
			boolean alnum = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
			if(!alnum || ended)
				return false;
			if(letters == 0 && !(c >= 'A' && c <= 'Z'))
				return false;
			letters++;
		}
		if(letters < 3)
			return false;
		int r0 = tape.byteAt(DRS_HEADER + 8);
		int r1 = tape.byteAt(DRS_HEADER + 9);
		return r0 >= 'A' && r0 <= 'Z' && r1 >= '0' && r1 <= '9';
	}
}
