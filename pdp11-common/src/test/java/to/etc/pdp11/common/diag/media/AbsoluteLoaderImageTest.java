package to.etc.pdp11.common.diag.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbsoluteLoaderImageTest {
	@Test
	void theProgramEndsAfterTheTransferBlock() {
		byte[] program = TestMedia.absoluteLoader(01000, TestMedia.pattern(100, 1), 01000, new byte[]{9, 9, 9, 9});
		//-- 106 + 1 checksum for the data block, 6 + 1 for the transfer block.
		assertEquals(107 + 7, AbsoluteLoaderImage.significantLength(program));
	}

	@Test
	void leadingBlankTapeIsSkipped() {
		byte[] program = TestMedia.absoluteLoader(01000, TestMedia.pattern(10, 1), 01000, new byte[0]);
		byte[] leader = new byte[program.length + 20];
		System.arraycopy(program, 0, leader, 20, program.length);
		assertEquals(leader.length, AbsoluteLoaderImage.significantLength(leader));
	}

	@Test
	void anythingElseIsSignificantToTheEnd() {
		byte[] text = "HELP FILE\r\n".getBytes();
		assertEquals(text.length, AbsoluteLoaderImage.significantLength(text));
		byte[] cut = TestMedia.absoluteLoader(01000, TestMedia.pattern(100, 1), 01000, new byte[0]);
		assertEquals(50, AbsoluteLoaderImage.significantLength(java.util.Arrays.copyOf(cut, 50)), "no transfer block");
	}
}
