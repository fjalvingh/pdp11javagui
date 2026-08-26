package to.etc.pdp11.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link AppVersion}: what the manifest's version string is taken to mean. */
public class AppVersionTest {
	@Test
	public void aTaggedReleaseVersionIsUsedAsItStands() {
		assertEquals("1.2.0", AppVersion.normalise("1.2.0"));
		assertEquals("1.2.0-rc1", AppVersion.normalise("1.2.0-rc1"));
		assertEquals("1.2.0", AppVersion.normalise("  1.2.0  "));
	}

	@Test
	public void noManifestEntryIsADevelopmentBuild() {
		assertEquals(AppVersion.DEVELOPMENT, AppVersion.normalise(null));
		assertEquals(AppVersion.DEVELOPMENT, AppVersion.normalise(""));
		assertEquals(AppVersion.DEVELOPMENT, AppVersion.normalise("   "));
	}

	@Test
	public void aSnapshotIsADevelopmentBuildToo() {
		//-- What a jar built from the checked-in pom carries; it is not a release and must not
		//-- claim to be one.
		assertEquals(AppVersion.DEVELOPMENT, AppVersion.normalise("1.0-SNAPSHOT"));
	}

	@Test
	public void aTestRunIsNeverAReleaseBuild() {
		assertEquals(AppVersion.DEVELOPMENT, AppVersion.get());
	}
}
