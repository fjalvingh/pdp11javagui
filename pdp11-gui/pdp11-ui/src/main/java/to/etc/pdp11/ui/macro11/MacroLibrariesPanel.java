package to.etc.pdp11.ui.macro11;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.macro11.asm.MacroLibrary;
import to.etc.pdp11.ui.UiColors;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which macro libraries every assembly searches for {@code .MCALL}, in order.
 *
 * <p>A library is checked when it is added, so that a file that is not one is turned away here
 * and says why, rather than failing the next assembly. A source can still name more with
 * {@code .LIBRARY}, which are searched first.</p>
 */
public final class MacroLibrariesPanel extends JPanel {
	private final DefaultListModel<Path> m_model = new DefaultListModel<>();

	private final JList<Path> m_list = new JList<>(m_model);

	private final JButton m_add = new JButton("Add ...");

	private final JButton m_remove = new JButton("Remove");

	private final JButton m_up = new JButton("Up");

	private final JLabel m_status = new JLabel(" ");

	public MacroLibrariesPanel(List<Path> libraries) {
		super(new MigLayout("fill, insets 6", "[grow][]", "[][grow][]"));
		libraries.forEach(m_model::addElement);

		add(new JLabel("Searched by .MCALL, in this order, after any the source names with .LIBRARY:"), "span, wrap");
		m_list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		m_list.setVisibleRowCount(8);
		add(new JScrollPane(m_list), "grow, w 420::, h 140::");

		JPanel buttons = new JPanel(new MigLayout("insets 0, wrap 1", "[fill]", "[]"));
		buttons.add(m_add);
		buttons.add(m_remove);
		buttons.add(m_up);
		add(buttons, "top, wrap");
		m_status.setForeground(UiColors.SECONDARY_TEXT);
		add(m_status, "span, growx");

		m_add.addActionListener(e -> chooseAndAdd());
		m_remove.addActionListener(e -> removeSelected());
		m_up.addActionListener(e -> moveSelectedUp());
		m_list.addListSelectionListener(e -> updateButtons());
		updateButtons();
	}

	/** The libraries as they are now in the list. */
	public List<Path> getLibraries() {
		return Collections.list(m_model.elements());
	}

	/**
	 * Add a library, if it is one.
	 *
	 * @return false, with the reason shown, when the file is not a macro library
	 */
	public boolean addLibrary(Path file) {
		if(m_model.contains(file)) {
			showStatus(file.getFileName() + " is in the list already", false);
			return false;
		}
		try {
			MacroLibrary library = MacroLibrary.read(file);
			m_model.addElement(file);
			showStatus(library.getName() + ": " + library.getFormat() + ", " + library.getMacroNames().size() + " macros", false);
			return true;
		} catch(IOException x) {
			showStatus(x.getMessage(), true);
			return false;
		}
	}

	private void chooseAndAdd() {
		JFileChooser chooser = new JFileChooser();
		chooser.setMultiSelectionEnabled(true);
		chooser.setFileFilter(new FileNameExtensionFilter("Macro libraries (*.mlb, *.sml)", "mlb", "sml"));
		if(chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		for(File f : chooser.getSelectedFiles())
			addLibrary(f.toPath());
	}

	private void removeSelected() {
		int i = m_list.getSelectedIndex();
		if(i >= 0)
			m_model.remove(i);
		updateButtons();
	}

	private void moveSelectedUp() {
		int i = m_list.getSelectedIndex();
		if(i <= 0)
			return;
		List<Path> l = new ArrayList<>(getLibraries());
		Collections.swap(l, i, i - 1);
		m_model.clear();
		l.forEach(m_model::addElement);
		m_list.setSelectedIndex(i - 1);
	}

	private void updateButtons() {
		int i = m_list.getSelectedIndex();
		m_remove.setEnabled(i >= 0);
		m_up.setEnabled(i > 0);
	}

	private void showStatus(String text, boolean error) {
		m_status.setText(text);
		m_status.setForeground(error ? UiColors.ERROR_TEXT : UiColors.SECONDARY_TEXT);
	}

	JList<Path> getList() {
		return m_list;
	}

	JLabel getStatus() {
		return m_status;
	}
}
