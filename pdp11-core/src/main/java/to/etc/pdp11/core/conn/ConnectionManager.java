package to.etc.pdp11.core.conn;

import to.etc.pdp11.core.console.AbstractConsole;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleConnection;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.OdtConsole;
import to.etc.pdp11.core.console.OdtDialect;
import to.etc.pdp11.core.console.Pdp1144Console;
import to.etc.pdp11.core.console.Pdp1144Firmware;
import to.etc.pdp11.core.console.SimhConsole;
import to.etc.pdp11.core.fake.FakePdp11;
import to.etc.pdp11.core.fake.FakePdp11Odt;
import to.etc.pdp11.core.fake.FakePdp1144;
import to.etc.pdp11.core.fake.FakePdp1144V340c;
import to.etc.pdp11.core.fake.FakeSimh;
import to.etc.pdp11.core.io.FakeTransport;
import to.etc.pdp11.core.io.PhysicalTransport;
import to.etc.pdp11.core.io.SerialTransport;
import to.etc.pdp11.core.io.SimhProcessTransport;
import to.etc.pdp11.core.io.TelnetTransport;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Turns a {@link ConnectionProfile} into a live console, and owns it until it is closed.
 *
 * <p>Replaces the half of {@code TSerialIoHub} that decides <i>what</i> to open - its
 * {@code Physical_InitFor*} methods and the {@code connectionType} switch running through the
 * whole unit - and the half of {@code FormMainU} that decides which console class to construct.
 * Neither of those is a thing the windows should know about.</p>
 *
 * <h2>Which thread</h2>
 *
 * <p><b>{@link #connect} blocks</b>, for as long as launching SimH or opening a serial port takes,
 * and it runs a console handshake at the end of it. It must not be called on the Swing event
 * thread. Everything after it goes through {@link ConsoleConnection#call}, which enforces the
 * same rule for itself.</p>
 */
public final class ConnectionManager implements AutoCloseable {
	/** Where a connection is. */
	public enum State {
		DISCONNECTED,
		CONNECTING,
		CONNECTED,
		/** The attempt failed, or a live connection dropped. {@link #getMessage()} says why. */
		FAILED
	}

	/** Told when the state changes, on whatever thread changed it. */
	@FunctionalInterface
	public interface Listener {
		void onConnectionState(ConnectionManager manager, State state);
	}

	/** How much of the emulated machine's own console to keep. See {@link #getConsoleChannelText}. */
	private static final int MAX_CONSOLE_TEXT = 256 * 1024;

	/** Long enough for a machine on the other side of a slow link, short enough to give up on a typo. */
	private static final int TELNET_CONNECT_TIMEOUT_MS = 10_000;

	private final MemoryCellGroups m_groups;

	private final Logger m_logger;

	private final Scheduler m_scheduler;

	/** Where SimH's generated configuration goes. */
	private final Path m_dataDir;

	private final List<Listener> m_listeners = new CopyOnWriteArrayList<>();

	private volatile State m_state = State.DISCONNECTED;

	private volatile String m_message = "";

	private volatile ConnectionProfile m_profile;

	private volatile ConsoleConnection m_connection;

	private volatile Console m_console;

	private volatile PhysicalTransport m_transport;

	/** The emulated machine's own console, on a SimH-direct connection. Null otherwise. */
	private volatile PhysicalTransport m_consoleChannel;

	private volatile Thread m_consoleDrain;

	private final StringBuilder m_consoleText = new StringBuilder();

	public ConnectionManager(MemoryCellGroups groups, Logger logger, Scheduler scheduler, Path dataDir) {
		m_groups = groups;
		m_logger = logger;
		m_scheduler = scheduler;
		m_dataDir = dataDir;
	}

	public State getState() {
		return m_state;
	}

	/** Why the last state change happened, when that is worth saying. Never null. */
	public String getMessage() {
		return m_message;
	}

	public boolean isConnected() {
		return m_state == State.CONNECTED;
	}

	public ConnectionProfile getProfile() {
		return m_profile;
	}

	/** The live console, or {@code null} when there is not one. */
	public Console getConsole() {
		return m_console;
	}

	/** The live connection, or {@code null}. This is what {@code call()} is invoked on. */
	public ConsoleConnection getConnection() {
		return m_connection;
	}

	public void addListener(Listener l) {
		m_listeners.add(l);
	}

	public void removeListener(Listener l) {
		m_listeners.remove(l);
	}

	private void setState(State state, String message) {
		m_state = state;
		m_message = message == null ? "" : message;
		m_logger.log(LogChannel.OTHER, "Connection: " + state + (m_message.isEmpty() ? "" : " - " + m_message));
		for(Listener l : m_listeners) {
			l.onConnectionState(this, state);
		}
	}

	// -------------------------------------------------------------------------------------
	// Connecting
	// -------------------------------------------------------------------------------------

	/**
	 * Open the transport, build the console and run its handshake.
	 *
	 * <p>Blocks. Any existing connection is closed first, so this doubles as "reconnect".</p>
	 *
	 * @throws ConsoleException if the transport could not be opened or the console did not answer
	 */
	public void connect(ConnectionProfile profile) throws ConsoleException {
		String problem = profile.validate();
		if(problem != null)
			throw new ConsoleException(problem);
		close();
		m_profile = profile;
		setState(State.CONNECTING, profile.describe());
		try {
			PhysicalTransport transport = openTransport(profile);
			m_transport = transport;
			if(transport instanceof SimhProcessTransport simh)
				startConsoleDrain(simh.getConsoleChannel());

			//-- AbstractConsole rather than Console, because attaching and the handshake are
			//-- the console's own plumbing rather than things the windows above ever call.
			AbstractConsole console = createConsole(profile);
			ConsoleConnection connection = new ConsoleConnection(transport, m_logger);
			m_console = console;
			m_connection = connection;
			connection.attach(console);
			connection.run(() -> console.init(connection));
			setState(State.CONNECTED, profile.describe());
		} catch(IOException x) {
			close();
			setState(State.FAILED, x.getMessage());
			throw new ConsoleException("Could not open " + profile.transport().describe() + ": " + x, x);
		} catch(ConsoleException | RuntimeException x) {
			close();
			setState(State.FAILED, x.getMessage());
			throw x;
		}
	}

	private PhysicalTransport openTransport(ConnectionProfile profile) throws IOException {
		TransportConfig t = profile.transport();
		return switch(t.kind()) {
			case SIMH_PROCESS -> SimhProcessTransport.launch(t.effectiveSimhExecutable(),
				t.simhConfigFile() == null || t.simhConfigFile().isBlank() ? null : Path.of(t.simhConfigFile()),
				m_dataDir, m_logger);
			case TELNET -> new TelnetTransport(t.host(), t.port(), TELNET_CONNECT_TIMEOUT_MS);
			case SERIAL -> new SerialTransport(t.serialPort(), t.baudRate(), t.effectiveSerialFormat());
			case SIMULATED -> new FakeTransport(createFake(profile.protocol()));
		};
	}

	/** A simulated machine of the kind this protocol talks to. */
	private FakePdp11 createFake(ConsoleProtocol protocol) {
		//-- Seeded, so a simulated session behaves the same way twice. The randomness is only
		//-- how long a pretend program runs before it halts.
		Random random = new Random(1);
		FakePdp11 fake = switch(protocol) {
			case SIMH -> new FakeSimh(m_scheduler, random);
			case ODT_16, ODT_18, ODT_22 -> new FakePdp11Odt(protocol.getAddressType(), m_scheduler, random,
				FakePdp11Odt.OdtDialect.DEC);
			case ODT_K1630 -> new FakePdp11Odt(protocol.getAddressType(), m_scheduler, random,
				FakePdp11Odt.OdtDialect.K1630);
			case PDP1144 -> new FakePdp1144(m_scheduler, random);
			case PDP1144_V340C -> new FakePdp1144V340c(m_scheduler, random);
		};
		fake.powerOn();
		//-- Give it the I/O page the loaded machine description declares, so the register windows
		//-- and the I/O page scanner have something real to find.
		fake.resetIoPageValidMap(m_groups, null);
		return fake;
	}

	private AbstractConsole createConsole(ConnectionProfile profile) {
		return switch(profile.protocol()) {
			case SIMH -> new SimhConsole(m_groups, m_logger, m_scheduler);
			case ODT_16, ODT_18, ODT_22 -> new OdtConsole(m_groups, profile.protocol().getAddressType(),
				OdtDialect.DEC, m_logger);
			case ODT_K1630 -> new OdtConsole(m_groups, profile.protocol().getAddressType(),
				OdtDialect.K1630, m_logger);
			case PDP1144 -> new Pdp1144Console(m_groups, Pdp1144Firmware.CLASSIC, m_logger);
			case PDP1144_V340C -> new Pdp1144Console(m_groups, Pdp1144Firmware.V340C, m_logger);
		};
	}

	/**
	 * Flip the simulated machine's pretend RUN/HALT switch, if that is what is connected.
	 *
	 * <p>A console cannot see a physical switch, so the execution-control window tells it where
	 * the operator says it is. When the machine is simulated there is no operator and no switch,
	 * and the fake has to be told the same thing or it answers as though the switch were
	 * somewhere else - which is what {@code FormExecuteU.pas:544-568} does with
	 * {@code FormMain.SerialIoHub.FakePDP11.RunMode}.</p>
	 *
	 * @return whether there was a simulated machine to tell
	 */
	public boolean setSimulatedRunMode(boolean running) {
		PhysicalTransport t = m_transport;
		if(!(t instanceof FakeTransport fake))
			return false;
		fake.getFake().setRunMode(running);
		return true;
	}

	// -------------------------------------------------------------------------------------
	// The emulated machine's own console
	// -------------------------------------------------------------------------------------

	/**
	 * Keep reading SimH's console channel, and keep the last of it.
	 *
	 * <p>PLAN.md phase 3 records the hazard plainly: <b>something must read this</b>. It is the
	 * emulated PDP-11's own serial console, it is connected because the remote console does not
	 * work without it, and a socket nobody empties eventually blocks SimH itself. The window that
	 * will display it arrives in phase 6; until then this drains it and holds the tail, so that
	 * when the window does arrive there is something to show and nothing to fix.</p>
	 */
	private void startConsoleDrain(PhysicalTransport channel) {
		m_consoleChannel = channel;
		synchronized(m_consoleText) {
			m_consoleText.setLength(0);
		}
		Thread t = new Thread(() -> {
			byte[] buf = new byte[4096];
			try {
				int n;
				while((n = channel.read(buf, 0, buf.length)) > 0) {
					synchronized(m_consoleText) {
						m_consoleText.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
						if(m_consoleText.length() > MAX_CONSOLE_TEXT)
							m_consoleText.delete(0, m_consoleText.length() - MAX_CONSOLE_TEXT);
					}
				}
			} catch(IOException x) {
				//-- Closed; nothing more to read.
			}
		}, "simh-console");
		t.setDaemon(true);
		t.start();
		m_consoleDrain = t;
	}

	/** The emulated machine's own console channel, or {@code null} when there is not one. */
	public PhysicalTransport getConsoleChannel() {
		return m_consoleChannel;
	}

	/** What the emulated machine has printed on its own console, up to the last 256 KB. */
	public String getConsoleChannelText() {
		synchronized(m_consoleText) {
			return m_consoleText.toString();
		}
	}

	// -------------------------------------------------------------------------------------
	// Closing
	// -------------------------------------------------------------------------------------

	/** Close whatever is open. Idempotent, and safe to call on a manager that never connected. */
	@Override
	public void close() {
		ConsoleConnection c = m_connection;
		m_connection = null;
		m_console = null;
		if(c != null)
			c.close();

		PhysicalTransport channel = m_consoleChannel;
		m_consoleChannel = null;
		if(channel != null)
			channel.close();

		PhysicalTransport t = m_transport;
		m_transport = null;
		if(t != null)
			t.close();

		Thread drain = m_consoleDrain;
		m_consoleDrain = null;
		if(drain != null)
			drain.interrupt();
	}

	/** Close and say so. {@link #close()} on its own is silent, because {@code connect} uses it. */
	public void disconnect() {
		close();
		setState(State.DISCONNECTED, "");
	}
}
