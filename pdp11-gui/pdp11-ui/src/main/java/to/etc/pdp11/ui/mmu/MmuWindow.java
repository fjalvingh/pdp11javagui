package to.etc.pdp11.ui.mmu;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * The MMU window: a frame around {@link MmuPanel}.
 *
 * <p>Thin, like the others: everything about how the map looks is in the panel, where it can be
 * laid out and rendered with no display.</p>
 */
public final class MmuWindow extends ToolWindow {
	private final MmuPanel m_panel;

	public MmuWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new MmuPanel(context);
		setContentPane(m_panel);
		//-- Wide enough for the status line beside the mode selector to be read rather than elided.
		setSize(new Dimension(900, 520));
		//-- The map is eight columns wide; below this the addresses in it start being elided.
		setMinimumSize(new Dimension(640, 320));
	}

	public MmuPanel getPanel() {
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
		context.getWindowManager().register(WindowType.MMU, key -> new MmuWindow(key, context));
	}
}
