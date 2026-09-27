package to.etc.pdp11.core.console;

import to.etc.pdp11.core.io.PhysicalTransport;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * A live conversation with one machine: the transport, the thread that reads it, and the thread
 * every console command runs on.
 *
 * <p>Replaces {@code TSerialIoHub}'s dispatching half ({@code SerialIoHubU.pas}) and, with it,
 * the whole of the Pascal's non-threading: a 10 ms poll timer ({@code :329-334}), a 20 ms telnet
 * poll, {@code Application.ProcessMessages} used as a coroutine yield, and the
 * {@code Physical_Poll_Disable} counter standing in for a lock ({@code :182}). See PLAN.md §1
 * for why a faithful port would have been the wrong port here.</p>
 *
 * <h2>Two threads and a rule</h2>
 *
 * <ul>
 * <li>The <b>reader thread</b> blocks on the transport, masks each byte to 7 bits, forwards a
 *     copy to the terminal, and feeds the console's decoder.</li>
 * <li>The <b>command thread</b> - a single-threaded executor - runs every {@link Console} call,
 *     serialized. This <i>is</i> the Pascal's critical section:
 *     {@code BeginCriticalSection}/{@code EndCriticalSection} and their nesting counter
 *     ({@code ConsoleGenericU.pas:392-408}) disappear entirely, because two commands can no
 *     longer be in flight at once.</li>
 * <li>The <b>event thread</b> - Swing's - owns the widgets and calls nothing here directly.
 *     {@link #call} is how it gets work onto the command thread, and it must not be called
 *     from the EDT either, because it waits for the result.</li>
 * </ul>
 */
public final class ConsoleConnection implements AutoCloseable {
	/** Everything the machine sends, for the terminal window. Called on the reader thread. */
	@FunctionalInterface
	public interface ByteSink {
		void accept(String data);
	}

	/**
	 * Told when the reader thread stops for a reason nobody asked for: the machine went away,
	 * SimH exited, the serial line was unplugged, the socket was reset.
	 *
	 * <p>Not called for a {@link #close()}, which is the same event with somebody's finger on
	 * it. Called on the reader thread, once, as the last thing it does.</p>
	 */
	@FunctionalInterface
	public interface LostListener {
		/** @param cause why the reader stopped, or {@code null} for a clean end of stream. */
		void onConnectionLost(ConsoleConnection connection, Throwable cause);
	}

	/** A console operation to run on the command thread. */
	@FunctionalInterface
	public interface ConsoleTask<T> {
		T run() throws ConsoleException;
	}

	/** A console operation with no result. */
	@FunctionalInterface
	public interface ConsoleAction {
		void run() throws ConsoleException;
	}

	private final PhysicalTransport m_transport;

	private final Logger m_logger;

	private final ExecutorService m_executor;

	private volatile Thread m_commandThread;

	private volatile Thread m_readerThread;

	private volatile SerialReceiver m_receiver;

	private volatile ByteSink m_terminalSink;

	private volatile LostListener m_lostListener;

	private volatile boolean m_closed;

	/** Set when the reader stops for a reason worth reporting. */
	private volatile Throwable m_failure;

	/** Held across every transport write, so two threads' bytes never interleave. */
	private final Object m_writeLock = new Object();

	/**
	 * Runs {@link #sendOutOfBand}, created on first use because most connections never need one.
	 * Guarded by this connection's monitor.
	 */
	private ExecutorService m_outOfBandExecutor;

	public ConsoleConnection(PhysicalTransport transport, Logger logger) {
		m_transport = transport;
		m_logger = logger;
		m_executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "pdp11-command");
			//-- Daemon: a console command blocked on a machine that stopped answering must not
			//-- be able to keep the JVM alive after the last window closes.
			t.setDaemon(true);
			m_commandThread = t;
			return t;
		});
	}

	/**
	 * Attach the console and start reading.
	 *
	 * <p>Two steps rather than a constructor argument because the console needs the connection
	 * as much as the connection needs the console - and because nothing should arrive before
	 * there is somewhere to put it.</p>
	 */
	public void attach(SerialReceiver receiver) {
		if(m_receiver != null)
			throw new IllegalStateException("This connection already has a console attached");
		m_receiver = receiver;
		Thread t = new Thread(this::readerLoop, "pdp11-reader");
		t.setDaemon(true);
		m_readerThread = t;
		t.start();
	}

	/**
	 * Where a copy of everything received goes.
	 *
	 * <p>Set by {@link to.etc.pdp11.core.conn.ConnectionManager}, which fans it out to the two
	 * channels the windows read - and set before {@link #attach}, because the console handshake
	 * is the most interesting thing that ever crosses this wire.</p>
	 */
	public void setTerminalSink(ByteSink terminalSink) {
		m_terminalSink = terminalSink;
	}

	/**
	 * Who to tell when this connection dies of its own accord.
	 *
	 * <p>Set by {@link to.etc.pdp11.core.conn.ConnectionManager} before {@link #attach}, because
	 * a machine that never answers its handshake is a machine that can drop the line during it.
	 * Without this the reader thread ends, the answer queue closes, and nothing above ever hears:
	 * the state stays CONNECTED and every window goes on offering buttons that reach a dead
	 * wire.</p>
	 */
	public void setLostListener(LostListener lostListener) {
		m_lostListener = lostListener;
	}

	public PhysicalTransport getTransport() {
		return m_transport;
	}

	public Logger getLogger() {
		return m_logger;
	}

	public String describe() {
		return m_transport.describe();
	}

	public boolean isOpen() {
		return !m_closed && m_transport.isOpen();
	}

	/** Why the reader stopped, if it stopped badly. */
	public Throwable getFailure() {
		return m_failure;
	}

	// -------------------------------------------------------------------------------------
	// The reader thread
	// -------------------------------------------------------------------------------------

	private void readerLoop() {
		byte[] buf = new byte[4096];
		Throwable failure = null;
		try {
			for(;;) {
				int n = m_transport.read(buf, 0, buf.length);
				if(n < 0)
					break;
				//-- Every PDP-11 console is a 7-bit device and the Pascal masks parity off
				//-- here, before anything sees the byte ({@code SerialIoHubU.pas:843}) - the
				//-- terminal included, which is why the mask is not the decoder's job.
				char[] chars = new char[n];
				for(int i = 0; i < n; i++) {
					chars[i] = (char) (buf[i] & 0x7F);
				}
				String data = new String(chars);
				ByteSink sink = m_terminalSink;
				if(sink != null)
					sink.accept(data);
				m_receiver.onSerialReceive(data);
			}
		} catch(Throwable x) {
			if(!m_closed)
				failure = x;
		} finally {
			m_failure = failure;
			if(failure != null)
				m_logger.log(LogChannel.OTHER, "Console connection lost: " + failure);
			//-- The console first, so that anything blocked waiting for an answer is already
			//-- unblocked by the time the layers above start tearing the connection down.
			m_receiver.onDisconnected(failure);
			if(!m_closed) {
				LostListener l = m_lostListener;
				if(l != null)
					l.onConnectionLost(this, failure);
			}
		}
	}

	// -------------------------------------------------------------------------------------
	// The command thread
	// -------------------------------------------------------------------------------------

	public boolean isCommandThread() {
		return Thread.currentThread() == m_commandThread;
	}

	/**
	 * Run a console operation on the command thread and wait for its result.
	 *
	 * <p>This is how the UI - from a worker, never from the EDT - reaches a console.</p>
	 *
	 * @throws IllegalStateException if called from the command thread itself, which would wait
	 *                               for a task that cannot start until this one returns. The
	 *                               Pascal has the same trap and hits it:
	 *                               {@code SilentHaltTimerTimer} issues a console command from
	 *                               inside a timer callback ({@code ConsolePDP11SimHU.pas:300-318}),
	 *                               guarded only by a nesting counter. Use {@link #execute} for
	 *                               that, or just call the console directly - you are already
	 *                               on the right thread.
	 */
	public <T> T call(ConsoleTask<T> task) throws ConsoleException {
		if(isCommandThread())
			throw new IllegalStateException("call() from the command thread would deadlock; call the console directly");
		Future<T> future;
		try {
			future = m_executor.submit((Callable<T>) task::run);
		} catch(RejectedExecutionException x) {
			throw new ConsoleException("The connection to " + describe() + " is closed");
		}
		try {
			return future.get();
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw new ConsoleException("Interrupted while waiting for " + describe());
		} catch(ExecutionException x) {
			Throwable c = x.getCause();
			if(c instanceof ConsoleException ce)
				throw ce;
			if(c instanceof RuntimeException re)
				throw re;
			if(c instanceof Error e)
				throw e;
			throw new ConsoleException(String.valueOf(c), c);
		}
	}

	/** {@link #call} for an operation with no result. */
	public void run(ConsoleAction action) throws ConsoleException {
		call(() -> {
			action.run();
			return null;
		});
	}

	/**
	 * Queue something on the command thread without waiting for it.
	 *
	 * <p>This is how the reader thread gets work onto the command thread - execution-stop
	 * events, and SimH's deferred silent-halt resolution. Serialization then gives the Pascal's
	 * "fire the stop event outside any command sequence" rule ({@code ConsoleGenericU.pas:511-531})
	 * for free.</p>
	 *
	 * @return false when the connection is closing and the task will never run. A caller that
	 *         only wants the work done can ignore that; a caller that has already told the user
	 *         something is happening cannot, because the callbacks it is waiting for live in
	 *         the task that was refused - see {@code AppContext.onConsole}.
	 */
	public boolean execute(Runnable task) {
		try {
			m_executor.execute(() -> {
				try {
					task.run();
				} catch(RuntimeException x) {
					//-- Nothing above is waiting on this one, so an exception has nowhere to go
					//-- and would otherwise reach the executor, which kills the worker thread and
					//-- quietly makes a new one. That loses whatever the task was doing and says
					//-- nothing anywhere. An Error is left alone: that is not ours to swallow.
					m_logger.log(LogChannel.OTHER, "Console task failed on " + describe() + ": " + x);
				}
			});
			return true;
		} catch(RejectedExecutionException x) {
			//-- Closing. The task is gone; whoever queued it decides whether that matters.
			return false;
		}
	}

	// -------------------------------------------------------------------------------------
	// Writing
	// -------------------------------------------------------------------------------------

	/**
	 * Send text to the machine.
	 *
	 * <p>One transport write for the whole string, never a loop of one-byte writes. The Pascal
	 * learned this the hard way ({@code SerialIoHubU.pas:945-957}): with no {@code TCP_NODELAY}
	 * on the telnet socket, a write per character lets Nagle's algorithm and the peer's delayed
	 * ACKs stall each byte by tens of milliseconds - invisible for one keystroke, fatal across
	 * the hundreds of round trips a bulk deposit makes.</p>
	 */
	public void write(String text) throws ConsoleException {
		try {
			//-- Serialized against the out-of-band writer below, which is the only other thread
			//-- that ever writes here. A write hands bytes over and returns; nothing waits for a
			//-- reply while holding this, so the interrupt byte is never delayed by more than
			//-- one command's worth of bytes.
			synchronized(m_writeLock) {
				m_transport.write(text.getBytes(StandardCharsets.ISO_8859_1));
			}
		} catch(IOException x) {
			throw new ConsoleException("Writing to " + describe() + " failed: " + x, x);
		}
	}

	/**
	 * Send an interrupt character at the machine now, ahead of everything the command thread
	 * has queued.
	 *
	 * <p>For {@code ^E} and nothing else. The command thread is strictly ordered on purpose,
	 * and almost everything wants to stay that way - but a stop character is the exception the
	 * Pascal already carves out ({@code ConsolePDP11SimHU.pas:1119-1163} writes it straight at
	 * the hub). The control that exists to interrupt a running machine cannot be made to queue
	 * behind the command that started it: a typed {@code go} holds the command thread waiting
	 * for a prompt that will not come until the machine stops, so Halt would wait out the whole
	 * command timeout before being sent (FABLE-ISSUES #48).</p>
	 *
	 * <p>Sent from a thread of its own rather than from the caller's, so a button may ask for
	 * it: the event thread must not make a transport write, however short. The byte cannot land
	 * in the middle of a command's bytes because {@link #write} takes the same lock, and it is
	 * harmless where it lands in the <i>protocol</i> - SimH ignores {@code ^E} unless it is
	 * mid-{@code RUN}, and the queued {@code haltCpu} that follows does the bookkeeping either
	 * way.</p>
	 *
	 * @return false if there is no longer a connection to send it on
	 */
	public boolean sendOutOfBand(String text) {
		if(m_closed)
			return false;
		outOfBandExecutor().execute(() -> {
			try {
				write(text);
			} catch(ConsoleException x) {
				m_logger.log(LogChannel.OTHER, "Out-of-band write dropped: " + x.getMessage());
			}
		});
		return true;
	}

	private synchronized ExecutorService outOfBandExecutor() {
		ExecutorService e = m_outOfBandExecutor;
		if(e == null) {
			e = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "pdp11-interrupt");
				t.setDaemon(true);
				return t;
			});
			m_outOfBandExecutor = e;
		}
		return e;
	}

	/**
	 * Something the user typed at the terminal, on its way to the machine.
	 *
	 * <p>Queued on the command thread, so it lands between console commands and never in the
	 * middle of one. The Pascal instead <i>drops</i> the keystroke whenever the console is busy
	 * ({@code SerialIoHubU.pas:1000-1002}), which is the kind of thing that makes a terminal
	 * feel broken; queueing costs nothing here because the queue is already there.</p>
	 */
	public void sendUserInput(String text) {
		execute(() -> {
			try {
				write(text);
			} catch(ConsoleException x) {
				m_logger.log(LogChannel.OTHER, "Terminal input dropped: " + x.getMessage());
			}
		});
	}

	@Override
	public void close() {
		m_closed = true;
		//-- Closing the transport is what unblocks the reader thread; it checks m_closed and
		//-- exits quietly rather than reporting the close as a failure.
		m_transport.close();
		m_executor.shutdownNow();
		ExecutorService oob;
		synchronized(this) {
			oob = m_outOfBandExecutor;
		}
		if(oob != null)
			oob.shutdownNow();
		Thread reader = m_readerThread;
		//-- Not when the reader is the one closing us: a connection that noticed its own death
		//-- and told somebody about it ends up here on that thread, and joining itself would
		//-- simply wait out the timeout.
		if(reader != null && reader != Thread.currentThread()) {
			try {
				reader.join(2000);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
