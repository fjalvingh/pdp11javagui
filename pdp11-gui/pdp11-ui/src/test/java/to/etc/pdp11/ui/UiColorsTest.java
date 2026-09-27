package to.etc.pdp11.ui;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * "Only {@link UiColors} names a colour", from CLAUDE.md, as a build failure.
 *
 * <p>{@code Pdp11Gui}'s own javadoc asserts that nothing else in the UI names a colour, and the
 * terminal quietly did: its background, its caret and the three stream colours - which are the
 * clearest possible case of a colour that <i>means</i> something, since they are how the window
 * says who is talking - were {@code new Color(...)} literals inside
 * {@code GlassTerminalView}'s constructor.</p>
 *
 * <p>The rule is what stops that coming back. A second theme should be a change to one file, and
 * it only is while this passes.</p>
 */
class UiColorsTest {
	private static final JavaClasses UI = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("to.etc.pdp11.ui");

	@Test
	void nothingOutsideUiColorsConstructsAColour() {
		ArchRule rule = noClasses()
			.that().doNotHaveFullyQualifiedName(UiColors.class.getName())
			.should().callConstructor(Color.class, int.class, int.class, int.class)
			.orShould().callConstructor(Color.class, int.class)
			.orShould().callConstructor(Color.class, int.class, boolean.class)
			.orShould().callConstructor(Color.class, int.class, int.class, int.class, int.class)
			.orShould().callConstructor(Color.class, float.class, float.class, float.class)
			.because("a colour that means something is a constant in UiColors, so that a second "
				+ "theme is a change to one file rather than a hunt through every panel");
		rule.check(UI);
	}

	@Test
	void theTerminalsOwnColoursAreAmongThem() {
		//-- Named here as well as in the rule: the rule would also pass if somebody deleted them.
		assertNotNull(UiColors.TERMINAL_BACKGROUND);
		assertNotNull(UiColors.TERMINAL_CARET);
		assertNotNull(UiColors.TERMINAL_PDP_TEXT);
		assertNotNull(UiColors.TERMINAL_USER_TEXT);
		assertNotNull(UiColors.TERMINAL_SYSTEM_TEXT);
	}
}
