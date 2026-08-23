package to.etc.pdp11.ui.exec;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

/**
 * The Execution Control window: a frame around {@link ExecutionPanel}.
 *
 * <p>Thin, like every window here - the panel is where the layout and the rules live, because a
 * panel can be laid out and rendered with no display.</p>
 */
public final class ExecutionWindow extends ToolWindow {
	private final ExecutionPanel m_panel;

	public ExecutionWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new ExecutionPanel(context);
		setContentPane(m_panel);
		pack();
		//-- Packed, so the layout's own preferred size is exactly the smallest this window is
		//-- worth being: everything in it is a labelled control, and there is nothing to give up.
		setMinimumSize(getSize());
	}

	public ExecutionPanel getPanel() {
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
		context.getWindowManager().register(WindowType.EXECUTION, key -> new ExecutionWindow(key, context));
	}
}
