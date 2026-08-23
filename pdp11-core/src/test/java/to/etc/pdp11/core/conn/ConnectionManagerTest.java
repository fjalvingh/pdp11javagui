package to.etc.pdp11.core.conn;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.console.ConsoleConnection;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Scheduler;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

	// ---------------------------------------------------------------------------------------
	// Which channel is the machine's console
	// ---------------------------------------------------------------------------------------

	@Test
	void aRealConsoleIsTheMachineConsoleBecauseThereIsOneWire() throws Exception {
		//-- ODT answers on the machine's serial line and PDP11GUI drives that same line, so the
		//-- main window's terminal shows the console protocol's own commands too. That is not a
		//-- leak: it is what a scope on the wire would show, and it is how a flaky console gets
		//-- debugged.
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_18));
			assertTrue(m.hasMachineConsole());
			assertFalse(m.hasSeparateMachineConsole(), "one wire, not two");
			assertFalse(m.getMachineConsole().getText().isEmpty(), "the handshake is on it");
			assertEquals(m.getProtocolChannel().getText(), m.getMachineConsole().getText(),
				"the same bytes, because it is the same wire");
		}
	}

	@Test
	void simhWeDidNotLaunchHasNoMachineConsoleAtAll() throws Exception {
		//-- The simulated SimH, and equally a telnet connection to somebody else's: there is a
		//-- sim> channel and nothing behind it. The main terminal says so rather than quietly
		//-- showing sim> traffic, which is what made that window confusing to read.
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			assertFalse(m.hasMachineConsole());
			assertFalse(m.hasSeparateMachineConsole());
			assertFalse(m.getProtocolChannel().getText().isEmpty(), "the sim> handshake happened");
			assertEquals("", m.getMachineConsole().getText(), "and none of it is machine console output");
		}
	}

	@Test
	void connectingAgainStartsBothChannelsEmpty() throws Exception {
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_18));
			int first = m.getMachineConsole().length();
			assertTrue(first > 0);

			List<String> cleared = new ArrayList<>();
			m.getMachineConsole().subscribe(new TextChannel.Listener() {
				@Override
				public void onText(String text) {
				}

				@Override
				public void onCleared() {
					cleared.add("machine");
				}
			});
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_18));
			assertEquals(List.of("machine"), cleared, "a view of it should empty too");
			assertFalse(m.getMachineConsole().getText().isEmpty(), "and then fill with the new session");
		}
	}

	@Test
	void whatIsTypedAtTheTerminalReachesTheMachine() throws Exception {
		//-- With no console channel of its own this goes down the console protocol's wire, queued
		//-- on the command thread. ODT echoes what it is sent, so the echo is the proof.
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_18));
			int before = m.getMachineConsole().length();
			m.writeToMachineConsole("\r");
			long deadline = System.currentTimeMillis() + 5000;
			while(m.getMachineConsole().length() == before && System.currentTimeMillis() < deadline) {
				Thread.sleep(5);
			}
			assertTrue(m.getMachineConsole().length() > before, "ODT answered what was typed");
		}
	}

	@Test
	void typingIsDroppedWhenThereIsNoMachineConsoleToTypeAt() throws Exception {
		//-- Simulated SimH: the only wire is the sim> channel, and a keystroke on that lands in
		//-- the middle of whatever the console layer is saying.
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			int before = m.getProtocolChannel().length();
			m.writeToMachineConsole("\r");
			Thread.sleep(200);
			assertEquals(before, m.getProtocolChannel().length(), "nothing was sent anywhere");
			assertEquals("", m.getMachineConsole().getText());
		}
	}

	@Test
	void typingWithNothingConnectedIsDroppedRatherThanThrown() {
		//-- It is called from a key listener; there is nothing useful a terminal can do about it.
		ConnectionManager m = manager();
		m.writeToMachineConsole("hello");
		assertEquals("", m.getMachineConsole().getText());
	}

	@Test
	void theMmuRegisterGroupBelongsToTheConnectionAndGoesWithIt() {
		//-- The console builds an MMU, which builds a register group inside the application's
		//-- groups. Connecting five times used to leave five of them, all still listening on the
		//-- propagation index.
		MemoryCellGroups groups = new MemoryCellGroups();
		try(ConnectionManager m = new ConnectionManager(groups, Logger.NULL, new Scheduler.Manual(),
			Path.of(System.getProperty("java.io.tmpdir")))) {
			for(int i = 0; i < 3; i++) {
				assertDoesNotThrow(() -> m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144)));
				assertEquals(1, mmuGroups(groups), "one MMU, however often it reconnects");
			}
			m.disconnect();
			assertEquals(0, mmuGroups(groups), "and none when there is no machine");
		}
	}

	private static long mmuGroups(MemoryCellGroups groups) {
		return groups.getGroups().stream()
			.filter(g -> to.etc.pdp11.core.mmu.Pdp11Mmu.USAGE_TAG.equals(g.getUsageTag()))
			.count();
	}

	// ---------------------------------------------------------------------------------------
	// Two attempts at once
	// ---------------------------------------------------------------------------------------

	/**
	 * A connection attempt that is overtaken while it is waiting on a machine must take nothing
	 * with it when it gives up.
	 *
	 * <p>The sequence this pins down is the one that stranded the UI: connect A blocks in its
	 * handshake, connect B replaces it and reports CONNECTED, A then fails - and used to close
	 * B's transport, remove B's MMU group and fire FAILED over B's CONNECTED, leaving a live
	 * command thread behind a manager that said it was not connected.</p>
	 */
	@Test
	void anOvertakenAttemptDoesNotTakeTheLiveConnectionWithIt() throws Exception {
		MemoryCellGroups groups = new MemoryCellGroups();
		try(ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			ConnectionManager m = new ConnectionManager(groups, Logger.NULL, new Scheduler.Manual(),
				Path.of(System.getProperty("java.io.tmpdir")))) {
			//-- Accepts, and then says nothing at all: the handshake waits, which is what a real
			//-- machine that is powered on but not listening does.
			List<ConnectionManager.State> seen = new CopyOnWriteArrayList<>();
			m.addListener((mgr, state) -> seen.add(state));

			AtomicReference<Exception> failure = new AtomicReference<>();
			Thread slow = new Thread(() -> {
				try {
					m.connect(new ConnectionProfile("slow", ConsoleProtocol.ODT_18,
						TransportConfig.telnet("localhost", silent.getLocalPort())));
				} catch(Exception x) {
					failure.set(x);
				}
			}, "slow-connect");
			slow.setDaemon(true);
			slow.start();
			waitFor(() -> m.getState() == ConnectionManager.State.CONNECTING, "the slow attempt to start");

			//-- Overtake it. This one has a machine behind it and finishes at once.
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
			assertTrue(m.isConnected());
			ConsoleConnection live = m.getConnection();

			//-- Now let the slow one fail, and wait until it has finished failing.
			silent.close();
			slow.join(10_000);
			assertFalse(slow.isAlive(), "the overtaken attempt should have given up");
			assertNotNull(failure.get(), "and should have said so to its own caller");

			assertEquals(ConnectionManager.State.CONNECTED, m.getState(),
				"the overtaken attempt must not report its failure over a newer connection");
			assertTrue(m.isConnected());
			assertSame(live, m.getConnection(), "nor replace it");
			assertEquals(1, mmuGroups(groups), "nor remove its MMU group");
			assertFalse(seen.contains(ConnectionManager.State.FAILED), "and nothing saw a failure");

			//-- And the connection it left alone is still a working one.
			Address a = Address.of(m.getConsole().physicalAddressType(), 01000);
			m.getConnection().run(() -> m.getConsole().deposit(a, 0123456));
			assertEquals(0123456, m.getConnection().call(() -> m.getConsole().examine(a)).word());
		}
	}

	/** The same race the other way round: disconnect while an attempt is still waiting. */
	@Test
	void disconnectingDuringAnAttemptEndsDisconnectedRatherThanFailed() throws Exception {
		try(ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			ConnectionManager m = manager()) {
			AtomicReference<Exception> failure = new AtomicReference<>();
			Thread slow = new Thread(() -> {
				try {
					m.connect(new ConnectionProfile("slow", ConsoleProtocol.ODT_18,
						TransportConfig.telnet("localhost", silent.getLocalPort())));
				} catch(Exception x) {
					failure.set(x);
				}
			}, "slow-connect");
			slow.setDaemon(true);
			slow.start();
			waitFor(() -> m.getState() == ConnectionManager.State.CONNECTING, "the slow attempt to start");

			m.disconnect();
			silent.close();
			slow.join(10_000);
			assertFalse(slow.isAlive());
			assertNotNull(failure.get());
			assertEquals(ConnectionManager.State.DISCONNECTED, m.getState(),
				"the abandoned attempt must not turn a deliberate disconnect into a failure");
			assertNull(m.getConnection());
			assertNull(m.getConsole());
		}
	}

	// ---------------------------------------------------------------------------------------
	// The machine going away by itself
	// ---------------------------------------------------------------------------------------

	/**
	 * A connection that dies under the application must not leave it saying "Connected".
	 *
	 * <p>The reader thread ends, the answer queue closes, and that used to be the whole of it:
	 * the state stayed CONNECTED, the status bar kept saying so, terminal input stayed live and
	 * every window went on offering buttons that reached a dead wire, each failing on its own a
	 * few seconds later.</p>
	 */
	@Test
	void aConnectionThatDropsBecomesFailedRatherThanStayingConnected() throws Exception {
		MemoryCellGroups groups = new MemoryCellGroups();
		try(ConnectionManager m = new ConnectionManager(groups, Logger.NULL, new Scheduler.Manual(),
			Path.of(System.getProperty("java.io.tmpdir")))) {
			List<ConnectionManager.State> seen = new CopyOnWriteArrayList<>();
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
			m.addListener((mgr, state) -> seen.add(state));
			assertTrue(m.isConnected());

			//-- The wire dies underneath it: closed by something that is not this manager, which
			//-- is what SimH exiting or a serial line being unplugged looks like from in here.
			m.getConnection().getTransport().close();

			waitFor(() -> m.getState() == ConnectionManager.State.FAILED, "the drop to be noticed");
			assertFalse(m.isConnected());
			assertEquals(List.of(ConnectionManager.State.FAILED), seen, "and the windows are told");
			assertNull(m.getConnection(), "nothing may be left to queue work on");
			assertNull(m.getConsole());
			assertFalse(m.hasMachineConsole(), "and nothing may be left to type at");
			assertTrue(m.getMessage().contains("closed at the other end"), m.getMessage());
			assertEquals(0, mmuGroups(groups), "the dead connection's MMU group goes with it");

			//-- And it is still usable: this is the state the reconnect starts from.
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
			assertTrue(m.isConnected());
		}
	}

	@Test
	void aDeliberateDisconnectIsNotReportedAsADroppedConnection() throws Exception {
		//-- The same reader thread ends the same way. The difference is whose idea it was, and
		//-- the state the user is left looking at says which.
		try(ConnectionManager m = manager()) {
			List<ConnectionManager.State> seen = new CopyOnWriteArrayList<>();
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_18));
			m.addListener((mgr, state) -> seen.add(state));
			m.disconnect();
			Thread.sleep(200);
			assertEquals(List.of(ConnectionManager.State.DISCONNECTED), seen,
				"a disconnect must not be followed by a failure as the reader notices");
			assertEquals(ConnectionManager.State.DISCONNECTED, m.getState());
		}
	}

	/** The same for a connection that has already been replaced: its death is not news. */
	@Test
	void aReplacedConnectionDyingDoesNotDisturbTheLiveOne() throws Exception {
		try(ConnectionManager m = manager()) {
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.ODT_18));
			ConsoleConnection first = m.getConnection();
			m.connect(ConnectionProfile.simulated(ConsoleProtocol.PDP1144));
			assertTrue(m.isConnected());

			first.getTransport().close();
			Thread.sleep(200);
			assertEquals(ConnectionManager.State.CONNECTED, m.getState());
			assertNotNull(m.getConnection());
		}
	}

	private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while(!condition.getAsBoolean()) {
			if(System.currentTimeMillis() > deadline)
				throw new AssertionError("Timed out waiting for " + what);
			Thread.sleep(5);
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
