package to.etc.pdp11.core.io;

import to.etc.pdp11.core.fake.FakePdp11;

import java.io.IOException;

/**
 * Plugs a simulated PDP-11 into the transport boundary, so the real console protocol code can
 * be driven with no hardware, no SimH and no display.
 *
 * <p>This is what PLAN.md §6 calls the primary safety net. It is a transport rather than a
 * special case inside the console layer precisely so that nothing above it can tell the
 * difference - the Pascal has the same property, with {@code connectionInternal} sitting
 * alongside serial and telnet in one {@code case} ({@code SerialIoHubU.pas:812-818}).</p>
 *
 * <h2>Blocking</h2>
 *
 * <p>A fake only produces output in response to input, so a read with nothing waiting has to
 * block until a write puts something there - and be woken by {@link #close()} so a reader
 * thread can be stopped. Reads and writes may therefore come from different threads, which is
 * exactly how the real threading model uses it.</p>
 */
public final class FakeTransport implements PhysicalTransport {
	private final FakePdp11 m_fake;

	/**
	 * Everything that touches the fake holds the fake's own monitor: this transport, and the
	 * scheduler thread that fires its run-to-halt. Using the fake itself rather than a private
	 * lock is what makes those two exclude each other.
	 */
	private final Object m_lock;

	private volatile boolean m_closed;

	private volatile long m_byteDelayMillis;

	public FakeTransport(FakePdp11 fake) {
		m_fake = fake;
		m_lock = fake;
		//-- Output can appear without a keystroke, when a simulated program halts. Wake
		//-- whoever is waiting for it.
		fake.setOutputListener(() -> {
			synchronized(m_lock) {
				m_lock.notifyAll();
			}
		});
	}

	public FakePdp11 getFake() {
		return m_fake;
	}

	/**
	 * Pretend the line is slow.
	 *
	 * <p>The Pascal calls {@code TransmissionWait(1)} after every byte in either direction
	 * ({@code SerialIoHubU.pas:816, 869}) so the fake feels like a serial link. Off by
	 * default, because in a test it is just a delay; useful when watching the terminal.</p>
	 */
	public void setByteDelayMillis(long byteDelayMillis) {
		m_byteDelayMillis = byteDelayMillis;
	}

	@Override
	public int read(byte[] buf, int off, int len) throws IOException {
		int n;
		synchronized(m_lock) {
			while(!m_closed && m_fake.available() == 0) {
				try {
					m_lock.wait();
				} catch(InterruptedException x) {
					Thread.currentThread().interrupt();
					return -1;
				}
			}
			if(m_closed)
				return -1;
			n = 0;
			while(n < len) {
				int b = m_fake.serialReadByte();
				if(b < 0)
					break;
				buf[off + n++] = (byte) b;
			}
			if(n == 0)
				return -1;
		}
		delay(n);
		return n;
	}

	@Override
	public void write(byte[] buf, int off, int len) throws IOException {
		synchronized(m_lock) {
			if(m_closed)
				throw new TransportException("The simulated " + m_fake.getName() + " is closed");
			for(int i = 0; i < len; i++) {
				m_fake.serialWriteByte(buf[off + i] & 0xFF);
			}
			//-- The write is what produces output, so wake whoever is blocked in read().
			m_lock.notifyAll();
		}
		delay(len);
	}

	/**
	 * Sleep for as long as the bytes would have taken on the wire.
	 *
	 * <p>Called by both directions with the fake's monitor <b>not</b> held, which is the whole
	 * point: a real serial line delays only the direction the bytes are travelling in, and
	 * holding the lock across the sleep made one direction's delay block the other one and the
	 * scheduler's run-to-halt callback with it (FABLE-ISSUES #53). Nothing here touches the
	 * fake, so there is nothing for the monitor to protect.</p>
	 */
	private void delay(int byteCount) {
		if(m_byteDelayMillis <= 0)
			return;
		try {
			Thread.sleep(m_byteDelayMillis * byteCount);
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isOpen() {
		return !m_closed;
	}

	@Override
	public void close() {
		synchronized(m_lock) {
			m_closed = true;
			//-- Wake the reader so it can see that it is over.
			m_lock.notifyAll();
		}
	}

	@Override
	public String describe() {
		return "simulated " + m_fake.getName();
	}
}
