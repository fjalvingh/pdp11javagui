package to.etc.pdp11.ui.mem;

import to.etc.pdp11.core.machine.MachineDescription;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * A window per device group in the machine description, keyed by the group's name.
 *
 * <p>{@code REGISTER_GROUP/"MMU"} is the case PLAN.md §3 gives for {@code instanceId} existing
 * at all: these windows are not a fixed set, they are whatever the loaded description declares.
 * The shipped {@code pdp11.ini} declares seventeen.</p>
 */
public final class RegisterGroupWindow extends ToolWindow {
	private final RegisterGroupPanel m_panel;

	private RegisterGroupWindow(WindowKey key, AppContext context, MemoryCellGroup group) {
		super(key, context);
		m_panel = new RegisterGroupPanel(context, group);
		setContentPane(m_panel);
		setSize(new Dimension(760, 320));
		setMinimumSize(new Dimension(460, 180));
	}

	public RegisterGroupPanel getPanel() {
		return m_panel;
	}

	@Override
	protected void onFirstShow() {
		//-- Reading a device's registers is what opening its window means. Once, on first show:
		//-- after that the window keeps what it has until somebody asks for more.
		m_panel.examineIfConnected();
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
		m_panel.detach();
		super.dispose();
	}

	/**
	 * Register the type. The factory looks the group up by name at open time rather than
	 * capturing it, so a window opened after a different description was loaded gets that
	 * description's group - or refuses, rather than showing cells that belong to nothing.
	 */
	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.REGISTER_GROUP, key -> {
			MemoryCellGroup group = context.getMemoryCellGroups().findByName(key.instanceId());
			if(group == null)
				throw new IllegalStateException("No register group named '" + key.instanceId()
					+ "' in the loaded machine description");
			return new RegisterGroupWindow(key, context, group);
		});
	}

	/** The groups the loaded machine description declares, in the order it declares them. */
	public static List<MemoryCellGroup> groupsOf(AppContext context) {
		List<MemoryCellGroup> l = new ArrayList<>();
		for(MemoryCellGroup g : context.getMemoryCellGroups().getGroups()) {
			if(MachineDescription.USAGE_TAG.equals(g.getUsageTag()))
				l.add(g);
		}
		return l;
	}
}
