package to.etc.pdp11.ui.simh;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.TextChannel;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.SimhConsole;
import to.etc.pdp11.core.console.TerminalProfile;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.terminal.GlassTerminalView;
import to.etc.pdp11.ui.terminal.TerminalStyle;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * SimH's {@code sim>} console: what PDP11GUI says to the simulator, what it answers, and a
 * place to type commands of your own.
 *
 * <h2>Which channel this is, and why it is not the terminal</h2>
 *
 * <p>A SimH connection has two wires. The <b>console</b> channel is the emulated PDP-11's own
 * serial console, and that goes to the main window's terminal, because the rule the application
 * follows is that the main terminal is the machine's console whatever the machine is. The
 * <b>remote</b> channel is this one: SimH's administrative {@code sim>} prompt, which no real
 * PDP-11 ever had and which PDP11GUI drives to examine and deposit memory.</p>
 *
 * <p>The Pascal splits them the other way round - its main terminal shows the {@code sim>}
 * channel and {@code FormSimhConsoleU} shows the machine's console - and then needs a second
 * window, {@code FormSimhRemoteLogU}, for a readable transcript of the protocol. Both of those
 * windows are this one: the machine's console moved to the terminal, and what is left is the
 * transcript, with a command line added to it.</p>
 *
 * <h2>What the transcript is</h2>
 *
 * <p>The raw channel, exactly as it arrives, from {@link ConnectionManager#getProtocolChannel()}.
 * That is deliberate and PLAN.md §3 asks for it: "the terminal shows the entire byte stream, the
 * console's own automated commands and their replies included ... that is how a flaky console
 * gets debugged". Since the main terminal no longer shows this channel, this window is the only
 * place that view exists.</p>
 *
 * <p>Typed commands are marked with a {@code &gt;} line before they are sent, because SimH echoes
 * everything and an echo of something a person typed is otherwise indistinguishable from an echo
 * of something the application issued.</p>
 */
public final class SimhConsolePanel extends JPanel {
	/** How many commands to remember for the up-arrow. Enough for a session's worth of retyping. */
	private static final int HISTORY_SIZE = 100;

	private final AppContext m_context;

	private final GlassTerminalView m_transcript = new GlassTerminalView();

	private final JTextField m_command = new JTextField();

	private final JButton m_halt = new JButton("Halt (^E)");

	private final JButton m_clear = new JButton("Clear");

	private final JLabel m_status = new JLabel();

	/** Most recent last. The up-arrow walks backwards from {@link #m_historyAt}. */
	private final List<String> m_history = new ArrayList<>();

	/** Where the up/down arrows are in {@link #m_history}; its size means "not in the history". */
	private int m_historyAt;

	private final TextChannel.Listener m_channelListener = new TextChannel.Listener() {
		@Override
		public void onText(String text) {
			m_transcript.append(text, TerminalStyle.PDP);
		}

		@Override
		public void onCleared() {
			m_transcript.clear();
		}
	};

	private final ConnectionManager.Listener m_connectionListener =
		(manager, state) -> AppContext.onUi(this::updateEnablement);

	public SimhConsolePanel(AppContext context) {
		super(new MigLayout("fill, insets 0, gap 0", "[grow]", "[][grow][]"));
		m_context = context;

		//-- SimH ends its lines with CR LF and means one line ending, not two.
		m_transcript.setProfile(TerminalProfile.of(true, true));
		//-- Fed from the channel, and commands are sent by the line rather than by the
		//-- keystroke: a stray character on this wire is a phrase the console's scanner has to
		//-- make sense of, and it was never meant to see one.
		m_transcript.setInputEnabled(false);

		add(buildToolBar(), "growx, wrap");
		add(m_transcript.getComponent(), "grow, wrap");
		add(buildCommandBar(), "growx");
		updateEnablement();
	}

	private JPanel buildToolBar() {
		JPanel bar = new JPanel(new MigLayout("insets 4 8 4 8", "[][]20[grow]", "[]"));
		//-- The one control a person needs that a command line cannot give them: a command that
		//-- starts the machine does not come back to a prompt, so there would be nowhere to type
		//-- the command that stops it again.
		m_halt.setToolTipText("Stop the running simulation, as ^E does at a real sim> prompt");
		m_halt.addActionListener(e -> halt());
		m_clear.setToolTipText("Clear this transcript. What SimH has already said is not kept elsewhere.");
		m_clear.addActionListener(e -> m_transcript.clear());
		bar.add(m_halt);
		bar.add(m_clear);
		m_status.setForeground(UiColors.SECONDARY_TEXT);
		bar.add(m_status, "growx");
		return bar;
	}

	private JPanel buildCommandBar() {
		JPanel bar = new JPanel(new MigLayout("insets 4 8 4 8", "[]6[grow]", "[]"));
		JLabel prompt = new JLabel("sim>");
		prompt.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
		m_command.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
		m_command.setToolTipText("A SimH command - \"show dev\", \"att rl0 disk.dsk\", \"set throttle 5M\"");
		m_command.addActionListener(e -> submit());
		//-- Bound rather than listened for, so the text field's own caret handling of Up and Down
		//-- does not also happen.
		m_command.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "history-back");
		m_command.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "history-forward");
		m_command.getActionMap().put("history-back", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				walkHistory(-1);
			}
		});
		m_command.getActionMap().put("history-forward", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				walkHistory(1);
			}
		});
		bar.add(prompt);
		bar.add(m_command, "growx");
		return bar;
	}

	// -------------------------------------------------------------------------------------
	// Showing and hiding
	// -------------------------------------------------------------------------------------

	/**
	 * Start showing the channel, from the beginning of what is still kept.
	 *
	 * <p>The transcript is cleared first because {@link TextChannel#subscribe} replays the whole
	 * buffer: what this window shows is exactly the channel's contents, every time it is opened,
	 * rather than the contents plus whatever it had from last time.</p>
	 */
	public void attach() {
		detach();
		m_transcript.clear();
		m_context.getConnectionManager().getProtocolChannel().subscribe(m_channelListener);
		m_context.getConnectionManager().addListener(m_connectionListener);
		updateEnablement();
	}

	public void detach() {
		m_context.getConnectionManager().getProtocolChannel().unsubscribe(m_channelListener);
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}

	// -------------------------------------------------------------------------------------
	// Commands
	// -------------------------------------------------------------------------------------

	/** The live console if it is a SimH one, or {@code null} - this window drives nothing else. */
	private SimhConsole simh() {
		Console console = m_context.getConnectionManager().getConsole();
		return console instanceof SimhConsole s ? s : null;
	}

	private void updateEnablement() {
		boolean live = simh() != null && m_context.getConnectionManager().isConnected();
		m_command.setEnabled(live);
		m_halt.setEnabled(live);
		if(live) {
			m_status.setText(m_context.getConnectionManager().getProfile().describe());
			m_status.setForeground(UiColors.SECONDARY_TEXT);
		} else if(m_context.getConnectionManager().isConnected()) {
			m_status.setText("Connected to a machine that is not SimH; nothing to say here");
			m_status.setForeground(UiColors.SECONDARY_TEXT);
		} else {
			m_status.setText("Not connected");
			m_status.setForeground(UiColors.ERROR_TEXT);
		}
	}

	private void submit() {
		String cmd = m_command.getText().trim();
		if(cmd.isEmpty()) {
			//-- A bare RETURN makes SimH repeat its last command, which here would be one of
			//-- PDP11GUI's - a deposit, most likely. Never send one.
			return;
		}
		if(simh() == null) {
			note("[not connected to SimH]");
			return;
		}
		m_command.setText("");
		remember(cmd);
		m_transcript.append("> " + cmd + "\n", TerminalStyle.USER);
		m_context.onConsole("SimH command \"" + cmd + "\"", console -> send(console, cmd));
	}

	/**
	 * Send one command, on the command thread, to whichever console the job was handed.
	 *
	 * <p>Package-visible so a test can hand it a console that is not SimH's, which is the thing
	 * this guards against: {@link #submit} asks {@link #simh()} on the event thread and the job
	 * runs later, on another thread, against the console that was live when it was queued. A
	 * reconnect to an 11/44 in between used to arrive as {@code ClassCastException} inside a
	 * failure dialog, which says nothing about what happened (FABLE-ISSUES #49). The console the
	 * job is given is the only one it may talk to, so it is the one that is asked.</p>
	 */
	void send(Console console, String cmd) throws ConsoleException {
		if(!(console instanceof SimhConsole simh)) {
			AppContext.onUi(() -> note("[this is not a SimH connection any more; the command was not sent]"));
			return;
		}
		SimhConsole.CommandResult r = simh.command(cmd);
		if(!r.prompted()) {
			AppContext.onUi(() -> note("[no sim> prompt within the command timeout - "
				+ "the simulation is probably running; Halt (^E) stops it]"));
		}
	}

	/**
	 * Stop a running simulation.
	 *
	 * <p>Two steps, and both are needed. The {@code ^E} goes out of band, because this is the
	 * control whose whole purpose is interrupting a running machine and it cannot be made to
	 * queue behind the command that started it - a typed {@code go} holds the command thread
	 * waiting for a prompt that will not come until something stops the simulation, so a queued
	 * Halt would sit there for the full eight-second command timeout before a byte was sent
	 * (FABLE-ISSUES #48). {@code haltCpu} then runs on the command thread as usual and does the
	 * bookkeeping: it is what tells the rest of the application the machine stopped, and it is
	 * safe to ask for when the machine is already halted.</p>
	 */
	private void halt() {
		SimhConsole simh = simh();
		if(simh == null) {
			note("[not connected to SimH]");
			return;
		}
		m_transcript.append("> ^E\n", TerminalStyle.USER);
		simh.interruptRunningProgram();
		m_context.onConsole("Halt", Console::haltCpu);
	}

	private void note(String text) {
		m_transcript.append(text + "\n", TerminalStyle.SYSTEM);
	}

	// -------------------------------------------------------------------------------------
	// History
	// -------------------------------------------------------------------------------------

	private void remember(String cmd) {
		//-- Not the same command twice in a row: a repeated "show dev" should not need two
		//-- presses of Up to get past.
		if(m_history.isEmpty() || !m_history.get(m_history.size() - 1).equals(cmd))
			m_history.add(cmd);
		while(m_history.size() > HISTORY_SIZE) {
			m_history.remove(0);
		}
		m_historyAt = m_history.size();
	}

	private void walkHistory(int direction) {
		if(m_history.isEmpty())
			return;
		int at = m_historyAt + direction;
		if(at < 0)
			at = 0;
		if(at > m_history.size())
			at = m_history.size();
		m_historyAt = at;
		//-- Past the newest is the empty line the user was typing on, which is where Down ends up.
		m_command.setText(at == m_history.size() ? "" : m_history.get(at));
		m_command.setCaretPosition(m_command.getText().length());
	}

	// -------------------------------------------------------------------------------------
	// For tests
	// -------------------------------------------------------------------------------------

	public JTextField getCommandField() {
		return m_command;
	}

	public JButton getHaltButton() {
		return m_halt;
	}

	public GlassTerminalView getTranscript() {
		return m_transcript;
	}

	public String getStatusText() {
		return m_status.getText();
	}

	public List<String> getHistory() {
		return List.copyOf(m_history);
	}
}
