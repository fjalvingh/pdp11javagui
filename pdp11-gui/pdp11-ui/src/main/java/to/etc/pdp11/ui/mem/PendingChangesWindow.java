package to.etc.pdp11.ui.mem;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/** The Pending changes window: a frame around {@link PendingChangesPanel}. */
public final class PendingChangesWindow extends ToolWindow {
	private final PendingChangesPanel m_panel;

	public PendingChangesWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new PendingChangesPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(560, 420));
		setMinimumSize(new Dimension(480, 240));
	}

	public PendingChangesPanel getPanel() {
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

	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.PENDING_CHANGES, key -> new PendingChangesWindow(key, context));
	}
}
