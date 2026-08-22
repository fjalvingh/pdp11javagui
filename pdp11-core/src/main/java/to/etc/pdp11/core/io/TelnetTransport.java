package to.etc.pdp11.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

/**
 * A telnet connection to a PDP-11's console server, or to SimH's remote console.
 *
 * <p>Replaces {@code OverbyteIcsTnCnx.pas}, itself a hand-written stand-in for the Windows ICS
 * component the Delphi version used. Same policy, which is the only one that makes sense for a
 * console: <b>refuse every option the peer proposes</b> - {@code IAC WILL} answered with
 * {@code IAC DONT}, {@code IAC DO} with {@code IAC WONT} - so the session stays in plain NVT
 * passthrough and the PDP-11's own bytes come through untouched.</p>
 *
 * <p>The 20 ms poll timer ({@code OverbyteIcsTnCnx.pas:105-108}) is gone: this blocks on the
 * socket and the reader thread of PLAN.md §1 blocks on this.</p>
 */
public final class TelnetTransport implements PhysicalTransport {
	private static final int IAC = 255;

	private static final int DONT = 254;

	private static final int DO = 253;

	private static final int WONT = 252;

	private static final int WILL = 251;

	private static final int SB = 250;

	private static final int SE = 240;

	/** Where in an IAC sequence the input stream currently is. */
	private enum ParseState {
		DATA,
		IAC_SEEN,
		/** An option command was seen; the next byte is the option number. */
		AWAIT_OPTION,
		/** Inside a subnegotiation, discarding until IAC SE. */
		SUB,
		SUB_IAC
	}

	private final String m_host;

	private final int m_port;

	private final Socket m_socket;

	private final InputStream m_in;

	private final OutputStream m_out;

	private final byte[] m_raw = new byte[4096];

	private ParseState m_state = ParseState.DATA;

	private int m_pendingCommand;

	private volatile boolean m_closed;

	public TelnetTransport(String host, int port, int connectTimeoutMillis) throws IOException {
		m_host = host;
		m_port = port;
		m_socket = new Socket();
		try {
			m_socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
			//-- A console is a stream of single keystrokes and single echoes; batching them
			//-- into 40 ms Nagle windows makes the terminal feel broken.
			m_socket.setTcpNoDelay(true);
			m_in = m_socket.getInputStream();
			m_out = m_socket.getOutputStream();
		} catch(IOException x) {
			closeQuietly();
			throw new TransportException("Cannot connect to " + host + ":" + port + ": " + x.getMessage(), x);
		}
	}

	/**
	 * Connect, retrying until the deadline. SimH needs a moment between being launched and
	 * having its remote console listening, and there is no event to wait for.
	 */
	public static TelnetTransport connectWithRetry(String host, int port, int timeoutMillis,
		int retryIntervalMillis) throws IOException {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		IOException last = null;
		while(true) {
			try {
				return new TelnetTransport(host, port, Math.max(200, retryIntervalMillis));
			} catch(IOException x) {
				last = x;
			}
			if(System.nanoTime() >= deadline)
				throw new TransportException("Gave up connecting to " + host + ":" + port
					+ " after " + timeoutMillis + " ms", last);
			try {
				Thread.sleep(retryIntervalMillis);
			} catch(InterruptedException ix) {
				Thread.currentThread().interrupt();
				throw new TransportException("Interrupted while connecting to " + host + ":" + port, ix);
			}
		}
	}

	/**
	 * Read, stripping IAC sequences and answering option offers.
	 *
	 * <p>Loops rather than returning zero: a chunk that is nothing but negotiation must not
	 * look like "no data", or the reader thread spins.</p>
	 */
	@Override
	public int read(byte[] buf, int off, int len) throws IOException {
		while(true) {
			int n;
			try {
				n = m_in.read(m_raw, 0, Math.min(len, m_raw.length));
			} catch(SocketException x) {
				if(m_closed)
					return -1;                              // closed underneath us: not an error
				throw x;
			}
			if(n < 0)
				return -1;

			int produced = filter(m_raw, n, buf, off);
			if(produced > 0)
				return produced;
			if(m_closed)
				return -1;
			//-- Everything in this chunk was negotiation. Go round again.
		}
	}

	/**
	 * Run {@code n} raw bytes through the IAC state machine, appending plain data to
	 * {@code out} and replying to option offers. Ported from {@code ProcessIncoming}
	 * ({@code OverbyteIcsTnCnx.pas:185-220}).
	 */
	private int filter(byte[] raw, int n, byte[] out, int outOff) throws IOException {
		int produced = 0;
		for(int i = 0; i < n; i++) {
			int b = raw[i] & 0xFF;
			switch(m_state) {
				case DATA -> {
					if(b == IAC)
						m_state = ParseState.IAC_SEEN;
					else
						out[outOff + produced++] = (byte) b;
				}
				case IAC_SEEN -> {
					switch(b) {
						//-- IAC IAC is a literal 0xFF data byte.
						case IAC -> {
							out[outOff + produced++] = (byte) IAC;
							m_state = ParseState.DATA;
						}
						case WILL, WONT, DO, DONT -> {
							m_pendingCommand = b;
							m_state = ParseState.AWAIT_OPTION;
						}
						case SB -> m_state = ParseState.SUB;
						//-- The other two-byte commands - NOP, AYT, IP and friends - mean
						//-- nothing to a PDP-11 console. Drop them.
						default -> m_state = ParseState.DATA;
					}
				}
				case AWAIT_OPTION -> {
					refuseOption(m_pendingCommand, b);
					m_state = ParseState.DATA;
				}
				case SUB -> {
					if(b == IAC)
						m_state = ParseState.SUB_IAC;
					//-- else: subnegotiation payload, discarded
				}
				case SUB_IAC -> m_state = b == SE ? ParseState.DATA : ParseState.SUB;
			}
		}
		return produced;
	}

	/** Answer WILL with DONT and DO with WONT, so no option is ever agreed. */
	private void refuseOption(int command, int option) throws IOException {
		if(command != WILL && command != DO)
			return;                                         // WONT/DONT need no answer
		byte[] reply = {(byte) IAC, (byte) (command == WILL ? DONT : WONT), (byte) option};
		synchronized(m_out) {
			m_out.write(reply);
			m_out.flush();
		}
	}

	/** Write, doubling any literal {@code 0xFF} so it is not read as an IAC. */
	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		byte[] escaped = null;
		int extra = 0;
		for(int i = 0; i < len; i++) {
			if((buf[off + i] & 0xFF) == IAC)
				extra++;
		}
		if(extra > 0) {
			escaped = new byte[len + extra];
			int j = 0;
			for(int i = 0; i < len; i++) {
				byte b = buf[off + i];
				escaped[j++] = b;
				if((b & 0xFF) == IAC)
					escaped[j++] = (byte) IAC;
			}
		}
		synchronized(m_out) {
			if(escaped != null)
				m_out.write(escaped);
			else
				m_out.write(buf, off, len);
			m_out.flush();
		}
	}

	@Override
	public boolean isOpen() {
		return !m_closed && m_socket.isConnected() && !m_socket.isClosed();
	}

	@Override
	public void close() {
		m_closed = true;
		closeQuietly();
	}

	private void closeQuietly() {
		try {
			//-- Closing the socket is what unblocks a reader parked in read().
			m_socket.close();
		} catch(IOException x) {
			//-- Nothing useful to do about a failure to close.
		}
	}

	@Override
	public String describe() {
		return "telnet " + m_host + ":" + m_port;
	}
}
