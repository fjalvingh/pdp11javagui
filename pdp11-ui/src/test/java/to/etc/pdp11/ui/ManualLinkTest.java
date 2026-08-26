package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.util.AppVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where <b>Help -&gt; User manual</b> sends the browser.
 *
 * <p>The interesting half is that a release points at its own tag rather than at {@code main}: a
 * jar somebody downloaded a year ago has to open the manual that was written for it, not one
 * describing windows it does not have. Nothing at run time can catch that being wrong - the URL
 * either 404s or, worse, quietly opens the wrong document - and the manifest is absent in a test
 * run, so {@link AppVersion#get()} can only ever be seen to answer "development build" here.
 * Hence a function of the version rather than a constant.</p>
 */
class ManualLinkTest {
	@Test
	void aReleasePointsAtItsOwnTag() {
		assertEquals("https://github.com/fjalvingh/pdp11javagui/blob/v1.2.0/manual/README.md",
			MainWindow.manualUrl("1.2.0"));
	}

	@Test
	void aPreReleasePointsAtItsOwnTagToo() {
		assertEquals("https://github.com/fjalvingh/pdp11javagui/blob/v1.2.0-rc1/manual/README.md",
			MainWindow.manualUrl("1.2.0-rc1"));
	}

	@Test
	void aDevelopmentBuildPointsAtMain() {
		assertEquals("https://github.com/fjalvingh/pdp11javagui/blob/main/manual/README.md",
			MainWindow.manualUrl(AppVersion.DEVELOPMENT));
	}

	/** The page it names is the manual's index, which is the one with the links to the rest. */
	@Test
	void itIsTheManualsIndexPage() {
		assertTrue(MainWindow.manualUrl(AppVersion.get()).endsWith("/manual/README.md"));
	}
}
