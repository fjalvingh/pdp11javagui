package to.etc.pdp11.ui.scan;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/** The I/O Page Scanner window: a frame around {@link IoPageScannerPanel}. */
public final class IoPageScannerWindow extends ToolWindow {
	private final IoPageScannerPanel m_panel;

	public IoPageScannerWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new IoPageScannerPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(1000, 520));
		setMinimumSize(new Dimension(600, 300));
	}

	public IoPageScannerPanel getPanel() {
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

	@Override
	public void dispose() {
		m_panel.dispose();
		super.dispose();
	}

	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.IO_PAGE_SCANNER,
			key -> new IoPageScannerWindow(key, context));
	}
}
