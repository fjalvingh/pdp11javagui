package to.etc.pdp11.core.conn;

import to.etc.pdp11.core.io.SerialTransport;

/**
 * Everything needed to open one transport, whichever kind it is.
 *
 * <p>Deliberately flat rather than a sealed hierarchy with one record per kind. This is written
 * to a settings file and read back by a general-purpose JSON binder, and a sealed hierarchy needs
 * a registry of type adapters to survive that round trip - machinery whose only purpose would be
 * to express, in a file, a discriminator that is already sitting right there as
 * {@link #kind()}.</p>
 *
 * <p>The fields not used by a kind are simply ignored. What stops that being sloppy is
 * {@link #describe()} and {@link #validate()}: one says what a configuration means, the other
 * says whether it means anything at all, and both switch on the kind.</p>
 */
public record TransportConfig(
	TransportKind kind,

	/** {@link TransportKind#SERIAL}: the port's name, {@code /dev/ttyUSB0} or {@code COM3}. */
	String serialPort,

	int baudRate,

	SerialTransport.SerialFormat serialFormat,

	/** {@link TransportKind#TELNET}: where to connect. */
	String host,

	int port,

	/** {@link TransportKind#SIMH_PROCESS}: what to run, and the configuration to run it with. */
	String simhExecutable,

	String simhConfigFile
) {
	/** The SimH binary's usual name, found on {@code PATH}. */
	public static final String DEFAULT_SIMH_EXECUTABLE = "pdp11";

	public static final int DEFAULT_TELNET_PORT = 23;

	public static final int DEFAULT_BAUD_RATE = 9600;

	public static TransportConfig simhProcess(String executable, String configFile) {
		return new TransportConfig(TransportKind.SIMH_PROCESS, null, 0, null, null, 0, executable, configFile);
	}

	public static TransportConfig telnet(String host, int port) {
		return new TransportConfig(TransportKind.TELNET, null, 0, null, host, port, null, null);
	}

	public static TransportConfig serial(String portName, int baudRate, SerialTransport.SerialFormat format) {
		return new TransportConfig(TransportKind.SERIAL, portName, baudRate, format, null, 0, null, null);
	}

	public static TransportConfig simulated() {
		return new TransportConfig(TransportKind.SIMULATED, null, 0, null, null, 0, null, null);
	}

	/** One line for a status bar: {@code localhost:4000}, {@code /dev/ttyUSB0 @ 9600 baud}. */
	public String describe() {
		return switch(kind) {
			case SIMH_PROCESS -> "SimH: " + (simhExecutable == null ? DEFAULT_SIMH_EXECUTABLE : simhExecutable)
				+ (simhConfigFile == null || simhConfigFile.isBlank() ? "" : " " + simhConfigFile);
			case TELNET -> host + ":" + port;
			case SERIAL -> serialPort + " @ " + baudRate + " baud"
				+ (serialFormat == null ? "" : " " + serialFormat);
			case SIMULATED -> "simulated machine";
		};
	}

	/**
	 * Why this configuration cannot be opened, or {@code null} if it can.
	 *
	 * <p>A message rather than an exception, because the caller is a settings dialog deciding
	 * whether to enable its Connect button, not something recovering from a failure.</p>
	 */
	public String validate() {
		return switch(kind) {
			case SIMH_PROCESS -> null;                      // the executable defaults, the config may be empty
			case TELNET -> host == null || host.isBlank()
				? "A telnet connection needs a host"
				: port <= 0 || port > 65535 ? "Port " + port + " is not a port number" : null;
			case SERIAL -> serialPort == null || serialPort.isBlank()
				? "A serial connection needs a port"
				: baudRate <= 0 ? "Baud rate " + baudRate + " is not a baud rate" : null;
			case SIMULATED -> null;
		};
	}

	public boolean isValid() {
		return validate() == null;
	}

	/** The executable to run, defaulted. */
	public String effectiveSimhExecutable() {
		return simhExecutable == null || simhExecutable.isBlank() ? DEFAULT_SIMH_EXECUTABLE : simhExecutable;
	}

	public SerialTransport.SerialFormat effectiveSerialFormat() {
		//-- 8N1 is what a modern USB adapter defaults to; the seven-bit formats are for the
		//-- machines that need them, and the user picks those deliberately.
		return serialFormat == null ? SerialTransport.SerialFormat.N8_1 : serialFormat;
	}
}
