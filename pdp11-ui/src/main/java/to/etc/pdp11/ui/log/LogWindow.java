package to.etc.pdp11.ui.log;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.window.ToolWindow;
import to.etc.pdp11.ui.window.WindowKey;
import to.etc.pdp11.ui.window.WindowType;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.Dimension;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The log, one column per channel.
 *
 * <p>Ported from {@code TFormLog} ({@code FormLogU.pas}), and the column layout is the whole
 * point of it. A console conversation logged into a single stream is unreadable: the bytes going
 * out, the bytes coming back and the phrases decoded from them interleave, and working out which
 * is which is exactly the job you opened the log to do. Side by side, they line up.</p>
 *
 * <p>The byte-level channels are off unless asked for. {@link LogChannel#TRANSPORT_READ} fires
 * once per received character, so leaving it on costs a row per byte of every transcript - which
 * is why the Pascal has the same switch ({@code Connection_LogIoStream}).</p>
 */
public final class LogWindow extends ToolWindow {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
		.withZone(ZoneId.systemDefault());

	private final LogTableModel m_model = new LogTableModel();

	private final JTable m_table = new JTable(m_model);

	public LogWindow(WindowKey key, AppContext context) {
		super(key, context);
		setContentPane(buildContent());
		setSize(new Dimension(1000, 500));
	}

	private JPanel buildContent() {
		JPanel panel = new JPanel(new MigLayout("fill, insets 6", "[grow]", "[][grow]"));

		JPanel channels = new JPanel(new MigLayout("insets 0"));
		for(LogChannel channel : LogChannel.values()) {
			JCheckBox box = new JCheckBox(channel.getColumnTitle(), logger().isEnabled(channel));
			box.setToolTipText(channel == LogChannel.TRANSPORT_READ || channel == LogChannel.TRANSPORT_WRITE
				? "One line per byte. Off unless you are debugging the wire itself."
				: null);
			box.addActionListener(e -> logger().setEnabled(channel, box.isSelected()));
			channels.add(box);
		}
		JButton clear = new JButton("Clear");
		clear.addActionListener(e -> {
			logger().clear();
			m_model.clear();
		});
		channels.add(clear, "gapleft 20");
		panel.add(channels, "wrap");

		m_table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		m_table.setShowGrid(false);
		m_table.getColumnModel().getColumn(0).setPreferredWidth(90);
		for(int i = 1; i < m_model.getColumnCount(); i++) {
			m_table.getColumnModel().getColumn(i).setPreferredWidth(220);
		}
		panel.add(new JScrollPane(m_table), "grow");
		return panel;
	}

	private UiLogger logger() {
		return (UiLogger) context().getLogger();
	}

	/**
	 * Take the history and start following.
	 *
	 * <p>On first show rather than in the constructor: the buffer exists from the first line
	 * logged, long before anybody opens this, and the point of attaching late is that nothing is
	 * missed by opening late.</p>
	 */
	@Override
	protected void onFirstShow() {
		m_model.setAll(logger().snapshot());
		logger().setListener(line -> SwingUtilities.invokeLater(() -> {
			boolean atBottom = isScrolledToBottom();
			m_model.add(line);
			if(atBottom)
				scrollToBottom();
		}));
	}

	@Override
	protected void onHiding() {
		//-- Stop drawing rows nobody is looking at. The buffer keeps filling regardless, so
		//-- reopening shows everything that happened meanwhile.
		logger().setListener(null);
	}

	private boolean isScrolledToBottom() {
		//-- Following the tail unless the user has scrolled up to read something, which is what
		//-- every log viewer does and what makes one usable while things are happening.
		JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, m_table);
		if(scroll == null)
			return true;
		var bar = scroll.getVerticalScrollBar();
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
			//-- The buffer is bounded, so this is too; without it a long session grows a table
			//-- model that nothing ever trims.
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

	/** Register this window type with a manager. */
	public static void register(AppContext context) {
		context.getWindowManager().register(WindowType.LOG, key -> new LogWindow(key, context));
	}
}
