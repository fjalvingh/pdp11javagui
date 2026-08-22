package to.etc.pdp11.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Somewhere to put "do this in a while".
 *
 * <p>Exists because the fake PDP-11s need one: {@code TFakePDP11Generic} uses a
 * {@code TTimer} to simulate a program running for a second or two and then hitting a HALT
 * ({@code FakePDP11GenericU.pas:96, 191-200}). A Swing timer cannot follow into
 * {@code pdp11-core}, and a bare {@code ScheduledExecutorService} would make every test of the
 * fakes wait in real time for a randomly chosen delay.</p>
 *
 * <p>So: an interface with a real implementation for the application and {@link Manual} for
 * tests, which fires on demand.</p>
 */
public interface Scheduler {
	/** Something scheduled that has not fired yet. */
	interface Handle {
		/** Cancel if it has not already run. Idempotent. */
		void cancel();

		boolean isPending();
	}

	Handle schedule(Runnable task, long delayMillis);

	/** A shared daemon-threaded scheduler for the application. */
	static Scheduler systemScheduler() {
		return SystemScheduler.INSTANCE;
	}

	/**
	 * Runs nothing until a test says so. Deterministic: no wall-clock waiting, and a test can
	 * assert that something <i>is</i> pending before firing it.
	 */
	final class Manual implements Scheduler {
		private final List<ManualHandle> m_pending = new ArrayList<>();

		@Override
		public Handle schedule(Runnable task, long delayMillis) {
			ManualHandle h = new ManualHandle(task, delayMillis);
			m_pending.add(h);
			return h;
		}

		/** Run everything scheduled and not cancelled, in the order it was scheduled. */
		public int fireAll() {
			List<ManualHandle> due = new ArrayList<>(m_pending);
			m_pending.clear();
			int fired = 0;
			for(ManualHandle h : due) {
				if(h.m_cancelled)
					continue;
				h.m_fired = true;
				h.m_task.run();
				fired++;
			}
			return fired;
		}

		public boolean hasPending() {
			return m_pending.stream().anyMatch(h -> !h.m_cancelled);
		}

		/** The delay the last scheduled task asked for, so a test can check the range. */
		public long lastDelayMillis() {
			if(m_pending.isEmpty())
				throw new IllegalStateException("nothing is scheduled");
			return m_pending.get(m_pending.size() - 1).m_delayMillis;
		}

		private static final class ManualHandle implements Handle {
			private final Runnable m_task;

			private final long m_delayMillis;

			private boolean m_cancelled;

			private boolean m_fired;

			private ManualHandle(Runnable task, long delayMillis) {
				m_task = task;
				m_delayMillis = delayMillis;
			}

			@Override
			public void cancel() {
				m_cancelled = true;
			}

			@Override
			public boolean isPending() {
				return !m_cancelled && !m_fired;
			}
		}
	}

	/** The real one: one daemon thread, shared. */
	final class SystemScheduler implements Scheduler {
		private static final SystemScheduler INSTANCE = new SystemScheduler();

		private final ScheduledExecutorService m_executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "pdp11-scheduler");
			//-- Daemon so a forgotten timer cannot keep the JVM alive after the last window
			//-- closes, which is exactly the kind of thing that makes an app look hung on exit.
			t.setDaemon(true);
			return t;
		});

		private SystemScheduler() {
		}

		@Override
		public Handle schedule(Runnable task, long delayMillis) {
			ScheduledFuture<?> f = m_executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
			return new Handle() {
				@Override
				public void cancel() {
					f.cancel(false);
				}

				@Override
				public boolean isPending() {
					return !f.isDone();
				}
			};
		}
	}
}
