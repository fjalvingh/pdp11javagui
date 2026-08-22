package to.etc.pdp11.core.io;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;

/**
 * A real serial port, which is how PDP11GUI talks to actual hardware.
 *
 * <p>Replaces the Pascal {@code TComm} wrapper. jSerialComm ships its native library inside
 * the jar for every platform, so this needs no per-OS install - one of the reasons the plan
 * picks it over the alternatives.</p>
 *
 * <h2>Framing</h2>
 *
 * <p>PDP-11 serial consoles are 7-bit devices and the older ones use mark or space parity,
 * hence {@link SerialFormat}. The Pascal masks the high bit off in the transport when the
 * format is not 8N1 ({@code SerialIoHubU.pas:820-822}) and then masks it off again
 * unconditionally two lines later ({@code :843}). Only the second one matters, and it belongs
 * to the protocol rather than the wire, so it is not done here: a transport delivers what
 * arrived.</p>
 */
public final class SerialTransport implements PhysicalTransport {
	/** The line settings a PDP-11 console might want. */
	public enum SerialFormat {
		/** Eight data bits, no parity, one stop bit. */
		N8_1(8, SerialPort.NO_PARITY),
		/** Seven data bits, even parity. */
		E7_1(7, SerialPort.EVEN_PARITY),
		/** Seven data bits, odd parity. */
		O7_1(7, SerialPort.ODD_PARITY),
		/** Seven data bits, mark parity - the high bit always set. */
		M7_1(7, SerialPort.MARK_PARITY),
		/** Seven data bits, space parity - the high bit always clear. */
		S7_1(7, SerialPort.SPACE_PARITY);

		private final int m_dataBits;

		private final int m_parity;

		SerialFormat(int dataBits, int parity) {
			m_dataBits = dataBits;
			m_parity = parity;
		}
	}

	private final SerialPort m_port;

	private final String m_description;

	private volatile boolean m_closed;

	public SerialTransport(String portName, int baudRate, SerialFormat format) throws IOException {
		SerialPort port = SerialPort.getCommPort(portName);
		port.setComPortParameters(baudRate, format.m_dataBits, SerialPort.ONE_STOP_BIT, format.m_parity);
		//-- Block until at least one byte arrives, with no timeout: the reader thread has
		//-- nothing else to do, and a timeout would just turn this back into a poll loop.
		port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
		if(!port.openPort())
			throw new TransportException("Cannot open serial port " + portName
				+ " (" + baudRate + " " + format + ")");
		m_port = port;
		m_description = "serial " + portName + " " + baudRate + " " + format;
	}

	/** The serial ports this machine currently has, for the connection dialog. */
	public static String[] availablePortNames() {
		SerialPort[] ports = SerialPort.getCommPorts();
		String[] names = new String[ports.length];
		for(int i = 0; i < ports.length; i++) {
			names[i] = ports[i].getSystemPortName();
		}
		return names;
	}

	@Override
	public int read(byte[] buf, int off, int len) throws IOException {
		int n = m_port.readBytes(buf, len, off);
		if(n < 0)
			return m_closed ? -1 : throwRead();
		//-- A blocking read returning zero means the port went away under us.
		return n == 0 ? -1 : n;
	}

	private int throwRead() throws IOException {
		throw new TransportException("Read failed on " + m_description);
	}

	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		int written = m_port.writeBytes(buf, len, off);
		if(written != len)
			throw new TransportException("Wrote " + written + " of " + len + " bytes to " + m_description);
	}

	@Override
	public boolean isOpen() {
		return !m_closed && m_port.isOpen();
	}

	@Override
	public void close() {
		m_closed = true;
		m_port.closePort();
	}

	@Override
	public String describe() {
		return m_description;
	}
}
