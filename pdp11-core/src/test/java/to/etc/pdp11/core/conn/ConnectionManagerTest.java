package to.etc.pdp11.core.conn;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Scheduler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection layer: a profile in, a live console out.
 *
 * <p>Every protocol is exercised against its simulated machine, which is the point of
 * {@link TransportKind#SIMULATED} existing - the whole application can be driven with no
 * hardware, no SimH and no serial port, and so can this.</p>
 */
class ConnectionManagerTest {
	private ConnectionManager manager() {
		return new ConnectionManager(new MemoryCellGroups(), Logger.NULL, new Scheduler.Manual(),
			Path.of(System.getProperty("java.io.tmpdir")));
	}

	// ---------------------------------------------------------------------------------------
	// The profile model
	// ---------------------------------------------------------------------------------------

	@Test
	void theTwoAxesAreIndependentAndTheImpossiblePairsAreNamed() {
		//-- The whole reason for decomposing the Pascal's 24 flat entries: protocol and transport
		//-- vary independently. Only two combinations are actually impossible, and both are
		//-- impossible for a reason worth stating rather than for a reason worth enumerating.
		assertTrue(new ConnectionProfile("x", ConsoleProtocol.ODT_18,
			TransportConfig.telnet("localhost", 2323)).isValid());
		assertTrue(new ConnectionProfile("x", ConsoleProtocol.ODT_18,
			TransportConfig.serial("/dev/ttyUSB0", 9600, null)).isValid());
		assertTrue(new ConnectionProfile("x", ConsoleProtocol.SIMH,
			TransportConfig.telnet("localhost", 4000)).isValid(), "somebody else's SimH over telnet");

		assertNotNull(new ConnectionProfile("x", ConsoleProtocol.SIMH,
			TransportConfig.serial("/dev/ttyUSB0", 9600, null)).validate(),
			"SimH is a program here, not something at the end of a wire");
		assertNotNull(new ConnectionProfile("x", ConsoleProtocol.PDP1144,
			TransportConfig.simhProcess(null, null)).validate(),
			"a real machine cannot be launched as a process");
	}

	@Test
	void aTransportSaysWhyItCannotBeOpened() {
		assertNotNull(TransportConfig.telnet("", 23).validate());
		assertNotNull(TransportConfig.telnet("host", 0).validate());
		assertNotNull(TransportConfig.serial("", 9600, null).validate());
		assertNotNull(TransportConfig.serial("/dev/x", 0, null).validate());
		//-- These two need nothing at all, which is why they are the ones a new installation
		//-- can offer without asking anything first.
		assertNull(TransportConfig.simulated().validate());
		assertNull(TransportConfig.simhProcess(null, null).validate());
	}

	@Test
	void aProfileDescribesItselfForTheStatusBar() {
		assertEquals("SimH over simulated machine",
			ConnectionProfile.simulated(ConsoleProtocol.SIMH).describe());
		assertEquals("PDP-11 ODT, 18 bit (11/23) over /dev/ttyUSB0 @ 9600 baud N8_1",
			new ConnectionProfile("x", ConsoleProtocol.ODT_18,
				TransportConfig.serial("/dev/ttyUSB0", 9600, to.etc.pdp11.core.io.SerialTransport.SerialFormat.N8_1))
				.describe());
	}

	// ---------------------------------------------------------------------------------------
	// Connecting
	// ---------------------------------------------------------------------------------------

	@Test
	void everyProtocolConnectsToItsOwnSimulatedMachineAndAnswers() throws Exception {
		for(ConsoleProtocol protocol : ConsoleProtocol.values()) {
			try(ConnectionManager m = manager()) {
				m.connect(ConnectionProfile.simulated(protocol));
				assertTrue(m.isConnected(), protocol + " should be connected");
				assertNotNull(m.getConsole(), protocol.toString());
				assertEquals(protocol.getAddressType(), m.getConsole().physicalAddressType(), protocol.toString());

				//-- And it really is a machine: deposit a word and read it back through the
				//-- whole stack - profile, transport, connection, console protocol.
				Address a = Address.of(protocol.getAddressType(), 01000);
				m.getConnection().run(() -> m.getConsole().deposit(a, 0123456));
				CellValue v = m.getConnection().call(() -> m.getConsole().examine(a));
				assertEquals(0123456, v.word(), protocol + " should read back what it wrote");
			}
		}
	}

	@Test
	void everyConsoleSaysHowItsOutputShouldBeDisplayed() throws Exception {
		//-- The consoles genuinely disagree about line endings, which is why the terminal has to
		//-- ask rather than assume. ODT means its LF; the 11/44 means a lone CR.
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_18));
			assertFalse(m.getConsole().terminalProfile().crIsNewline());
			assertTrue(m.getConsole().terminalProfile().lfIsNewline());
		}
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
			assertTrue(m.getConsole().terminalProfile().crIsNewline());
			assertFalse(m.getConsole().terminalProfile().lfIsNewline());
		}
	}

	@Test
	void connectingAgainReplacesWhatWasThere() throws Exception {
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_22));
			Object first = m.getConnection();
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
			assertTrue(m.isConnected());
			assertFalse(first == m.getConnection(), "the old connection should have been replaced");
			assertEquals(ConsoleProtocol.PDP1144, m.getProfile().protocol());
		}
	}

	@Test
	void theStateIsReportedAsItChanges() throws Exception {
		try(ConnectionManager m = manager()) {
			List<ConnectionManager.State> seen = new ArrayList<>();
			m.addListener((mgr, state) -> seen.add(state));
			assertEquals(ConnectionManager.State.DISCONNECTED, m.getState());
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			m.disconnect();
			assertEquals(List.of(ConnectionManager.State.CONNECTING, ConnectionManager.State.CONNECTED,
				ConnectionManager.State.DISCONNECTED), seen);
		}
	}

	@Test
	void aProfileThatCannotWorkIsRefusedBeforeAnythingIsOpened() throws Exception {
		try(ConnectionManager m = manager()) {
			ConsoleException x = assertThrows(ConsoleException.class,
				() -> m.connect(new ConnectionProfile("bad", ConsoleProtocol.SIMH,
					TransportConfig.serial("/dev/ttyUSB0", 9600, null))));
			assertTrue(x.getMessage().contains("serial"), x.getMessage());
			assertEquals(ConnectionManager.State.DISCONNECTED, m.getState(),
				"a refusal is not a failed connection - nothing was attempted");
		}
	}

	@Test
	void aTransportThatWillNotOpenLeavesTheManagerFailedAndNotHalfConnected() throws Exception {
		try(ConnectionManager m = manager()) {
			//-- Port 1 on localhost: nothing listens there, and connecting is refused at once.
			assertThrows(ConsoleException.class,
				() -> m.connect(new ConnectionProfile("nope", ConsoleProtocol.SIMH,
					TransportConfig.telnet("localhost", 1))));
			assertEquals(ConnectionManager.State.FAILED, m.getState());
			assertNull(m.getConsole());
			assertNull(m.getConnection());
			//-- And it can still be used afterwards.
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			assertTrue(m.isConnected());
		}
	}

	@Test
	void closingIsIdempotentAndWorksOnAManagerThatNeverConnected() {
		ConnectionManager m = manager();
		m.close();
		m.close();
		assertEquals(ConnectionManager.State.DISCONNECTED, m.getState());
	}
}
