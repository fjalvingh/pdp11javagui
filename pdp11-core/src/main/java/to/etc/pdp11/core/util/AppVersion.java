package to.etc.pdp11.core.util;

/**
 * Which release of the application this is.
 *
 * <p>The release build stamps {@code Implementation-Version} into the shaded jar's manifest from
 * the Maven version, which the release workflow in turn sets from the git tag. So a jar that
 * somebody downloaded a year ago can say which release it is, with nothing having had to be
 * edited by hand and nothing to get out of step.</p>
 *
 * <p>A development build has no such entry - it runs from {@code target/classes}, or from a jar
 * still at {@code -SNAPSHOT} - and answers {@link #DEVELOPMENT} rather than inventing a release
 * number. Reading a manifest is the sort of thing that can fail in a class loader nobody
 * anticipated, so it is done once, defensively, and never throws: a program that will not start
 * because it cannot work out its own version number would be an absurd thing to ship.</p>
 */
public final class AppVersion {
	/** What {@link #get()} answers when this is not a release build. */
	public static final String DEVELOPMENT = "development build";

	private static final String VERSION = read();

	private AppVersion() {
	}

	/** The release version ("1.2.0"), or {@link #DEVELOPMENT}. Never null. */
	public static String get() {
		return VERSION;
	}

	private static String read() {
		try {
			Package p = AppVersion.class.getPackage();
			return normalise(p == null ? null : p.getImplementationVersion());
		} catch(RuntimeException x) {
			return DEVELOPMENT;
		}
	}

	/**
	 * What a raw {@code Implementation-Version} means. Package-private so the interesting half
	 * has a test: the manifest is absent in a test run, so {@link #read()} itself can only ever
	 * be seen to answer {@link #DEVELOPMENT}.
	 */
	static String normalise(String raw) {
		if(raw == null)
			return DEVELOPMENT;
		String v = raw.trim();
		if(v.isEmpty() || v.endsWith("-SNAPSHOT"))
			return DEVELOPMENT;
		return v;
	}
}
