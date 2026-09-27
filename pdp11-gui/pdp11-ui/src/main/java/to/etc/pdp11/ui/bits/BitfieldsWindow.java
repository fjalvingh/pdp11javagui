package to.etc.pdp11.ui.bits;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/** The Bitfields window: a frame around {@link BitfieldsPanel}. */
public final class BitfieldsWindow extends ToolWindow {
	private final BitfieldsPanel m_panel;

	public BitfieldsWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new BitfieldsPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(720, 380));
		setMinimumSize(new Dimension(480, 200));
	}

	public BitfieldsPanel getPanel() {
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
		context.getWindowManager().register(WindowType.BITFIELDS, key -> new BitfieldsWindow(key, context));
	}
}
