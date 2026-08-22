package to.etc.pdp11.ui.settings;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.ui.AppContext;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import java.awt.Dialog;
import java.awt.Window;
import java.util.function.Consumer;

/**
 * The Settings dialog: pick or describe a connection, then connect with it.
 *
 * <p>Replaces {@code TFormSettings} ({@code FormSettingsU.pas}). The substance is in
 * {@link ConnectionSettingsPanel}; what is here is the three buttons and the one thing a dialog
 * decides - that Connect is only offered for a configuration that could work, which is what
 * {@code ConnectionProfile.validate()} is for.</p>
 *
 * <p>Connecting is not done here. The dialog hands the profile back and closes, because
 * connecting blocks for as long as launching SimH takes and the caller already knows how to do
 * that off the event thread.</p>
 */
public final class SettingsDialog extends JDialog {
	private final ConnectionSettingsPanel m_panel;

	private final JButton m_connect = new JButton("Connect");

	private SettingsDialog(Window owner, AppContext context, Consumer<ConnectionProfile> onConnect) {
		super(owner, "Connection settings", Dialog.ModalityType.APPLICATION_MODAL);
		m_panel = new ConnectionSettingsPanel(context.getSettings());

		JPanel buttons = new JPanel(new MigLayout("insets 8", "[grow][][]", "[]"));
		JButton close = new JButton("Close");
		buttons.add(new JPanel(), "growx");
		buttons.add(m_connect);
		buttons.add(close);

		JPanel content = new JPanel(new MigLayout("fill, insets 0", "[grow]", "[grow][]"));
		content.add(m_panel, "grow, wrap");
		content.add(buttons, "growx");
		setContentPane(content);

		m_panel.setOnChange(() -> m_connect.setEnabled(m_panel.getProfile().isValid()));
		m_connect.addActionListener(e -> {
			ConnectionProfile profile = m_panel.getProfile();
			//-- Saving on connect, deliberately: a connection worth making is one worth being
			//-- able to make again, and the Pascal's settings dialog writes on OK regardless.
			context.getSettings().putProfile(profile);
			context.saveSettings();
			setVisible(false);
			dispose();
			onConnect.accept(profile);
		});
		close.addActionListener(e -> {
			context.saveSettings();
			setVisible(false);
			dispose();
		});
		m_connect.setEnabled(m_panel.getProfile().isValid());
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		pack();
		setLocationRelativeTo(owner);
	}

	public ConnectionSettingsPanel getPanel() {
		return m_panel;
	}

	/** Put it up. Returns when the user has closed it; {@code onConnect} runs first if they connected. */
	public static void open(Window owner, AppContext context, Consumer<ConnectionProfile> onConnect) {
		new SettingsDialog(owner, context, onConnect).setVisible(true);
	}
}
