package to.etc.pdp11.ui.simh;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.KeyStroke;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SimH Console window, against the simulated SimH and with no display.
 *
 * <p>What it has to get right is what it <b>shows</b>: the {@code sim>} channel and nothing else,
 * from the beginning of what is still kept, without doubling any of it when the window is closed
 * and opened again. Everything below drives it the way the window does - {@code attach()} on
 * show, {@code detach()} on hide - because that pairing is where a transcript gets duplicated.</p>
 */
class SimhConsolePanelTest {
	private static final int WIDTH = 900;

	private static final int HEIGHT = 520;

	private static final long TIMEOUT_MS = 10_000;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static AppContext connected(Path dir, ConsoleProtocol protocol) throws Exception {
		AppContext ctx = TestContext.create(dir);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(protocol));
		return ctx;
	}

	/** Wait for something a console command will get round to. */
	private static void until(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean())
				return;
			try {
				Thread.sleep(10);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(x);
			}
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	private static String transcript(SimhConsolePanel panel) {
		return Edt.call(() -> panel.getTranscript().getText());
	}

	@Test
	void itShowsTheHandshakeThatHappenedBeforeItWasOpened(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			//-- The window opens on connect, which is *after* the handshake: everything
			//-- interesting has already been said by the time anybody looks.
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			String text = transcript(panel);
			assertTrue(text.contains("sim>"), "the prompt: " + text);
			assertTrue(text.contains("sh cpu iospace"), "and the console layer's own setup: " + text);
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void closingAndOpeningItAgainDoesNotDoubleTheTranscript(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			String first = transcript(panel);
			Edt.run(panel::detach);
			Edt.run(panel::attach);
			assertEquals(first, transcript(panel), "the channel's contents, once, however often it is opened");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void whileHiddenItHearsNothingAndCatchesUpOnTheWayBack(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			Edt.run(panel::detach);
			String whileHidden = transcript(panel);

			//-- Something happens with the window shut.
			ctx.onConsole("examine", console -> console.examine(
				to.etc.pdp11.core.addr.Address.of(to.etc.pdp11.core.addr.MemoryAddressType.PHYSICAL22, 01000)));
			until("the examine to reach the channel",
				() -> ctx.getConnectionManager().getProtocolChannel().getText().contains("E 1000"));
			assertEquals(whileHidden, transcript(panel), "a hidden window is not being updated");

			Edt.run(panel::attach);
			assertTrue(transcript(panel).contains("E 1000"), "and shows it when it comes back");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aTypedCommandIsMarkedSentAndAnswered(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			Edt.run(() -> {
				panel.getCommandField().setText("show cpu");
				panel.getCommandField().postActionEvent();
			});

			until("SimH to answer", () -> transcript(panel).contains("11/70"));
			String text = transcript(panel);
			//-- Marked, because SimH echoes everything and an echo of what a person typed is
			//-- otherwise indistinguishable from an echo of what the application issued.
			assertTrue(text.contains("> show cpu"), "the mark: " + text);
			assertEquals(List.of("show cpu"), Edt.call(panel::getHistory));
			assertEquals("", Edt.call(() -> panel.getCommandField().getText()), "and the field is cleared");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void anEmptyCommandIsNotSentAtAll(@TempDir Path dir) throws Exception {
		//-- A bare RETURN makes SimH repeat its last command, which here would be one of
		//-- PDP11GUI's - a deposit, most likely.
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			String before = transcript(panel);
			Edt.run(() -> {
				panel.getCommandField().setText("   ");
				panel.getCommandField().postActionEvent();
			});
			assertEquals(before, transcript(panel));
			assertEquals(List.of(), Edt.call(panel::getHistory));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void theArrowsWalkTheCommandHistory(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			for(String cmd : List.of("show cpu", "reset")) {
				Edt.run(() -> {
					panel.getCommandField().setText(cmd);
					panel.getCommandField().postActionEvent();
				});
				until("\"" + cmd + "\" to be sent", () -> Edt.call(panel::getHistory).contains(cmd));
			}
			assertEquals(List.of("show cpu", "reset"), Edt.call(panel::getHistory));

			assertEquals("reset", Edt.call(() -> {
				press(panel, KeyEvent.VK_UP);
				return panel.getCommandField().getText();
			}));
			assertEquals("show cpu", Edt.call(() -> {
				press(panel, KeyEvent.VK_UP);
				return panel.getCommandField().getText();
			}));
			assertEquals("show cpu", Edt.call(() -> {
				press(panel, KeyEvent.VK_UP);
				return panel.getCommandField().getText();
			}), "and it stops at the oldest rather than emptying");
			Edt.run(() -> {
				press(panel, KeyEvent.VK_DOWN);
				press(panel, KeyEvent.VK_DOWN);
			});
			assertEquals("", Edt.call(() -> panel.getCommandField().getText()),
				"past the newest is the empty line you were typing on");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/** Fire the field's own key binding, which is where the history lives. */
	private static void press(SimhConsolePanel panel, int keyCode) {
		Object name = panel.getCommandField().getInputMap().get(KeyStroke.getKeyStroke(keyCode, 0));
		panel.getCommandField().getActionMap().get(name)
			.actionPerformed(new java.awt.event.ActionEvent(panel.getCommandField(), 0, ""));
	}

	@Test
	void itSaysSoWhenThereIsNoSimhToTalkTo(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.ODT_18);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			assertFalse(Edt.call(() -> panel.getCommandField().isEnabled()), "nothing to type at");
			assertFalse(Edt.call(() -> panel.getHaltButton().isEnabled()));
			assertTrue(Edt.call(panel::getStatusText).contains("not SimH"), Edt.call(panel::getStatusText));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void withNothingConnectedItIsInertAndSaysThat(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
		Edt.run(panel::attach);
		assertFalse(Edt.call(() -> panel.getCommandField().isEnabled()));
		assertEquals("Not connected", Edt.call(panel::getStatusText));
	}

	@Test
	void theTranscriptGetsTheRoomAndTheCommandLineOneRow(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			Edt.run(() -> UiRenderer.layOut(panel, WIDTH, HEIGHT));

			Rectangle transcript = Edt.call(() -> panel.getTranscript().getComponent().getBounds());
			Rectangle field = Edt.call(() -> panel.getCommandField().getBounds());
			assertTrue(transcript.height > HEIGHT / 2, "the transcript gets the room: " + transcript);
			assertTrue(field.height > 0 && field.height < 60, "the command line is one row: " + field);
			assertTrue(transcript.x + transcript.width <= WIDTH, "and it stays inside the panel");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			SimhConsolePanel panel = Edt.call(() -> new SimhConsolePanel(ctx));
			Edt.run(panel::attach);
			Path file = Edt.call(() -> UiRenderer.renderToFile(panel, WIDTH, HEIGHT,
				Path.of("target", "ui-render", "simh-console-panel.png")));
			assertTrue(java.nio.file.Files.size(file) > 0);
		} finally {
			ctx.getConnectionManager().close();
		}
	}
}
