package to.etc.pdp11.ui.settings;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.conn.TransportConfig;
import to.etc.pdp11.core.conn.TransportKind;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.JButton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection editor: two selectors instead of twenty-four sentences.
 *
 * <p>The point of the decomposition PLAN.md §3 asks for is that the two axes are independent, so
 * the tests here are mostly about that - every console over every transport that can carry it,
 * and a clear reason when one cannot.</p>
 */
class ConnectionSettingsPanelTest {
	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static ConnectionSettingsPanel panel(Settings settings) {
		return Edt.call(() -> new ConnectionSettingsPanel(settings));
	}

	@Test
	void everyConsoleCanBeChosenSeparatelyFromEveryTransport() {
		Settings settings = new Settings();
		ConnectionSettingsPanel panel = panel(settings);

		assertEquals(ConsoleProtocol.values().length, panel.getProtocolCombo().getItemCount(),
			"seven consoles, listed once each rather than once per transport");
		assertEquals(TransportKind.values().length, panel.getTransportCombo().getItemCount());

		Edt.run(() -> {
			panel.getProtocolCombo().setSelectedItem(ConsoleProtocol.ODT_22);
			panel.getTransportCombo().setSelectedItem(TransportKind.SIMULATED);
		});
		ConnectionProfile profile = panel.getProfile();
		assertEquals(ConsoleProtocol.ODT_22, profile.protocol());
		assertEquals(TransportKind.SIMULATED, profile.transport().kind());
		assertTrue(profile.isValid());
	}

	@Test
	void theTwoCombinationsThatCannotWorkSayWhy() {
		Settings settings = new Settings();
		ConnectionSettingsPanel panel = panel(settings);

		//-- SimH is a program on this machine, so it is not at the end of a serial line.
		Edt.run(() -> {
			panel.getProtocolCombo().setSelectedItem(ConsoleProtocol.SIMH);
			panel.getTransportCombo().setSelectedItem(TransportKind.SERIAL);
		});
		assertFalse(panel.getProfile().isValid());
		assertTrue(panel.getProblemText().contains("serial"), panel.getProblemText());

		//-- And an ODT machine cannot be launched as a process.
		Edt.run(() -> {
			panel.getProtocolCombo().setSelectedItem(ConsoleProtocol.ODT_18);
			panel.getTransportCombo().setSelectedItem(TransportKind.SIMH_PROCESS);
		});
		assertFalse(panel.getProfile().isValid());
		assertTrue(panel.getProblemText().contains("launched as a process"), panel.getProblemText());
	}

	@Test
	void choosingATransportShowsThatTransportsSettings() {
		Settings settings = new Settings();
		ConnectionSettingsPanel panel = panel(settings);
		UiRenderer.layOut(panel, 620, 320);

		Edt.run(() -> panel.getTransportCombo().setSelectedItem(TransportKind.TELNET));
		UiRenderer.layOut(panel, 620, 320);
		assertTrue(panel.getHostField().isShowing() || panel.getHostField().getParent().isVisible(),
			"the telnet card is the one on top");

		Edt.run(() -> panel.getTransportCombo().setSelectedItem(TransportKind.SIMULATED));
		UiRenderer.layOut(panel, 620, 320);
		assertFalse(panel.getHostField().getParent().isVisible(),
			"and it is not, once there is nothing to reach over the network");
	}

	@Test
	void anIncompleteTelnetConfigurationIsRefusedBeforeItIsTried() {
		Settings settings = new Settings();
		ConnectionSettingsPanel panel = panel(settings);
		Edt.run(() -> {
			panel.getTransportCombo().setSelectedItem(TransportKind.TELNET);
			panel.getHostField().setText("");
			panel.getPortField().setText("4000");
		});
		assertFalse(panel.getProfile().isValid());

		Edt.run(() -> panel.getHostField().setText("localhost"));
		assertTrue(panel.getProfile().isValid());

		Edt.run(() -> panel.getPortField().setText("not a port"));
		assertFalse(panel.getProfile().isValid(), "a port that is not a number is not a port");
	}

	@Test
	void aProfileSurvivesBeingSavedAndLoadedAgain(@TempDir Path dir) {
		SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
		Settings settings = store.get();
		settings.putProfile(new ConnectionProfile("The 11/44 in the shed", ConsoleProtocol.PDP1144,
			TransportConfig.telnet("shed", 2323)));
		assertNotEquals("failed to save", String.valueOf(store.save()), "saving must work");

		SettingsStore reloaded = new SettingsStore(dir.resolve("settings.json"));
		ConnectionSettingsPanel panel = panel(reloaded.get());
		ConnectionProfile profile = panel.getProfile();

		assertEquals("The 11/44 in the shed", profile.name());
		assertEquals(ConsoleProtocol.PDP1144, profile.protocol());
		assertEquals("shed", profile.transport().host());
		assertEquals(2323, profile.transport().port());
	}

	@Test
	void aFreshInstallationOffersSomethingThatWorks() {
		//-- No profiles at all, which is what a first run looks like. Offering nothing would mean
		//-- a first run whose only working action is to open this dialog again.
		ConnectionSettingsPanel panel = panel(new Settings());
		assertTrue(panel.getProfile().isValid());
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		Settings settings = new Settings();
		settings.putProfile(new ConnectionProfile("SimH", ConsoleProtocol.SIMH,
			TransportConfig.simhProcess("pdp11", "")));
		ConnectionSettingsPanel panel = panel(settings);
		Path file = UiRenderer.renderToFile(panel, 620, 300,
			Path.of("target", "ui-render", "connection-settings.png"));
		assertTrue(Files.size(file) > 0);
	}

	// ---------------------------------------------------------------------------------------
	// Deleting a saved profile
	// ---------------------------------------------------------------------------------------

	private static Settings settingsWith(String... names) {
		Settings settings = new Settings();
		for(String name : names) {
			settings.putProfile(new ConnectionProfile(name, ConsoleProtocol.ODT_18,
				TransportConfig.telnet("localhost", 2323)));
		}
		return settings;
	}

	/** The Delete button, wherever it has been laid out. */
	private static JButton deleteButton(java.awt.Container c) {
		for(java.awt.Component child : c.getComponents()) {
			if(child instanceof JButton b && "Delete".equals(b.getText()))
				return b;
			if(child instanceof java.awt.Container inner) {
				JButton deeper = deleteButton(inner);
				if(deeper != null)
					return deeper;
			}
		}
		return null;
	}

	/**
	 * Forgetting a saved connection asks first.
	 *
	 * <p>FABLE-ISSUES #41: Delete removed the profile on the click - a serial port, a baud rate,
	 * a SimH configuration file, all typed by hand - with nothing asked and no way back. It is
	 * the one destructive gesture in this panel.</p>
	 */
	@Test
	void deletingASavedProfileAsksFirst() {
		Settings settings = settingsWith("the 11/44 in the shed", "simh");
		ConnectionSettingsPanel panel = panel(settings);

		List<String> asked = new ArrayList<>();
		Edt.run(() -> {
			panel.setConfirmer(question -> {
				asked.add(question);
				return false;                               // the user says no
			});
			panel.getProfileList().setSelectedItem("the 11/44 in the shed");
			deleteButton(panel).doClick();
		});

		assertEquals(1, asked.size(), "it asked");
		assertTrue(asked.get(0).contains("the 11/44 in the shed"), asked.get(0));
		assertEquals(2, settings.profiles().size(), "and kept it, because the answer was no");
	}

	@Test
	void sayingYesDeletesIt() {
		Settings settings = settingsWith("the 11/44 in the shed", "simh");
		ConnectionSettingsPanel panel = panel(settings);

		Edt.run(() -> {
			panel.setConfirmer(question -> true);
			panel.getProfileList().setSelectedItem("the 11/44 in the shed");
			deleteButton(panel).doClick();
		});

		assertEquals(1, settings.profiles().size());
		assertEquals("simh", settings.profiles().get(0).name());
	}
}
