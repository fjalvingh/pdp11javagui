package to.etc.pdp11.ui;

import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConnectionSupersededException;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.conn.TextChannel;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.TerminalProfile;
import to.etc.pdp11.core.util.AppVersion;
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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
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

	/** The Help menu's manual entry, so a test can press what the user presses. */
	private JMenuItem m_manualItem;

	/**
	 * Whether a connect <i>or a disconnect</i> worker is running. Read and written on the event
	 * thread only: the state change arrives a moment later than the click, and a second click
	 * inside that moment is exactly what the disabled menu items cannot catch.
	 */
	private boolean m_changingConnection;

	/**
	 * What the connection last was, so that losing one can be told apart from failing to make
	 * one. A failed attempt has already said so, in the terminal and in a dialog; a connection
	 * that died under the user has said nothing anywhere.
	 */
	private ConnectionManager.State m_lastState = ConnectionManager.State.DISCONNECTED;

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
		//-- And then wherever it was last time, if that is still a place. Every tool window came
		//-- back where it was left; the frame they all sit in did not.
		m_context.getWindowManager().applyGeometry(this, WindowManager.MAIN_WINDOW_KEY);

		m_panel.getTerminal().setInputListener(this::sendToMachine);
		//-- Subscribed once and for the life of the application, rather than re-wired on every
		//-- connect: the channel outlives any one connection, so nothing is lost between the
		//-- transport opening and this window hearing about it.
		context.getConnectionManager().getMachineConsole().subscribe(
			text -> m_panel.getTerminal().append(text, TerminalStyle.PDP));
		context.getConnectionManager().addListener((manager, state) ->
			SwingUtilities.invokeLater(() -> onConnectionState(manager)));
		context.setFailureHandler((message, cause) -> SwingUtilities.invokeLater(() -> showFailure(message, cause)));
		//-- The window that can put a dialog on the screen is the one that owns the asking; see
		//-- AppContext.confirmDiscard.
		context.setDiscardConfirmer(this::askBeforeDiscarding);
		onConnectionState(context.getConnectionManager());
	}

	/** What is in the window, for a test that wants to know what it showed. */
	/**
	 * The Windows menu, rebuilt as if it had just been opened.
	 *
	 * <p>For a test: the menu is built on {@code menuSelected} rather than kept in step by a
	 * timer, so asking what is in it means asking for it to be built first.</p>
	 */
	public JMenu getWindowsMenuRebuilt() {
		rebuildWindowsMenu();
		return m_windowsMenu;
	}

	/** The "Connect to simulated" submenu, so a test can pick a machine the way the user does. */
	public JMenu getConnectToSimulatedMenu() {
		return m_connectToMenu;
	}

	/** The Connect menu item, for a test that asks whether it is on offer. */
	public JMenuItem getConnectItem() {
		return m_connectItem;
	}

	/** The Disconnect menu item, so a test can press what the user presses. */
	public JMenuItem getDisconnectItem() {
		return m_disconnectItem;
	}

	/** The Help menu's User manual item. */
	public JMenuItem getManualItem() {
		return m_manualItem;
	}

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

		//-- No accelerator. Ctrl/Cmd+Comma is the macOS preferences convention and means nothing
		//-- on the Linux this port is for, and opening the connection dialog is not something
		//-- anybody does often enough to reach for a key (FABLE-ISSUES #63).
		JMenuItem settings = new JMenuItem("Connection settings ...");
		settings.addActionListener(e -> SettingsDialog.open(this, m_context, this::connect));

		JMenuItem disconnect = new JMenuItem("Disconnect");
		m_disconnectItem = disconnect;
		disconnect.addActionListener(e -> disconnect());

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

		JMenuItem manual = new JMenuItem("User manual");
		//-- F1, which is what every application on every platform this runs on binds help to.
		manual.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
		manual.addActionListener(e -> openManual());
		m_manualItem = manual;

		JMenuItem about = new JMenuItem("About PDP11GUI");
		about.addActionListener(e -> showAbout());
		JMenu help = new JMenu("Help");
		help.setMnemonic(KeyEvent.VK_H);
		help.add(manual);
		help.addSeparator();
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
		//-- Then the ones that exist, which is the replacement for the MDI window list.
		java.util.List<ToolWindow> open = windows.openWindows();
		//-- Hidden ones are listed too, and this is not a nicety. Closing a tool window hides it,
		//-- and the entries above reopen a window of a *type* - so a singleton comes back either
		//-- way, but a closed "Memory - 1" does not: "New memory window" builds "Memory - 2"
		//-- beside it, because the hidden one still holds id 1. Listing only the visible ones left
		//-- it unreachable for the rest of the session, still on the propagation bus, still
		//-- holding its range and its edits.
		java.util.List<ToolWindow> hidden = windows.hiddenWindows();
		if(!open.isEmpty() || !hidden.isEmpty()) {
			m_windowsMenu.addSeparator();
			for(ToolWindow w : open) {
				JMenuItem item = new JMenuItem(w.getTitle());
				item.addActionListener(e -> windows.raise(w.key()));
				m_windowsMenu.add(item);
			}
			for(ToolWindow w : hidden) {
				//-- Said out loud rather than shown by a checkmark: choosing one of these brings
				//-- it back, and choosing one of the above only raises it. Two gestures that read
				//-- the same would be worse than a word.
				JMenuItem item = new JMenuItem(w.getTitle() + " (closed)");
				item.addActionListener(e -> windows.raise(w.key()));
				m_windowsMenu.add(item);
			}
			m_windowsMenu.addSeparator();
			if(!hidden.isEmpty()) {
				JMenuItem showAll = new JMenuItem("Show all");
				showAll.addActionListener(e -> windows.showAll());
				m_windowsMenu.add(showAll);
			}
			if(!open.isEmpty()) {
				JMenuItem hideAll = new JMenuItem("Hide all");
				hideAll.addActionListener(e -> windows.hideAll());
				m_windowsMenu.add(hideAll);
			}
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
		if(m_changingConnection) {
			//-- Belt and braces with the disabled menu items: this closes the gap between the
			//-- click and CONNECTING coming back, which is where a double-click lands.
			m_context.getLogger().log(LogChannel.OTHER, "Connect ignored: already connecting");
			return;
		}
		setChangingConnection(true);
		m_panel.getTerminal().append("\n[connecting to " + profile.describe() + "]\n", TerminalStyle.SYSTEM);
		Thread worker = new Thread(() -> {
			try {
				m_context.getConnectionManager().connect(profile);
				//-- On the event thread, because that is who owns Settings. This worker used to
				//-- write it directly while the EDT was editing profiles, moving windows and
				//-- handing the same object to Gson in save() - an unsynchronized object with a
				//-- writer nobody had noticed (FABLE-ISSUES #44).
				AppContext.onUi(() -> m_context.getSettings().setLastProfileName(profile.name()));
			} catch(ConnectionSupersededException x) {
				//-- Somebody asked for a different connection while this one was being made. The
				//-- one they asked for is the one that is live; there is nothing to report.
				m_context.getLogger().log(LogChannel.OTHER, "Connect abandoned: " + x.getMessage());
			} catch(Exception x) {
				m_context.reportFailure("Could not connect to " + profile.describe(), x);
			} finally {
				SwingUtilities.invokeLater(() -> {
					setChangingConnection(false);
					onConnectionState(m_context.getConnectionManager());
				});
			}
		}, "pdp11-connect");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Disconnect, on a worker thread.
	 *
	 * <p>Closing is as slow as opening and for the same reasons: {@code close()} waits up to two
	 * seconds for the reader thread to notice, then tears the transport down - which for SimH is
	 * killing a child process and waiting for it, and for a serial line is closing the port. Done
	 * on the event thread, as this was, a wedged transport freezes the whole window for several
	 * seconds with no way to tell that from a crash. Same boundary as {@link #connect}, same
	 * worker.</p>
	 *
	 * <p>The terminal is told afterwards rather than before, because before is a claim and after
	 * is a fact.</p>
	 */
	private void disconnect() {
		if(m_changingConnection) {
			m_context.getLogger().log(LogChannel.OTHER, "Disconnect ignored: already connecting or disconnecting");
			return;
		}
		setChangingConnection(true);
		Thread worker = new Thread(() -> {
			try {
				m_context.getConnectionManager().disconnect();
			} catch(RuntimeException x) {
				m_context.reportFailure("Could not disconnect cleanly", x);
			} finally {
				SwingUtilities.invokeLater(() -> {
					setChangingConnection(false);
					m_panel.getTerminal().append("\n[disconnected]\n", TerminalStyle.SYSTEM);
					onConnectionState(m_context.getConnectionManager());
				});
			}
		}, "pdp11-disconnect");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * A connect or disconnect worker has started or finished: remember it, show it, and turn the
	 * menu items to match.
	 *
	 * <p>The wait cursor is the busy affordance. Every other long operation in the application
	 * gets a {@code ProgressDialog} with a Cancel in it, and this one deliberately does not: a
	 * connection being made is not cancellable - SimH is being launched, or a port opened, or a
	 * handshake is in flight - so a Cancel button would be a lie, and the dialog without one
	 * would be a modal window whose only content is the word "Working". What is left that is
	 * honest is the cursor, the greyed-out menu and the status bar, which says which machine is
	 * being connected to.</p>
	 */
	private void setChangingConnection(boolean changing) {
		m_changingConnection = changing;
		setCursor(changing ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
		updateConnectionControls();
	}

	/**
	 * Offer the three ways to start or end a connection when they mean something.
	 *
	 * <p>Connecting is not re-entrant: while one is happening the machine is being launched or a
	 * port opened, and a second Connect - or a Disconnect - would be racing that. See
	 * {@link ConnectionManager#connect}.</p>
	 *
	 * <p>Disconnect is offered only when there <i>is</i> a connection. It used to be enabled at
	 * all times, so choosing it with nothing connected started a worker, tore down nothing and
	 * printed "[disconnected]" in the terminal - the application answering a question about a
	 * machine it does not have (FABLE-ISSUES #42). Connect stays enabled while connected,
	 * because connecting again is how the user moves to another machine and
	 * {@code connect()} is documented to double as a reconnect.</p>
	 */
	private void updateConnectionControls() {
		boolean idle = !m_changingConnection;
		if(m_connectItem != null)
			m_connectItem.setEnabled(idle);
		if(m_connectToMenu != null)
			m_connectToMenu.setEnabled(idle);
		if(m_disconnectItem != null)
			m_disconnectItem.setEnabled(idle && m_lastState == ConnectionManager.State.CONNECTED);
	}

	private void onConnectionState(ConnectionManager manager) {
		ConnectionManager.State state = manager.getState();
		ConnectionManager.State was = m_lastState;
		m_lastState = state;
		m_panel.showConnectionState(state, manager.getMessage());
		updateConnectionControls();
		if(state == ConnectionManager.State.FAILED && was == ConnectionManager.State.CONNECTED) {
			//-- The machine went away rather than the user going anywhere. Nothing else says so:
			//-- the windows simply grey out, which on its own looks like the application broke.
			m_panel.getTerminal().append("\n[" + manager.getMessage() + "]\n", TerminalStyle.SYSTEM);
		}
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
		//-- Only on arrival, not on every report of the state. Two things call this for one
		//-- connection - the ConnectionManager listener when the state changes, and the connect
		//-- worker when it has finished - and both used to run everything below, so connecting
		//-- printed "[connected: ...]" twice.
		if(state == ConnectionManager.State.CONNECTED && was != ConnectionManager.State.CONNECTED) {
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
		JOptionPane.showMessageDialog(dialogOwner(),
			message + (cause == null ? "" : "\n\n" + cause.getMessage()),
			"PDP11GUI", JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * The window a message about a failure should belong to.
	 *
	 * <p>These messages come from anywhere - a tool window is a free-floating frame of its own -
	 * but the handler lives here, and a dialog owned by the main window raises the main window
	 * when it is dismissed. The tool window that asked then goes behind it and looks as if it
	 * closed itself. So the dialog belongs to whatever window the user is actually looking at,
	 * and only falls back to this one when that cannot be told.</p>
	 */
	private Window dialogOwner() {
		Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		return active != null && active.isShowing() ? active : this;
	}

	/**
	 * Open the manual in the user's browser.
	 *
	 * <p>On a worker, because opening a browser means launching a program and waiting to see
	 * whether it took - which on a cold browser is seconds - and every other thing in this window
	 * that starts a process does it off the event thread for the same reason. {@link Browser} is
	 * where the several ways of doing it live.</p>
	 *
	 * <p>A machine where none of them work is not a failure worth an error dialog: the manual is a
	 * web page and the address is short. It goes on the clipboard and into a dialog that says
	 * so.</p>
	 */
	private void openManual() {
		String url = manualUrl(AppVersion.get());
		m_context.getLogger().log(LogChannel.OTHER, "Opening the manual at %s", url);
		Thread worker = new Thread(() -> {
			if(!Browser.open(url, m_context.getLogger()))
				SwingUtilities.invokeLater(() -> showManualAddress(url));
		}, "pdp11-manual");
		worker.setDaemon(true);
		worker.start();
	}

	/** Say where the manual is, and put it somewhere it can be pasted from. */
	private void showManualAddress(String url) {
		String copied = "";
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(url), null);
			copied = "\n\nThe address has been copied to the clipboard.";
		} catch(RuntimeException x) {
			//-- A clipboard can be unavailable or held by something else. The address is in the
			//-- dialog either way, which is the part that matters.
			m_context.getLogger().log(LogChannel.OTHER, "Cannot reach the clipboard: %s", x);
		}
		JOptionPane.showMessageDialog(this,
			"No browser could be opened here. The manual is at:\n\n" + url + copied,
			"PDP11GUI", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * The manual for a given version of this application, on GitHub.
	 *
	 * <p>A release points at <i>its own tag</i> rather than at {@code main}, so a jar somebody
	 * downloaded a year ago opens the manual that was written for it instead of one describing
	 * windows it does not have. That is the same rule the version stamping follows: a build says
	 * what it is, and never what the branch has become since. A development build has no tag to
	 * point at and gets {@code main}.</p>
	 *
	 * <p>Package-visible for a test - the manifest is absent in a test run, so
	 * {@link AppVersion#get()} can only ever be seen to answer {@code development build} there.</p>
	 */
	static String manualUrl(String version) {
		String ref = AppVersion.DEVELOPMENT.equals(version) ? "main" : "v" + version;
		return "https://github.com/fjalvingh/pdp11javagui/blob/" + ref + "/manual/README.md";
	}

	private void showAbout() {
		JOptionPane.showMessageDialog(this,
			"PDP11GUI " + AppVersion.get() + "\n\n"
				+ "An IDE for real and simulated PDP-11 computers.\n"
				+ "Java/Swing rewrite of Joerg Hoppe's original.\n\n"
				+ "Settings: " + m_context.getSettingsStore().getFile() + "\n"
				+ "Running on Java " + Runtime.version() + ".",
			"About PDP11GUI", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Yes/No on something the user has not saved. Always on the event thread, because every
	 * caller is a button or a menu item.
	 */
	private boolean askBeforeDiscarding(String question) {
		return JOptionPane.showConfirmDialog(this, question, "PDP11GUI",
			JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
	}

	/** Remember where everything was, close the connection, and go. */
	public void quit() {
		//-- Before anything is torn down: this is the last chance to keep the source, and the
		//-- window that holds it may not even be open.
		if(!m_context.getAssembler().confirmDiscard("quit"))
			return;
		m_context.getLogger().log(LogChannel.OTHER, "Shutting down");
		m_context.getWindowManager().rememberGeometry(this, WindowManager.MAIN_WINDOW_KEY);
		m_context.getWindowManager().rememberAllGeometry();
		m_context.getWindowManager().closeAll();
		m_context.saveSettings();
		//-- Everything above is the event thread's own work and is quick. Closing the connection
		//-- is not - see disconnect() - so the windows go first and the machine is torn down
		//-- behind a screen that is already empty, rather than in front of one that has stopped
		//-- repainting. Not a daemon: the exit is its last statement, and a daemon could be
		//-- killed halfway through leaving SimH orphaned.
		dispose();
		Thread worker = new Thread(() -> {
			try {
				m_context.getConnectionManager().close();
			} catch(RuntimeException x) {
				m_context.getLogger().log(LogChannel.OTHER, "Shutdown: " + x);
			}
			System.exit(0);
		}, "pdp11-shutdown");
		worker.start();
	}
}
