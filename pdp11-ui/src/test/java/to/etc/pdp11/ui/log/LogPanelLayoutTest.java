package to.etc.pdp11.ui.log;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.UiRenderer;

import java.awt.Rectangle;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Log window's layout, checked and rendered without a display.
 *
 * <p>The column-per-channel table is the part of this application most likely to be laid out
 * wrong - a table with eight columns inside a scroll pane inside a panel with a row of
 * checkboxes above it - and it is the part hardest to eyeball, because it only looks wrong once
 * there is something in it.</p>
 */
class LogPanelLayoutTest {
	private static final int WIDTH = 1000;

	private static final int HEIGHT = 500;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static UiLogger loggerWithAConversation() {
		UiLogger logger = new UiLogger();
		logger.log(LogChannel.OTHER, "PDP11GUI starting");
		logger.log(LogChannel.OTHER, "Connection: CONNECTING - SimH over simulated machine");
		logger.setEnabled(LogChannel.TRANSPORT_WRITE, true);
		logger.setEnabled(LogChannel.TRANSPORT_READ, true);
		logger.log(LogChannel.TRANSPORT_WRITE, "E 1000<0d>");
		logger.log(LogChannel.TRANSPORT_READ, "E 1000<0d><0a>");
		logger.log(LogChannel.PROTOCOL, "Console answered: OtherLine \"E 1000\"");
		logger.log(LogChannel.TRANSPORT_READ, "1000:<09>123456<0d><0a>");
		logger.log(LogChannel.PROTOCOL, "Console answered: Examine, addr=00001000, value=123456");
		logger.log(LogChannel.TRANSPORT_READ, "sim> ");
		logger.log(LogChannel.PROTOCOL, "Console answered: Prompt");
		logger.log(LogChannel.EXECUTION, "haltCpu: CPU state is HALTED, nothing to halt");
		return logger;
	}

	@Test
	void thereIsAColumnPerChannelAndTheTimeInFront() {
		LogPanel panel = new LogPanel(new UiLogger());
		//-- The whole point of the window: one column per channel, so a conversation lines up
		//-- instead of interleaving.
		assertEquals(LogChannel.values().length + 1, panel.getTable().getColumnCount());
		assertEquals("Time", panel.getTable().getColumnName(0));
		for(int i = 0; i < LogChannel.values().length; i++) {
			assertEquals(LogChannel.values()[i].getColumnTitle(), panel.getTable().getColumnName(i + 1));
		}
	}

	@Test
	void theTableGetsTheSpaceAndTheCheckboxesGetOneRow() {
		LogPanel panel = new LogPanel(loggerWithAConversation());
		panel.attach();
		UiRenderer.layOut(panel, WIDTH, HEIGHT);

		Rectangle scroll = panel.getScroll().getBounds();
		assertTrue(scroll.height > HEIGHT / 2, "the table gets the room, not the checkboxes: " + scroll);
		assertTrue(scroll.y > 0 && scroll.y < 80, "the checkbox row is one row tall: " + scroll);
		assertTrue(scroll.x + scroll.width <= WIDTH, "and it stays inside the panel");
		//-- Every line that was logged before the panel existed is in it.
		assertEquals(10, panel.getRowCount());
	}

	@Test
	void aLineAppearsInItsOwnColumnAndNowhereElse() {
		LogPanel panel = new LogPanel(loggerWithAConversation());
		panel.attach();
		int protocolColumn = 1 + LogChannel.PROTOCOL.ordinal();
		int row = -1;
		for(int i = 0; i < panel.getRowCount(); i++) {
			if(!panel.getTable().getValueAt(i, protocolColumn).toString().isEmpty()) {
				row = i;
				break;
			}
		}
		assertTrue(row >= 0, "there should be a protocol line");
		for(int c = 1; c < panel.getTable().getColumnCount(); c++) {
			if(c == protocolColumn)
				continue;
			assertEquals("", panel.getTable().getValueAt(row, c), "column " + c + " should be empty on that row");
		}
	}

	@Test
	void renderToAFileForLookingAt() throws Exception {
		LogPanel panel = new LogPanel(loggerWithAConversation());
		panel.attach();
		Path file = UiRenderer.renderToFile(panel, WIDTH, HEIGHT,
			Path.of("target", "ui-render", "log-panel.png"));
		assertTrue(java.nio.file.Files.size(file) > 0);
	}
}
