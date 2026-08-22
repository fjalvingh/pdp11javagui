package to.etc.pdp11.ui.numbers;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;

/**
 * The Number Converter window: a frame around {@link NumberConverterPanel}.
 *
 * <p>The one window that needs no machine. It is a desk tool - the thing you reach for when a
 * listing says {@code 012737} and the manual says {@code 0xABDF} - so it works whether or not
 * anything is connected, and it has no {@code attach}/{@code detach} because it watches
 * nothing.</p>
 */
public final class NumberConverterWindow extends ToolWindow {
	private final NumberConverterPanel m_panel = new NumberConverterPanel();

	public NumberConverterWindow(WindowKey key, AppContext context) {
		super(key, context);
		setContentPane(m_panel);
		//-- Wide enough for 32 bits of binary in groups of three, which is the widest line here.
		setSize(new Dimension(560, 340));
	}

	public NumberConverterPanel getPanel() {
		return m_panel;
	}

	/** Register this window type with a manager. */
	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.NUMBER_CONVERTER,
			key -> new NumberConverterWindow(key, context));
	}
}
