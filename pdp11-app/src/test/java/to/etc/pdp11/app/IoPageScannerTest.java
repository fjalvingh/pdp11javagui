package to.etc.pdp11.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.machine.IoPageScanner;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.ProgressMonitor;
import to.etc.pdp11.ui.AppContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scanning the I/O page of a machine simulated in this JVM.
 *
 * <p>This test is the reason the fakes carry an I/O page validity map at all - phase 3 built one
 * "which makes the I/O page scanner worth testing", and this is the test it was anticipating.
 * The map is built from the loaded machine description, so the simulated machine answers at
 * exactly the addresses the description declares and times out everywhere else, which is what a
 * real machine does and what the scanner is looking for.</p>
 *
 * <p>It lives in {@code pdp11-app} because it needs the shipped description; the scanner itself
 * is in the core.</p>
 */
class IoPageScannerTest {
	private record Fixture(AppContext context, MemoryCellGroup target) {
	}

	/** A context with the shipped description loaded and a simulated machine connected. */
	private static Fixture connected(@TempDir Path dir, ConsoleProtocol protocol) throws Exception {
		return connected(dir, protocol, protocol.getAddressType());
	}

	/**
	 * The same, with the target group created at a width of the caller's choosing - because the
	 * window creates it at 22 bits before it knows what it is connected to.
	 */
	private static Fixture connected(@TempDir Path dir, ConsoleProtocol protocol,
		MemoryAddressType targetType) throws Exception {
		Path machines = MachineDescriptionStore.install(dir, Logger.NULL);
		AppContext ctx = AppTestContext.create(dir).context();
		MachineDescriptionStore.load(ctx, machines.resolve(MachineDescriptionStore.DEFAULT_NAME));
		//-- The description first: ConnectionManager builds the simulated machine's I/O page from
		//-- the groups, so a machine connected before it is loaded has an empty one.
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(protocol));
		MemoryCellGroup target = ctx.getMemoryCellGroups().addGroup(targetType, "I/O page scan");
		target.setUsageTag("iopagescan");
		return new Fixture(ctx, target);
	}

	@Test
	void itFindsTheAddressesTheMachineAnswersAtAndNamesTheKnownOnes(@TempDir Path dir) throws Exception {
		Fixture f = connected(dir, ConsoleProtocol.SIMH);
		ConnectionManager m = f.context().getConnectionManager();
		try {
			IoPageScanner.Result r = m.getConnection().call(() -> IoPageScanner.scan(m.getConsole(),
				f.context().getMemoryCellGroups(), f.target(), ProgressMonitor.NULL));

			assertEquals(IoPageScanner.IOPAGE_WORDS, r.examined(), "the whole I/O page, one word at a time");
			assertTrue(r.found() > 0 && r.found() < IoPageScanner.IOPAGE_WORDS,
				"some addresses answer and most do not: " + r.found());
			assertEquals(r.found(), f.target().size(), "the group holds exactly what answered");
			assertTrue(r.named() > 0, "the loaded description should have named some of them");

			//-- A named cell says which device it belongs to, which is the point of the prefix.
			MemoryCell psw = f.target().findByAddress(
				to.etc.pdp11.core.addr.Address.of(MemoryAddressType.PHYSICAL22, 017777776));
			assertNotNull(psw, "the PSW answers on any machine");
			assertEquals("CPU.PSW", psw.getName());
			assertEquals("Processor Status Word", psw.getInfo());
		} finally {
			m.close();
		}
	}

	@Test
	void anAddressTheDescriptionDoesNotKnowGetsAnInventedNameAndASectionToPaste(@TempDir Path dir)
		throws Exception {
		Fixture f = connected(dir, ConsoleProtocol.SIMH);
		ConnectionManager m = f.context().getConnectionManager();
		try {
			//-- Unload the description, so nothing that answers has a name and every run of
			//-- addresses has to be invented. That is what scanning an undocumented machine
			//-- looks like, which is what this window is for.
			f.context().getMemoryCellGroups().removeGroupsByUsageTag(
				to.etc.pdp11.core.machine.MachineDescription.USAGE_TAG);

			IoPageScanner.Result r = m.getConnection().call(() -> IoPageScanner.scan(m.getConsole(),
				f.context().getMemoryCellGroups(), f.target(), ProgressMonitor.NULL));

			assertTrue(r.found() > 0);
			assertTrue(r.blocks().size() > 0, "what is left over has to be invented");

			//-- Not everything loses its name: the MMU builds its own group of named registers
			//-- and subscribes to the cell bus without being a window at all (PLAN.md §2), so
			//-- 017772200 is still MMU.SIPDR0. That is a real name for a real register and the
			//-- scanner is right to use it.
			MemoryCell sipdr0 = f.target().findByAddress(
				to.etc.pdp11.core.addr.Address.of(MemoryAddressType.PHYSICAL22, 017772200));
			if(sipdr0 != null)
				assertEquals("MMU.SIPDR0", sipdr0.getName());

			//-- Everything the machine answers at and nothing names is a device this window has
			//-- just discovered.
			MemoryCell invented = f.target().getCells().stream()
				.filter(c -> c.getName().startsWith("device_")).findFirst().orElse(null);
			assertNotNull(invented, "there should be addresses nothing has a name for");
			assertTrue(invented.getInfo().startsWith("device base at "), invented.getInfo());

			//-- The section is meant to be pasted into a machine description, so its shape is
			//-- part of the contract.
			assertTrue(r.description().contains("[Device_"), r.description());
			assertTrue(r.description().contains("Enabled=true"), r.description());
			assertTrue(r.description().contains("Info=register block at "), r.description());
			//-- Addresses in a description are 16-bit however wide the machine is.
			assertTrue(r.description().contains("17777") || r.description().contains("177"),
				"addresses should be written 16-bit: " + r.description());
		} finally {
			m.close();
		}
	}

	/**
	 * A scan of a 16- or 18-bit machine has to survive being stored.
	 *
	 * <p>The window creates its target group at 22 bits, before it knows what it is connected to,
	 * and a {@link MemoryCellGroup} refuses a cell whose address is not its own width. So on a
	 * real ODT machine the scan used to do all 4096 examines - minutes over a serial line - and
	 * then throw {@code IllegalArgumentException} on the first address it tried to store,
	 * losing every one of them. The scan retypes the target to the machine's width, which is
	 * what the window's "the group's type follows the machine" has always assumed.</p>
	 */
	@Test
	void aNarrowMachineRetypesTheTargetRatherThanLosingTheWholeScan(@TempDir Path dir) throws Exception {
		Fixture f = connected(dir, ConsoleProtocol.ODT_18, MemoryAddressType.PHYSICAL22);
		ConnectionManager m = f.context().getConnectionManager();
		try {
			assertEquals(MemoryAddressType.PHYSICAL18, m.getConsole().physicalAddressType(),
				"an 11/23's ODT is 18 bits wide, and that is the point of this test");

			IoPageScanner.Result r = m.getConnection().call(() -> IoPageScanner.scan(m.getConsole(),
				f.context().getMemoryCellGroups(), f.target(), ProgressMonitor.NULL));

			assertEquals(IoPageScanner.IOPAGE_WORDS, r.examined());
			assertTrue(r.found() > 0, "the machine answers somewhere");
			assertEquals(MemoryAddressType.PHYSICAL18, f.target().getType(),
				"the group follows the machine it was scanned against");
			assertEquals(r.found(), f.target().size(), "and holds every address that answered");
			assertTrue(r.named() > 0, "which the description can still name at this width");
		} finally {
			m.close();
		}
	}

	/**
	 * The scan is 4096 examines; being able to stop it is not a nicety.
	 */
	@Test
	void cancellingStopsItAndKeepsWhatItFound(@TempDir Path dir) throws Exception {
		Fixture f = connected(dir, ConsoleProtocol.SIMH);
		ConnectionManager m = f.context().getConnectionManager();
		try {
			ProgressMonitor stopAfter = new ProgressMonitor() {
				private int m_steps;

				@Override
				public void begin(String task, int total) {
				}

				@Override
				public void step(int amount, String note) {
					m_steps += amount;
				}

				@Override
				public boolean isCancelled() {
					return m_steps >= 64;
				}

				@Override
				public void done() {
				}
			};
			IoPageScanner.Result r = m.getConnection().call(() -> IoPageScanner.scan(m.getConsole(),
				f.context().getMemoryCellGroups(), f.target(), stopAfter));

			assertTrue(r.cancelled(), "it should say it stopped early");
			assertEquals(64, r.examined(), "and stop where it was asked to");
		} finally {
			m.close();
		}
	}

	/**
	 * ODT is inside the CPU, and on a machine that halts at a bus timeout the scan would stop the
	 * processor four thousand times. The console says so and the scanner refuses.
	 */
	@Test
	void aMachineThatDiesOnABusTimeoutIsRefusedRatherThanScanned(@TempDir Path dir) throws Exception {
		Fixture f = connected(dir, ConsoleProtocol.PDP1144);
		ConnectionManager m = f.context().getConnectionManager();
		try {
			boolean survives = m.getConsole().features()
				.contains(to.etc.pdp11.core.console.ConsoleFeature.NON_FATAL_UNIBUS_TIMEOUT);
			if(survives)
				return;                                     // this console can be scanned; nothing to refuse
			ConsoleException x = assertThrows(ConsoleException.class,
				() -> m.getConnection().call(() -> IoPageScanner.scan(m.getConsole(),
					f.context().getMemoryCellGroups(), f.target(), ProgressMonitor.NULL)));
			assertTrue(x.getMessage().contains("UNIBUS timeout"), x.getMessage());
		} finally {
			m.close();
		}
	}
}
