package to.etc.pdp11.ui.microcode;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * The microcode window: a frame around {@link MicrocodePanel}.
 */
public final class MicrocodeWindow extends ToolWindow {
	private final MicrocodePanel m_panel;

	public MicrocodeWindow(WindowKey key, AppContext context) {
		super(key, context);
		m_panel = new MicrocodePanel(context);
		//-- The chosen board revision has to be visible without opening the combo: two revisions
		//-- of one KD11-B differ in 20 bits and in nothing else, so the wrong one looks right.
		m_panel.setTitleListener(this::setTitle);
		setContentPane(m_panel);
		//-- Tall: the rows are all one microword - 43 of them for the 11/44 - so scrolling between
		//-- two of its fields to compare them is the thing to avoid.
		setSize(new Dimension(900, 760));
		setMinimumSize(new Dimension(640, 360));
	}

	public MicrocodePanel getPanel() {
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
		context.getWindowManager().register(WindowType.MICROCODE, key -> new MicrocodeWindow(key, context));
	}
}
