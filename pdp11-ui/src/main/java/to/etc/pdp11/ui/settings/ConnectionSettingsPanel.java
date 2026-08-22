package to.etc.pdp11.ui.settings;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.conn.TransportConfig;
import to.etc.pdp11.core.conn.TransportKind;
import to.etc.pdp11.core.io.SerialTransport;
import to.etc.pdp11.ui.UiColors;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.CardLayout;
import java.util.List;

/**
 * Where a connection is described: which console dialect, over which transport.
 *
 * <p>This is the decomposition PLAN.md §3 asks for, made visible. {@code FormSettingsU.pas}
 * offers <b>24 flat entries</b> in one combo box - "Physical PDP-11 ODT 18 bit (11/23) over
 * serial port", then the same thing over telnet, and so on for every pair - and then doubles
 * every one of them again for the self-test variants ({@code TConsoleType},
 * {@code ConsoleGenericU.pas:55-74}). Two independent selectors express the same thing without
 * multiplying, and adding a transport later adds one entry rather than seven.</p>
 *
 * <p>A panel rather than the dialog, so the layout can be checked with no display - and so the
 * same editor can be dropped into a first-run wizard later without moving any of it.</p>
 */
public final class ConnectionSettingsPanel extends JPanel {
	private final JComboBox<String> m_profiles = new JComboBox<>();

	private final JTextField m_name = new JTextField(18);

	private final JComboBox<ConsoleProtocol> m_protocol = new JComboBox<>(ConsoleProtocol.values());

	private final JComboBox<TransportKind> m_transport = new JComboBox<>(TransportKind.values());

	private final JPanel m_transportCards = new JPanel(new CardLayout());

	private final JTextField m_simhExecutable = new JTextField(20);

	private final JTextField m_simhConfig = new JTextField(20);

	private final JTextField m_host = new JTextField(16);

	private final JTextField m_port = new JTextField(6);

	private final JComboBox<String> m_serialPort = new JComboBox<>();

	private final JTextField m_baudRate = new JTextField(8);

	private final JComboBox<SerialTransport.SerialFormat> m_serialFormat =
		new JComboBox<>(SerialTransport.SerialFormat.values());

	private final JLabel m_problem = new JLabel(" ");

	private final Settings m_settings;

	/** Set while a profile is being loaded into the widgets, so their events do not write back. */
	private boolean m_loading;

	private Runnable m_onChange = () -> {
	};

	public ConnectionSettingsPanel(Settings settings) {
		super(new MigLayout("fillx, insets 10", "[][grow]", "[][]12[][]8[grow][]"));
		m_settings = settings;

		add(new JLabel("Saved profile:"));
		JPanel profileRow = new JPanel(new MigLayout("insets 0", "[grow][][]", "[]"));
		profileRow.add(m_profiles, "growx");
		JButton save = new JButton("Save");
		JButton delete = new JButton("Delete");
		profileRow.add(save);
		profileRow.add(delete);
		add(profileRow, "growx, wrap");

		add(new JLabel("Name:"));
		add(m_name, "growx, wrap");

		add(new JLabel("Console:"));
		add(m_protocol, "growx, wrap");

		add(new JLabel("Reached over:"));
		add(m_transport, "growx, wrap");

		buildTransportCards();
		add(m_transportCards, "spanx, growx, wrap");

		m_problem.setForeground(UiColors.ERROR_TEXT);
		add(m_problem, "spanx, growx");

		m_protocol.addActionListener(e -> changed());
		m_transport.addActionListener(e -> {
			showTransportCard();
			changed();
		});
		m_profiles.addActionListener(e -> {
			if(!m_loading)
				loadSelectedProfile();
		});
		save.addActionListener(e -> saveProfile());
		delete.addActionListener(e -> deleteProfile());
		for(JTextField f : List.of(m_simhExecutable, m_simhConfig, m_host, m_port, m_baudRate, m_name)) {
			f.addActionListener(e -> changed());
			f.addFocusListener(new java.awt.event.FocusAdapter() {
				@Override
				public void focusLost(java.awt.event.FocusEvent e) {
					changed();
				}
			});
		}
		m_serialPort.addActionListener(e -> changed());
		m_serialFormat.addActionListener(e -> changed());

		refreshProfileList();
		setProfile(settings.currentProfile());
	}

	private void buildTransportCards() {
		JPanel simh = new JPanel(new MigLayout("insets 6", "[][grow][]", "[][]"));
		simh.add(new JLabel("SimH executable:"));
		m_simhExecutable.setToolTipText("Found on PATH unless this is an absolute path");
		simh.add(m_simhExecutable, "growx");
		simh.add(new JLabel(""), "wrap");
		simh.add(new JLabel("Configuration:"));
		simh.add(m_simhConfig, "growx");
		JButton browse = new JButton("...");
		browse.addActionListener(e -> chooseSimhConfig());
		simh.add(browse, "wrap");

		JPanel telnet = new JPanel(new MigLayout("insets 6", "[][grow][][]", "[]"));
		telnet.add(new JLabel("Host:"));
		telnet.add(m_host, "growx");
		telnet.add(new JLabel("Port:"));
		telnet.add(m_port);

		JPanel serial = new JPanel(new MigLayout("insets 6", "[][grow][][][][]", "[]"));
		serial.add(new JLabel("Port:"));
		m_serialPort.setEditable(true);                 // a port that is not plugged in yet is still a port
		serial.add(m_serialPort, "growx");
		serial.add(new JLabel("Baud:"));
		serial.add(m_baudRate);
		serial.add(new JLabel("Format:"));
		serial.add(m_serialFormat);

		JPanel simulated = new JPanel(new MigLayout("insets 6", "[grow]", "[]"));
		simulated.add(new JLabel("<html>A machine simulated inside this program. Needs no hardware, no SimH"
			+ " and no serial port - and it speaks whichever console protocol is selected above.</html>"), "growx");

		m_transportCards.add(simh, TransportKind.SIMH_PROCESS.name());
		m_transportCards.add(telnet, TransportKind.TELNET.name());
		m_transportCards.add(serial, TransportKind.SERIAL.name());
		m_transportCards.add(simulated, TransportKind.SIMULATED.name());
	}

	private void chooseSimhConfig() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("SimH configuration file");
		if(chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			m_simhConfig.setText(chooser.getSelectedFile().getAbsolutePath());
			changed();
		}
	}

	/** Called whenever the edited profile changes, so a dialog can re-enable its buttons. */
	public void setOnChange(Runnable onChange) {
		m_onChange = onChange == null ? () -> {
		} : onChange;
	}

	// -------------------------------------------------------------------------------------
	// The profile being edited
	// -------------------------------------------------------------------------------------

	/** What the widgets currently describe. Never null, and not necessarily valid. */
	public ConnectionProfile getProfile() {
		TransportKind kind = (TransportKind) m_transport.getSelectedItem();
		TransportConfig transport = switch(kind == null ? TransportKind.SIMULATED : kind) {
			case SIMH_PROCESS -> TransportConfig.simhProcess(m_simhExecutable.getText().trim(),
				m_simhConfig.getText().trim());
			case TELNET -> TransportConfig.telnet(m_host.getText().trim(), intOr(m_port.getText(), -1));
			case SERIAL -> TransportConfig.serial(selectedSerialPort(), intOr(m_baudRate.getText(), -1),
				(SerialTransport.SerialFormat) m_serialFormat.getSelectedItem());
			case SIMULATED -> TransportConfig.simulated();
		};
		String name = m_name.getText().trim();
		ConsoleProtocol protocol = (ConsoleProtocol) m_protocol.getSelectedItem();
		return new ConnectionProfile(name.isEmpty() ? "Unnamed" : name,
			protocol == null ? ConsoleProtocol.SIMH : protocol, transport);
	}

	/** Show this profile. Does not save it. */
	public void setProfile(ConnectionProfile profile) {
		m_loading = true;
		try {
			m_name.setText(profile.name());
			m_protocol.setSelectedItem(profile.protocol());
			TransportConfig t = profile.transport();
			m_transport.setSelectedItem(t.kind());
			m_simhExecutable.setText(t.effectiveSimhExecutable());
			m_simhConfig.setText(t.simhConfigFile() == null ? "" : t.simhConfigFile());
			m_host.setText(t.host() == null ? "localhost" : t.host());
			m_port.setText(String.valueOf(t.port() <= 0 ? TransportConfig.DEFAULT_TELNET_PORT : t.port()));
			refreshSerialPorts(t.serialPort());
			m_baudRate.setText(String.valueOf(t.baudRate() <= 0 ? TransportConfig.DEFAULT_BAUD_RATE : t.baudRate()));
			m_serialFormat.setSelectedItem(t.effectiveSerialFormat());
			m_profiles.setSelectedItem(profile.name());
			showTransportCard();
		} finally {
			m_loading = false;
		}
		changed();
	}

	private void changed() {
		if(m_loading)
			return;
		String problem = getProfile().validate();
		m_problem.setText(problem == null ? " " : problem);
		m_onChange.run();
	}

	private void showTransportCard() {
		TransportKind kind = (TransportKind) m_transport.getSelectedItem();
		((CardLayout) m_transportCards.getLayout()).show(m_transportCards,
			(kind == null ? TransportKind.SIMULATED : kind).name());
	}

	// -------------------------------------------------------------------------------------
	// Saved profiles
	// -------------------------------------------------------------------------------------

	private void refreshProfileList() {
		m_loading = true;
		try {
			DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
			for(ConnectionProfile p : m_settings.profiles()) {
				model.addElement(p.name());
			}
			m_profiles.setModel(model);
		} finally {
			m_loading = false;
		}
	}

	private void loadSelectedProfile() {
		Object selected = m_profiles.getSelectedItem();
		if(selected == null)
			return;
		for(ConnectionProfile p : m_settings.profiles()) {
			if(p.name().equals(selected)) {
				setProfile(p);
				return;
			}
		}
	}

	private void saveProfile() {
		ConnectionProfile profile = getProfile();
		m_settings.putProfile(profile);
		refreshProfileList();
		m_profiles.setSelectedItem(profile.name());
		changed();
	}

	private void deleteProfile() {
		Object selected = m_profiles.getSelectedItem();
		if(selected == null)
			return;
		m_settings.profiles().removeIf(p -> p.name().equals(selected));
		refreshProfileList();
		if(m_settings.profiles().isEmpty())
			setProfile(ConnectionProfile.defaultProfile());
		else
			setProfile(m_settings.profiles().get(0));
	}

	private void refreshSerialPorts(String selected) {
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
		//-- What is plugged in now, plus whatever was configured before even if it is not - a
		//-- profile for a machine that is switched off is still the profile you want to keep.
		for(String name : SerialTransport.availablePortNames()) {
			model.addElement(name);
		}
		if(selected != null && !selected.isBlank() && model.getIndexOf(selected) < 0)
			model.addElement(selected);
		m_serialPort.setModel(model);
		if(selected != null && !selected.isBlank())
			m_serialPort.setSelectedItem(selected);
	}

	private String selectedSerialPort() {
		Object o = m_serialPort.getSelectedItem();
		return o == null ? "" : o.toString().trim();
	}

	private static int intOr(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch(NumberFormatException x) {
			return fallback;
		}
	}

	// -------------------------------------------------------------------------------------
	// For tests
	// -------------------------------------------------------------------------------------

	public JComboBox<ConsoleProtocol> getProtocolCombo() {
		return m_protocol;
	}

	public JComboBox<TransportKind> getTransportCombo() {
		return m_transport;
	}

	public JPanel getTransportCards() {
		return m_transportCards;
	}

	public JTextField getNameField() {
		return m_name;
	}

	public JTextField getHostField() {
		return m_host;
	}

	public JTextField getPortField() {
		return m_port;
	}

	public String getProblemText() {
		return m_problem.getText().trim();
	}
}
