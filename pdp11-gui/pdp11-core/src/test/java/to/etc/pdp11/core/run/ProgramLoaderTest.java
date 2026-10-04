package to.etc.pdp11.core.run;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.memfile.AbsoluteLoaderTape;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.OperationCancelledException;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.common.util.Scheduler;
import to.etc.pdp11.core.console.AbstractConsole;
import to.etc.pdp11.core.console.ConsoleConnection;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.ConsoleRunMode;
import to.etc.pdp11.core.console.OdtConsole;
import to.etc.pdp11.core.console.OdtDialect;
import to.etc.pdp11.core.console.SimhConsole;
import to.etc.pdp11.core.fake.FakePdp11;
import to.etc.pdp11.core.fake.FakePdp11Odt;
import to.etc.pdp11.core.fake.FakeSimh;
import to.etc.pdp11.core.io.FakeTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A program deposited through a real console into a simulated machine, and started - over ODT,
 * which deposits a word at a time, and over SimH.
 */
class ProgramLoaderTest {
	private static final MemoryAddressType MAT = MemoryAddressType.PHYSICAL22;

	/** A console on a fake, connected and initialised. */
	private static final class Rig implements AutoCloseable {
		final Scheduler.Manual clock = new Scheduler.Manual();

		final FakePdp11 fake;

		final MemoryCellGroups groups = new MemoryCellGroups();

		final ConsoleConnection connection;

		final AbstractConsole console;

		Rig(boolean simh) throws ConsoleException {
			if(simh) {
				FakeSimh f = new FakeSimh(clock, new Random(42));
				fake = f;
				f.powerOn();
				console = new SimhConsole(groups, Logger.NULL, new Scheduler.Manual());
			} else {
				FakePdp11Odt f = new FakePdp11Odt(MAT, clock, new Random(7), FakePdp11Odt.OdtDialect.DEC);
				fake = f;
				f.powerOn();
				console = new OdtConsole(groups, MAT, OdtDialect.DEC, Logger.NULL);
			}
			connection = new ConsoleConnection(new FakeTransport(fake), Logger.NULL);
			connection.attach(console);
			connection.run(() -> console.init(connection));
			//-- The fake's power-on halt report is not any test's stop; see ConsoleRigs.
			connection.run(() -> {
			});
		}

		int mem(int address) {
			return fake.getMem(Address.of(MAT, address));
		}

		void setMem(int address, int value) {
			fake.setMem(Address.of(MAT, address), value);
		}

		@Override
		public void close() {
			connection.close();
		}
	}

	/** An absolute loader image of the blocks given, ending with a transfer to {@code transfer}. */
	private static AbsoluteLoaderTape tape(int transfer, Object... addressAndBytes) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for(int i = 0; i < addressAndBytes.length; i += 2)
			block(out, (Integer) addressAndBytes[i], (byte[]) addressAndBytes[i + 1]);
		block(out, transfer, new byte[0]);
		return AbsoluteLoaderTape.parse(out.toByteArray(), "test.bin");
	}

	private static void block(ByteArrayOutputStream out, int address, byte[] data) {
		int count = data.length + 6;
		int sum = 1 + (count & 0xff) + (count >> 8) + (address & 0xff) + (address >> 8);
		out.writeBytes(new byte[]{1, 0, (byte) count, (byte) (count >> 8), (byte) address, (byte) (address >> 8)});
		for(byte b : data)
			sum += b & 0xff;
		out.writeBytes(data);
		out.write(-sum & 0xff);
	}

	private static byte[] words(int... w) {
		byte[] b = new byte[w.length * 2];
		for(int i = 0; i < w.length; i++) {
			b[2 * i] = (byte) w[i];
			b[2 * i + 1] = (byte) (w[i] >> 8);
		}
		return b;
	}

	private void depositsEveryWord(boolean simh) throws Exception {
		try(Rig rig = new Rig(simh)) {
			AbsoluteLoaderTape t = tape(01, 0200, words(012706, 01000, 0137, 01000), 01000, words(0, 0777));
			ProgramLoader.Loaded loaded = rig.connection.call(() -> ProgramLoader.load(rig.console, rig.groups, t, null, ProgressMonitor.NULL));
			assertEquals(6, loaded.words());
			assertEquals(012706, rig.mem(0200));
			assertEquals(0137, rig.mem(0204));
			assertEquals(0777, rig.mem(01002));
			assertEquals(null, rig.groups.findByName("Program load"), "the temporary group is gone again");
		}
	}

	@Test
	void everyWordIsDepositedOverOdt() throws Exception {
		depositsEveryWord(false);
	}

	@Test
	void everyWordIsDepositedOverSimh() throws Exception {
		depositsEveryWord(true);
	}

	@Test
	void aHalfWordKeepsTheMachinesOtherByte() throws Exception {
		try(Rig rig = new Rig(false)) {
			rig.setMem(02000, 0125252);
			rig.setMem(02004, 0125252);
			//-- Three bytes from 2000: the word at 2000 whole, the low byte of 2002. And one byte at
			//-- the odd address 2005: the high byte of 2004.
			AbsoluteLoaderTape t = tape(01, 02000, new byte[]{1, 2, 3}, 02005, new byte[]{7});
			rig.setMem(02002, 0177400);
			ProgramLoader.Loaded loaded = rig.connection.call(() -> ProgramLoader.load(rig.console, rig.groups, t, null, ProgressMonitor.NULL));
			assertEquals(2, loaded.examined());
			assertEquals(0x0201, rig.mem(02000));
			assertEquals(0177400 | 3, rig.mem(02002), "high byte kept");
			assertEquals(0x0700 | (0125252 & 0xFF), rig.mem(02004), "low byte kept");
		}
	}

	@Test
	void theSoftwareSwitchRegisterIsSetAfterTheImage() throws Exception {
		try(Rig rig = new Rig(true)) {
			//-- The image itself clears 176, as diagnostics do; what was asked for wins.
			AbsoluteLoaderTape t = tape(01, 0174, words(0, 0));
			rig.connection.call(() -> ProgramLoader.load(rig.console, rig.groups, t, 0100000, ProgressMonitor.NULL));
			assertEquals(0100000, rig.mem(0176));
		}
	}

	@Test
	void aCancelledLoadSaysSoRatherThanLookingFinished() throws Exception {
		try(Rig rig = new Rig(false)) {
			AbsoluteLoaderTape t = tape(01, 01000, new byte[200]);
			AtomicInteger steps = new AtomicInteger();
			ProgressMonitor cancelAfterTen = new ProgressMonitor() {
				@Override
				public void begin(String task, int total) {
				}

				@Override
				public void step(int amount, String note) {
					steps.addAndGet(amount);
				}

				@Override
				public boolean isCancelled() {
					return steps.get() > 10;
				}

				@Override
				public void done() {
				}
			};
			assertThrows(OperationCancelledException.class,
				() -> rig.connection.call(() -> ProgramLoader.load(rig.console, rig.groups, t, null, cancelAfterTen)));
			assertEquals(null, rig.groups.findByName("Program load"));
		}
	}

	@Test
	void startingResetsAndRunsAtTheStartAddress() throws Exception {
		try(Rig rig = new Rig(false)) {
			((OdtConsole) rig.console).setRunMode(ConsoleRunMode.RUN);
			((FakePdp11Odt) rig.fake).setRunMode(true);
			assertFalse(rig.fake.isRunning());
			rig.connection.run(() -> ProgramLoader.start(rig.console, 0200));
			assertTrue(rig.fake.isRunning());
			assertEquals(0200, rig.fake.getMem(rig.fake.getProgramCounterAddr()));
		}
	}
}
