package to.etc.pdp11.ui.mem;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.util.List;

/**
 * One device's registers: the list, and the four buttons that talk to the machine.
 *
 * <p>Ported from {@code TFormMemoryList} ({@code FormMemoryListU.pas}), which is a thin frame
 * around {@code TFrameMemoryCellGroupList} and four buttons that forward to it - and that is
 * what this is, with {@link MemoryCellGroupList} in the frame's place.</p>
 *
 * <p>These windows are created from the machine description, one per device group it declares -
 * seventeen of them in the shipped {@code pdp11.ini}. The Pascal builds them in
 * {@code LoadMachineDescription} ({@code FormMainU.pas:608-645}) and frees them again in
 * {@code UnloadMachineDescription}; here they are keyed {@code REGISTER_GROUP/<name>} and
 * created when somebody opens one.</p>
 */
public final class RegisterGroupPanel extends JPanel {
	private final AppContext m_context;

	private final MemoryCellGroupList m_list;

	private final JLabel m_info = new JLabel();

	private final JButton m_examineAll = new JButton("Examine all");

	private final JButton m_examineOne = new JButton("Examine cell");

	private final JButton m_depositChanged = new JButton("Deposit changed");

	private final JButton m_depositAll = new JButton("Deposit all");

	public RegisterGroupPanel(AppContext context, MemoryCellGroup group) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;
		m_list = new MemoryCellGroupList(context);

		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]16[][]", "[]"));
		bar.add(m_examineAll);
		bar.add(m_examineOne);
		bar.add(m_depositChanged);
		bar.add(m_depositAll);

		m_examineAll.addActionListener(e -> m_list.examineAll(owner()));
		m_examineOne.addActionListener(e -> m_list.examineCell(m_list.getSelectedCell()));
		m_depositChanged.addActionListener(e -> m_list.depositAll(true, owner()));
		m_depositAll.addActionListener(e -> m_list.depositAll(false, owner()));

		m_info.setForeground(UiColors.SECONDARY_TEXT);
		add(bar, "growx, wrap");
		add(m_list, "grow, wrap");
		add(m_info, "growx");

		m_list.setOnUpdate(this::updateInfo);
		//-- Say what is selected; the Bitfields window is watching, and this panel does not
		//-- know that.
		m_list.setOnSelect(context.getCellSelection()::select);
		m_list.connectTo(group);
		updateButtons();
		updateInfo();
	}

	/**
	 * Switch the four buttons off while there is no machine, as every other data window does.
	 *
	 * <p>All four of them are a console round trip and nothing else: with nothing connected the
	 * click used to fall through {@code AppContext.onConsole} into a modal
	 * "Not connected to a machine" dialog, where the same gesture in the Loader or the Scanner
	 * is simply a dead button.</p>
	 */
	private void updateButtons() {
		boolean connected = m_context.getConnectionManager().isConnected();
		m_examineAll.setEnabled(connected);
		m_examineOne.setEnabled(connected);
		m_depositChanged.setEnabled(connected);
		m_depositAll.setEnabled(connected);
	}

	private void updateInfo() {
		MemoryCellGroup group = m_list.getGroup();
		if(group == null) {
			m_info.setText("No registers");
			return;
		}
		int edited = m_list.getEditedCells().size();
		String what = group.getGroupInfo().isEmpty() ? group.getGroupName() : group.getGroupInfo();
		m_info.setText(what + "  -  " + group.size() + " registers"
			+ (edited == 0 ? "" : ", " + edited + " changed and not deposited"));
	}

	public MemoryCellGroupList getList() {
		return m_list;
	}

	public String getInfoText() {
		return m_info.getText();
	}

	/** The controls that need a machine, for a test that wants to know whether they are live. */
	public List<JButton> getMachineControls() {
		return List.of(m_examineAll, m_examineOne, m_depositChanged, m_depositAll);
	}

	/**
	 * Read the machine when this window is shown, and again when a machine arrives.
	 *
	 * <p><b>One policy, chosen once.</b> There used to be three: a Register Group window examined
	 * its whole device on <i>first</i> show and never again - so reconnecting to a different
	 * machine left the previous one's values on the screen with nothing saying so - the MMU
	 * window never read anything and opened showing a map built from registers nobody had
	 * examined, and the Memory windows read on Show and on Enter. A data window that is looking
	 * at a live machine shows what that machine holds; that is what it is for.</p>
	 */
	public void examineIfConnected() {
		if(m_context.getConnectionManager().isConnected())
			m_list.examineAll(owner());
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}

	// -------------------------------------------------------------------------------------
	// Following the connection
	// -------------------------------------------------------------------------------------

	private final ConnectionManager.Listener m_connectionListener =
		(manager, state) -> AppContext.onUi(() -> {
			updateButtons();
			//-- A different machine holds different values. Reading them is the same thing
			//-- opening the window does, for the same reason.
			if(state == ConnectionManager.State.CONNECTED)
				examineIfConnected();
		});

	public void attach() {
		detach();
		m_context.getConnectionManager().addListener(m_connectionListener);
		updateButtons();
		examineIfConnected();
	}

	public void detach() {
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}
}
