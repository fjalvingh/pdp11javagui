package to.etc.pdp11.ui.terminal;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.console.TerminalProfile;
import to.etc.pdp11.ui.Edt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A carriage return that does not end the line goes back to its start, and what follows writes
 * over it.
 *
 * <p>The case that found it: ZQKC ends every pass with CR SPACE CR, which a terminal shows as
 * nothing, and which came out here as a line slowly filling with spaces.</p>
 */
class GlassTerminalViewReturnTest {
	private static final TerminalProfile M9312 = TerminalProfile.of(false, true);

	private static GlassTerminalView view() {
		GlassTerminalView view = Edt.call(GlassTerminalView::new);
		Edt.run(() -> view.setProfile(M9312));
		return view;
	}

	@Test
	void zqkcsPassMarkerLeavesTheScreenAsItWas() {
		GlassTerminalView view = view();
		Edt.run(() -> {
			view.append("\r\nDZQKC\r\n", TerminalStyle.PDP);
			for(int pass = 0; pass < 5; pass++)
				view.append("\r \r", TerminalStyle.PDP);
		});
		assertEquals("\nDZQKC\n ", Edt.call(view::getText));
	}

	@Test
	void whatFollowsAReturnWritesOverTheLine() {
		GlassTerminalView view = view();
		Edt.run(() -> view.append("first\nABCDEF\rxy", TerminalStyle.PDP));
		assertEquals("first\nxyCDEF", Edt.call(view::getText));
		//-- Writing past the end of the old line carries on adding to it.
		Edt.run(() -> view.append("zzzzzz", TerminalStyle.PDP));
		assertEquals("first\nxyzzzzzz", Edt.call(view::getText));
	}

	@Test
	void aLineEndingInTheMiddleOfAnOverwriteGoesAtTheEnd() {
		GlassTerminalView view = view();
		Edt.run(() -> view.append("ABCDEF\rxy\r\nnext", TerminalStyle.PDP));
		assertEquals("xyCDEF\nnext", Edt.call(view::getText));
	}

	@Test
	void odtsLineEndingsAreUnchanged() {
		GlassTerminalView view = view();
		Edt.run(() -> {
			view.append("@1000/000000 \r", TerminalStyle.PDP);
			view.append("\n@", TerminalStyle.PDP);
		});
		assertEquals("@1000/000000 \n@", Edt.call(view::getText));
	}

	@Test
	void theApplicationsOwnTextIsNotWrittenOver() {
		GlassTerminalView view = view();
		Edt.run(() -> {
			view.append("ABC\r", TerminalStyle.PDP);
			view.append("[Disconnected]", TerminalStyle.SYSTEM);
			view.append("x", TerminalStyle.PDP);
		});
		assertEquals("ABC[Disconnected]x", Edt.call(view::getText));
	}
}
