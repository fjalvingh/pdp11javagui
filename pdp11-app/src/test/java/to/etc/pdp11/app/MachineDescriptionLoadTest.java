package to.etc.pdp11.app;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.bits.BitfieldDef;
import to.etc.pdp11.core.bits.BitfieldsDef;
import to.etc.pdp11.core.bits.BitfieldsDefs;
import to.etc.pdp11.core.machine.M4Preprocessor;
import to.etc.pdp11.core.machine.MachineDescription;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN.md phase 2 is done when "a real {@code machines/pdp11.ini} loads into groups and
 * bitfield defs". This is that test, and it lives here rather than in {@code pdp11-core}
 * because this is where the machine descriptions are.
 *
 * <p>Worth remembering that this feature <b>has never worked on Linux</b>: the Pascal
 * hardcodes {@code m4.bat} ({@code MemoryCellU.pas:599}) and raises if it is missing, so the
 * bitfields, the I/O page scanner and the register-group windows are all dead there. The
 * numbers below are the first time anything has checked what the shipped description actually
 * contains.</p>
 */
class MachineDescriptionLoadTest {
	private static final Path MACHINES = Path.of("src/main/resources/machines");

	private static final Path INI = MACHINES.resolve("pdp11.ini");

	/**
	 * The Java m4 must reproduce GNU m4 1.4.21 exactly over the shipped description. The
	 * fixture was generated in phase 0 by {@code m4 --include=. pdp11.ini}; see
	 * {@code src/test/resources/machines/README.md}.
	 */
	@Test
	void theJavaM4ReproducesGnuM4ByteForByte() throws IOException {
		String ours = new M4Preprocessor(MACHINES).processFile(INI);
		byte[] expected = Files.readAllBytes(Path.of("src/test/resources/machines/pdp11.expected.ini"));
		assertEquals(new String(expected, StandardCharsets.ISO_8859_1), ours);
	}

	@Test
	void theShippedDescriptionLoadsWithoutComplaint() {
		MemoryCellGroups groups = new MemoryCellGroups();
		BitfieldsDefs bits = new BitfieldsDefs();
		MachineDescription md = MachineDescription.load(INI, groups, bits, Logger.NULL);

		assertEquals(List.of(), md.getWarnings(), "the shipped description should load cleanly");
		assertEquals(17, groups.size());
		assertEquals(62, bits.getDefinitions().size());
		assertEquals(233, groups.getGroups().stream().mapToInt(MemoryCellGroup::size).sum());
	}

	@Test
	void theCpuGroupHasItsRegistersWithNamesAndBits() {
		MemoryCellGroups groups = new MemoryCellGroups();
		BitfieldsDefs bits = new BitfieldsDefs();
		MachineDescription.load(INI, groups, bits, Logger.NULL);

		MemoryCellGroup cpu = groups.findByName("CPU");
		assertNotNull(cpu);
		assertEquals("CPU Register", cpu.getGroupInfo());

		MemoryCell psw = cpu.findByAddress(0177776);
		assertNotNull(psw);
		assertEquals("PSW", psw.getName());
		assertEquals("Processor Status Word", psw.getInfo());

		//-- R0..R5, SP and PC are the console's register addresses at 0177700..0177707.
		assertEquals("R0", cpu.findByAddress(0177700).getName());
		assertEquals("SP", cpu.findByAddress(0177706).getName());
		assertEquals("stack pointer = R6", cpu.findByAddress(0177706).getInfo());

		//-- An empty info field is legal: "CPU Error=177766;;Bits.CPU.Error".
		assertEquals("", cpu.findByAddress(0177766).getInfo());
	}

	@Test
	void bitfieldDefinitionsAreLinkedToTheirRegisters() {
		MemoryCellGroups groups = new MemoryCellGroups();
		BitfieldsDefs bits = new BitfieldsDefs();
		MachineDescription.load(INI, groups, bits, Logger.NULL);

		BitfieldsDef psw = bits.findByAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776));
		assertNotNull(psw, "the PSW should have named bits");
		assertEquals("Bits.CPU.PSW", psw.getName());

		BitfieldDef prio = psw.findByName("Priority");
		assertNotNull(prio);
		assertEquals(7, prio.bitHi());
		assertEquals(5, prio.bitLo());
		assertEquals(7, prio.get(0340));

		BitfieldDef mode = psw.findByName("Current Mode");
		assertEquals(15, mode.bitHi());
		assertEquals(14, mode.bitLo());
	}

	/**
	 * The DZ11 defines the read and write meanings of one address in a single section, so
	 * overlapping bit definitions are normal rather than a mistake.
	 */
	@Test
	void aRegisterThatMeansTwoThingsHasOverlappingBitfields() {
		MemoryCellGroups groups = new MemoryCellGroups();
		BitfieldsDefs bits = new BitfieldsDefs();
		MachineDescription.load(INI, groups, bits, Logger.NULL);

		BitfieldsDef d = bits.findByName("Bits.M7819.RBUF_LPR");
		assertNotNull(d);
		List<BitfieldDef> atBit12 = d.fieldsAtBit(12);
		assertEquals(2, atBit12.size(), "bit 12 is RBUF.PAR ERR when read and LPR.RX ON when written");
	}

	/**
	 * The RX211's single data buffer register is declared six times under six names, because
	 * the controller reinterprets it at each stage of a transfer.
	 */
	@Test
	void aRegisterWithSixMeaningsBecomesSixCellsAtOneAddress() {
		MemoryCellGroups groups = new MemoryCellGroups();
		MachineDescription.load(INI, groups, new BitfieldsDefs(), Logger.NULL);

		MemoryCellGroup rx = groups.findByName("RX211");
		assertNotNull(rx);
		long same = rx.getCells().stream()
			.filter(c -> c.getAddr().val() == 0177172)
			.count();
		assertEquals(6, same);
		assertEquals(6, groups.cellsAt(Address.of(MemoryAddressType.PHYSICAL16, 0177172)).size());
	}

	/** An address range expands to one named cell per word. */
	@Test
	void anAddressRangeBecomesIndexedCells() {
		MemoryCellGroups groups = new MemoryCellGroups();
		MachineDescription.load(INI, groups, new BitfieldsDefs(), Logger.NULL);

		MemoryCellGroup mmu = groups.findByName("MMU44");
		assertNotNull(mmu);
		assertTrue(mmu.size() > 1);
		MemoryCell first = mmu.getCells().get(0);
		assertTrue(first.getName().endsWith("[0]") || !first.getName().contains("["),
			"a ranged register is indexed from zero, got " + first.getName());
	}

	/**
	 * Everything a description loads carries one usage tag, so selecting a different target
	 * machine can drop exactly those groups and no others.
	 */
	@Test
	void aReloadCanDropExactlyWhatTheDescriptionAdded() {
		MemoryCellGroups groups = new MemoryCellGroups();
		MemoryCellGroup mine = groups.addGroup(MemoryAddressType.PHYSICAL16, "Memory dump");
		mine.add(01000);

		MachineDescription.load(INI, groups, new BitfieldsDefs(), Logger.NULL);
		assertEquals(18, groups.size());

		groups.removeGroupsByUsageTag(MachineDescription.USAGE_TAG);
		assertEquals(1, groups.size());
		assertEquals("Memory dump", groups.getGroups().get(0).getGroupName());
	}

	/** Every group loads at 16 bits and moves together once the target machine is known. */
	@Test
	void theWholeDescriptionRebasesOntoATwentyTwoBitMachine() {
		MemoryCellGroups groups = new MemoryCellGroups();
		MachineDescription.load(INI, groups, new BitfieldsDefs(), Logger.NULL);

		groups.changeAddressWidth(MemoryAddressType.PHYSICAL22);

		MemoryCell psw = groups.findByName("CPU").findByAddress(017777776L);
		assertNotNull(psw, "the PSW should now be at its 22-bit address");
		assertEquals("PSW", psw.getName());
		for(MemoryCellGroup g : groups.getGroups()) {
			assertEquals(MemoryAddressType.PHYSICAL22, g.getType(), g.getGroupName());
		}
	}
}
