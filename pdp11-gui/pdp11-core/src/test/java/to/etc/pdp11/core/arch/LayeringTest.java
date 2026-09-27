package to.etc.pdp11.core.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.console.ConsoleScanner;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The layering constraint from PLAN.md section 2: pdp11-core must not depend on Swing or AWT.
 *
 * <p>This is what keeps the console protocol layer testable headlessly against the ported
 * Fake* simulators, and it is the single most important structural rule in the project. The
 * Pascal original has the opposite property - {@code SerialXferU.pas} alone carries 31
 * references to {@code FormMain.} and {@code BusyForm.} - so the rule has to be a build
 * failure rather than a code review question.</p>
 *
 * <p>Production code is already blocked at compile time by {@code --limit-modules java.base}
 * in this module's pom, which is both earlier and harder than any test. This test exists
 * because that flag cannot be applied to the test sources - ArchUnit and JUnit themselves
 * need more of the JDK than the production code is allowed to touch - and because it fails
 * with a message that names the offending class rather than the offending import.</p>
 */
class LayeringTest {
	/**
	 * Scanning without test classes on purpose: the rule is about what pdp11-core ships,
	 * and the test tree deliberately uses more of the JDK.
	 */
	private static final JavaClasses CORE = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("to.etc.pdp11.core");

	@Test
	void coreDoesNotDependOnSwingOrAwt() {
		ArchRule rule = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage(
				"javax.swing..",
				"java.awt..",
				"javax.imageio..",
				"java.applet..")
			.because("pdp11-core must stay headless so the protocol layer can be tested "
				+ "against the fakes with no display (PLAN.md section 2)");
		rule.check(CORE);
	}

	/**
	 * The scanner's buffer is a plain StringBuilder that the reader thread appends to under the
	 * console's decode lock. Reading it from the command thread without that lock - which every
	 * {@code NoConsolePromptException} used to do while building its own diagnostic - can hand
	 * back corrupt text or throw {@link ArrayIndexOutOfBoundsException} out of the code that
	 * exists to explain a failure. {@code AbstractConsole.getUnconsumedInput()} is the one way
	 * in, so it is the only caller allowed.
	 */
	@Test
	void onlyAbstractConsoleReadsTheScannerBufferAcrossThreads() {
		//-- By target owner rather than by name: every scanner is a subclass, so a call through
		//-- OdtScanner is a call to OdtScanner.getInput as far as the bytecode is concerned.
		ArchRule rule = noClasses()
			.that().doNotHaveFullyQualifiedName("to.etc.pdp11.core.console.AbstractConsole")
			.should(callMethodWhere(describe("a call to ConsoleScanner.getInput()",
				(JavaMethodCall call) -> "getInput".equals(call.getTarget().getName())
					&& call.getTarget().getOwner().isAssignableTo(ConsoleScanner.class))))
			.because("the buffer is appended on the reader thread; go through "
				+ "AbstractConsole.getUnconsumedInput(), which takes the decode lock");
		rule.check(CORE);
	}

	@Test
	void coreDoesNotDependOnTheUiOrAppModules() {
		ArchRule rule = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage(
				"to.etc.pdp11.ui..",
				"to.etc.pdp11.app..")
			.because("the dependency runs core <- ui <- app and never the other way");
		rule.check(CORE);
	}
}
