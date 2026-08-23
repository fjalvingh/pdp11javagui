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
 *
 * <p>Connecting, reconnecting and disconnecting are serialised against each other by a
 * generation counter rather than by a lock held across the blocking part, so a disconnect never
 * waits on an attempt that is itself waiting on a machine. An attempt builds everything into
 * locals and publishes it in one step at the end; an attempt that has been overtaken by then
 * closes what it built and changes no state at all.</p>
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

	/** How much of either channel to keep. See {@link #getMachineConsole}. */
	private static final int MAX_CONSOLE_TEXT = 256 * 1024;

	/** Long enough for a machine on the other side of a slow link, short enough to give up on a typo. */
	private static final int TELNET_CONNECT_TIMEOUT_MS = 10_000;

	private final MemoryCellGroups m_groups;

	private final Logger m_logger;

	private final Scheduler m_scheduler;

	/** Where SimH's generated configuration goes. */
	private final Path m_dataDir;

	private final List<Listener> m_listeners = new CopyOnWriteArrayList<>();

	/**
	 * Guards the connect/disconnect sequence: the generation counter, the published connection
	 * below, and the state change that goes with them.
	 *
	 * <p>The blocking part of {@link #connect} - launching SimH, opening a port, the handshake -
	 * deliberately runs <i>outside</i> this lock, so a disconnect never waits on a connection
	 * attempt that is waiting on a machine. What the lock covers is the moment an attempt takes
	 * over and the moment it publishes its result.</p>
	 */
	private final Object m_connectionLock = new Object();

	/**
	 * Bumped by every connect, disconnect and close. An attempt whose generation is no longer
	 * the current one has been superseded: it closes what it built and touches nothing else.
	 *
	 * <p>This is what stops the sequence that used to strand the UI - a second connect while the
	 * first is still blocked launching SimH, the first then failing and closing the <i>second's</i>
	 * transport and firing FAILED over its CONNECTED. Guarded by {@link #m_connectionLock}.</p>
	 */
	private long m_generation;

	private volatile State m_state = State.DISCONNECTED;

	private volatile String m_message = "";

	private volatile ConnectionProfile m_profile;

	private volatile ConsoleConnection m_connection;

	private volatile Console m_console;

	private volatile PhysicalTransport m_transport;

	/** The emulated machine's own console, on a SimH-direct connection. Null otherwise. */
	private volatile PhysicalTransport m_consoleChannel;

	private volatile Thread m_consoleDrain;

	/**
	 * What the machine's own console says - the main window's terminal.
	 *
	 * <p>On SimH direct that is the separate console channel; on every other connection it is
	 * the one wire there is, which the console protocol shares. See
	 * {@link #hasSeparateMachineConsole()}.</p>
	 */
	private final TextChannel m_machineConsole = new TextChannel("machine console", MAX_CONSOLE_TEXT);

	/**
	 * What goes over the console protocol - the SimH Console window.
	 *
	 * <p>Everything the console layer says and hears, its own automated commands included. On a
	 * real PDP-11 this carries the same bytes as {@link #m_machineConsole}, because there is one
	 * serial line and PDP11GUI drives it; on SimH direct it carries the {@code sim>} admin
	 * channel and nothing else.</p>
	 */
	private final TextChannel m_protocolChannel = new TextChannel("console protocol", MAX_CONSOLE_TEXT);

	/** Whether the machine's console is a channel of its own. True only for SimH direct. */
	private volatile boolean m_separateMachineConsole;

	/**
	 * Whether the wire the console protocol talks over <i>is</i> the machine's console.
	 *
	 * <p>True for every real console - ODT and the 11/44 firmware are what answers on the
	 * machine's serial line - and false for SimH, whose {@code sim>} channel is an
	 * administrative one that no PDP-11 ever had.</p>
	 */
	private volatile boolean m_protocolIsMachineConsole;

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

	/**
	 * Publish a state change on behalf of one connect/disconnect attempt.
	 *
	 * <p>Does nothing at all when that attempt has been superseded, which is the whole point: a
	 * stale attempt's FAILED must never land on top of a newer attempt's CONNECTED.</p>
	 *
	 * <p>Listeners are told while the lock is held, so that the order they see states in is the
	 * order the states happened in. They are expected not to block - every listener in the
	 * application marshals to the event thread with {@code AppContext.onUi} and returns.</p>
	 *
	 * @return whether this attempt was still the current one, and the state therefore changed
	 */
	private boolean setState(long generation, State state, String message) {
		synchronized(m_connectionLock) {
			if(generation != m_generation)
				return false;
			m_state = state;
			m_message = message == null ? "" : message;
			m_logger.log(LogChannel.OTHER, "Connection: " + state + (m_message.isEmpty() ? "" : " - " + m_message));
			for(Listener l : m_listeners) {
				l.onConnectionState(this, state);
			}
			return true;
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
	 * <p>Safe to call while another attempt is still running, and that is the point: the last
	 * call in wins, and every earlier one abandons what it built without touching the winner.
	 * The UI should still not offer it - a connection being made is not cancellable and there is
	 * nothing useful to do twice - but a race here cannot leave the manager saying one thing
	 * while the connection does another.</p>
	 *
	 * @throws ConnectionSupersededException if a newer connect or disconnect took over meanwhile;
	 * 	nothing failed, and the connection the caller asked for is simply not the live one
	 * @throws ConsoleException if the transport could not be opened or the console did not answer
	 */
	public void connect(ConnectionProfile profile) throws ConsoleException {
		String problem = profile.validate();
		if(problem != null)
			throw new ConsoleException(problem);

		long generation;
		synchronized(m_connectionLock) {
			generation = ++m_generation;
			closeCurrent();
			m_profile = profile;
		}
		m_machineConsole.clear();
		m_protocolChannel.clear();
		if(!setState(generation, State.CONNECTING, profile.describe()))
			throw new ConnectionSupersededException();

		//-- Everything this attempt builds stays in locals until it is published, below. An
		//-- attempt that is overtaken while it is blocked launching SimH then has its own
		//-- transport, its own console and its own drain thread to close, and no way to reach
		//-- the connection that overtook it.
		boolean separateMachineConsole = false;
		boolean protocolIsMachineConsole = profile.protocol() != ConsoleProtocol.SIMH;
		PhysicalTransport transport = null;
		PhysicalTransport consoleChannel = null;
		Thread drain = null;
		AbstractConsole console = null;
		ConsoleConnection connection = null;
		try {
			transport = openTransport(profile);
			separateMachineConsole = transport instanceof SimhProcessTransport;
			if(transport instanceof SimhProcessTransport simh) {
				consoleChannel = simh.getConsoleChannel();
				drain = startConsoleDrain(consoleChannel);
			}

			//-- AbstractConsole rather than Console, because attaching and the handshake are
			//-- the console's own plumbing rather than things the windows above ever call.
			AbstractConsole c = createConsole(profile);
			console = c;
			//-- The console has just built its MMU, and with it a register group at addresses the
			//-- simulated machine was told nothing about - it was given the I/O page a moment ago,
			//-- before those cells existed. Telling it again is what lets the MMU window's
			//-- Refresh read something back from a machine that is not there.
			if(transport instanceof FakeTransport fake)
				fake.getFake().resetIoPageValidMap(m_groups, null);
			ConsoleConnection cc = new ConsoleConnection(transport, m_logger);
			connection = cc;
			//-- Before attach(), which starts the reader: the handshake is the most interesting
			//-- thing that ever crosses this channel and it happens in init(), below. The sink is
			//-- this attempt's own, because which channels those bytes belong on is a fact about
			//-- this connection and not about whatever is published at the time they arrive.
			boolean alsoMachineConsole = protocolIsMachineConsole;
			cc.setTerminalSink(text -> onProtocolData(text, alsoMachineConsole));
			cc.attach(c);
			cc.run(() -> c.init(cc));

			synchronized(m_connectionLock) {
				if(generation != m_generation)
					throw new ConnectionSupersededException();
				m_transport = transport;
				m_consoleChannel = consoleChannel;
				m_consoleDrain = drain;
				m_separateMachineConsole = separateMachineConsole;
				m_protocolIsMachineConsole = protocolIsMachineConsole;
				m_console = c;
				m_connection = cc;
			}
			setState(generation, State.CONNECTED, profile.describe());
		} catch(IOException x) {
			abandon(connection, consoleChannel, drain, transport, console);
			setState(generation, State.FAILED, x.getMessage());
			throw new ConsoleException("Could not open " + profile.transport().describe() + ": " + x, x);
		} catch(ConsoleException | RuntimeException x) {
			abandon(connection, consoleChannel, drain, transport, console);
			setState(generation, State.FAILED, x.getMessage());
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
	// The two channels
	// -------------------------------------------------------------------------------------

	/**
	 * What the machine's own console says: the stream the main window's terminal shows.
	 *
	 * <p>The rule the whole application follows is one sentence: <b>the main terminal is the
	 * machine's console, whatever the machine is.</b> On a real PDP-11 - ODT, an 11/44, over
	 * serial or telnet - the console is one serial line, and PDP11GUI drives that same line, so
	 * this carries the console protocol's own commands and replies as well; that is not a leak,
	 * it is what a scope on the wire would show.</p>
	 *
	 * <p>On SimH direct there are two wires and they are not the same thing at all. This one is
	 * SimH's console channel, the emulated PDP-11's serial console: boot messages, RT-11, a
	 * program's output. The {@code sim>} traffic goes to {@link #getProtocolChannel()} and is
	 * shown in the SimH Console window instead, which is what "the terminal is the machine's
	 * console" means when the simulator has an administrative channel of its own.</p>
	 *
	 * <p>The Pascal splits these the other way round: its main terminal shows the physical
	 * channel whatever it is ({@code SerialIoHubU.Physical_Poll}), so on a SimH connection it
	 * shows {@code sim>} chatter and the machine's console is off in
	 * {@code FormSimhConsoleU}. An agreed UX change, not a port bug.</p>
	 */
	public TextChannel getMachineConsole() {
		return m_machineConsole;
	}

	/**
	 * What crosses the console protocol, the console layer's own commands included.
	 *
	 * <p>This is the view PLAN.md §3 insists on keeping - "the terminal shows the entire byte
	 * stream ... that is how a flaky console gets debugged" - and it is what the SimH Console
	 * window displays. On a connection with no separate machine console the same bytes are on
	 * {@link #getMachineConsole()}, because there is one wire.</p>
	 */
	public TextChannel getProtocolChannel() {
		return m_protocolChannel;
	}

	/**
	 * Whether the machine's console is a channel of its own, rather than the wire the console
	 * protocol is already using. True for SimH direct, false for everything else.
	 */
	public boolean hasSeparateMachineConsole() {
		return m_separateMachineConsole;
	}

	/**
	 * Whether there is a machine console to show at all.
	 *
	 * <p>False for exactly one case, and it is worth naming: <b>SimH that we did not launch</b> -
	 * the simulated machine, and a telnet connection to somebody else's SimH. There is a
	 * {@code sim>} channel and no console channel behind it, so the main window's terminal has
	 * nothing to show and says so rather than quietly showing {@code sim>} traffic instead.</p>
	 */
	public boolean hasMachineConsole() {
		return m_separateMachineConsole || m_protocolIsMachineConsole;
	}

	/**
	 * Everything read from the console protocol's wire, on the reader thread.
	 *
	 * @param alsoMachineConsole whether this connection's protocol wire <i>is</i> the machine's
	 * 	console, decided when the connection was built rather than read from the manager now
	 */
	private void onProtocolData(String text, boolean alsoMachineConsole) {
		m_protocolChannel.append(text);
		if(alsoMachineConsole)
			m_machineConsole.append(text);
	}

	/**
	 * Something the user typed at the main terminal, on its way to the machine's console.
	 *
	 * <p>Two routes, because there are two kinds of console. With a channel of its own it is
	 * written straight to that socket - it is a terminal line to the emulated machine and has
	 * nothing to do with the command thread, which is busy talking {@code sim>} on the other
	 * one. Without, it is the console protocol's own wire, so it is queued on the command thread
	 * and lands between console commands rather than in the middle of one.</p>
	 *
	 * <p>A keystroke that cannot be delivered is logged and dropped rather than thrown: this is
	 * called from a key listener, and there is nothing useful a terminal can do about it.</p>
	 */
	public void writeToMachineConsole(String text) {
		if(text == null || text.isEmpty())
			return;
		if(!hasMachineConsole()) {
			//-- SimH we did not launch. The only wire is the sim> channel, and a keystroke on
			//-- that is a character the console layer's scanner has to make sense of - it would
			//-- land in the middle of an examine and be read as part of its answer. There is
			//-- nowhere for this to go; the SimH Console window is where commands are typed.
			m_logger.log(LogChannel.OTHER, "Console input dropped: this connection has no machine console");
			return;
		}
		PhysicalTransport channel = m_consoleChannel;
		if(m_separateMachineConsole && channel != null) {
			try {
				channel.write(text.getBytes(StandardCharsets.ISO_8859_1));
			} catch(IOException x) {
				m_logger.log(LogChannel.OTHER, "Console input dropped: " + x);
			}
			return;
		}
		ConsoleConnection c = m_connection;
		if(c == null) {
			m_logger.log(LogChannel.OTHER, "Console input dropped: not connected");
			return;
		}
		c.sendUserInput(text);
	}

	/**
	 * Keep reading SimH's console channel, and hand it to whoever is listening.
	 *
	 * <p>PLAN.md phase 3 records the hazard plainly: <b>something must read this</b>. It is the
	 * emulated PDP-11's own serial console, it is connected because the remote console does not
	 * work without it, and a socket nobody empties eventually blocks SimH itself.</p>
	 */
	private Thread startConsoleDrain(PhysicalTransport channel) {
		Thread t = new Thread(() -> {
			byte[] buf = new byte[4096];
			try {
				int n;
				while((n = channel.read(buf, 0, buf.length)) > 0) {
					m_machineConsole.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
				}
			} catch(IOException x) {
				//-- Closed; nothing more to read.
			}
		}, "simh-console");
		t.setDaemon(true);
		t.start();
		return t;
	}

	/** The emulated machine's own console channel, or {@code null} when there is not one. */
	public PhysicalTransport getConsoleChannel() {
		return m_consoleChannel;
	}

	// -------------------------------------------------------------------------------------
	// Closing
	// -------------------------------------------------------------------------------------

	/** Close whatever is open. Idempotent, and safe to call on a manager that never connected. */
	@Override
	public void close() {
		synchronized(m_connectionLock) {
			//-- Anything still being built is stale from here on, and closes itself.
			m_generation++;
			closeCurrent();
		}
	}

	/** Close and say so. {@link #close()} on its own is silent, because {@code connect} uses it. */
	public void disconnect() {
		long generation;
		synchronized(m_connectionLock) {
			generation = ++m_generation;
			closeCurrent();
		}
		setState(generation, State.DISCONNECTED, "");
	}

	/** Close and forget the published connection. The caller holds {@link #m_connectionLock}. */
	private void closeCurrent() {
		ConsoleConnection connection = m_connection;
		Console console = m_console;
		PhysicalTransport consoleChannel = m_consoleChannel;
		PhysicalTransport transport = m_transport;
		Thread drain = m_consoleDrain;
		m_connection = null;
		m_console = null;
		m_consoleChannel = null;
		m_transport = null;
		m_consoleDrain = null;
		m_separateMachineConsole = false;
		m_protocolIsMachineConsole = false;
		closeParts(connection, consoleChannel, drain, transport, console);
	}

	/**
	 * Give up on one connection attempt: unpublish it if it got as far as being published, and
	 * close what it built either way.
	 *
	 * <p>Unpublishing is by identity rather than by generation, because a stale attempt reaching
	 * here has by definition published nothing - and must not clear the fields of the connection
	 * that overtook it.</p>
	 */
	private void abandon(ConsoleConnection connection, PhysicalTransport consoleChannel, Thread drain,
		PhysicalTransport transport, Console console) {
		synchronized(m_connectionLock) {
			if(connection != null && m_connection == connection) {
				m_connection = null;
				m_console = null;
				m_consoleChannel = null;
				m_transport = null;
				m_consoleDrain = null;
				m_separateMachineConsole = false;
				m_protocolIsMachineConsole = false;
			}
		}
		closeParts(connection, consoleChannel, drain, transport, console);
	}

	/**
	 * Close one connection's plumbing. Every argument is optional: an attempt can fail half-built,
	 * and a manager that never connected has none of it.
	 */
	private void closeParts(ConsoleConnection connection, PhysicalTransport consoleChannel, Thread drain,
		PhysicalTransport transport, Console console) {
		if(connection != null)
			connection.close();
		if(consoleChannel != null)
			consoleChannel.close();
		if(transport != null)
			transport.close();

		//-- The console built an MMU, and the MMU built a register group inside the application's
		//-- groups so that examining those registers anywhere updates it. That group belongs to
		//-- the connection: leaving it behind means every reconnect adds another 99 cells to the
		//-- propagation index, all of them still listening. Removed by identity rather than by
		//-- usage tag, so that an abandoned attempt cannot remove the live connection's group.
		if(console != null && console.getMmu() != null)
			m_groups.removeGroup(console.getMmu().getRegisterGroup());

		if(drain != null)
			drain.interrupt();
	}
}
