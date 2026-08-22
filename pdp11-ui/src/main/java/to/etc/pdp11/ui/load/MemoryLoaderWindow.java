package to.etc.pdp11.ui.load;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/** The Memory Loader window: a frame around {@link MemoryLoaderPanel}. */
public final class MemoryLoaderWindow extends ToolWindow {
	private final MemoryLoaderPanel m_panel;

	public MemoryLoaderWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new MemoryLoaderPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(980, 520));
		setMinimumSize(new Dimension(680, 320));
	}

	public MemoryLoaderPanel getPanel() {
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
		context.getWindowManager().register(WindowType.MEMORY_LOADER, key -> new MemoryLoaderWindow(key, context));
	}
}
