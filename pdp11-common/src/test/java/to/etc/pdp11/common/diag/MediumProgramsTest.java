package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.diag.media.MediaFile;
import to.etc.pdp11.common.diag.media.TestMedia;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.memfile.MemoryFileLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taking a program off a medium for the Memory Loader: which files are offered, and that the one
 * chosen ends up in memory where its image says.
 */
class MediumProgramsTest {
	/** Four words at 001000, started at 001000. */
	private static final byte[] PROGRAM = TestMedia.absoluteLoader(01000,
		new byte[]{(byte) 0301, 012, 0, 0, (byte) 0377, 0, 0, 0}, 01000, TestMedia.pattern(40, 9));

	private static final List<MediaFile> FILES = List.of(
		new MediaFile("CKBAB0.BIC", null, PROGRAM),
		new MediaFile("README.TXT", null, "Not a program".getBytes(StandardCharsets.ISO_8859_1)));

	@Test
	void aGzippedDiskOffersItsProgramsAndLoadsTheChosenOne() throws Exception {
		byte[] rl02 = TestMedia.disk(TestMedia.Form.XXDP_PLUS, 20480, FILES);
		MediumPrograms.Contents c = MediumPrograms.read("xxdp25.rl02.gz", TestMedia.gzip(rl02));

		assertEquals(1, c.volumes());
		assertEquals(List.of("CKBAB0.BIC"), c.programs().stream().map(MediumPrograms.Program::name).toList(),
			"the text file is not offered");
		MediumPrograms.Program p = c.programs().get(0);
		assertTrue(p.verdict().isRunnable(), p.verdict().reason());
		assertFalse(c.isSeveralVolumes());

		MemoryCellGroup g = new MemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "loaded");
		Address like = Address.of(MemoryAddressType.PHYSICAL16, 0);
		MemoryFileLoader.Result r = MemoryFileLoader.loadTape(g, p.tape(), like,
			Address.of(MemoryAddressType.PHYSICAL16, p.tape().startAddress()), p.name());
		assertEquals(4, r.wordsLoaded());
		assertEquals(01000, r.entryAddress().val());
		assertEquals(CellValue.of(05301), g.findByAddress(Address.of(MemoryAddressType.PHYSICAL16, 01000)).getEditValue());
		assertEquals(CellValue.of(0377), g.findByAddress(Address.of(MemoryAddressType.PHYSICAL16, 01004)).getEditValue());
	}

	@Test
	void aLooseProgramFileIsItsOwnMedium() {
		MediumPrograms.Contents c = MediumPrograms.read("ckbab0.bic", PROGRAM);
		assertEquals(List.of("CKBAB0.BIC"), c.programs().stream().map(MediumPrograms.Program::name).toList());
	}

	@Test
	void somethingThatIsNotAMediumSaysSo() {
		MediumPrograms.Contents c = MediumPrograms.read("notes.txt", "hello".getBytes(StandardCharsets.ISO_8859_1));
		assertTrue(c.programs().isEmpty());
		assertFalse(c.problems().isEmpty(), "an empty list must come with a reason");
	}
}
