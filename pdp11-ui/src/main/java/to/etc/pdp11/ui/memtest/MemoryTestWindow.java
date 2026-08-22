package to.etc.pdp11.ui.memtest;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/** The Memory Test window: a frame around {@link MemoryTestPanel}. */
public final class MemoryTestWindow extends ToolWindow {
	private final MemoryTestPanel m_panel;

	public MemoryTestWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new MemoryTestPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(900, 480));
		setMinimumSize(new Dimension(620, 300));
	}

	public MemoryTestPanel getPanel() {
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
		context.getWindowManager().register(WindowType.MEMORY_TEST, key -> new MemoryTestWindow(key, context));
	}
}
