package to.etc.pdp11.ui.dump;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/** The Memory Dumper window: a frame around {@link MemoryDumperPanel}. */
public final class MemoryDumperWindow extends ToolWindow {
	private final MemoryDumperPanel m_panel;

	public MemoryDumperWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new MemoryDumperPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(980, 520));
		setMinimumSize(new Dimension(680, 320));
	}

	public MemoryDumperPanel getPanel() {
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
		context.getWindowManager().register(WindowType.MEMORY_DUMPER, key -> new MemoryDumperWindow(key, context));
	}
}
