package to.etc.pdp11.core.fake;

import to.etc.pdp11.core.util.Scheduler;

import java.util.Random;

/**
 * A PDP-11 whose console is the older M9301 boot ROM's console emulator.
 *
 * <p>Ported from {@code TFakePDP11M9301} ({@code FakePDP11M9301U.pas}), which is four lines long:
 * the M9301 is the M9312 with a different prompt. Described in the <i>PDP-11/34 system user's
 * manual</i> (July 1977, EK-11034-UG-001), chapter 2.1.3, pages 2-3.</p>
 *
 * <p>The prompt is <b>{@code $} followed by a NUL</b>, and the NUL is the interesting part. The
 * ROMs in an M9301-YA send it as a fill character while the line settles; an M9301-YF does not.
 * A console driver has to cope with either, which is exactly why the scanner filters NULs
 * everywhere rather than only where they are expected.</p>
 *
 * <p>The Pascal's own comment on it: "With NUL it is more difficult to parse!" That is the
 * point - this fake is the one that proves the filtering works.</p>
 */
public final class FakePdp11M9301 extends FakePdp11M9312 {
	public FakePdp11M9301(Scheduler scheduler, Random random) {
		super("Fake PDP-11 M9301", scheduler, random);
		setPrompt("$\0");
	}
}
