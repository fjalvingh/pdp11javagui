package to.etc.pdp11.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.ui.terminal.TerminalStyle;

import javax.swing.JComponent;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where things end up, checked without a display.
 *
 * <p>These are the mistakes a layout actually makes: a component that gets no height, a status bar
 * that floats in the middle, a panel that stops short of the edge, a constraint that reads like
 * one thing and does another. All of them are visible in the component bounds after a layout pass,
 * and none of them need a window on a screen - see {@link UiRenderer}.</p>
 *
 * <p>It also renders the panel to {@code target/ui-render/} on every run, because a layout can
 * satisfy every invariant anybody thought to write down and still look wrong.</p>
 */
class MainPanelLayoutTest {
	private static final int WIDTH = 1000;

	private static final int HEIGHT = 700;

	@BeforeAll
	static void lookAndFeel() {
		//-- Not asserted on: a layout checked against the cross-platform default is still worth
		//-- checking, and the render is only prettier with the real one.
		UiRenderer.installLookAndFeel();
	}

	private static MainPanel laidOut() {
		MainPanel panel = new MainPanel();
		UiRenderer.layOut(panel, WIDTH, HEIGHT);
		return panel;
	}

	@Test
	void theStatusBarIsAtTheBottomAndTheTerminalHasTheRest() {
		MainPanel panel = laidOut();
		Rectangle status = panel.getStatusBar().getBounds();
		Rectangle terminal = ((JComponent) panel.getTerminal().getComponent()).getBounds();

		assertEquals(0, status.x, "the status bar starts at the left edge");
		assertEquals(WIDTH, status.width, "and runs the full width");
		assertEquals(HEIGHT, status.y + status.height, "and sits on the bottom edge");
		assertTrue(status.height > 10 && status.height < 60, "one line tall, not more: " + status);

		assertEquals(0, terminal.x);
		assertEquals(0, terminal.y, "the terminal starts at the top");
		assertEquals(WIDTH, terminal.width);
		assertEquals(status.y, terminal.y + terminal.height,
			"and stops exactly where the status bar starts, with no seam of background between them");
		assertTrue(terminal.height > HEIGHT / 2, "the terminal gets the space, not the status bar: " + terminal);
	}

	@Test
	void nothingWithSomethingToShowIsSqueezedToNothing() {
		MainPanel panel = new MainPanel();
		//-- With content, because an empty JLabel is legitimately zero-sized: the status detail
		//-- collapses when there is no detail, which is what it should do. What must never happen
		//-- is a component with something in it being given no room.
		panel.showConnectionState(ConnectionManager.State.CONNECTED, "SimH over simulated machine");
		UiRenderer.layOut(panel, WIDTH, HEIGHT);
		assertNoZeroSizedComponents(panel, "");
	}

	private static void assertNoZeroSizedComponents(java.awt.Container c, String path) {
		for(java.awt.Component child : c.getComponents()) {
			if(!child.isVisible())
				continue;
			String where = path + "/" + child.getClass().getSimpleName();
			if(child instanceof javax.swing.JLabel label && (label.getText() == null || label.getText().isEmpty()))
				continue;                                   // nothing to show, so nothing to squeeze
			assertTrue(child.getWidth() > 0 && child.getHeight() > 0,
				"invisible because it has no size: " + where + " " + child.getBounds());
			if(child instanceof java.awt.Container container)
				assertNoZeroSizedComponents(container, where);
		}
	}

	@Test
	void theLayoutSurvivesBeingMadeSmall() {
		//-- The minimum the window will go to. A layout that only works at the size it was
		//-- designed at is a layout that breaks the first time somebody drags a corner.
		MainPanel panel = new MainPanel();
		UiRenderer.layOut(panel, 720, 420);
		Rectangle status = panel.getStatusBar().getBounds();
		assertEquals(720, status.width);
		assertEquals(420, status.y + status.height);
		assertTrue(((JComponent) panel.getTerminal().getComponent()).getHeight() > 200,
			"the terminal still gets most of it");
	}

	@Test
	void theStatusBarSaysWhatIsHappening() {
		MainPanel panel = laidOut();
		assertEquals("Not connected", panel.getStateText());
		assertEquals("", panel.getDetailText());

		panel.showConnectionState(ConnectionManager.State.CONNECTED, "SimH over simulated machine");
		assertEquals("Connected", panel.getStateText());
		assertEquals("SimH over simulated machine", panel.getDetailText());

		panel.showConnectionState(ConnectionManager.State.FAILED, "no such port");
		assertEquals("Connection failed", panel.getStateText());
		//-- Connected and failed must not look the same at a glance, which is the whole job of a
		//-- status bar.
		java.awt.Color failed = colourOfStateLabel(panel);
		panel.showConnectionState(ConnectionManager.State.CONNECTED, "");
		assertNotEquals(failed, colourOfStateLabel(panel));
	}

	private static java.awt.Color colourOfStateLabel(MainPanel panel) {
		return ((javax.swing.JLabel) panel.getStatusBar().getComponent(0)).getForeground();
	}

	@Test
	void itPaintsAndTheTerminalIsDarkerThanTheFrameAroundIt() throws Exception {
		MainPanel panel = new MainPanel();
		panel.getTerminal().append("sim> E 1000\n1000:   123456\nsim> ", TerminalStyle.PDP);
		panel.showConnectionState(ConnectionManager.State.CONNECTED, "SimH over simulated machine");
		BufferedImage image = UiRenderer.render(panel, WIDTH, HEIGHT);

		assertEquals(WIDTH, image.getWidth());
		assertEquals(HEIGHT, image.getHeight());
		//-- Somewhere in the middle of the terminal. The application runs a dark theme, so this
		//-- is no longer "dark against light" - what says the terminal painted at all is that it
		//-- is its own near-black rather than the theme's window background.
		int middle = image.getRGB(WIDTH / 2, HEIGHT / 3);
		assertTrue(brightness(middle) < 40, "the terminal should be near-black, was " + Integer.toHexString(middle));
		//-- And the status bar, two pixels off the bottom, is the frame around it: darker than a
		//-- light theme ever was, and still clearly not the terminal.
		int bottom = image.getRGB(WIDTH / 2, HEIGHT - 3);
		assertTrue(brightness(bottom) > brightness(middle) + 20,
			"the status bar should be distinguishable from the terminal, was " + Integer.toHexString(bottom));
	}

	private static int brightness(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return (r + g + b) / 3;
	}

	/**
	 * Write the panel out as a picture, so a person can look at it.
	 *
	 * <p>Not an assertion. The invariants above catch a layout that is broken; this catches one
	 * that is merely wrong, which needs eyes.</p>
	 */
	@Test
	void renderToAFileForLookingAt() throws Exception {
		MainPanel panel = new MainPanel();
		panel.getTerminal().append("[connecting to SimH over simulated machine]\n", TerminalStyle.SYSTEM);
		panel.getTerminal().append("\nsim> sh cpu iospace\nsim> set throttle 5M\n"
			+ "sim> E 1000\n1000:   123456\nsim> D 1000 4567\nsim> ", TerminalStyle.PDP);
		panel.showConnectionState(ConnectionManager.State.CONNECTED, "SimH over simulated machine");
		Path file = UiRenderer.renderToFile(panel, WIDTH, HEIGHT,
			Path.of("target", "ui-render", "main-panel.png"));
		assertTrue(java.nio.file.Files.size(file) > 0);
	}
}
