package to.etc.pdp11.ui.simh;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * The SimH Console window: a frame around {@link SimhConsolePanel}.
 *
 * <p>Opened by hand from the Windows menu, and on its own whenever a SimH connection is made -
 * {@code MainWindow.onConnectionState}. That is not decoration: the main window's terminal shows
 * the emulated machine's console now, so on a SimH connection nothing else on screen shows that
 * a simulator is being driven at all.</p>
 */
public final class SimhConsoleWindow extends ToolWindow {
	private final SimhConsolePanel m_panel;

	public SimhConsoleWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new SimhConsolePanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(900, 520));
	}

	public SimhConsolePanel getPanel() {
		return m_panel;
	}

	@Override
	protected void onShowing() {
		m_panel.attach();
	}

	@Override
	protected void onHiding() {
		m_panel.detach();
	}

	/** Register this window type with a manager. */
	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.SIMH_CONSOLE, key -> new SimhConsoleWindow(key, context));
	}
}
