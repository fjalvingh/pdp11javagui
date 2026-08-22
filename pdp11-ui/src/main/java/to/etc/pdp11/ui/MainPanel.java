package to.etc.pdp11.ui;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.ui.terminal.GlassTerminalView;
import to.etc.pdp11.ui.terminal.TerminalView;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;

/**
 * What is inside the main window: the terminal, and the connection status under it.
 *
 * <p>Separate from {@link MainWindow} for one concrete reason: <b>a {@code JPanel} can be built,
 * laid out and painted with no display at all, and a {@code JFrame} cannot.</b> So everything
 * about how this looks - which is most of what can go wrong in a layout - is testable on a build
 * machine, and can be rendered to an image and looked at without putting a window on anybody's
 * screen. The frame keeps what genuinely belongs to a frame: the menu bar, the title, and
 * closing.</p>
 */
public final class MainPanel extends JPanel {
	private final GlassTerminalView m_terminal = new GlassTerminalView();

	private final JLabel m_state = new JLabel();

	private final JLabel m_detail = new JLabel();

	private final JPanel m_statusBar;

	public MainPanel() {
		//-- "gap 0" as well as "insets 0": without it MigLayout leaves its default gap between
		//-- the rows, and a terminal that fills the window should sit flush against the status
		//-- bar rather than showing six pixels of panel background between them.
		super(new MigLayout("fill, insets 0, gap 0", "[grow]", "[grow][]"));
		m_statusBar = buildStatusBar();
		add(m_terminal.getComponent(), "grow, wrap");
		add(m_statusBar, "growx");
		showConnectionState(ConnectionManager.State.DISCONNECTED, "");
	}

	private JPanel buildStatusBar() {
		JPanel bar = new JPanel(new MigLayout("insets 4 8 4 8", "[]20[grow]", "[]"));
		bar.add(m_state);
		m_detail.setForeground(new Color(0x60, 0x60, 0x60));
		bar.add(m_detail, "growx");
		return bar;
	}

	public TerminalView getTerminal() {
		return m_terminal;
	}

	/** The terminal, as its own type, for the two things only it can do. */
	public GlassTerminalView getGlassTerminal() {
		return m_terminal;
	}

	/** The status bar, for a test that wants to know where it ended up. */
	public JPanel getStatusBar() {
		return m_statusBar;
	}

	public String getStateText() {
		return m_state.getText();
	}

	public String getDetailText() {
		return m_detail.getText();
	}

	/** Say where the connection is. Called on the event thread. */
	public void showConnectionState(ConnectionManager.State state, String detail) {
		m_state.setText(switch(state) {
			case DISCONNECTED -> "Not connected";
			case CONNECTING -> "Connecting…";
			case CONNECTED -> "Connected";
			case FAILED -> "Connection failed";
		});
		m_state.setForeground(switch(state) {
			case CONNECTED -> new Color(0x1E, 0x7A, 0x32);
			case FAILED -> new Color(0xA0, 0x20, 0x20);
			default -> Color.DARK_GRAY;
		});
		m_detail.setText(detail == null ? "" : detail);
	}
}
