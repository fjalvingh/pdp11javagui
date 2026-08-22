package to.etc.pdp11.ui.exec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.console.ConsoleRunMode;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run control, and specifically which buttons are alive.
 *
 * <p>That is the substance of this window and the easiest thing in it to get wrong, because it
 * depends on the console's feature set and the machine's state at the same time - and getting it
 * wrong produces a button that silently does nothing rather than an error. Each case here is one
 * row of {@code UpdateDisplay} ({@code FormExecuteU.pas:281-380}).</p>
 *
 * <p>Everything runs against a machine simulated in this JVM, so it needs no hardware, no SimH
 * and no serial port.</p>
 */
class ExecutionPanelTest {
	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static AppContext connected(Path dir, ConsoleProtocol protocol) throws Exception {
		AppContext ctx = TestContext.create(dir);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(protocol));
		return ctx;
	}

	@Test
	void withNoMachineNothingIsOffered(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
		UiRenderer.layOut(panel, 640, 260);

		assertEquals("Not connected", panel.getStateText());
		assertFalse(panel.getSingleStepButton().isEnabled());
		assertFalse(panel.getHaltButton().isEnabled(), "there is nothing to halt");
		assertFalse(panel.getSwitchPanel().isVisible(), "and no switch to be in a position");
	}

	@Test
	void simhOffersEverythingItCanDo(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			UiRenderer.layOut(panel, 640, 260);

			assertTrue(panel.getSingleStepButton().isEnabled());
			assertTrue(panel.getHaltButton().isEnabled());
			//-- SimH is a program, not a machine with a front panel.
			assertFalse(panel.getSwitchPanel().isVisible());
			assertEquals("Machine: state unknown", panel.getStateText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * ODT's feature set genuinely changes with the switch: {@code nnnG} and {@code P} mean
	 * "reset" and "step" with the machine halted and "run" and "continue" with it enabled
	 * ({@code OdtConsole}, from {@code ConsolePDP11ODTU.pas:330-352}). So the buttons have to
	 * change with it too.
	 */
	@Test
	void odtShowsTheRunHaltSwitchAndFollowsIt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.ODT_16);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			UiRenderer.layOut(panel, 640, 260);
			assertTrue(panel.getSwitchPanel().isVisible(), "ODT has a physical switch");
			//-- ODT cannot halt a running CPU at all - it is in the CPU. The button stays enabled
			//-- so it can say which switch to move, which is the Pascal's rule.
			assertTrue(panel.getHaltButton().isEnabled());

			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.HALT);
			Edt.run(panel::updateDisplay);
			assertTrue(panel.getSingleStepButton().isEnabled(), "halted, so P steps one instruction");

			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.RUN);
			Edt.run(panel::updateDisplay);
			assertFalse(panel.getSingleStepButton().isEnabled(), "enabled, so P continues instead");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aRunningMachineOffersOnlyHalt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			ctx.getMachineState().running();
			Edt.run(panel::updateDisplay);

			assertTrue(panel.getHaltButton().isEnabled());
			assertFalse(panel.getSingleStepButton().isEnabled());
			assertEquals("Machine: running", panel.getStateText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aStopShowsWhereTheMachineStopped(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 01234));
			Edt.run(panel::updateDisplay);

			assertEquals("001234", panel.getCurrentPcField().getText());
			assertEquals("Machine: stopped at 001234", panel.getStateText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * The window that is not open still hears about the stop.
	 *
	 * <p>This is what {@link MachineState} buys: in the Pascal the PC reaches the disassembler
	 * because the execution window calls it by name, so a stop while that window was shut told
	 * nobody anything.</p>
	 */
	@Test
	void theMachineStateIsFollowedWithNoWindowOpenAtAll(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			assertEquals(MachineState.ExecutionState.UNKNOWN, ctx.getMachineState().getState());
			ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 0500));
			assertEquals(MachineState.ExecutionState.STOPPED, ctx.getMachineState().getState());
			assertEquals(0500, ctx.getMachineState().getPc().val());

			//-- And a new connection forgets it, because it is a different machine.
			ctx.getConnectionManager().disconnect();
			assertEquals(MachineState.ExecutionState.UNKNOWN, ctx.getMachineState().getState());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.ODT_16);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.HALT);
			ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 01000));
			Edt.run(panel::updateDisplay);
			Path file = UiRenderer.renderToFile(panel, 700, 260,
				Path.of("target", "ui-render", "execution-panel.png"));
			assertTrue(Files.size(file) > 0);
		} finally {
			ctx.getConnectionManager().close();
		}
	}
}
