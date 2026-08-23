package to.etc.pdp11.core.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.bits.BitfieldsDef;
import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.disas.DecodedInstruction;
import to.etc.pdp11.core.machine.MachineDescription;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.NumberConverter;

import java.util.Locale;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Nothing in pdp11-core may fold case or format a number in whatever locale the machine
 * happens to be set to.
 *
 * <p>Every case conversion in this module is over a <b>protocol or file token</b> - an ini
 * section name, a bitfield definition name, an instruction mnemonic, an ODT address
 * expression. Under {@code tr-TR}, {@code "Bits.PSW".toUpperCase()} is {@code "BİTS.PSW"}
 * with a dotted capital I, so {@code startsWith("BITS.")} is false and every {@code [Bits.*]}
 * section loads as a bogus device group; {@code "INC".toLowerCase()} is {@code "ınc"}, which
 * breaks the display and the byte-identical diff against the Pascal. The same goes for
 * {@code String.format} with a digit conversion: several locales do not write {@code 0} as
 * {@code '0'}.</p>
 *
 * <p>The rules below are the guard; the tests under them are the demonstration that the rules
 * are guarding something real.</p>
 */
class DefaultLocaleTest {
	private static final JavaClasses CORE = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("to.etc.pdp11.core");

	private final Locale m_saved = Locale.getDefault();

	@AfterEach
	void restoreLocale() {
		Locale.setDefault(m_saved);
	}

	@Test
	void coreNeverFoldsCaseInTheDefaultLocale() {
		ArchRule rule = noClasses()
			.should().callMethod(String.class, "toUpperCase")
			.orShould().callMethod(String.class, "toLowerCase")
			.because("a machine description, a mnemonic and an ODT expression are protocol "
				+ "tokens, so they fold with Locale.ROOT and not with the user's locale");
		rule.check(CORE);
	}

	@Test
	void coreNeverFormatsInTheDefaultLocale() {
		ArchRule rule = noClasses()
			.should().callMethod(String.class, "format", String.class, Object[].class)
			.because("a digit conversion in the default locale can write digits that are not "
				+ "0-9; pass Locale.ROOT");
		rule.check(CORE);
	}

	// ---------------------------------------------------------------------------------------
	// What the rules are protecting, under the locale that actually breaks it
	// ---------------------------------------------------------------------------------------

	@Test
	void aBitsSectionIsStillABitsSectionInTurkey() {
		Locale.setDefault(new Locale("tr", "TR"));
		MemoryCellGroups groups = new MemoryCellGroups();
		BitfieldsDefs bits = new BitfieldsDefs();
		MachineDescription md = MachineDescription.parse(
			"[Bits.PSW]\nCarry = 0 ; carry\n", "test", groups, bits);

		assertNotNull(md);
		assertNotNull(bits.findByName("Bits.PSW"), "the section loaded as bit definitions");
		assertFalse(groups.getGroups().stream().anyMatch(g -> g.getGroupName().startsWith("Bits.")),
			"and not as a device group");
	}

	@Test
	void aBitfieldsDefIsFoundByNameInTurkey() {
		Locale.setDefault(new Locale("tr", "TR"));
		BitfieldsDefs defs = new BitfieldsDefs();
		defs.add(new BitfieldsDef("Bits.RLCS"));
		assertNotNull(defs.findByName("BITS.RLCS"), "case-insensitive means ASCII-insensitive");
		assertNotNull(defs.findByName("bits.rlcs"));
	}

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
