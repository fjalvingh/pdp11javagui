package to.etc.pdp11.common.diag;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramCheckTest {
	private static final DiagnosticCatalog CATALOG = DiagnosticCatalog.builtIn();

	private static void block(ByteArrayOutputStream out, int address, byte[] data) {
		int count = data.length + 6;
		int sum = 1 + (count & 0xff) + (count >> 8) + (address & 0xff) + (address >> 8);
		out.writeBytes(new byte[]{1, 0, (byte) count, (byte) (count >> 8), (byte) address, (byte) (address >> 8)});
		for(byte b : data)
			sum += b & 0xff;
		out.writeBytes(data);
		out.write(-sum & 0xff);
	}

	/** A classic MAINDEC program: vectors from 0, code from 200, halt after loading. */
	private static byte[] standalone(int transfer) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 0, new byte[]{(byte) 0302, 0, 0, 0});
		block(out, 0200, new byte[]{(byte) 0306, 0x15, 0, 2});
		block(out, transfer, new byte[0]);
		return out.toByteArray();
	}

	/** A DRS program: the hooks word at 52, then the header at 2000 and code after it. */
	private static byte[] drs(String name, String revision) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 052, new byte[]{0, 0x10});
		byte[] header = new byte[16];
		byte[] n = name.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(n, 0, header, 0, n.length);
		header[8] = (byte) revision.charAt(0);
		header[9] = (byte) revision.charAt(1);
		block(out, 02000, header);
		block(out, 02020, new byte[]{1, 2, 3, 4});
		block(out, 01, new byte[0]);
		return out.toByteArray();
	}

	private static ProgramCheck.Verdict check(String name, byte[] data) {
		return ProgramCheck.check(name, data, false, CATALOG.identify(name));
	}

	@Test
	void aMaindecProgramIsStandaloneAndStartsAt200() {
		ProgramCheck.Verdict v = check("FKAAC0.BIC", standalone(01));
		assertEquals(ProgramCheck.Kind.STANDALONE, v.kind());
		assertTrue(v.isRunnable());
		assertEquals(0200, v.tape().startAddress());
		assertTrue(v.reason().contains("000200"), v.reason());
	}

	@Test
	void aProgramWithARealTransferAddressStartsThere() {
		assertEquals(01000, check("ZADAA0.BIN", standalone(01000)).tape().startAddress());
	}

	@Test
	void aProgramWithADrsHeaderNeedsTheSupervisor() {
		assertEquals(ProgramCheck.Kind.NEEDS_SUPERVISOR, check("ZRQBC1.BIN", drs("ZRQB", "C1")).kind());
		assertEquals(ProgramCheck.Kind.NEEDS_SUPERVISOR, check("ZRLGE0.BIC", drs("CZRLG", "E0")).kind());
		assertEquals(ProgramCheck.Kind.NEEDS_SUPERVISOR, check("ZRCDB0.BIN", drs(" CZRCD", "B0")).kind(), "a blank in front, as CZRCD has");
		assertFalse(check("ZRQBC1.BIN", drs("ZRQB", "C1")).isRunnable());
	}

	@Test
	void codeAt2000IsNotAHeader() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 02000, new byte[]{(byte) 0306, 0x15, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0});
		block(out, 01, new byte[0]);
		assertEquals(ProgramCheck.Kind.STANDALONE, check("ZXYZA0.BIN", out.toByteArray()).kind());
	}

	@Test
	void aHeaderWithVectorsBelowItIsAStandaloneProgram() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		block(out, 0, new byte[]{(byte) 0302, 0, 0, 0});
		byte[] h = "CZABC\0\0\0A0".getBytes(StandardCharsets.US_ASCII);
		block(out, 02000, h);
		block(out, 01, new byte[0]);
		assertEquals(ProgramCheck.Kind.STANDALONE, check("ZABCA0.BIN", out.toByteArray()).kind());
	}

	@Test
	void xxdpItselfNeedsTheMonitor() {
		assertEquals(ProgramCheck.Kind.NEEDS_MONITOR, check("XXDPSM.SYS", standalone(01)).kind());
		assertEquals(ProgramCheck.Kind.NEEDS_MONITOR, check("HUDIB0.SYS", standalone(01)).kind());
		assertEquals(ProgramCheck.Kind.NEEDS_MONITOR, check("UPDAT.BIC", standalone(01000)).kind());
		assertEquals(ProgramCheck.Kind.NEEDS_MONITOR, check("RKDP.BIN", standalone(01)).kind(), "a pre-V2 monitor");
		assertEquals(ProgramCheck.Kind.NEEDS_MONITOR, check("UPD2.BIN", standalone(01)).kind());
	}

	@Test
	void textAndBrokenFilesAreNotPrograms() {
		assertEquals(ProgramCheck.Kind.NOT_A_PROGRAM, check("HELP.TXT", "HELP".getBytes(StandardCharsets.US_ASCII)).kind());
		assertEquals(ProgramCheck.Kind.NOT_A_PROGRAM, check("TEST.CCC", new byte[10]).kind());
		assertEquals(ProgramCheck.Kind.NOT_A_PROGRAM, check("EMPTY.BIN", new byte[512]).kind());
		byte[] bad = standalone(01);
		bad[7] ^= 1;
		assertEquals(ProgramCheck.Kind.NOT_A_PROGRAM, check("BAD.BIN", bad).kind());
	}

	@Test
	void aDamagedCopyIsNeverRun() {
		ProgramCheck.Verdict v = ProgramCheck.check("FKAAC0.BIC", standalone(01), true, CATALOG.identify("FKAAC0.BIC"));
		assertEquals(ProgramCheck.Kind.DAMAGED, v.kind());
		assertFalse(v.isRunnable());
	}
}
