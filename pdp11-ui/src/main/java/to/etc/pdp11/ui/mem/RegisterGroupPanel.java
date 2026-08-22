package to.etc.pdp11.ui.mem;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.UiColors;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Window;

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

	public RegisterGroupPanel(AppContext context, MemoryCellGroup group) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow][]"));
		m_context = context;
		m_list = new MemoryCellGroupList(context);

		JPanel bar = new JPanel(new MigLayout("insets 0", "[][]16[][]", "[]"));
		JButton examineAll = new JButton("Examine all");
		JButton examineOne = new JButton("Examine register");
		JButton depositChanged = new JButton("Deposit changed");
		JButton depositAll = new JButton("Deposit all");
		bar.add(examineAll);
		bar.add(examineOne);
		bar.add(depositChanged);
		bar.add(depositAll);

		examineAll.addActionListener(e -> m_list.examineAll(owner()));
		examineOne.addActionListener(e -> m_list.examineCell(m_list.getSelectedCell()));
		depositChanged.addActionListener(e -> m_list.depositAll(true, owner()));
		depositAll.addActionListener(e -> m_list.depositAll(false, owner()));

		m_info.setForeground(UiColors.SECONDARY_TEXT);
		add(bar, "growx, wrap");
		add(m_list, "grow, wrap");
		add(m_info, "growx");

		m_list.setOnUpdate(this::updateInfo);
		m_list.connectTo(group);
		updateInfo();
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

	/** Read everything as soon as the window is looked at, if there is a machine to ask. */
	public void examineIfConnected() {
		if(m_context.getConnectionManager().isConnected())
			m_list.examineAll(owner());
	}

	private Window owner() {
		return SwingUtilities.getWindowAncestor(this);
	}
}
