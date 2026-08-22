package to.etc.pdp11.ui.log;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.util.LogChannel;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The log itself: one column per channel, with the channels that are worth switching off.
 *
 * <p>Ported from {@code TFormLog} ({@code FormLogU.pas}), and the column layout is the whole point
 * of it. A console conversation logged into a single stream is unreadable - the bytes going out,
 * the bytes coming back and the phrases decoded from them interleave, and telling them apart is
 * the job you opened the log to do. Side by side, they line up.</p>
 *
 * <p>A panel rather than a window, for the same reason as {@code MainPanel}: this can then be
 * laid out and rendered with no display, which is where the layout is checked.</p>
 */
public final class LogPanel extends JPanel {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
		.withZone(ZoneId.systemDefault());

	private final UiLogger m_logger;

	private final LogTableModel m_model = new LogTableModel();

	private final JTable m_table = new JTable(m_model);

	private final JScrollPane m_scroll = new JScrollPane(m_table);

	public LogPanel(UiLogger logger) {
		super(new MigLayout("fill, insets 6", "[grow]", "[][grow]"));
		m_logger = logger;

		JPanel channels = new JPanel(new MigLayout("insets 0"));
		for(LogChannel channel : LogChannel.values()) {
			JCheckBox box = new JCheckBox(channel.getColumnTitle(), logger.isEnabled(channel));
			if(channel == LogChannel.TRANSPORT_READ || channel == LogChannel.TRANSPORT_WRITE)
				box.setToolTipText("One line per byte. Off unless you are debugging the wire itself.");
			box.addActionListener(e -> logger.setEnabled(channel, box.isSelected()));
			channels.add(box);
		}
		JButton clear = new JButton("Clear");
		clear.addActionListener(e -> {
			logger.clear();
			m_model.clear();
		});
		channels.add(clear, "gapleft 20");

		m_table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		m_table.setShowGrid(false);
		m_table.getColumnModel().getColumn(0).setPreferredWidth(90);
		for(int i = 1; i < m_model.getColumnCount(); i++) {
			m_table.getColumnModel().getColumn(i).setPreferredWidth(220);
		}

		add(channels, "wrap");
		add(m_scroll, "grow");
	}

	public JTable getTable() {
		return m_table;
	}

	public JScrollPane getScroll() {
		return m_scroll;
	}

	public int getRowCount() {
		return m_model.getRowCount();
	}

	/**
	 * Take the history and start following.
	 *
	 * <p>The buffer exists from the first line logged, long before anybody opens this. Attaching
	 * late is meant to miss nothing, which is why the snapshot comes first.</p>
	 */
	public void attach() {
		m_model.setAll(m_logger.snapshot());
		m_logger.setListener(line -> SwingUtilities.invokeLater(() -> {
			boolean atBottom = isScrolledToBottom();
			m_model.add(line);
			if(atBottom)
				scrollToBottom();
		}));
	}

	/** Stop drawing rows nobody is looking at. The buffer keeps filling regardless. */
	public void detach() {
		m_logger.setListener(null);
	}

	private boolean isScrolledToBottom() {
		//-- Follow the tail unless the user has scrolled up to read something, which is what makes
		//-- a log usable while things are happening.
		var bar = m_scroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
	}

	private void scrollToBottom() {
		int rows = m_model.getRowCount();
		if(rows > 0)
			m_table.scrollRectToVisible(m_table.getCellRect(rows - 1, 0, true));
	}

	/** One row per line, with the text in the column belonging to its channel. */
	private static final class LogTableModel extends AbstractTableModel {
		private final List<LogLine> m_rows = new ArrayList<>();

		private final LogChannel[] m_channels = LogChannel.values();

		@Override
		public int getRowCount() {
			return m_rows.size();
		}

		@Override
		public int getColumnCount() {
			return m_channels.length + 1;
		}

		@Override
		public String getColumnName(int column) {
			return column == 0 ? "Time" : m_channels[column - 1].getColumnTitle();
		}

		@Override
		public Object getValueAt(int row, int column) {
			LogLine line = m_rows.get(row);
			if(column == 0)
				return TIME.format(Instant.ofEpochMilli(line.millis()));
			//-- Empty in every column but its own, which is what makes the channels line up.
			return m_channels[column - 1] == line.channel() ? line.text() : "";
		}

		void add(LogLine line) {
			m_rows.add(line);
			//-- The buffer is bounded, so this is too; otherwise a long session grows a table
			//-- model nothing ever trims.
			if(m_rows.size() > UiLogger.MAX_LINES) {
				m_rows.remove(0);
				fireTableDataChanged();
				return;
			}
			fireTableRowsInserted(m_rows.size() - 1, m_rows.size() - 1);
		}

		void setAll(List<LogLine> lines) {
			m_rows.clear();
			m_rows.addAll(lines);
			fireTableDataChanged();
		}

		void clear() {
			m_rows.clear();
			fireTableDataChanged();
		}
	}
}
