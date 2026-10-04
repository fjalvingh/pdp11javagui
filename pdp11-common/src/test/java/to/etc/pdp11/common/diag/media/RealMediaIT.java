package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The readers against real diagnostic media, which this repository may not contain.
 *
 * <p>Set {@code PDP11_DIAG_SAMPLES} (or the system property of the same name) to a directory of
 * downloaded media - plain or gzipped, under the names bitsavers and AK6DN publish them - and each
 * test whose medium is there runs; the rest skip. Everything asserted here was first seen by
 * reading the same media with AK6DN's {@code xxdpdir} or by booting them, and the numbers are the
 * ones the media's own directory listings give.</p>
 *
 * <p>These are what keep the test writer in {@link TestMedia} honest: it and the readers could
 * share a misunderstanding of the format, and a real disk cannot.</p>
 */
class RealMediaIT {
	private static final String VARIABLE = "PDP11_DIAG_SAMPLES";

	private static MediaVolume medium(String name) throws IOException {
		String dir = System.getProperty(VARIABLE, System.getenv(VARIABLE));
		assumeTrue(dir != null && !dir.isBlank(), VARIABLE + " is not set");
		for(String candidate : List.of(name, name + ".gz")) {
			Path p = Path.of(dir).resolve(candidate);
			if(Files.exists(p)) {
				List<MediaVolume> v = MediaReader.read(candidate, Files.readAllBytes(p));
				assertEquals(1, v.size());
				return v.get(0);
			}
		}
		assumeTrue(false, name + " is not in " + dir);
		return null;
	}

	private static Map<String, MediaFile> byName(MediaVolume v) {
		return v.files().stream().collect(Collectors.toMap(MediaFile::name, Function.identity(), (a, b) -> a));
	}

	@Test
	void xxdp25OnRl02() throws IOException {
		MediaVolume v = medium("xxdp25.rl02");
		assertEquals("RL02 disk, XXDP+ file system", v.description());
		assertEquals(727, v.files().size(), "the 727 files XXDP's own DIR lists");
		assertEquals(List.of(), v.problems());
		MediaFile first = v.files().get(0);
		assertEquals("XXDPXM.SYS", first.name());
		assertEquals(LocalDate.of(1989, 3, 1), first.date());
		assertEquals(39 * 510, first.data().length);
	}

	@Test
	void theSameSetOnTu58AndOnRx02IsTheSameFiles() throws IOException {
		MediaVolume tu58 = medium("1134_1.DSK");
		MediaVolume rx02 = medium("1134_1.RX2");
		assertEquals("TU58 tape, DOS-11 file system", tu58.description());
		assertEquals("RX02 floppy, DOS-11 file system", rx02.description());
		assertEquals(30, tu58.files().size());
		assertEquals(tu58.files().size(), rx02.files().size());
		for(int i = 0; i < tu58.files().size(); i++) {
			assertEquals(tu58.files().get(i).name(), rx02.files().get(i).name());
			assertArrayEquals(tu58.files().get(i).data(), rx02.files().get(i).data(), tu58.files().get(i).name());
		}
	}

	@Test
	void aTapeHoldsTheSameProgramsAsTheDisk() throws IOException {
		MediaVolume tape = medium("AP-T071S-MC_CZZZ4S0_MMDP_V2_1OF2_800_1986.tap");
		MediaVolume disk = medium("xxdp25.rl02");
		assertEquals(364, tape.files().size());
		Map<String, MediaFile> onDisk = byName(disk);
		int same = 0;
		for(MediaFile f : tape.files()) {
			MediaFile d = onDisk.get(f.name());
			if(d != null && Arrays.equals(f.data(), d.data()))
				same++;
		}
		assertTrue(same > 300, "a 1986 tape and a 1989 disk share most of their programs byte for byte; " + same + " did");
	}

	@Test
	void theXxdpDistributionOnAnRk05() throws IOException {
		MediaVolume v = medium("xxdp-rkdp.rk");
		assertEquals("RK05 disk, DOS-11 file system", v.description());
		assertEquals(190, v.files().size());
	}

	@Test
	void a1972DecTapeAndItsContiguousFiles() throws IOException {
		MediaVolume v = medium("MAINDEC-11-DZQDD-A-UB1_10-72.dta");
		assertEquals("DECtape, DOS-11 DECtape file system", v.description());
		assertEquals(52, v.files().size());
		assertEquals(List.of(), v.problems(), "the .SAC files are contiguous, not chains");
		assertEquals(13 * 512, byName(v).get("D0AA0.SAC").data().length);
	}

	@Test
	void rx01ImagesBothPhysicalAndLogical() throws IOException {
		MediaVolume logical = medium("rxdp_01.dsk");
		assertEquals(11, logical.files().size());
		MediaVolume physical = medium("maindec-11-dzzgc-c-yb.img");
		assertEquals("RX01 floppy, DOS-11 file system", physical.description());
		assertEquals(37, physical.files().size());
		assertEquals("CKBQB0.BIN", physical.files().get(0).name());
	}

	@Test
	void aPartialImageDiskFileGivesWhatItHasAndSaysTheRestIsDamaged() throws IOException {
		MediaVolume v = medium("as-9639e-mc_rxdp1.imd");
		assertEquals(17, v.files().size());
		assertTrue(v.problems().get(0).contains("Only 40"));
		assertTrue(v.files().stream().anyMatch(MediaFile::damaged));
		assertFalse(byName(v).get("UPD1.BIN").damaged());
	}
}
