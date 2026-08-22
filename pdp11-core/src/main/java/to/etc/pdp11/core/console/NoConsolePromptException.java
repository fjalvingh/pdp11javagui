package to.etc.pdp11.core.console;

import to.etc.pdp11.core.util.Logger;

/**
 * The console never sent its prompt back, so whatever was sent cannot be confirmed.
 *
 * <p>Ported from {@code TConsoleGeneric.CheckPrompt} ({@code ConsoleGenericU.pas:474-508}),
 * which showed {@code FormNoConsolePrompt} modally and then {@code Abort}ed. The dialog is the
 * UI's business; this exception is the protocol layer's half of that conversation.</p>
 *
 * <p>It carries the same diagnostics the Pascal wrote to the debug output, because they answer
 * the one question worth asking here: did <i>nothing</i> arrive - genuine silence on the wire -
 * or did something arrive that was not recognised as a prompt, which is a parsing bug? Both
 * look identical from the outside.</p>
 */
public class NoConsolePromptException extends ConsoleException {
	private final String m_unconsumedInput;

	private final java.util.List<AnswerPhrase> m_answers;

	public NoConsolePromptException(String message, String unconsumedInput, java.util.List<AnswerPhrase> answers) {
		super(message);
		m_unconsumedInput = unconsumedInput;
		m_answers = java.util.List.copyOf(answers);
	}

	/** Whatever is still sitting in the scanner's buffer, unrecognised. */
	public String getUnconsumedInput() {
		return m_unconsumedInput;
	}

	/** The phrases that were decoded since the command was sent. */
	public java.util.List<AnswerPhrase> getAnswers() {
		return m_answers;
	}

	/**
	 * Write the diagnostics somewhere they can be read. The Pascal's own comment
	 * ({@code :483-485}) is the reason this is worth doing at all: {@code Log()} only reached
	 * the in-app window, so the failure was invisible to anything watching the process.
	 */
	public void logDiagnostics(Logger logger) {
		logger.log(to.etc.pdp11.core.util.LogChannel.OTHER, getMessage());
		//-- Cap what gets printed: a buffer big enough for the tail to matter is itself the
		//-- interesting fact, and the whole of it is not.
		String tail = m_unconsumedInput.length() <= 500
			? m_unconsumedInput
			: m_unconsumedInput.substring(m_unconsumedInput.length() - 500);
		logger.log(to.etc.pdp11.core.util.LogChannel.OTHER,
			String.format("  unconsumed raw input (%d bytes, last %d shown): \"%s\"",
				m_unconsumedInput.length(), tail.length(), printable(tail)));
		logger.log(to.etc.pdp11.core.util.LogChannel.OTHER,
			String.format("  %d answer(s) since the command was sent:", m_answers.size()));
		for(int i = 0; i < m_answers.size(); i++) {
			logger.log(to.etc.pdp11.core.util.LogChannel.OTHER, "    #" + i + ": " + m_answers.get(i).asText());
		}
	}

	/** Control characters as {@code <nn>}, so a log line stays one line. */
	static String printable(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if(c < 0x20 || c == 0x7F)
				sb.append('<').append(String.format("%02x", (int) c)).append('>');
			else
				sb.append(c);
		}
		return sb.toString();
	}
}
