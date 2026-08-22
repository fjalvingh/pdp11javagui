package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.mem.CellValue;

/**
 * One decoded thing a console said.
 *
 * <p>Ported from {@code TConsoleAnswerPhrase} ({@code ConsoleGenericU.pas:85-100}), which is a
 * discriminated union faked with a plain class: {@code haltaddr} means something only when
 * {@code phrasetype} is {@code phHalt}, {@code examineaddr}/{@code examinevalue} only for
 * {@code phExamine}, and nothing in the type system says so. A sealed hierarchy says so, and
 * the use sites become pattern matches. See PLAN.md §2.</p>
 *
 * <p>Every phrase keeps the exact text it was decoded from. That is what makes a console
 * transcript reconstructible after the fact, and it is what the "no prompt" diagnostics
 * print.</p>
 *
 * <p>Note {@code otherline} is a {@code shortstring} in the Pascal and so truncates at 255
 * characters; this does not. A behaviour change, almost certainly harmless, worth knowing.</p>
 */
public sealed interface AnswerPhrase {
	/** The raw text this was decoded from, exactly as received. */
	String rawText();

	/** One line for the log, ported from {@code TConsoleAnswerPhrase.AsText} ({@code :279-292}). */
	String asText();

	/** The command prompt: the console is idle and listening. */
	record Prompt(String rawText) implements AnswerPhrase {
		@Override
		public String asText() {
			return "Console answered: Prompt";
		}
	}

	/**
	 * The CPU stopped, and said where.
	 *
	 * <p>The address is <b>virtual</b> - a console reports the PC as the program sees it, not
	 * as the UNIBUS does.</p>
	 */
	record Halt(String rawText, Address haltAddr) implements AnswerPhrase {
		@Override
		public String asText() {
			return "Console answered: Halt, haltaddr=" + haltAddr.toOctal();
		}
	}

	/**
	 * The answer to an examine - or to an examine that failed.
	 *
	 * @param examineAddr the address the console echoed back, or {@code null} when it did not
	 *                    say (SimH's "Address space exceeded" names no address, so a UNIBUS
	 *                    timeout has to be attributed by the caller, which knows what it
	 *                    asked for)
	 * @param value       {@link CellValue#UNKNOWN} for a UNIBUS timeout, which is a valid
	 *                    answer and not an error
	 */
	record ExamineResult(String rawText, Address examineAddr, CellValue value) implements AnswerPhrase {
		/** Whether this reports a nonexistent address rather than a value. */
		public boolean isTimeout() {
			return !value.isKnown();
		}

		@Override
		public String asText() {
			return "Console answered: Examine, addr="
				+ (examineAddr == null ? "unknown" : examineAddr.toOctal())
				+ ", value=" + value.toOctal();
		}
	}

	/** A line nothing else recognised. Banners, errors, echoes of what we just sent. */
	record OtherLine(String rawText, String text) implements AnswerPhrase {
		public OtherLine(String text) {
			this(text, text);
		}

		@Override
		public String asText() {
			return "Console answered: OtherLine \"" + text + "\"";
		}
	}
}
