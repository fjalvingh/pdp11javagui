package to.etc.pdp11.ui.terminal;

import to.etc.pdp11.core.console.TerminalProfile;

/**
 * Turns one console's byte stream into something a terminal can display.
 *
 * <p><b>This sits in front of the emulator, not inside it.</b> PLAN.md §3 is explicit about why:
 * these consoles are not ANSI devices and they disagree about line endings. ODT sends CR and LF
 * and means the LF; the 11/44's console sends a lone CR and means it. Hand a lone-CR stream to a
 * conforming VT100 emulator and every line overwrites the one before it, and the transcript the
 * user is trying to read becomes one line of gibberish.</p>
 *
 * <p>So the profile is applied here, and what comes out is plain text plus two control characters
 * the view knows: {@code \n} to end a line and {@code \b} to rub out the character before it.
 * Everything else the emulator - or the plain text pane standing in for one - can take
 * literally.</p>
 *
 * <p>Stateful, because tab expansion needs to know the column and the stream arrives in
 * arbitrary pieces. One filter per terminal, and it is reset when the profile changes.</p>
 */
public final class TerminalFilter {
	/** What the view is told to do: rub out the character before this one. */
	public static final char ERASE = '\b';

	private TerminalProfile m_profile;

	private int m_column;

	/** For absorbing the LF of a CR LF pair; see {@link #filter}. */
	private boolean m_lastWasCr;

	public TerminalFilter(TerminalProfile profile) {
		setProfile(profile);
	}

	/** The profile in use. Changing it starts a new line, since the old column means nothing. */
	public void setProfile(TerminalProfile profile) {
		m_profile = profile == null ? TerminalProfile.of(true, true) : profile;
		reset();
	}

	public TerminalProfile getProfile() {
		return m_profile;
	}

	public void reset() {
		m_column = 0;
		m_lastWasCr = false;
	}

	/**
	 * Filter one chunk of whatever arrived.
	 *
	 * <p>Chunk boundaries do not matter: nothing here needs more than one character of context,
	 * which is what lets a reader thread hand over whatever a single read happened to return.</p>
	 */
	public String filter(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for(int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			boolean wasCr = m_lastWasCr;
			m_lastWasCr = false;
			switch(c) {
				case '\r' -> {
					if(m_profile.crIsNewline()) {
						newline(out);
						m_lastWasCr = true;
					}
					//-- else: dropped. ODT sends one before every LF and means only the LF.
				}
				case '\n' -> {
					//-- A CR LF pair is one line ending, not two. Both flags are true for SimH -
					//-- its Enter sends CR and telnet breaks lines with LF - and it sends them
					//-- together, so taking each at face value double-spaces the entire
					//-- transcript. The Pascal does exactly that ({@code FormTerminalU.pas:248-253,
					//-- 278-279}), which is a deliberate divergence rather than an oversight
					//-- carried across: the redesign of this terminal is an agreed decision, and
					//-- every terminal ever built collapses the pair.
					if(wasCr) {
						//-- Already ended by the CR.
					} else if(m_profile.lfIsNewline()) {
						newline(out);
					}
					//-- else: dropped. The 11/44 console sends these and means nothing by them.
				}
				case '\t' -> tab(out);
				case 0 -> {
					//-- Fill characters. The M9301's prompt ends with one, and the 11/44 V3.40C
					//-- sends five on power-up; they mean "the line is settling", not a character.
				}
				default -> {
					if(c == m_profile.backspace() && m_profile.hasBackspace()) {
						out.append(ERASE);
						if(m_column > 0)
							m_column--;
					} else if(c >= 0x20 && c != 0x7F) {
						out.append(c);
						m_column++;
					}
					//-- Any other control character is dropped. It is not printable and, unlike
					//-- the ones above, means nothing to any console here.
				}
			}
		}
		return out.toString();
	}

	private void newline(StringBuilder out) {
		out.append('\n');
		m_column = 0;
	}

	/**
	 * Expand a tab, or pass it through when the console does not use them.
	 *
	 * <p>SimH is the only one with tab stops, and it uses them in its examine replies -
	 * {@code 1000:\t123456} - so getting this wrong misaligns every memory dump.</p>
	 */
	private void tab(StringBuilder out) {
		if(!m_profile.expandsTabs()) {
			out.append('\t');
			return;
		}
		int stop = m_profile.tabStop();
		int spaces = stop - (m_column % stop);
		out.append(" ".repeat(spaces));
		m_column += spaces;
	}

	/** Which column the next character would land in. For tests, and for a status readout. */
	public int getColumn() {
		return m_column;
	}
}
