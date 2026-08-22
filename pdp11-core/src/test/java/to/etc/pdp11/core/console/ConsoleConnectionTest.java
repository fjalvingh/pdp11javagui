package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.io.PhysicalTransport;
import to.etc.pdp11.core.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The threading contract of PLAN.md §1, stated as tests: one reader thread, one command thread,
 * and the rule that nothing calls a console from anywhere else.
 */
class ConsoleConnectionTest {
	/** A transport that hands out a canned script and records what was written to it. */
	private static final class ScriptedTransport implements PhysicalTransport {
		private final byte[] m_script;

		private final StringBuilder m_written = new StringBuilder();

		private int m_pos;

		private volatile boolean m_closed;

		private ScriptedTransport(String script) {
			m_script = script.getBytes(StandardCharsets.ISO_8859_1);
		}

		@Override
		public synchronized int read(byte[] buf, int off, int len) {
			while(!m_closed && m_pos >= m_script.length) {
				try {
					wait();
				} catch(InterruptedException x) {
					Thread.currentThread().interrupt();
					return -1;
				}
			}
			if(m_closed)
				return -1;
			int n = Math.min(len, m_script.length - m_pos);
			System.arraycopy(m_script, m_pos, buf, off, n);
			m_pos += n;
			return n;
		}

		@Override
		public synchronized void write(byte[] buf, int off, int len) {
			m_written.append(new String(buf, off, len, StandardCharsets.ISO_8859_1));
		}

		synchronized String written() {
			return m_written.toString();
		}

		@Override
		public boolean isOpen() {
			return !m_closed;
		}

		@Override
		public synchronized void close() {
			m_closed = true;
			notifyAll();
		}

		@Override
		public String describe() {
			return "a script";
		}
	}

	/** Records what it is given, and says which thread it was given it on. */
	private static final class RecordingReceiver implements SerialReceiver {
		private final List<String> m_chunks = new ArrayList<>();

		private final CountDownLatch m_disconnected = new CountDownLatch(1);

		private final AtomicReference<Thread> m_thread = new AtomicReference<>();

		@Override
		public synchronized void onSerialReceive(String data) {
			m_thread.set(Thread.currentThread());
			m_chunks.add(data);
			notifyAll();
		}

		@Override
		public void onDisconnected(Throwable cause) {
			m_disconnected.countDown();
		}

		synchronized String awaitText(String needle) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			for(;;) {
				String all = String.join("", m_chunks);
				if(all.contains(needle))
					return all;
				long remaining = deadline - System.nanoTime();
				if(remaining <= 0)
					throw new AssertionError("Timed out waiting for \"" + needle + "\", got \"" + all + "\"");
				wait(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
			}
		}
	}

	@Test
	void receivedBytesReachTheConsoleAndTheTerminalAlike() throws Exception {
		ScriptedTransport t = new ScriptedTransport("hello\r\n");
		ConsoleConnection c = new ConsoleConnection(t, Logger.NULL);
		StringBuilder terminal = new StringBuilder();
		c.setTerminalSink(s -> {
			synchronized(terminal) {
				terminal.append(s);
			}
		});
		RecordingReceiver r = new RecordingReceiver();
		try {
			c.attach(r);
			r.awaitText("hello");
			synchronized(terminal) {
				assertTrue(terminal.toString().contains("hello"), terminal.toString());
			}
			assertEquals("pdp11-reader", r.m_thread.get().getName());
		} finally {
			c.close();
		}
	}

	@Test
	void everyByteIsMaskedToSevenBitsBeforeAnythingSeesIt() throws Exception {
		//-- Every PDP-11 console is a 7-bit device and the Pascal masks parity off in the read
		//-- itself (SerialIoHubU.pas:843) - the terminal included, which is why this is the
		//-- connection's job and not the decoder's.
		ScriptedTransport t = new ScriptedTransport("ÁÂ\r\n");
		ConsoleConnection c = new ConsoleConnection(t, Logger.NULL);
		RecordingReceiver r = new RecordingReceiver();
		try {
			c.attach(r);
			assertTrue(r.awaitText("AB").contains("AB"));
		} finally {
			c.close();
		}
	}

	@Test
	void aConsoleCallFromTheCommandThreadIsRefusedRatherThanDeadlocked() throws Exception {
		ScriptedTransport t = new ScriptedTransport("");
		ConsoleConnection c = new ConsoleConnection(t, Logger.NULL);
		try {
			c.attach(new RecordingReceiver());
			//-- The trap PLAN.md §1 names: the Pascal issues a console command from inside a
			//-- timer callback, guarded by a nesting counter. Waiting here for a task that
			//-- cannot start until this one returns is a deadlock, so it is an error instead.
			assertThrows(IllegalStateException.class,
				() -> c.run(() -> c.call(() -> "nested")));
			//-- Queueing work from the command thread, on the other hand, is exactly how the
			//-- stop event and the silent-halt lookup are meant to reach it.
			CountDownLatch queued = new CountDownLatch(1);
			c.run(() -> c.execute(queued::countDown));
			assertTrue(queued.await(5, TimeUnit.SECONDS));
		} finally {
			c.close();
		}
	}

	@Test
	void commandsRunOnOneThreadInTheOrderTheyWereGiven() throws Exception {
		ScriptedTransport t = new ScriptedTransport("");
		ConsoleConnection c = new ConsoleConnection(t, Logger.NULL);
		try {
			c.attach(new RecordingReceiver());
			List<String> order = new ArrayList<>();
			AtomicReference<Thread> on = new AtomicReference<>();
			for(int i = 0; i < 20; i++) {
				String label = "task" + i;
				c.run(() -> {
					on.set(Thread.currentThread());
					order.add(label);
				});
			}
			assertEquals(20, order.size());
			assertEquals("task19", order.get(19));
			assertEquals("pdp11-command", on.get().getName());
		} finally {
			c.close();
		}
	}

	@Test
	void aFailedCommandArrivesAsItselfRatherThanWrappedInAnExecutionException() throws Exception {
		ScriptedTransport t = new ScriptedTransport("");
		ConsoleConnection c = new ConsoleConnection(t, Logger.NULL);
		try {
			c.attach(new RecordingReceiver());
			ConsoleException x = assertThrows(ConsoleException.class, () -> c.run(() -> {
				throw new ConsoleException("the machine said no");
			}));
			assertEquals("the machine said no", x.getMessage());
		} finally {
			c.close();
		}
	}

	@Test
	void whatTheUserTypesIsQueuedRatherThanDropped() throws Exception {
		//-- The Pascal drops a keystroke outright whenever the console happens to be busy
		//-- (SerialIoHubU.pas:1000-1002). Queueing it costs nothing here, because the queue
		//-- that serialises it against console commands is already there.
		ScriptedTransport t = new ScriptedTransport("");
		ConsoleConnection c = new ConsoleConnection(t, Logger.NULL);
		try {
			c.attach(new RecordingReceiver());
			c.sendUserInput("hi");
			c.run(() -> {
			});                                             // let the queued write get there
			assertEquals("hi", t.written());
		} finally {
			c.close();
		}
	}

	@Test
	void closingWakesWhoeverIsWaitingForAnAnswerThatCanNoLongerArrive() throws Exception {
		ScriptedTransport t = new ScriptedTransport("");
		ConsoleConnection c = new ConsoleConnection(t, Logger.NULL);
		RecordingReceiver r = new RecordingReceiver();
		c.attach(r);
		c.close();
		assertTrue(r.m_disconnected.await(5, TimeUnit.SECONDS), "the receiver should be told");
		assertFalse(c.isOpen());
		//-- And a quiet close is not a failure worth reporting.
		assertEquals(null, c.getFailure());
	}

	@Test
	void aTransportThatBreaksIsReportedRatherThanSilentlyStopping() throws Exception {
		PhysicalTransport broken = new PhysicalTransport() {
			@Override
			public int read(byte[] buf, int off, int len) throws IOException {
				throw new IOException("the cable came out");
			}

			@Override
			public void write(byte[] buf, int off, int len) {
			}

			@Override
			public boolean isOpen() {
				return true;
			}

			@Override
			public void close() {
			}

			@Override
			public String describe() {
				return "a broken thing";
			}
		};
		ConsoleConnection c = new ConsoleConnection(broken, Logger.NULL);
		RecordingReceiver r = new RecordingReceiver();
		c.attach(r);
		assertTrue(r.m_disconnected.await(5, TimeUnit.SECONDS));
		assertNotNull(c.getFailure());
		c.close();
	}
}
