package to.etc.pdp11.ui.log;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * The Log window: a frame around {@link LogPanel}.
 *
 * <p>Thin on purpose. Everything about how the log looks and behaves is in the panel, where it
 * can be laid out and rendered without a display; what is left here is the two things that
 * genuinely belong to a window - its size, and knowing when it is being looked at.</p>
 */
public final class LogWindow extends ToolWindow {
	private final LogPanel m_panel;

	public LogWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new LogPanel((UiLogger) context.getLogger());
		setContentPane(m_panel);
		setSize(new Dimension(1000, 500));
	}

	public LogPanel getPanel() {
		return m_panel;
	}

	@Override
	protected void onShowing() {
		//-- Every show, not only the first: onHiding detaches, so a window that subscribed once
		//-- would come back empty the second time it was opened.
		m_panel.attach();
	}

	@Override
	protected void onHiding() {
		m_panel.detach();
	}

	/** Register this window type with a manager. */
	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.LOG, key -> new LogWindow(key, context));
	}
}
