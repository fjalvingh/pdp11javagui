package to.etc.pdp11.core.mem;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.common.util.Scheduler;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.console.Console;

import java.nio.file.Path;
import to.etc.pdp11.core.console.ConsoleConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operations on shared memory as a whole, against a simulated machine.
 */
class SharedMemoryOperationsTest {
	private final MemoryCellGroups m_groups = new MemoryCellGroups();

	private final ConnectionManager m_manager = new ConnectionManager(m_groups, Logger.NULL, new Scheduler.Manual(),
		Path.of(System.getProperty("java.io.tmpdir")));

	private <T> T onConsole(ConsoleConnection.ConsoleTask<T> job) throws Exception {
		return m_manager.getConnection().call(job);
	}

	private Console console() {
		return m_manager.getConsole();
	}

	private int machineWord(long addr) throws Exception {
		return onConsole(() -> console().examine(Address.of(console().physicalAddressType(), addr))).word();
	}

	private static Address p22(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	@Test
	void depositChangedWritesEveryPendingWordWhoeverMadeIt() throws Exception {
		m_manager.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			MemoryCellGroup loader = m_groups.addGroup(MemoryAddressType.PHYSICAL22, "Loader");
			loader.add(p22(01000)).setEditValue(CellValue.of(012701));
			loader.add(p22(01002)).setEditValue(CellValue.of(0200));
			MemoryCellGroup memory = m_groups.addGroup(MemoryAddressType.PHYSICAL22, "Memory");
			memory.add(p22(02000)).setEditValue(CellValue.of(0777));
			int groupsBefore = m_groups.size();

			SharedMemoryOperations.DepositResult r = onConsole(
				() -> SharedMemoryOperations.depositPending(m_groups, console(), ProgressMonitor.NULL));

			assertEquals(3, r.deposited());
			assertEquals(0, r.unreachable());
			assertEquals(0, m_groups.getSharedMemory().getPendingCount());
			assertEquals(012701, machineWord(01000));
			assertEquals(0777, machineWord(02000));
			assertEquals(groupsBefore, m_groups.size(), "the group it worked through is given back");
		} finally {
			m_manager.close();
		}
	}

	/** A 22-bit address does not exist on a 16-bit bus; it stays pending, and is counted. */
	@Test
	void aWordTheBusCannotReachStaysPending() throws Exception {
		m_manager.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_16));
		try {
			MemoryCellGroup g = m_groups.addGroup(MemoryAddressType.PHYSICAL22, "Loader");
			g.add(p22(01000)).setEditValue(CellValue.of(1));
			MemoryCell high = g.add(p22(0200000));
			high.setEditValue(CellValue.of(2));

			SharedMemoryOperations.DepositResult r = onConsole(
				() -> SharedMemoryOperations.depositPending(m_groups, console(), ProgressMonitor.NULL));

			assertEquals(1, r.deposited());
			assertEquals(1, r.unreachable());
			assertTrue(high.isEdited());
		} finally {
			m_manager.close();
		}
	}

	@Test
	void rereadShownReadsEachWordOnceAndOnlyWhatWindowsHold() throws Exception {
		m_manager.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			onConsole(() -> {
				console().deposit(p22(01000), 0111);
				console().deposit(p22(01002), 0222);
				return null;
			});
			//-- Two windows over overlapping ranges, nothing read yet.
			MemoryCellGroup a = m_groups.addGroup(MemoryAddressType.PHYSICAL22, "A");
			a.add(01000, 2);
			MemoryCellGroup b = m_groups.addGroup(MemoryAddressType.VIRTUAL, "B");
			b.add(Address.of(MemoryAddressType.VIRTUAL, 01002));
			//-- A device register is not memory and is not this button's to read.
			m_groups.addGroup(MemoryAddressType.PHYSICAL22, "CPU").add(p22(017777776));

			int read = onConsole(() -> SharedMemoryOperations.rereadShown(m_groups, console(), ProgressMonitor.NULL));

			assertEquals(2, read, "1000 and 1002, once each");
			assertEquals(0111, a.cell(0).getPdpValue().word());
			assertEquals(0222, b.cell(0).getPdpValue().word());
		} finally {
			m_manager.close();
		}
	}

	/**
	 * A program turns relocation on behind the application's back. Until the MMU is read again,
	 * virtual 1000 is still taken to be physical 1000; after, it is wherever page 0 now points.
	 */
	@Test
	void checkMmuMovesAVirtualViewToWhereTheMmuNowPointsIt() throws Exception {
		m_manager.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
		try {
			MemoryCellGroup disassembly = m_groups.addGroup(MemoryAddressType.VIRTUAL, "Disassembly");
			MemoryCell v1000 = disassembly.add(Address.of(MemoryAddressType.VIRTUAL, 01000));
			assertEquals(p22(01000), v1000.getPhysical());
			MemoryCell typed = m_groups.addGroup(MemoryAddressType.PHYSICAL22, "Memory").add(p22(01000));
			typed.setEditValue(CellValue.of(0123));

			onConsole(() -> {
				console().deposit(p22(017772340), 02000);         // KISAR0: page 0 at 200000
				console().deposit(p22(017772300), 077406);        // KISDR0: full length, read/write
				console().deposit(p22(017777572), 1);             // MMR0: relocation on
				return null;
			});
			SharedMemoryOperations.MmuCheck check = onConsole(
				() -> SharedMemoryOperations.checkMmu(m_groups, console(), ProgressMonitor.NULL));

			assertTrue(check.registersChanged() >= 3, "MMR0, KISAR0 and KISDR0 at least: " + check.registersChanged());
			assertEquals(1, check.moved().size());
			assertEquals(p22(0201000), v1000.getPhysical());
			assertFalse(v1000.isEdited(), "the edit was at physical 1000, and stays there");
			assertTrue(typed.isEdited());
		} finally {
			m_manager.close();
		}
	}
}
