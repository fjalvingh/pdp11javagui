package to.etc.pdp11.ui.terminal;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.console.TerminalProfile;
import to.etc.pdp11.ui.Edt;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine's bell, made visible.
 *
 * <p>XXDP rings once per completed pass. The view flashes and counts rather than beeping, and
 * prints nothing for it: the transcript is still what the machine said, character for
 * character.</p>
 */
class GlassTerminalViewBellTest {
	@Test
	void aBellFlashesCountsAndPrintsNothing() {
		GlassTerminalView view = Edt.call(GlassTerminalView::new);
		AtomicInteger heard = new AtomicInteger();
		Edt.run(() -> {
			view.setProfile(new TerminalProfile(true, true, (char) 8, 8));
			view.setBellListener(heard::incrementAndGet);
			assertFalse(view.isBellFlashing());
			view.append("END PASS #1\u0007\r\nEND PASS #2\u0007\r\n", TerminalStyle.PDP);
		});
		assertEquals("END PASS #1\nEND PASS #2\n", Edt.call(view::getText));
		assertEquals(2, Edt.call(view::getBellCount));
		assertEquals(2, heard.get());
		assertTrue(Edt.call(view::isBellFlashing), "the background flashes on a bell");
	}

	@Test
	void aBellBesideAnEraseDoesNotUpsetIt() {
		GlassTerminalView view = Edt.call(GlassTerminalView::new);
		Edt.run(() -> {
			view.setProfile(new TerminalProfile(true, true, (char) 8, 8));
			view.append("abc\u0007\bd", TerminalStyle.PDP);
		});
		assertEquals("abd", Edt.call(view::getText));
		assertEquals(1, Edt.call(view::getBellCount));
	}
}
