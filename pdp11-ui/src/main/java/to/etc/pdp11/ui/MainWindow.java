package to.etc.pdp11.ui;

import net.miginfocom.swing.MigLayout;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;

/**
 * The application's main window.
 *
 * <p>Scaffolding only, for now. Per PLAN.md section 3 this window ends up hosting the VT100
 * terminal, the connection status bar and the menu bar, and closing it quits the application.
 * Every other window is a free-floating top-level {@code JFrame} owned by the
 * {@code WindowManager} - there is no MDI desktop pane here and there never will be.</p>
 *
 * <p>Final because the constructor hands {@code this} to menu-item listeners; there is exactly
 * one main window and nothing to gain from subclassing it.</p>
 */
public final class MainWindow extends JFrame {
	public MainWindow() {
		super("PDP11GUI");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setJMenuBar(createMenuBar());
		setContentPane(createContentPane());
		setMinimumSize(new Dimension(640, 400));
		setSize(new Dimension(1000, 700));
		setLocationByPlatform(true);
	}

	private JPanel createContentPane() {
		JPanel panel = new JPanel(new MigLayout("fill, insets 20"));
		JLabel label = new JLabel("<html><center>PDP11GUI<br><br>"
			+ "The terminal, connection status and tool windows arrive in phase 5.</center></html>",
			SwingConstants.CENTER);
		label.setEnabled(false);
		panel.add(label, "grow");
		return panel;
	}

	private JMenuBar createMenuBar() {
		int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

		JMenuItem quit = new JMenuItem("Quit");
		quit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, menuMask));
		//-- Route Quit through the same close path as the window's own close button, so that
		//-- whatever shutdown handling later hangs off windowClosing applies to both.
		quit.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));

		JMenu file = new JMenu("File");
		file.setMnemonic(KeyEvent.VK_F);
		file.add(quit);

		JMenuItem about = new JMenuItem("About PDP11GUI");
		about.addActionListener(e -> showAbout());

		JMenu help = new JMenu("Help");
		help.setMnemonic(KeyEvent.VK_H);
		help.add(about);

		JMenuBar bar = new JMenuBar();
		bar.add(file);
		bar.add(help);
		return bar;
	}

	private void showAbout() {
		JOptionPane.showMessageDialog(this,
			"PDP11GUI\n\n"
				+ "An IDE for real and simulated PDP-11 computers.\n"
				+ "Java/Swing rewrite of Joerg Hoppe's original.\n\n"
				+ "Running on Java " + Runtime.version() + ".",
			"About PDP11GUI", JOptionPane.INFORMATION_MESSAGE);
	}
}
