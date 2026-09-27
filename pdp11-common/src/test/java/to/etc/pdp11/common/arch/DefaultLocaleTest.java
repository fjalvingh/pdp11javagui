package to.etc.pdp11.common.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.disas.DecodedInstruction;
import to.etc.pdp11.common.util.NumberConverter;

import java.util.Locale;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nothing in pdp11-common may fold case or format a number in whatever locale the machine
 * happens to be set to - the same rule, for the same reasons, as pdp11-core's test of this name.
 *
 * <p>Every case conversion here is over a <b>protocol or file token</b>: an instruction
 * mnemonic, a MACRO-11 listing, a microcode tag. Under {@code tr-TR}, {@code "INC".toLowerCase()}
 * is {@code "ınc"}, which breaks the display and the byte-identical diff against the Pascal; and
 * {@code String.format} with a digit conversion does not write {@code 0} as {@code '0'} in
 * several locales. On a web server the default locale is whatever the host was installed with,
 * which is less under anybody's control than a desktop's.</p>
 *
 * <p>The rules below are the guard; the tests under them are the demonstration that the rules
 * are guarding something real.</p>
 */
class DefaultLocaleTest {
	private static final JavaClasses COMMON = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("to.etc.pdp11.common");

	private final Locale m_saved = Locale.getDefault();

	@AfterEach
	void restoreLocale() {
		Locale.setDefault(m_saved);
	}

	@Test
	void commonNeverFoldsCaseInTheDefaultLocale() {
		ArchRule rule = noClasses()
			.should().callMethod(String.class, "toUpperCase")
			.orShould().callMethod(String.class, "toLowerCase")
			.because("a mnemonic, a listing and a microcode tag are file tokens, so they fold "
				+ "with Locale.ROOT and not with the user's locale");
		rule.check(COMMON);
	}

	@Test
	void commonNeverFormatsInTheDefaultLocale() {
		ArchRule rule = noClasses()
			.should().callMethod(String.class, "format", String.class, Object[].class)
			.because("a digit conversion in the default locale can write digits that are not "
				+ "0-9; pass Locale.ROOT");
		rule.check(COMMON);
	}

	// ---------------------------------------------------------------------------------------
	// What the rules are protecting, under the locale that actually breaks it
	// ---------------------------------------------------------------------------------------

	@Test
	void aMnemonicIsStillAsciiInTurkey() {
		Locale.setDefault(new Locale("tr", "TR"));
		assertEquals("inc     r0", new DecodedInstruction(0, 1, "INC", "r0", true).text());
		assertEquals("mfpi", new DecodedInstruction(0, 1, "MFPI", "", true).textTrimmed());
	}

	@Test
	void hexStillComesOutInAsciiDigitsInTurkey() {
		Locale.setDefault(new Locale("tr", "TR"));
		assertEquals("FF", NumberConverter.format(NumberConverter.Base.HEX, 255));
	}
}
