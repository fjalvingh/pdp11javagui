package to.etc.pdp11.core.io;

import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "SimH direct": launch SimH ourselves and drive it over its own remote console.
 *
 * <p>Ported from {@code Physical_InitForSimhProcess} ({@code SerialIoHubU.pas:275-400ish}).
 * PDP11GUI writes a temporary {@code .ini} based on the user's, appends its own
 * {@code set remote}/{@code set console} directives, starts {@code pdp11} as a child process
 * and connects to the admin channel - which at this level is byte-identical to a plain telnet
 * connection, so the work is delegated to {@link TelnetTransport}.</p>
 *
 * <p>Two ports are used: the <b>remote</b> channel, SimH's own {@code sim>} prompt, which is
 * what this transport carries; and the <b>console</b> channel, the emulated PDP-11's serial
 * console, which the SimH Console window displays.</p>
 *
 * <h2>Both channels must be connected, or the remote console never answers</h2>
 *
 * <p>This is not obvious and cost an afternoon to find. With only the remote channel
 * connected, SimH sends its banner -</p>
 *
 * <pre>
 * Connected to the PDP-11 simulator REM-CON device
 * PDP-11 Remote Console
 * Enter single commands or to enter multiple command mode enter the ^E character
 * </pre>
 *
 * <p>- and then answers <b>nothing at all</b>: no prompt, no echo, no reply, however long you
 * wait and whatever you send, {@code ^E} included. Connect the console channel as well and it
 * comes to life immediately, printing {@code Master Mode Session} and a {@code sim>} prompt.
 * The Pascal connects both ({@code SerialIoHubU.pas:726-727}) and so never meets this, but it
 * connects them from two unrelated places, so nothing there records that one depends on the
 * other.</p>
 *
 * <p>So this class opens both, and {@link #getConsoleChannel()} hands the second one out for
 * the SimH Console window to display. <b>Something must read it.</b> Nothing does yet - the
 * window arrives in phase 6 - and an emulated console that is never drained will eventually
 * fill its socket buffer and block SimH.</p>
 *
 * <p>One more thing is needed before the remote console will answer, and it belongs to the
 * console layer rather than here, but this is where it was found: <b>{@code ^E} is what
 * produces a {@code sim>} prompt</b> at all, whatever {@code set remote master} is in the
 * configuration. SimH echoes the {@code ^E} back as though it were a command and answers it
 * {@code Unknown command}, which is harmless. {@code SimhProcessTransportIT} has the worked
 * sequence.</p>
 */
public final class SimhProcessTransport implements PhysicalTransport {
	/** The admin {@code sim>} channel. */
	public static final int REMOTE_PORT_BASE = 4000;

	/** The emulated machine's own console. */
	public static final int CONSOLE_PORT_BASE = 4001;

	/** How far above the base to search for a free pair. */
	public static final int PORT_PROBE_TRIES = 50;

	public static final int CONNECT_TIMEOUT_MS = 5000;

	/** A port pair for one SimH instance. The two stay adjacent. */
	public record Ports(int remote, int console) {
	}

	private final Process m_process;

	private final TelnetTransport m_telnet;

	/** The emulated PDP-11's own console. See the class comment: this has to be connected. */
	private final TelnetTransport m_console;

	/**
	 * The last of what SimH printed on its own stdout.
	 *
	 * <p>Drained by a daemon thread, because it is the only diagnosis available when a launch
	 * goes wrong: SimH reports a failed port bind or a bad configuration line here and nowhere
	 * else. It also means a long-running SimH can never block on a full pipe, though in
	 * practice it prints far too little for that.</p>
	 *
	 * <p>Do not synchronise on this. SimH's stdout is a pipe rather than a terminal, so its
	 * writes are block-buffered and what it has printed usually has not reached us yet.</p>
	 */
	private final StringBuilder m_processOutput = new StringBuilder();

	private final Ports m_ports;

	private final Path m_iniFile;

	private volatile boolean m_closed;

	private SimhProcessTransport(Process process, TelnetTransport telnet, TelnetTransport console,
		Ports ports, Path iniFile) {
		m_process = process;
		m_telnet = telnet;
		m_console = console;
		m_ports = ports;
		m_iniFile = iniFile;
	}

	/**
	 * The emulated machine's own serial console, for the SimH Console window.
	 *
	 * <p>Opened here rather than by the window because the remote console does not work
	 * without it - see the class comment. Whoever displays it is responsible for reading it.</p>
	 */
	public PhysicalTransport getConsoleChannel() {
		return m_console;
	}

	/**
	 * Launch SimH against a copy of {@code userIni} and connect to its remote console.
	 *
	 * @param simhExecutable normally just {@code "pdp11"}, found on {@code PATH}
	 * @param userIni        the user's SimH configuration, copied and amended
	 * @param tmpDir         where the amended copy goes
	 */
	public static SimhProcessTransport launch(String simhExecutable, Path userIni, Path tmpDir,
		Logger logger) throws IOException {
		Ports ports = findFreePorts(logger);
		Path ini = generateIniFile(userIni, tmpDir, ports);

		ProcessBuilder pb = new ProcessBuilder(simhExecutable, ini.toString());
		pb.directory(ini.getParent().toFile());
		//-- One stream for both, so a failed launch is diagnosable from one place.
		pb.redirectErrorStream(true);
		Process process = pb.start();
		logger.log(LogChannel.OTHER, "Started %s %s (remote %d, console %d)",
			simhExecutable, ini, ports.remote(), ports.console());

		SimhProcessTransport result = null;
		try {
			//-- SimH needs a moment between being launched and having its remote console
			//-- listening, and there is no event to wait for.
			TelnetTransport telnet = TelnetTransport.connectWithRetry(
				"localhost", ports.remote(), CONNECT_TIMEOUT_MS, 100);
			//-- And the console channel, without which the remote one stays mute. See above.
			TelnetTransport console = TelnetTransport.connectWithRetry(
				"localhost", ports.console(), CONNECT_TIMEOUT_MS, 100);
			result = new SimhProcessTransport(process, telnet, console, ports, ini);
			result.startOutputDrain();
			return result;
		} catch(IOException x) {
			process.destroyForcibly();
			throw new TransportException("SimH started but its consoles on ports "
				+ ports.remote() + "/" + ports.console() + " never accepted a connection.", x);
		}
	}

	/**
	 * Pick the remote/console port pair, searching upwards in steps of two so the pair stays
	 * adjacent and the familiar 4000/4001 is used whenever it is free.
	 *
	 * <p>Ported from {@code FindFreeSimhPorts} ({@code SerialIoHubU.pas:622-639}), and PLAN.md
	 * §4 says to preserve it exactly. The reason is in the Pascal's own comment
	 * ({@code :256-272}) and worth repeating, because it looks like a bug otherwise:
	 * <b>the probe deliberately does not set {@code SO_REUSEADDR}</b>. SimH does not set it
	 * either, so a port still in {@code TIME_WAIT} from a previous run is one SimH cannot bind
	 * - and probing with {@code SO_REUSEADDR} would call it free. Restarting within about a
	 * minute then left SimH running with its remote console disabled, an emulator the GUI
	 * cannot drive. Java's {@code ServerSocket} defaults to reuse <i>off</i>, which is what
	 * this needs; the {@code setReuseAddress(false)} below is explicit so nobody helpfully
	 * turns it on.</p>
	 *
	 * <p>There is an unavoidable race - the ports are released again before SimH binds them -
	 * but a loss is not silent: it surfaces as the connect timeout above.</p>
	 */
	public static Ports findFreePorts(Logger logger) throws IOException {
		for(int i = 0; i < PORT_PROBE_TRIES; i++) {
			int remote = REMOTE_PORT_BASE + 2 * i;
			int console = CONSOLE_PORT_BASE + 2 * i;
			if(isBindable(remote) && isBindable(console)) {
				if(i > 0) {
					logger.log(LogChannel.OTHER, "SimH ports %d/%d busy, using %d/%d instead",
						REMOTE_PORT_BASE, CONSOLE_PORT_BASE, remote, console);
				}
				return new Ports(remote, console);
			}
		}
		throw new TransportException("No free TCP port pair for SimH in "
			+ REMOTE_PORT_BASE + ".." + (CONSOLE_PORT_BASE + 2 * (PORT_PROBE_TRIES - 1)));
	}

	private static boolean isBindable(int port) {
		try(ServerSocket ss = new ServerSocket()) {
			//-- Explicitly NOT reusing: see findFreePorts.
			ss.setReuseAddress(false);
			ss.bind(new InetSocketAddress(port));
			return true;
		} catch(IOException x) {
			return false;
		}
	}

	/**
	 * Copy the user's {@code .ini}, dropping any {@code set remote}/{@code set console} lines
	 * it already has so ours always win, and append our own.
	 *
	 * <p>Ported from {@code GenerateSimhIniFile} ({@code SerialIoHubU.pas:648-676}). The temp
	 * file is deliberately left behind, as it is there, because when a launch goes wrong it is
	 * the first thing you want to look at.</p>
	 */
	public static Path generateIniFile(Path userIni, Path tmpDir, Ports ports) throws IOException {
		List<String> out = new ArrayList<>();
		if(userIni != null && Files.isReadable(userIni)) {
			for(String line : Files.readAllLines(userIni, StandardCharsets.ISO_8859_1)) {
				String lower = line.strip().toLowerCase(Locale.ROOT);
				if(lower.startsWith("set remote") || lower.startsWith("set console"))
					continue;
				out.add(line);
			}
		}
		out.add("set remote connections=1");
		out.add("set remote telnet=" + ports.remote());
		out.add("set console telnet=" + ports.console());
		out.add("set remote master");

		Files.createDirectories(tmpDir);
		Path f = Files.createTempFile(tmpDir, "pdp11gui_simh", ".ini");
		Files.write(f, out, StandardCharsets.ISO_8859_1);
		return f;
	}

	/** Keep reading SimH's own output so it can never block on a full pipe. */
	private void startOutputDrain() {
		Thread t = new Thread(() -> {
			byte[] buf = new byte[4096];
			try {
				int n;
				while((n = m_process.getInputStream().read(buf)) > 0) {
					synchronized(m_processOutput) {
						m_processOutput.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
						//-- Bounded: this is for diagnosis, not a log file.
						if(m_processOutput.length() > MAX_PROCESS_OUTPUT)
							m_processOutput.delete(0, m_processOutput.length() - MAX_PROCESS_OUTPUT);
					}
				}
			} catch(IOException x) {
				//-- The process exited; nothing more to read.
			}
		}, "simh-output");
		t.setDaemon(true);
		t.start();
	}

	private static final int MAX_PROCESS_OUTPUT = 64 * 1024;

	/** What SimH has printed on its own stdout, which is where it reports startup trouble. */
	public String getProcessOutput() {
		synchronized(m_processOutput) {
			return m_processOutput.toString();
		}
	}

	/** The port pair in use; the SimH Console window needs the console one. */
	public Ports getPorts() {
		return m_ports;
	}

	/** The generated configuration, left on disk for diagnosis. */
	public Path getIniFile() {
		return m_iniFile;
	}

	@Override
	public int read(byte[] buf, int off, int len) throws IOException {
		return m_telnet.read(buf, off, len);
	}

	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		m_telnet.write(buf, off, len);
	}

	@Override
	public boolean isOpen() {
		return !m_closed && m_process.isAlive() && m_telnet.isOpen();
	}

	@Override
	public void close() {
		m_closed = true;
		m_telnet.close();
		m_console.close();
		//-- Ask SimH to go before insisting: a clean exit lets it flush and release its ports,
		//-- which matters because the next launch has to bind them again.
		m_process.destroy();
		try {
			if(!m_process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
				m_process.destroyForcibly();
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
			m_process.destroyForcibly();
		}
	}

	@Override
	public String describe() {
		return "SimH child process, remote console on port " + m_ports.remote();
	}
}
