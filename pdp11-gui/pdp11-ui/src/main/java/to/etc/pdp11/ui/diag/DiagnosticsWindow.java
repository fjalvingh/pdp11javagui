package to.etc.pdp11.ui.diag;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * The Diagnostics window: a frame around {@link DiagnosticsPanel}.
 *
 * <p>Needs no machine. Collecting is something done once, before there is a machine on the
 * bench to run anything on, and the library is browsed the same way.</p>
 */
public final class DiagnosticsWindow extends ToolWindow {
	private final DiagnosticsPanel m_panel;

	public DiagnosticsWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new DiagnosticsPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(900, 640));
		//-- Below this the title column, which is the reason to look at the list, goes first.
		setMinimumSize(new Dimension(620, 420));
	}

	public DiagnosticsPanel getPanel() {
		return m_panel;
	}

	@Override
	public void dispose() {
		m_panel.shutdown();
		super.dispose();
	}

	/** Register this window type with a manager. */
	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.DIAGNOSTICS, key -> new DiagnosticsWindow(key, context));
	}
}
