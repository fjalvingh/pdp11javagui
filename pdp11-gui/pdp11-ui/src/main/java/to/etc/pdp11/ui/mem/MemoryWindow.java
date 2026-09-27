package to.etc.pdp11.ui.mem;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * A Memory window, of which there may be any number.
 *
 * <p>{@code WindowKey}'s {@code instanceId} is what makes that work - {@code MEMORY/"2"} is a
 * different window from {@code MEMORY/"1"} and remembers its own geometry. The Pascal creates
 * exactly four memory forms in {@code FormCreate} and calls it unlimited; here the number is
 * whatever the user opens.</p>
 */
public final class MemoryWindow extends ToolWindow {
	private final MemoryPanel m_panel;

	public MemoryWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new MemoryPanel(context, key.instanceId().isEmpty() ? "1" : key.instanceId());
		setContentPane(m_panel);
		setSize(new Dimension(900, 460));
		setMinimumSize(new Dimension(600, 260));
	}

	public MemoryPanel getPanel() {
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
		context.getWindowManager().register(WindowType.MEMORY, key -> new MemoryWindow(key, context));
	}
}
