package to.etc.pdp11.core.io;

import java.io.IOException;

/**
 * A byte pipe to a PDP-11's console: a serial port, a telnet socket, a SimH child process, or
 * a simulated machine in this JVM.
 *
 * <p><b>This is the boundary the whole test strategy rests on.</b> The Pascal fakes already
 * sit exactly here - {@code TFakePDP11Generic} exposes only {@code SerialReadByte} and
 * {@code SerialWriteByte} ({@code FakePDP11GenericU.pas:100-101}), called from
 * {@code SerialIoHubU.pas:815-818} and {@code :865-868} - and everything above runs unchanged
 * whichever one is plugged in. Keeping the boundary in the same place means the ported fakes
 * exercise the real console protocol code, with no PDP-11 and no display. See PLAN.md §1.</p>
 *
 * <h2>Blocking, not polling</h2>
 *
 * <p>{@link #read} blocks. That is the point of the rewrite: the Pascal has no threads at all,
 * so it polls every 10 ms ({@code SerialIoHubU.pas:329-334}), 20 ms for telnet, and pumps
 * {@code Application.ProcessMessages} inside {@code Physical_ReadByte} itself ({@code :829})
 * while waiting. One reader thread per connection blocking here replaces all of it.</p>
 *
 * <h2>Bytes, not text</h2>
 *
 * <p>Nothing below the terminal is text. The consoles are 7-bit devices and the protocol layer
 * masks accordingly, but that masking belongs to the protocol, not here: a transport delivers
 * exactly the bytes that arrived.</p>
 */
public interface PhysicalTransport extends AutoCloseable {
	/**
	 * Read at least one byte, blocking until some arrive.
	 *
	 * @return how many bytes were placed in {@code buf}, always at least 1, or -1 at end of
	 *         stream - the port closed, the process exited, the peer hung up.
	 * @throws IOException if the connection failed. A transport closed by {@link #close()}
	 *                     while a read is in progress returns -1 rather than throwing.
	 */
	int read(byte[] buf, int off, int len) throws IOException;

	/** Write all of it, blocking until it is handed over. */
	void write(byte[] buf, int off, int len) throws IOException;

	default void write(byte[] buf) throws IOException {
		write(buf, 0, buf.length);
	}

	default void write(int b) throws IOException {
		write(new byte[]{(byte) b}, 0, 1);
	}

	boolean isOpen();

	/**
	 * Close, releasing the port, socket or process. Idempotent, and safe to call from another
	 * thread while a {@link #read} is blocked - which is how a reader thread is stopped.
	 */
	@Override
	void close();

	/** How to describe this connection to the user, for the status bar and the log. */
	String describe();
}
