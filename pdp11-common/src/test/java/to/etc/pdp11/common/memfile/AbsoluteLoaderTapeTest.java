package to.etc.pdp11.common.memfile;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbsoluteLoaderTapeTest {
	static void block(ByteArrayOutputStream out, int address, byte[] data) {
		int count = data.length + 6;
		int sum = 1 + (count & 0xff) + (count >> 8) + (address & 0xff) + (address >> 8);
		out.writeBytes(new byte[]{1, 0, (byte) count, (byte) (count >> 8), (byte) address, (byte) (address >> 8)});
		for(byte b : data)
			sum += b & 0xff;
		out.writeBytes(data);
		out.write(-sum & 0xff);
	}

	@Test
	void theLoadStopsAtTheTransferBlockAndTheSlackAfterItIsNotRead() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 01000, new byte[]{1, 2, 3, 4});
		block(out, 01, new byte[0]);
		//-- Rubbish from the rest of the disk block, starting with what looks like a header: the
		//-- loader never reads it, and a reader that did would fail its checksum.
		out.writeBytes(new byte[]{1, 0, 10, 0, 0, 2, 9, 9, 9, 9, 9, 9, 9});
		AbsoluteLoaderTape t = AbsoluteLoaderTape.parse(out.toByteArray(), "x.bin");
		assertEquals(01, t.getTransferAddress());
		assertEquals(4, t.loadedBytes());
		assertFalse(t.isLoaded(01000 + 4));
		assertEquals(List.of(), t.getWarnings());
	}

	@Test
	void anOddTransferAddressMeansStartAt200() throws IOException {
		assertEquals(0200, tapeWithTransfer(01).startAddress());
		assertEquals(0200, tapeWithTransfer(0).startAddress(), "location 0 is the trap catcher");
		assertEquals(01000, tapeWithTransfer(01000).startAddress());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 01000, new byte[2]);
		AbsoluteLoaderTape none = AbsoluteLoaderTape.parse(out.toByteArray(), "x");
		assertNull(none.getTransferAddress());
		assertEquals(0200, none.startAddress());
	}

	private static AbsoluteLoaderTape tapeWithTransfer(int transfer) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 01000, new byte[2]);
		block(out, transfer, new byte[0]);
		return AbsoluteLoaderTape.parse(out.toByteArray(), "x");
	}

	@Test
	void wordsSayWhichAreOnlyHalfLoaded() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 01001, new byte[]{5, 6, 7});
		block(out, 01, new byte[0]);
		List<AbsoluteLoaderTape.Word> w = AbsoluteLoaderTape.parse(out.toByteArray(), "x").words();
		assertEquals(List.of(new AbsoluteLoaderTape.Word(01000, 0x0500, false), new AbsoluteLoaderTape.Word(01002, 0x0706, true)), w);
	}

	@Test
	void aBadChecksumIsRefused() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 01000, new byte[]{1, 2});
		byte[] b = out.toByteArray();
		b[6] ^= 1;
		IOException x = assertThrows(IOException.class, () -> AbsoluteLoaderTape.parse(b, "bad.bin"));
		assertTrue(x.getMessage().contains("Checksum error in bad.bin"), x.getMessage());
	}

	@Test
	void theRangeIsTheLowestAndHighestByte() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 0200, new byte[2]);
		block(out, 027000, new byte[3]);
		AbsoluteLoaderTape t = AbsoluteLoaderTape.parse(out.toByteArray(), "x");
		assertEquals(0200, t.lowestAddress());
		assertEquals(027002, t.highestAddress());
	}
}
