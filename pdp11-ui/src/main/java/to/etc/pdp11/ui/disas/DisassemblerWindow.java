package to.etc.pdp11.ui.disas;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/** The Disassembler window: a frame around {@link DisassemblerPanel}. */
public final class DisassemblerWindow extends ToolWindow {
	private final DisassemblerPanel m_panel;

	public DisassemblerWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new DisassemblerPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(640, 440));
		setMinimumSize(new Dimension(420, 220));
	}

	public DisassemblerPanel getPanel() {
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
		context.getWindowManager().register(WindowType.DISASSEMBLER, key -> new DisassemblerWindow(key, context));
	}
}
