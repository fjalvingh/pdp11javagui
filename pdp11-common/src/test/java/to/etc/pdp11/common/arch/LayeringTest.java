package to.etc.pdp11.common.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * pdp11-common is shared by the desktop and the web application, so it may depend on neither,
 * nor on anything that only one of them can have.
 *
 * <p>Swing and AWT are kept out at compile time by {@code --limit-modules java.base} in this
 * module's pom; this test is for the test sources that flag cannot reach, and for the rule no
 * compiler flag can state: nothing here reaches into another module of this project. The
 * machine connection layer ({@code to.etc.pdp11.core}) is the one that matters - it drags in
 * the serial port, which the web application must never have.</p>
 */
class LayeringTest {
	private static final JavaClasses COMMON = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("to.etc.pdp11.common");

	@Test
	void commonDoesNotDependOnSwingOrAwt() {
		ArchRule rule = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage(
				"javax.swing..",
				"java.awt..",
				"javax.imageio..",
				"java.applet..")
			.because("pdp11-common is used by the web application, which has no display");
		rule.check(COMMON);
	}

	@Test
	void commonDoesNotDependOnAnyOtherModule() {
		ArchRule rule = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage(
				"to.etc.pdp11.core..",
				"to.etc.pdp11.ui..",
				"to.etc.pdp11.app..",
				"to.etc.pdp11.web..")
			.because("pdp11-common is shared by the desktop and the web application, "
				+ "and depends on neither");
		rule.check(COMMON);
	}
}
