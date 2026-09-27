package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One progress monitor, more than one phase.
 *
 * <p>{@code MemoryTester}'s phases are each a {@code begin(…) … finally done()} pair and
 * {@code MemoryTestPanel} chains two of them through one {@link ProgressDialog}, so a monitor
 * that can only be used once means the second - typically longer - phase of every two-phase
 * memory test runs with no progress display and nothing to press to stop it.</p>
 *
 * <h2>Why this asks the monitor rather than looking for a dialog</h2>
 *
 * <p>The whole sequence runs inside one {@link Edt#run} block, which is deliberate twice over: a
 * dialog is never built, so this needs no display and can run on CI, and the show timer cannot
 * fire in the middle of it, because its action would have to run on the event thread this block
 * is occupying. What is asserted is the guard the second phase silently failed.</p>
 */
class ProgressDialogTest {
	@Test
	void aSecondPhaseGetsItsOwnDialogAndItsOwnCancel() {
		boolean[] seen = new boolean[6];
		Edt.run(() -> {
			//-- No owner: nothing here gets as far as wanting a parent window.
			ProgressDialog pm = new ProgressDialog(null);

			pm.begin("Checking address lines ...", 2 * 16);
			seen[0] = pm.shouldShow();
			seen[1] = pm.isShowScheduled();
			pm.done();
			seen[2] = pm.shouldShow();
			seen[3] = pm.isShowScheduled();

			//-- Phase 2: the one that finds a short rather than a break, and the longer of the two.
			pm.begin("Checking address lines, the other direction ...", 2 * 16);
			seen[4] = pm.shouldShow();
			seen[5] = pm.isShowScheduled();
			pm.done();
		});

		assertTrue(seen[0], "phase 1 shows a dialog if it takes longer than the threshold");
		assertTrue(seen[1], "and arms the timer that decides that");
		assertFalse(seen[2], "the phase is over, so there is nothing left to put up");
		assertFalse(seen[3], "and nothing armed to put it up");
		assertTrue(seen[4], "phase 2 is not the end of the operation and gets a dialog of its own");
		assertTrue(seen[5], "with its own threshold, and its own Cancel when it fires");
	}
}
