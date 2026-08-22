package to.etc.pdp11.ui.macro11;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * The Assembler window: a frame around {@link AssemblerPanel}.
 *
 * <p>Replaces three windows - {@code FormMacro11Source}, {@code FormMacro11Listing} and
 * {@code FormMacro11Code} - with one holding three tabs, per PLAN.md §3.</p>
 */
public final class AssemblerWindow extends ToolWindow {
	private final AssemblerPanel m_panel;

	public AssemblerWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new AssemblerPanel(context);
		setContentPane(m_panel);
		setSize(new Dimension(1000, 700));
		setMinimumSize(new Dimension(640, 400));
	}

	public AssemblerPanel getPanel() {
		return m_panel;
	}

	/**
	 * Reopen whatever source was open last time.
	 *
	 * <p>Only on the first show, and only into an empty editor: the Pascal does it from
	 * {@code OnAfterShow} ({@code FormMacro11SourceU.pas:202-209}), which runs on <i>every</i>
	 * show and therefore re-reads the file over the top of any unsaved edits each time the
	 * window is reopened.</p>
	 */
	@Override
	protected void onFirstShow() {
		m_panel.loadLastSource();
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
		context.getWindowManager().register(WindowType.ASSEMBLER, key -> new AssemblerWindow(key, context));
	}
}
