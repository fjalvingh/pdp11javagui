package to.etc.pdp11.common.microcode;

import java.util.List;

/**
 * What a microword is for, as the machine's own documentation says: which routine it belongs to -
 * the instruction fetch, a source or destination addressing mode, the execution of one
 * instruction, a trap, a console switch - and what it does there.
 *
 * <p>The bits say what a microword does in the sense of which lines it asserts. They do not say
 * that {@code ET-2} is the second step of taking a trap, and a reader who has to work that out
 * from the next-address chain is doing by hand what DEC wrote down. So this is carried beside the
 * microword rather than derived from it, and it can be absent: a document that has no such
 * commentary, or a microword the commentary leaves out, simply has none.</p>
 *
 * @param category what sort of routine, in a few words - "Trap", "Console" - or {@code null}
 *                 where the microword is not shown in any routine
 * @param routine  the routine's heading as printed, or {@code null} likewise
 * @param action   what the microword does, as printed beside it, or {@code null}
 * @param notes    the comments printed about this microword: how it is reached, where it
 *                 branches, why it is the way it is; empty for none
 * @param source   where this came from, to be shown beside it
 */
public record MicrowordRole(String category, String routine, String action, List<String> notes, String source) {
	public MicrowordRole {
		notes = List.copyOf(notes);
	}

	/** The routine with its category, "Trap: EMT TRAP (VECTOR LOC=30)", or {@code null}. */
	public String summary() {
		if(routine == null)
			return null;
		return category == null ? routine : category + ": " + routine;
	}
}
