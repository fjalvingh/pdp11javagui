package to.etc.pdp11.core.conn;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.util.Logger;
import to.etc.pdp11.common.util.Scheduler;
import to.etc.pdp11.core.console.ConsoleRunMode;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static to.etc.pdp11.core.conn.Capability.CONTINUE;
import static to.etc.pdp11.core.conn.Capability.HALT;
import static to.etc.pdp11.core.conn.Capability.RESET;
import static to.etc.pdp11.core.conn.Capability.RUN_HALT_SWITCH;
import static to.etc.pdp11.core.conn.Capability.SET_PC;
import static to.etc.pdp11.core.conn.Capability.SINGLE_STEP;
import static to.etc.pdp11.core.conn.Capability.START;
import static to.etc.pdp11.core.conn.Capability.SURVIVES_BUS_TIMEOUT;

/**
 * What can be done over each console, as a table.
 *
 * <p>Asked of the real drivers, connected to their simulated machines, rather than of a copy of
 * their feature sets: what is checked is what a window will be told.</p>
 */
class CapabilitiesTest {
	private static ConnectionManager manager() {
		return new ConnectionManager(new MemoryCellGroups(), Logger.NULL, new Scheduler.Manual(),
			Path.of(System.getProperty("java.io.tmpdir")));
	}

	private static Set<Capability> of(ConsoleProtocol protocol, CpuModel cpu, ConsoleRunMode mode) throws Exception {
		try(ConnectionManager m = manager()) {
			m.connect(new ConnectionProfile("x", protocol, TransportConfig.simulated(), cpu));
			if(mode != null)
				m.getConsole().setRunMode(mode);
			return m.capabilities().asSet();
		}
	}

	@Test
	void simhAndThe1144CanDoEverythingButHaveNoSwitch() throws Exception {
		Set<Capability> all = EnumSet.of(START, RESET, SET_PC, CONTINUE, SINGLE_STEP, HALT, SURVIVES_BUS_TIMEOUT);
		assertEquals(all, of(ConsoleProtocol.SIMH, CpuModel.UNKNOWN, null));
		assertEquals(all, of(ConsoleProtocol.PDP1144, CpuModel.PDP11_44, null));
		assertEquals(all, of(ConsoleProtocol.PDP1144_V340C, CpuModel.UNKNOWN, null));
	}

	/** {@code nnnG} and {@code P} mean different things depending on the switch. */
	@Test
	void odtFollowsItsRunHaltSwitchAndCannotHaltFromHere() throws Exception {
		assertEquals(EnumSet.of(RESET, SINGLE_STEP, SET_PC, RUN_HALT_SWITCH, SURVIVES_BUS_TIMEOUT),
			of(ConsoleProtocol.ODT_18, CpuModel.PDP11_23, ConsoleRunMode.HALT));
		assertEquals(EnumSet.of(START, CONTINUE, SET_PC, RUN_HALT_SWITCH, SURVIVES_BUS_TIMEOUT),
			of(ConsoleProtocol.ODT_18, CpuModel.PDP11_23, ConsoleRunMode.RUN));
	}

	/**
	 * The console emulator is a program on the CPU: it can start one, and nothing else. It cannot
	 * set the PC, because the registers are its own, nor stop what it started.
	 */
	@Test
	void aBootRomConsoleCanOnlyStart() throws Exception {
		for(ConsoleProtocol p : new ConsoleProtocol[]{ConsoleProtocol.M9312, ConsoleProtocol.M9301}) {
			for(CpuModel cpu : CpuModel.forProtocol(p)) {
				assertEquals(EnumSet.of(START), of(p, cpu, null), p + " on " + cpu);
			}
		}
	}

	@Test
	void aBootRomConsoleCannotStopAProgramAndTheOthersCan() throws Exception {
		try(ConnectionManager m = manager()) {
			assertSame(Capabilities.NONE, m.capabilities(), "nothing connected");
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.M9312));
			assertFalse(m.capabilities().canStopProgram());
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_16));
			assertTrue(m.capabilities().canStopProgram(), "by the switch");
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			assertTrue(m.capabilities().canStopProgram(), "by command");
		}
	}

	/**
	 * A profile saved before there was a CPU, or naming one its console is never on, is
	 * {@link CpuModel#UNKNOWN} rather than a profile that will not load.
	 */
	@Test
	void aMissingOrImpossibleCpuIsUnknown() {
		assertEquals(CpuModel.UNKNOWN,
			new ConnectionProfile("x", ConsoleProtocol.M9312, TransportConfig.simulated(), null).cpu());
		assertEquals(CpuModel.UNKNOWN,
			new ConnectionProfile("x", ConsoleProtocol.M9312, TransportConfig.simulated(), CpuModel.PDP11_73).cpu());
		assertEquals(CpuModel.PDP11_05,
			new ConnectionProfile("x", ConsoleProtocol.M9312, TransportConfig.simulated(), CpuModel.PDP11_05).cpu());
	}

	@Test
	void everyProtocolOffersUnknownFirst() {
		for(ConsoleProtocol p : ConsoleProtocol.values()) {
			assertEquals(CpuModel.UNKNOWN, CpuModel.forProtocol(p).get(0), p.toString());
		}
	}
}
