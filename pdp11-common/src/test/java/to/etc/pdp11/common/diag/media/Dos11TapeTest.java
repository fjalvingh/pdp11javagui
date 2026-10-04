package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dos11TapeTest {
	private static final LocalDate JULY_1986 = LocalDate.of(1986, 7, 1);

	@Test
	void filesReadBackByteForByteAsOffADisk() throws Exception {
		List<MediaFile> files = List.of(
			new MediaFile("XXBOOT.MON", JULY_1986, TestMedia.pattern(16 * 510, 1)),
			new MediaFile("DATE.SYS", JULY_1986, TestMedia.pattern(767, 2)));
		List<String> problems = new ArrayList<>();
		List<MediaFile> read = Dos11Tape.read(TestMedia.tape(files), problems);
		assertEquals(List.of(), problems);
		assertEquals(2, read.size());
		assertEquals("XXBOOT.MON", read.get(0).name());
		assertEquals(JULY_1986, read.get(0).date());
		assertArrayEquals(files.get(0).data(), read.get(0).data(), "whole blocks: no slack at all");
		assertArrayEquals(files.get(1).data(), Arrays.copyOf(read.get(1).data(), 767));
		assertEquals(2 * 510, read.get(1).data().length, "the link word is not data, nor is the empty last record");

		//-- And the same file off a disk is the same bytes.
		byte[] disk = TestMedia.disk(TestMedia.Form.DOS11, 512, files);
		List<MediaFile> fromDisk = XxdpVolume.open(BlockImage.linear(disk)).readAll(new ArrayList<>());
		assertArrayEquals(fromDisk.get(1).data(), read.get(1).data());
	}

	@Test
	void anEraseGapIsSkipped() throws Exception {
		byte[] tape = TestMedia.tape(List.of(new MediaFile("A.BIN", null, TestMedia.pattern(10, 1))));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		TestMedia.u32(out, 0xFFFFFFFEL);
		out.writeBytes(tape);
		assertEquals("A.BIN", Dos11Tape.read(out.toByteArray(), new ArrayList<>()).get(0).name());
	}

	@Test
	void aRecordWhereAHeaderShouldBeIsSkippedToTheNextFile() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		TestMedia.record(out, new byte[80]);
		TestMedia.u32(out, 0);
		out.writeBytes(TestMedia.tape(List.of(new MediaFile("B.BIN", null, TestMedia.pattern(10, 1)))));
		List<String> problems = new ArrayList<>();
		List<MediaFile> read = Dos11Tape.read(out.toByteArray(), problems);
		assertEquals("B.BIN", read.get(0).name());
		assertTrue(problems.get(0).contains("80-byte record"), problems.toString());
	}

	@Test
	void aTruncatedImageKeepsWhatCameBefore() throws Exception {
		byte[] tape = TestMedia.tape(List.of(new MediaFile("A.BIN", null, TestMedia.pattern(10, 1)),
			new MediaFile("B.BIN", null, TestMedia.pattern(2000, 1))));
		List<String> problems = new ArrayList<>();
		List<MediaFile> read = Dos11Tape.read(Arrays.copyOf(tape, tape.length - 700), problems);
		assertEquals("A.BIN", read.get(0).name());
		assertTrue(problems.get(0).contains("ends inside a record"), problems.toString());
	}

	@Test
	void somethingElseIsNotATape() {
		assertThrows(MediaFormatException.class, () -> Dos11Tape.read(TestMedia.pattern(5000, 3), new ArrayList<>()));
		assertThrows(MediaFormatException.class, () -> Dos11Tape.read(new byte[0], new ArrayList<>()));
	}
}
