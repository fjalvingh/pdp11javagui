package to.etc.pdp11.ui;

import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConnectionSupersededException;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.conn.TextChannel;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.TerminalProfile;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.mem.RegisterGroupWindow;
import to.etc.pdp11.ui.settings.SettingsDialog;
import to.etc.pdp11.ui.terminal.TerminalStyle;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowManager;
import to.etc.pdp11.ui.window.WindowType;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The application's main window: the terminal, the connection status, and the menu bar.
 *
 * <p>Replaces {@code TFormMain} ({@code FormMainU.pas}, ~1,900 lines), which is an MDI parent
 * that also owns every window, every console, the machine description, the connection and four
 * global logging procedures. Almost none of that is here: windows belong to the
 * {@link WindowManager}, services to the {@link AppContext}, and this window has a terminal in
 * it and a menu on it.</p>
 *
 * <p>Closing it quits, which is the one MDI-parent behaviour worth keeping.</p>
 */
public final class MainWindow extends JFrame {
	private final AppContext m_context;

	private final MainPanel m_panel = new MainPanel();

	private final JMenu m_windowsMenu = new JMenu("Windows");

	/**
	 * The three ways to start a connection, kept so that they can be turned off while one is
	 * being made. Two connects at once used to race inside {@link ConnectionManager}, and the
	 * loser could tear down the winner's transport on its way out.
	 */
	private JMenuItem m_connectItem;

	private JMenu m_connectToMenu;

	private JMenuItem m_disconnectItem;

	/**
	 * Whether a connect worker is running. Read and written on the event thread only: the state
	 * change to CONNECTING arrives a moment later than the click, and a second click inside that
	 * moment is exactly what the disabled menu items cannot catch.
	 */
	private boolean m_connecting;

	public MainWindow(AppContext context) {
		super("PDP11GUI");
		m_context = context;
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				quit();
			}
		});
		setJMenuBar(buildMenuBar());
		setContentPane(m_panel);
		setMinimumSize(new Dimension(720, 420));
		setSize(new Dimension(1000, 700));
		setLocationByPlatform(true);

		m_panel.getTerminal().setInputListener(this::sendToMachine);
		//-- Subscribed once and for the life of the application, rather than re-wired on every
		//-- connect: the channel outlives any one connection, so nothing is lost between the
		//-- transport opening and this window hearing about it.
		context.getConnectionManager().getMachineConsole().subscribe(
			text -> m_panel.getTerminal().append(text, TerminalStyle.PDP));
		context.getConnectionManager().addListener((manager, state) ->
			SwingUtilities.invokeLater(() -> onConnectionState(manager)));
		context.setFailureHandler((message, cause) -> SwingUtilities.invokeLater(() -> showFailure(message, cause)));
		onConnectionState(context.getConnectionManager());
	}

	/** What is in the window, for a test that wants to know what it showed. */
	public MainPanel getPanel() {
		return m_panel;
	}

	// -------------------------------------------------------------------------------------
	// Menus
	// -------------------------------------------------------------------------------------

	private JMenuBar buildMenuBar() {
		int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

		JMenuItem connect = new JMenuItem("Connect");
		connect.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, menuMask));
		connect.addActionListener(e -> connect(m_context.getSettings().currentProfile()));
		m_connectItem = connect;

		JMenuItem settings = new JMenuItem("Connection settings ...");
		settings.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, menuMask));
		settings.addActionListener(e -> SettingsDialog.open(this, m_context, this::connect));

		JMenuItem disconnect = new JMenuItem("Disconnect");
		m_disconnectItem = disconnect;
		disconnect.addActionListener(e -> {
			m_context.getConnectionManager().disconnect();
			m_panel.getTerminal().append("\n[disconnected]\n", TerminalStyle.SYSTEM);
		});

		JMenuItem quit = new JMenuItem("Quit");
		quit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, menuMask));
		quit.addActionListener(e -> quit());

		JMenu file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		m_connectToMenu = buildConnectToMenu();
		file.add(connect);
		file.add(settings);
		file.add(m_connectToMenu);
		file.add(disconnect);
		file.addSeparator();
		file.add(quit);

		//-- Rebuilt when it opens rather than kept in step by a timer. The Pascal runs a 100 ms
		//-- timer for exactly this ({@code UpdateGUI}, FormMainU.pas:1111-1133).
		m_windowsMenu.setMnemonic(KeyEvent.VK_W);
		m_windowsMenu.addMenuListener(new MenuListener() {
			@Override
			public void menuSelected(MenuEvent e) {
				rebuildWindowsMenu();
			}

			@Override
			public void menuDeselected(MenuEvent e) {
			}

			@Override
			public void menuCanceled(MenuEvent e) {
			}
		});

		JMenuItem about = new JMenuItem("About PDP11GUI");
		about.addActionListener(e -> showAbout());
		JMenu help = new JMenu("Help");
		help.setMnemonic(KeyEvent.VK_H);
		help.add(about);

		JMenuBar bar = new JMenuBar();
		bar.add(file);
		bar.add(m_windowsMenu);
		bar.add(help);
		return bar;
	}

	/**
	 * Connect to a simulated machine of each kind, without configuring anything.
	 *
	 * <p>Earns its place beside the settings dialog: a simulated machine needs no hardware, no
	 * SimH and no serial port, so this is the one menu that always works and the quickest way to
	 * see whether anything is broken.</p>
	 */
	private JMenu buildConnectToMenu() {
		JMenu menu = new JMenu("Connect to simulated");
		for(ConsoleProtocol protocol : ConsoleProtocol.values()) {
			JMenuItem item = new JMenuItem(protocol.getLabel());
			item.addActionListener(e -> connect(ConnectionProfile.simulated(protocol)));
			menu.add(item);
		}
		return menu;
	}

	private void rebuildWindowsMenu() {
		m_windowsMenu.removeAll();
		WindowManager windows = m_context.getWindowManager();
		for(WindowType type : WindowType.values()) {
			if(!windows.isRegistered(type))
				continue;
			if(type == WindowType.REGISTER_GROUP) {
				//-- Not "another one": these are whatever the machine description declares, so
				//-- the menu lists them by name.
				m_windowsMenu.add(buildRegisterGroupMenu());
				continue;
			}
			if(type.isMultiple()) {
				//-- There can be any number of these, so the menu offers another one rather than
				//-- "the" one; the ones that exist are listed below with everything else open.
				JMenuItem item = new JMenuItem("New " + type.getTitle().toLowerCase() + " window");
				item.addActionListener(e -> windows.openNew(type));
				m_windowsMenu.add(item);
				continue;
			}
			JMenuItem item = new JMenuItem(type.getTitle());
			item.addActionListener(e -> windows.open(type));
			m_windowsMenu.add(item);
		}
		//-- Then the ones that are open, which is the replacement for the MDI window list.
		java.util.List<ToolWindow> open = windows.openWindows();
		if(!open.isEmpty()) {
			m_windowsMenu.addSeparator();
			for(ToolWindow w : open) {
				JMenuItem item = new JMenuItem(w.getTitle());
				item.addActionListener(e -> windows.raise(w.key()));
				m_windowsMenu.add(item);
			}
			JMenuItem hideAll = new JMenuItem("Hide all");
			hideAll.addActionListener(e -> windows.hideAll());
			m_windowsMenu.addSeparator();
			m_windowsMenu.add(hideAll);
		}
	}

	/**
	 * One entry per device group in the loaded machine description.
	 *
	 * <p>The Pascal builds these menu items in {@code LoadMachineDescription}
	 * ({@code FormMainU.pas:628-641}) and frees them in {@code UnloadMachineDescription},
	 * matching them back to their windows by caption. Building the menu when it opens means
	 * there is nothing to keep in step: load a different description and the next look at this
	 * menu shows that description's devices.</p>
	 */
	private JMenu buildRegisterGroupMenu() {
		JMenu menu = new JMenu("Device registers");
		java.util.List<to.etc.pdp11.core.mem.MemoryCellGroup> groups = RegisterGroupWindow.groupsOf(m_context);
		if(groups.isEmpty()) {
			JMenuItem none = new JMenuItem("No machine description loaded");
			none.setEnabled(false);
			menu.add(none);
			return menu;
		}
		for(to.etc.pdp11.core.mem.MemoryCellGroup group : groups) {
			JMenuItem item = new JMenuItem(group.getGroupName());
			if(!group.getGroupInfo().isEmpty())
				item.setToolTipText(group.getGroupInfo());
			item.addActionListener(e -> m_context.getWindowManager()
				.open(to.etc.pdp11.ui.window.WindowKey.of(WindowType.REGISTER_GROUP, group.getGroupName())));
			menu.add(item);
		}
		return menu;
	}

	// -------------------------------------------------------------------------------------
	// Connecting
	// -------------------------------------------------------------------------------------

	/**
	 * Connect, on a worker thread.
	 *
	 * <p>{@code connect} launches processes and opens ports and finishes with a console
	 * handshake; doing that on the event thread would freeze the window for as long as it takes
	 * and deadlock outright against the command thread. This is the boundary PLAN.md §1 draws,
	 * and the menu item is the place it gets crossed.</p>
	 */
	private void connect(ConnectionProfile profile) {
		if(m_connecting) {
			//-- Belt and braces with the disabled menu items: this closes the gap between the
			//-- click and CONNECTING coming back, which is where a double-click lands.
			m_context.getLogger().log(LogChannel.OTHER, "Connect ignored: already connecting");
			return;
		}
		m_connecting = true;
		setConnectionControlsEnabled(false);
		m_panel.getTerminal().append("\n[connecting to " + profile.describe() + "]\n", TerminalStyle.SYSTEM);
		Thread worker = new Thread(() -> {
			try {
				m_context.getConnectionManager().connect(profile);
				m_context.getSettings().setLastProfileName(profile.name());
			} catch(ConnectionSupersededException x) {
				//-- Somebody asked for a different connection while this one was being made. The
				//-- one they asked for is the one that is live; there is nothing to report.
				m_context.getLogger().log(LogChannel.OTHER, "Connect abandoned: " + x.getMessage());
			} catch(Exception x) {
				m_context.reportFailure("Could not connect to " + profile.describe(), x);
			} finally {
				SwingUtilities.invokeLater(() -> {
					m_connecting = false;
					onConnectionState(m_context.getConnectionManager());
				});
			}
		}, "pdp11-connect");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Turn the three ways to start or end a connection on or off together.
	 *
	 * <p>Connecting is not cancellable and not re-entrant: while it is happening the machine is
	 * being launched or a port opened, and a second Connect - or a Disconnect - would be racing
	 * that. See {@link ConnectionManager#connect}.</p>
	 */
	private void setConnectionControlsEnabled(boolean enabled) {
		if(m_connectItem != null)
			m_connectItem.setEnabled(enabled);
		if(m_connectToMenu != null)
			m_connectToMenu.setEnabled(enabled);
		if(m_disconnectItem != null)
			m_disconnectItem.setEnabled(enabled);
	}

	private void onConnectionState(ConnectionManager manager) {
		ConnectionManager.State state = manager.getState();
		m_panel.showConnectionState(state, manager.getMessage());
		setConnectionControlsEnabled(!m_connecting && state != ConnectionManager.State.CONNECTING);
		//-- Typing does something only when there is a machine console to type at. On a SimH
		//-- connection PDP11GUI did not launch there is not one, and the only wire is the sim>
		//-- channel, which is not a place to put keystrokes.
		m_panel.getTerminal().setInputEnabled(state == ConnectionManager.State.CONNECTED
			&& manager.hasMachineConsole());

		Console console = manager.getConsole();
		if(console != null) {
			//-- The consoles disagree about line endings, so the terminal is told which one it is
			//-- looking at every time that changes. With a console channel of its own the profile
			//-- is not the console protocol's: what is on that wire is whatever the emulated
			//-- machine prints, and an operating system ends its lines with CR LF.
			m_panel.getTerminal().setProfile(manager.hasSeparateMachineConsole()
				? TerminalProfile.of(true, true)
				: console.terminalProfile());
			setTitle("PDP11GUI - " + console.name());
		} else {
			setTitle("PDP11GUI");
		}
		if(state == ConnectionManager.State.CONNECTED) {
			m_panel.getTerminal().append("[connected: " + manager.getProfile().describe() + "]\n", TerminalStyle.SYSTEM);
			if(!manager.hasMachineConsole()) {
				//-- SimH we did not launch: a sim> channel and nothing behind it. Saying so is
				//-- better than quietly showing sim> traffic here, which is what made this
				//-- window confusing in the first place.
				m_panel.getTerminal().append(
					"[this connection has no machine console; SimH's sim> channel is in the SimH Console window]\n",
					TerminalStyle.SYSTEM);
			}
			//-- SimH is driven over a channel this terminal no longer shows, so the window that
			//-- does show it opens with the connection rather than waiting to be looked for.
			if(manager.getProfile().protocol() == ConsoleProtocol.SIMH)
				m_context.getWindowManager().open(WindowType.SIMH_CONSOLE);
			m_panel.getGlassTerminal().focusTerminal();
		}
	}

	/**
	 * What the user types here goes to the machine's console.
	 *
	 * <p>Which wire that is depends on the connection, and {@link ConnectionManager} is what
	 * knows: SimH's console channel when there is one, the console protocol's own wire when
	 * there is not - queued on the command thread in that case, so a keystroke lands between
	 * console commands rather than in the middle of one. The Pascal drops it instead whenever
	 * the console is busy.</p>
	 */
	private void sendToMachine(String text) {
		m_context.getConnectionManager().writeToMachineConsole(text);
	}

	// -------------------------------------------------------------------------------------
	// Failure, and going away
	// -------------------------------------------------------------------------------------

	private void showFailure(String message, Throwable cause) {
		m_panel.getTerminal().append("[" + message + (cause == null ? "" : ": " + cause.getMessage()) + "]\n",
			TerminalStyle.SYSTEM);
		JOptionPane.showMessageDialog(this,
			message + (cause == null ? "" : "\n\n" + cause.getMessage()),
			"PDP11GUI", JOptionPane.ERROR_MESSAGE);
	}

	private void showAbout() {
		JOptionPane.showMessageDialog(this,
			"PDP11GUI\n\n"
				+ "An IDE for real and simulated PDP-11 computers.\n"
				+ "Java/Swing rewrite of Joerg Hoppe's original.\n\n"
				+ "Settings: " + m_context.getSettingsStore().getFile() + "\n"
				+ "Running on Java " + Runtime.version() + ".",
			"About PDP11GUI", JOptionPane.INFORMATION_MESSAGE);
	}

	/** Remember where everything was, close the connection, and go. */
	public void quit() {
		m_context.getLogger().log(LogChannel.OTHER, "Shutting down");
		m_context.getWindowManager().rememberAllGeometry();
		m_context.getWindowManager().closeAll();
		m_context.getConnectionManager().close();
		m_context.saveSettings();
		dispose();
		System.exit(0);
	}
}
