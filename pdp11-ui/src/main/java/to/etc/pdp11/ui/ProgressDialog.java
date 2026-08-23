package to.etc.pdp11.ui;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.util.ProgressMonitor;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Dialog;
import java.awt.Window;

/**
 * The dialog a long console operation puts up, and the way it gets cancelled.
 *
 * <p>Ported from {@code FormBusyU} plus the {@code BusyForm} calls scattered through
 * {@code TConsoleGeneric} ({@code ConsoleGenericU.pas:539-554}). The important difference is
 * which side of the module boundary it is on: there the protocol layer drives the dialog
 * directly, which is why {@code pdp11-core}'s ancestor cannot be used without a UI. Here the
 * console is handed a {@link ProgressMonitor} and this is one implementation of it.</p>
 *
 * <h2>Threads</h2>
 *
 * <p>Constructed on the event thread; every {@link ProgressMonitor} method is called from the
 * command thread and marshals for itself. {@link #isCancelled()} is written on the EDT and read
 * from the command thread, so it is volatile.</p>
 *
 * <h2>Why it does not appear immediately</h2>
 *
 * <p>{@link ProgressMonitor#DISPLAY_THRESHOLD_MS} - a second, the same as
 * {@code FormBusyU.pas:113-124}. Examining sixteen words over a simulated machine takes no time
 * at all, and a dialog that flashes up and vanishes is worse than no dialog. Over a 9600-baud
 * serial line the same operation takes long enough to want a way out of it, and then it
 * appears.</p>
 *
 * <h2>One monitor, several phases</h2>
 *
 * <p>A {@link ProgressMonitor} is reusable: each of {@code MemoryTester}'s phases is its own
 * {@code begin(…) … finally done()} pair, and the memory test window chains two of them through
 * <b>one</b> dialog. So {@link #begin} resets everything {@link #done} set, and the second phase
 * gets its own dialog and its own Cancel exactly like the first. The one thing it does not reset
 * is {@link #isCancelled()} - see there.</p>
 */
public final class ProgressDialog implements ProgressMonitor {
	private final Window m_owner;

	private final JProgressBar m_bar = new JProgressBar();

	private final JLabel m_label = new JLabel(" ");

	private JDialog m_dialog;

	private Timer m_showTimer;

	private volatile boolean m_cancelled;

	private volatile boolean m_finished;

	private int m_progress;

	public ProgressDialog(Window owner) {
		m_owner = owner;
	}

	@Override
	public void begin(String task, int total) {
		m_progress = 0;
		//-- The phase that just ended set this; without clearing it here the next phase's timer
		//-- fires into a showNow() that bails at its first guard, and a two-phase operation runs
		//-- its second - typically longer - phase with no progress display and no way to cancel.
		m_finished = false;
		AppContext.onUi(() -> {
			m_label.setText(task == null || task.isBlank() ? " " : task);
			m_bar.setMinimum(0);
			m_bar.setMaximum(Math.max(1, total));
			m_bar.setValue(0);
			//-- Whatever the previous phase left armed is about to be replaced.
			if(m_showTimer != null)
				m_showTimer.stop();
			//-- A dialog still standing from a previous phase is relabelled rather than shut and
			//-- rebuilt: the label and the bar above have already re-pointed it at this phase.
			//-- done() closes it between phases today, so this is for a caller that begins twice
			//-- without one in between.
			if(m_dialog != null)
				return;
			//-- Nothing is shown yet. If the work is over before this fires, it never will be.
			m_showTimer = new Timer(DISPLAY_THRESHOLD_MS, e -> showNow());
			m_showTimer.setRepeats(false);
			m_showTimer.start();
		});
	}

	@Override
	public void step(int amount, String note) {
		m_progress += amount;
		int now = m_progress;
		AppContext.onUi(() -> {
			m_bar.setValue(now);
			if(note != null)
				m_label.setText(note);
		});
	}

	/**
	 * Cancelled once, cancelled for the rest of this monitor's life.
	 *
	 * <p>Deliberately <b>not</b> reset by {@link #begin}, unlike everything else it sets. Cancel
	 * means "stop what I asked for", and what the user asked for is the whole operation, not the
	 * phase that happened to be running when they pressed it. A monitor is made per operation -
	 * every caller does {@code new ProgressDialog(owner)} at the point of the button press - so
	 * "the rest of this monitor's life" and "the rest of this operation" are the same thing.</p>
	 */
	@Override
	public boolean isCancelled() {
		return m_cancelled;
	}

	@Override
	public void done() {
		m_finished = true;
		AppContext.onUi(() -> {
			if(m_showTimer != null)
				m_showTimer.stop();
			if(m_dialog != null) {
				m_dialog.setVisible(false);
				m_dialog.dispose();
				m_dialog = null;
			}
		});
	}

	/**
	 * Whether the timer firing now should put a dialog up: only while this phase is still
	 * running, and only if it has not already got one.
	 *
	 * <p>Package-private because this guard is the whole of the reuse bug - it was the thing a
	 * second phase silently failed - and it is the only part of showing a dialog a headless test
	 * can look at.</p>
	 */
	boolean shouldShow() {
		return !m_finished && m_dialog == null;
	}

	/** Whether a show is armed and waiting for the threshold. On the EDT. */
	boolean isShowScheduled() {
		return m_showTimer != null && m_showTimer.isRunning();
	}

	/** On the EDT, from the timer. Builds the dialog only if the work is still going. */
	private void showNow() {
		if(!shouldShow())
			return;
		JPanel content = new JPanel(new MigLayout("fill, insets 12", "[grow]", "[][][]"));
		content.add(m_label, "growx, wrap");
		m_bar.setStringPainted(true);
		content.add(m_bar, "growx, w 320!, wrap");
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> {
			m_cancelled = true;
			cancel.setEnabled(false);
			m_label.setText("Stopping ...");
		});
		content.add(cancel, "align right");

		//-- Modal: while a console operation is in flight the only useful thing to do is wait
		//-- for it or stop it, and every button that could be pressed instead would just queue
		//-- another command behind this one.
		JDialog dialog = new JDialog(m_owner, "Working ...", Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(m_owner);
		m_dialog = dialog;
		//-- setVisible on a modal dialog does not return until it closes, and done() closes it
		//-- from a task queued behind this one. That is an ordinary Swing nested event loop.
		SwingUtilities.invokeLater(() -> {
			if(!m_finished && m_dialog == dialog)
				dialog.setVisible(true);
		});
	}
}
