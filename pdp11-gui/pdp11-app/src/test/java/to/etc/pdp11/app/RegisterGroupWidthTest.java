package to.etc.pdp11.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.ProgressMonitor;
import to.etc.pdp11.ui.AppContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine descriptions declare every address as a <b>16-bit</b> I/O page address, "so the
 * same device definition can be used for 16, 18 and 22 bit machines" ({@code pdp11.ini:12-15}).
 * This is the test that says nothing has to be done about that.
 *
 * <p>The Pascal re-expresses <i>every group in the application</i> when a console is chosen -
 * {@code MemoryCellGroups.ChangeAdddressWidth(PDP11Console.getPhysicalMemoryAddressType)},
 * called from nine places in {@code FormMainU} - because its consoles send the address they are
 * given. Every console here normalises to its own width on the way out ({@code toPhysical} in
 * {@code SimhConsole}, {@code OdtConsole} and {@code Pdp1144Console}), and
 * {@code MemoryCellGroups} keys its propagation index on the 22-bit form, so a 16-bit register
 * group works against a 22-bit machine untouched.</p>
 *
 * <p>That is worth a test rather than a comment, because the mechanism it retires is one the
 * original could not do without.</p>
 */
class RegisterGroupWidthTest {
	@Test
	void aSixteenBitRegisterGroupReachesTheRightRegisterOnATwentyTwoBitMachine(@TempDir Path dir) throws Exception {
		Path machines = MachineDescriptionStore.install(dir, Logger.NULL);
		AppContext ctx = AppTestContext.create(dir).context();
		MachineDescriptionStore.load(ctx, machines.resolve(MachineDescriptionStore.DEFAULT_NAME));

		MemoryCellGroup cpu = ctx.getMemoryCellGroups().findByName("CPU");
		assertNotNull(cpu);
		assertEquals(MemoryAddressType.PHYSICAL16, cpu.getType(), "as the descriptions declare them");

		MemoryCell psw = cpu.findByAddress(0177776);
		assertNotNull(psw);

		ConnectionManager m = ctx.getConnectionManager();
		m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			assertEquals(MemoryAddressType.PHYSICAL22, m.getConsole().physicalAddressType());

			//-- Deposit through the 16-bit cell...
			psw.setEditValue(CellValue.of(0340));
			m.getConnection().run(() -> m.getConsole().deposit(cpu, true, ProgressMonitor.NULL));

			//-- ...and read the 22-bit address back directly. Same register, or the value would
			//-- have gone to 0177776 in low memory instead.
			CellValue v = m.getConnection().call(() -> m.getConsole()
				.examine(Address.of(MemoryAddressType.PHYSICAL22, 017777776)));
			assertEquals(0340, v.word(), "the 16-bit cell addressed the machine's real PSW");
		} finally {
			m.close();
		}
	}

	@Test
	void examiningTheWholeCpuGroupFillsItsCells(@TempDir Path dir) throws Exception {
		Path machines = MachineDescriptionStore.install(dir, Logger.NULL);
		AppContext ctx = AppTestContext.create(dir).context();
		MachineDescriptionStore.load(ctx, machines.resolve(MachineDescriptionStore.DEFAULT_NAME));
		MemoryCellGroup cpu = ctx.getMemoryCellGroups().findByName("CPU");

		ConnectionManager m = ctx.getConnectionManager();
		m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			m.getConnection().run(() -> m.getConsole().examine(cpu, false, ProgressMonitor.NULL));
			long known = cpu.getCells().stream().filter(c -> c.getPdpValue().isKnown()).count();
			assertTrue(known > 0, "the register window would be showing nothing at all otherwise");
		} finally {
			m.close();
		}
	}
}
